package dev.javaharness.adapters.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.workspace.WorkspaceRoots;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ContextSummary;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.ModelUsage;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunEvent;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.run.RunStep;
import dev.javaharness.spi.run.SubagentRun;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionSnapshot;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.session.UsageEntry;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolExecution;
import dev.javaharness.spi.workspace.Workspace;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One store instance owns a single-process H2 database. The monitor serializes views and mutations;
 * each mutation commits its projection, sequence and event together in a database transaction.
 */
public final class JdbcSessionStore implements SessionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcWorkspaceStore workspaces;
    private final JdbcRunRecordStore records;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RowMapper<Run> RUN_MAPPER = (rs, row) -> new Run(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("request_id"),
            rs.getString("prompt"),
            rs.getString("scenario"),
            RunStatus.valueOf(rs.getString("status")),
            rs.getString("output"),
            rs.getString("detail"),
            instant(rs, "created_at"),
            rs.getString("provider"),
            rs.getString("model"),
            decode(rs.getString("context_summary"), ContextSummary.class),
            rs.getString("workspace"),
            rs.getString("model_profile_id"),
            rs.getString("reasoning_effort"));
    // Children of one batch can share a timestamp; their QUEUED events keep the creation order.
    private static final String BY_QUEUED = """
            ORDER BY (SELECT e.seq FROM harness_event e
                      WHERE e.run_id=harness_run.id AND e.event_type='STATE' AND e.status='QUEUED')
            """;
    private static final RowMapper<SubagentRun> SUBAGENT_MAPPER = (rs, row) -> new SubagentRun(
            rs.getString("parent_run_id"),
            rs.getString("parent_call_id"),
            rs.getString("agent_name"),
            RUN_MAPPER.mapRow(rs, row));
    private static final RowMapper<Session> SESSION_MAPPER = (rs, row) -> new Session(
            rs.getString("id"),
            rs.getString("title"),
            instant(rs, "created_at"),
            rs.getString("workspace"),
            rs.getString("model_profile_id"),
            rs.getString("reasoning_effort"),
            rs.getString("workspace_id") == null ? "" : rs.getString("workspace_id"),
            rs.getString("approval_mode"));

    public JdbcSessionStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.workspaces = new JdbcWorkspaceStore(jdbc, tx);
        this.records = new JdbcRunRecordStore(jdbc, tx);
        // Link existing sessions without rewriting their run snapshots or requiring directories online.
        tx.executeWithoutResult(status -> {
            for (Session session : allSessions()) {
                if (!session.workspace().isEmpty() && session.workspaceId().isEmpty())
                    jdbc.update(
                            "UPDATE harness_session SET workspace_id=? WHERE id=?",
                            workspaces.legacyWorkspace(session.workspace()),
                            session.id());
            }
        });
    }

    static Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, OffsetDateTime.class).toInstant();
    }

    static Instant optionalInstant(ResultSet rs, String name) throws SQLException {
        OffsetDateTime value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    static <T> T decode(String value, Class<T> type) {
        if (value == null) return null;
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid stored context data");
        }
    }

    static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode context data");
        }
    }

    @Override
    public synchronized Session createSession(String title) {
        return createSession(title, "");
    }

    @Override
    public synchronized Session createSession(String title, String workspace) {
        if (title == null || title.isBlank() || title.length() > 100) {
            throw new HarnessException("INVALID", "会话名称需为 1–100 个字符");
        }
        String id = UUID.randomUUID().toString();
        OffsetDateTime created = now();
        JdbcWorkspaceStore.checkWorkspace(workspace);
        return tx.execute(status -> {
            String workspaceId = workspace.isEmpty() ? null : workspaces.legacyWorkspace(workspace);
            jdbc.update(
                    "INSERT INTO harness_session(id,title,created_at,workspace,workspace_id) VALUES (?,?,?,?,?)",
                    id,
                    title.strip(),
                    created,
                    workspace,
                    workspaceId);
            return session(id);
        });
    }

    /** Persist the title and notify subscribers in the same transaction. */
    @Override
    public synchronized Session renameSession(String sessionId, String title) {
        String normalized = title == null ? "" : title.strip();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new HarnessException("INVALID", "会话名称需为 1–100 个字符");
        }
        return tx.execute(status -> {
            Session current = session(sessionId);
            if (!current.title().equals(normalized)) {
                jdbc.update("UPDATE harness_session SET title=? WHERE id=?", normalized, sessionId);
                appendEvent(sessionId, null, "TITLE", null, "会话名称已更新");
            }
            return session(sessionId);
        });
    }

    /** Delete run artifacts in dependency order, then the session itself, atomically. */
    @Override
    public synchronized void deleteSession(String sessionId) {
        tx.executeWithoutResult(status -> {
            session(sessionId);
            if (jdbc.queryForObject("SELECT active_run_id FROM harness_session WHERE id=?", String.class, sessionId)
                    != null) throw new HarnessException("BUSY", "请先停止当前任务，再删除会话");
            String runs = "SELECT id FROM harness_run WHERE session_id=?";
            jdbc.update("DELETE FROM harness_file_change WHERE run_id IN (" + runs + ")", sessionId);
            jdbc.update("DELETE FROM harness_command WHERE run_id IN (" + runs + ")", sessionId);
            jdbc.update("DELETE FROM harness_tool WHERE run_id IN (" + runs + ")", sessionId);
            jdbc.update("DELETE FROM harness_step WHERE run_id IN (" + runs + ")", sessionId);
            jdbc.update("DELETE FROM harness_model_request WHERE run_id IN (" + runs + ")", sessionId);
            jdbc.update("DELETE FROM harness_model_step WHERE run_id IN (" + runs + ")", sessionId);
            jdbc.update("DELETE FROM harness_conversation WHERE run_id IN (" + runs + ")", sessionId);
            jdbc.update("DELETE FROM harness_event WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM harness_run WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM harness_session WHERE id=?", sessionId);
        });
    }

    /** Compare-and-set rename in one transaction: a user edit landing first wins over late auto-naming. */
    @Override
    public synchronized boolean renameSessionIfTitle(String sessionId, String expectedTitle, String title) {
        String normalized = title == null ? "" : title.strip();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new HarnessException("INVALID", "会话名称需为 1–100 个字符");
        }
        return tx.execute(status -> {
            Session current = session(sessionId);
            if (!current.title().equals(expectedTitle)) return false;
            if (!current.title().equals(normalized)) {
                jdbc.update("UPDATE harness_session SET title=? WHERE id=?", normalized, sessionId);
                appendEvent(sessionId, null, "TITLE", null, "会话名称已更新");
            }
            return true;
        });
    }

    @Override
    public synchronized Session updateWorkspace(String sessionId, String workspace) {
        JdbcWorkspaceStore.checkWorkspace(workspace);
        return tx.execute(status -> {
            session(sessionId);
            String active = jdbc.queryForObject(
                    "SELECT active_run_id FROM harness_session WHERE id=?", String.class, sessionId);
            if (active != null) throw new HarnessException("BUSY", "请先停止当前任务，再切换工作区");
            jdbc.update(
                    "UPDATE harness_session SET workspace=?,workspace_id=? WHERE id=?",
                    workspace,
                    workspace.isEmpty() ? null : workspaces.legacyWorkspace(workspace),
                    sessionId);
            appendEvent(sessionId, null, "WORKSPACE", null, "会话工作区已更新");
            return session(sessionId);
        });
    }

    @Override
    public synchronized List<Workspace> workspaces() {
        return workspaces.workspaces();
    }

    @Override
    public synchronized Workspace workspace(String id) {
        return workspaces.workspace(id);
    }

    /** Persist workspace metadata and update every bound session atomically under the session lock. */
    @Override
    public synchronized Workspace saveWorkspace(
            String id, String name, List<String> directories, String icon, String color, long revision) {
        return workspaces.saveWorkspace(
                id, name, directories, icon, color, revision, this::allSessions, this::appendEvent);
    }

    @Override
    public synchronized Session selectWorkspace(String sessionId, String workspaceId) {
        return tx.execute(status -> {
            session(sessionId);
            if (jdbc.queryForObject("SELECT active_run_id FROM harness_session WHERE id=?", String.class, sessionId)
                    != null) throw new HarnessException("BUSY", "请先停止当前任务，再切换工作区");
            String key = workspaceId == null || workspaceId.isEmpty() ? null : workspaceId;
            String roots = key == null
                    ? ""
                    : WorkspaceRoots.encode(workspaces.workspace(key).directories());
            jdbc.update("UPDATE harness_session SET workspace=?,workspace_id=? WHERE id=?", roots, key, sessionId);
            appendEvent(sessionId, null, "WORKSPACE", null, "会话工作区已更新");
            return session(sessionId);
        });
    }

    @Override
    public synchronized Session createSessionInWorkspace(String title, String workspaceId) {
        return tx.execute(status -> {
            if (workspaceId != null && !workspaceId.isEmpty()) workspaces.workspace(workspaceId);
            Session session = createSession(title);
            return selectWorkspace(session.id(), workspaceId);
        });
    }

    /** Hidden group member and flow item sessions are listed by their group chat or flow, not here. */
    @Override
    public synchronized List<Session> sessions() {
        return jdbc.query(
                "SELECT * FROM harness_session WHERE group_id IS NULL AND flow_id IS NULL ORDER BY created_at DESC, id DESC",
                SESSION_MAPPER);
    }

    private List<Session> allSessions() {
        return jdbc.query("SELECT * FROM harness_session ORDER BY created_at DESC, id DESC", SESSION_MAPPER);
    }

    private Session session(String id) {
        var rows = jdbc.query("SELECT * FROM harness_session WHERE id=?", SESSION_MAPPER, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "会话不存在");
        return rows.getFirst();
    }

    @Override
    public synchronized SessionSnapshot snapshot(String sessionId) {
        Session session = session(sessionId);
        var runs = jdbc.query(
                "SELECT * FROM harness_run WHERE session_id=? AND parent_run_id IS NULL ORDER BY created_at,id",
                RUN_MAPPER,
                sessionId);
        var subagents = jdbc.query(
                "SELECT * FROM harness_run WHERE session_id=? AND parent_run_id IS NOT NULL " + BY_QUEUED,
                SUBAGENT_MAPPER,
                sessionId);
        long seq = jdbc.queryForObject("SELECT last_seq FROM harness_session WHERE id=?", Long.class, sessionId);
        return new SessionSnapshot(session, runs, seq, records.usage(sessionId), subagents);
    }

    @Override
    public synchronized Run startSubagent(String parentRunId, String parentCallId, String agent, String prompt) {
        return tx.execute(status -> {
            Run parent = run(parentRunId);
            if (parent.status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止");
            if (jdbc.queryForObject("SELECT parent_run_id FROM harness_run WHERE id=?", String.class, parentRunId)
                    != null) throw new HarnessException("CONFLICT", "子智能体不能再委派");
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at,provider,model,
                        workspace,model_profile_id,reasoning_effort,requested_selection,plugin_snapshot,parent_run_id,parent_call_id,agent_name,approval_mode)
                    SELECT ?,session_id,?,?,scenario,'QUEUED','','',?,provider,model,
                        workspace,model_profile_id,reasoning_effort,requested_selection,plugin_snapshot,id,?,?,approval_mode
                    FROM harness_run WHERE id=?
                    """, id, UUID.randomUUID().toString(), prompt, now(), parentCallId, agent, parentRunId);
            appendEvent(parent.sessionId(), id, "STATE", RunStatus.QUEUED, "");
            return transition(id, RunStatus.RUNNING, "");
        });
    }

    @Override
    public synchronized List<SubagentRun> subagents(String runId) {
        run(runId);
        return jdbc.query("SELECT * FROM harness_run WHERE parent_run_id=? " + BY_QUEUED, SUBAGENT_MAPPER, runId);
    }

    @Override
    public synchronized Run run(String runId) {
        var rows = jdbc.query("SELECT * FROM harness_run WHERE id=?", RUN_MAPPER, runId);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "执行记录不存在");
        return rows.getFirst();
    }

    @Override
    public synchronized List<Run> contextHistory(String runId) {
        return records.contextHistory(run(runId), RUN_MAPPER);
    }

    @Override
    public synchronized void saveModelRequest(ModelRequest request) {
        records.saveModelRequest(request, this::run, this::appendEvent);
    }

    @Override
    public synchronized CompactedHistory compactedHistory(String sessionId, String workspace) {
        return records.compactedHistory(sessionId, workspace);
    }

    @Override
    public synchronized void saveCompactedHistory(String sessionId, String workspace, CompactedHistory history) {
        session(sessionId);
        records.saveCompactedHistory(sessionId, workspace, history);
    }

    @Override
    public synchronized ModelRequest modelRequest(String runId) {
        return records.modelRequest(runId, this::run);
    }

    @Override
    public synchronized List<ModelRequest> modelRequests(String runId) {
        return records.modelRequests(runId, this::run);
    }

    @Override
    public synchronized Run bindWorkspace(String runId, String workspace) {
        if (run(runId).status() != RunStatus.RUNNING || modelRequest(runId) != null)
            throw new HarnessException("CONFLICT", "不能更改工作区");
        jdbc.update("UPDATE harness_run SET workspace=? WHERE id=?", workspace, runId);
        return run(runId);
    }

    @Override
    public synchronized List<ModelMessage> conversation(String runId) {
        return records.conversation(run(runId));
    }

    @Override
    public synchronized void completeRun(String runId, List<ModelMessage> messages) {
        completeRun(runId, messages, "");
    }

    @Override
    public synchronized void completeRun(String runId, List<ModelMessage> messages, String detail) {
        ModelMessage.validate(messages, false);
        tx.executeWithoutResult(status -> {
            records.saveCompletedConversation(run(runId), messages);
            transition(runId, RunStatus.COMPLETED, detail);
        });
    }

    @Override
    public synchronized void beginTools(String runId, int step, List<ToolCall> calls) {
        records.beginTools(runId, step, calls, this::run, this::appendEvent);
    }

    @Override
    public synchronized void updateTool(String runId, String callId, String next, String output) {
        records.updateTool(runId, callId, next, output, this::run, this::appendEvent);
    }

    @Override
    public synchronized List<ToolExecution> tools(String runId) {
        run(runId);
        return records.tools(runId);
    }

    @Override
    public synchronized void appendReasoning(String runId, int step, String text) {
        records.appendReasoning(runId, step, text, this::run, this::appendEvent);
    }

    @Override
    public synchronized void recordUsage(String runId, int step, ModelUsage usage) {
        records.recordUsage(runId, step, usage, this::run, this::appendEvent);
    }

    @Override
    public synchronized List<UsageEntry> usageSince(Instant from) {
        return records.usageSince(from);
    }

    @Override
    public synchronized List<RunStep> steps(String runId) {
        run(runId);
        return records.steps(runId);
    }

    @Override
    public synchronized Submission submit(
            String sessionId, String requestId, String prompt, String scenario, ModelInfo model) {
        return submit(
                sessionId,
                requestId,
                prompt,
                scenario,
                model,
                ModelSelection.DEFAULT,
                new ModelSelection("default", ""));
    }

    @Override
    public synchronized Submission retry(
            String sessionId, String requestId, String prompt, String scenario, ModelSelection requested) {
        session(sessionId);
        try {
            if (!UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException();
        } catch (Exception error) {
            throw new HarnessException("INVALID", "requestId 必须是标准 UUID");
        }
        // 长度不设固定上限，由模型上下文预算兜底。
        if (prompt == null || prompt.isBlank()) throw new HarnessException("INVALID", "输入不能为空");
        if (scenario == null || !List.of("NORMAL", "FAIL", "SLOW", "ACP").contains(scenario)) {
            throw new HarnessException("INVALID", "未知模拟场景");
        }
        var existing = jdbc.query(
                "SELECT * FROM harness_run WHERE session_id=? AND request_id=?", RUN_MAPPER, sessionId, requestId);
        if (!existing.isEmpty()) {
            Run run = existing.getFirst();
            var priorSelection = decode(
                    jdbc.queryForObject(
                            "SELECT requested_selection FROM harness_run WHERE id=?", String.class, run.id()),
                    ModelSelection.class);
            if (!run.prompt().equals(prompt) || !run.scenario().equals(scenario) || !requested.equals(priorSelection)) {
                throw new HarnessException("CONFLICT", "相同 requestId 不能用于不同输入、模型或思考强度");
            }
            return new Submission(run, false);
        }
        return null;
    }

    @Override
    public synchronized Submission submit(
            String sessionId,
            String requestId,
            String prompt,
            String scenario,
            ModelInfo model,
            ModelSelection requested,
            ModelSelection selected) {
        return submit(sessionId, requestId, prompt, scenario, model, requested, selected, "{}");
    }

    @Override
    public synchronized Submission submit(
            String sessionId,
            String requestId,
            String prompt,
            String scenario,
            ModelInfo model,
            ModelSelection requested,
            ModelSelection selected,
            String extensions) {
        return submit(sessionId, requestId, prompt, scenario, model, requested, selected, extensions, null);
    }

    @Override
    public synchronized Submission submit(
            String sessionId,
            String requestId,
            String prompt,
            String scenario,
            ModelInfo model,
            ModelSelection requested,
            ModelSelection selected,
            String extensions,
            ApprovalMode approvalMode) {
        var retry = retry(sessionId, requestId, prompt, scenario, requested);
        if (retry != null) return retry;
        return tx.execute(status -> {
            boolean external = scenario.equals("ACP");
            if (external != model.provider().equals("external-acp"))
                throw new HarnessException("INVALID", "执行类型与任务不匹配");
            if (!external && !model.provider().equals("simulated") && !scenario.equals("NORMAL")) {
                throw new HarnessException("INVALID", "真实模型仅支持 NORMAL 场景");
            }
            if (!model.configured()) throw new HarnessException("INVALID", "请在页面的模型配置中设置模型名称和 API Key");
            String active = jdbc.queryForObject(
                    "SELECT active_run_id FROM harness_session WHERE id=?", String.class, sessionId);
            if (active != null) throw new HarnessException("BUSY", "此会话已有正在执行的任务");
            String id = UUID.randomUUID().toString();
            String mode = approvalMode == null ? session(sessionId).approvalMode() : approvalMode.name();
            jdbc.update(
                    """
                    INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at,provider,model,workspace,model_profile_id,reasoning_effort,requested_selection,plugin_snapshot,approval_mode,memory_workspace_id)
                    VALUES (?,?,?,?,?,'QUEUED','','',?,?,?,?,?,?,?,?,?,?)
                    """,
                    id,
                    sessionId,
                    requestId,
                    prompt,
                    scenario,
                    now(),
                    model.provider(),
                    model.model(),
                    session(sessionId).workspace(),
                    selected.profileId(),
                    selected.reasoningEffort(),
                    encode(requested),
                    extensions,
                    mode,
                    session(sessionId).workspaceId());
            if (external) jdbc.update("UPDATE harness_session SET active_run_id=? WHERE id=?", id, sessionId);
            else
                jdbc.update(
                        "UPDATE harness_session SET active_run_id=?,model_profile_id=?,reasoning_effort=?,approval_mode=? WHERE id=?",
                        id,
                        selected.profileId(),
                        selected.reasoningEffort(),
                        mode,
                        sessionId);
            appendEvent(sessionId, id, "STATE", RunStatus.QUEUED, "");
            return new Submission(run(id), true);
        });
    }

    @Override
    public synchronized Run transition(String runId, RunStatus next, String detail) {
        return tx.execute(status -> {
            Run run = run(runId);
            if (run.status() == next) return run;
            boolean allowed = switch (run.status()) {
                case QUEUED ->
                    next == RunStatus.RUNNING
                            || next == RunStatus.CANCELLING
                            || next == RunStatus.FAILED
                            || next == RunStatus.INTERRUPTED;
                case RUNNING ->
                    next == RunStatus.COMPLETED
                            || next == RunStatus.FAILED
                            || next == RunStatus.CANCELLING
                            || next == RunStatus.INTERRUPTED;
                case CANCELLING ->
                    next == RunStatus.CANCELLED || next == RunStatus.TIMED_OUT || next == RunStatus.INTERRUPTED;
                default -> false;
            };
            if (!allowed) throw new HarnessException("CONFLICT", "非法执行状态转换");
            jdbc.update(
                    "UPDATE harness_run SET status=?,detail=?,state_updated_at=? WHERE id=?",
                    next.name(),
                    detail,
                    now(),
                    runId);
            if (!next.active()) {
                // After a restart, the last recorded event is a better end time than the recovery moment.
                OffsetDateTime finished = next != RunStatus.INTERRUPTED
                        ? now()
                        : jdbc.queryForObject(
                                "SELECT MAX(created_at) FROM harness_event WHERE run_id=?",
                                OffsetDateTime.class,
                                runId);
                jdbc.update(
                        "UPDATE harness_tool SET status=?,output=?,finished_at=? WHERE run_id=? AND status IN ('PENDING','RUNNING')",
                        next == RunStatus.INTERRUPTED ? "INTERRUPTED" : "CANCELLED",
                        "任务已结束，工具未完成；不会自动重跑",
                        finished,
                        runId);
                jdbc.update(
                        "UPDATE harness_step SET finished_at=? WHERE run_id=? AND finished_at IS NULL",
                        finished,
                        runId);
                jdbc.update(
                        "UPDATE harness_session SET active_run_id=NULL WHERE id=? AND active_run_id=?",
                        run.sessionId(),
                        runId);
            }
            appendEvent(run.sessionId(), runId, "STATE", next, detail);
            return run(runId);
        });
    }

    @Override
    public synchronized void appendText(String runId, String text) {
        if (text == null || text.length() > 4096) throw new IllegalArgumentException("Invalid model chunk");
        if (text.isEmpty()) return;
        tx.executeWithoutResult(status -> {
            Run run = run(runId);
            if (run.status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止接收输出");
            if (run.output().length() + text.length() > 100_000)
                throw new IllegalStateException("Output budget exceeded");
            jdbc.update("UPDATE harness_run SET output=? WHERE id=?", run.output() + text, runId);
            appendEvent(run.sessionId(), runId, "TEXT", null, text);
        });
    }

    @Override
    public synchronized void fileChangesUpdated(String runId) {
        tx.executeWithoutResult(status -> appendEvent(run(runId).sessionId(), runId, "FILE_CHANGE", null, "文件修改记录已更新"));
    }

    @Override
    public synchronized void commandsUpdated(String runId) {
        tx.executeWithoutResult(status -> appendEvent(run(runId).sessionId(), runId, "COMMAND", null, "命令记录已更新"));
    }

    @Override
    public synchronized void pluginsUpdated(String runId) {
        tx.executeWithoutResult(status -> appendEvent(run(runId).sessionId(), runId, "PLUGIN", null, "插件执行记录已更新"));
    }

    @Override
    public synchronized void questionsUpdated(String runId) {
        tx.executeWithoutResult(status -> appendEvent(run(runId).sessionId(), runId, "QUESTION", null, "提问记录已更新"));
    }

    private void appendEvent(String sessionId, String runId, String type, RunStatus state, String text) {
        jdbc.update("UPDATE harness_session SET last_seq=last_seq+1 WHERE id=?", sessionId);
        long seq = jdbc.queryForObject("SELECT last_seq FROM harness_session WHERE id=?", Long.class, sessionId);
        jdbc.update("""
                INSERT INTO harness_event(session_id,seq,run_id,event_type,status,event_text,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, sessionId, seq, runId, type, state == null ? null : state.name(), text, now());
    }

    @Override
    public synchronized List<RunEvent> events(String sessionId, long after, int limit) {
        session(sessionId);
        long last = jdbc.queryForObject("SELECT last_seq FROM harness_session WHERE id=?", Long.class, sessionId);
        if (after < 0 || after > last || limit < 1 || limit > 256) {
            throw new HarnessException("INVALID", "无效的事件游标或批次大小");
        }
        return jdbc.query(
                """
                SELECT * FROM harness_event WHERE session_id=? AND seq>? ORDER BY seq LIMIT ?
                """,
                (rs, row) -> new RunEvent(
                        rs.getString("session_id"),
                        rs.getLong("seq"),
                        rs.getString("run_id"),
                        rs.getString("event_type"),
                        rs.getString("status") == null ? null : RunStatus.valueOf(rs.getString("status")),
                        rs.getString("event_text"),
                        instant(rs, "created_at")),
                sessionId,
                after,
                limit);
    }

    @Override
    public synchronized void recoverInterruptedRuns() {
        tx.executeWithoutResult(status -> {
            var interrupted = jdbc.query(
                    "SELECT * FROM harness_run WHERE status IN ('QUEUED','RUNNING','CANCELLING')", RUN_MAPPER);
            for (Run run : interrupted) {
                transition(run.id(), RunStatus.INTERRUPTED, "服务已重启，任务中断；不会自动重跑");
            }
        });
    }
}
