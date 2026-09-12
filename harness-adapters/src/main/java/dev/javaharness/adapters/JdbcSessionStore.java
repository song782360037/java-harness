package dev.javaharness.adapters;

import dev.javaharness.spi.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * One store instance owns a single-process H2 database. The monitor serializes views and mutations;
 * each mutation commits its projection, sequence and event together in a database transaction.
 */
public final class JdbcSessionStore implements SessionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RowMapper<Run> RUN_MAPPER = (rs, row) -> new Run(
            rs.getString("id"), rs.getString("session_id"), rs.getString("request_id"),
            rs.getString("prompt"), rs.getString("scenario"), RunStatus.valueOf(rs.getString("status")),
            rs.getString("output"), rs.getString("detail"), instant(rs, "created_at"),
            rs.getString("provider"), rs.getString("model"), decode(rs.getString("context_summary"), ContextSummary.class), rs.getString("workspace"),
            rs.getString("model_profile_id"), rs.getString("reasoning_effort"));
    private static final RowMapper<Session> SESSION_MAPPER = (rs, row) ->
            new Session(rs.getString("id"), rs.getString("title"), instant(rs, "created_at"), rs.getString("workspace"),
                    rs.getString("model_profile_id"), rs.getString("reasoning_effort"));

    public JdbcSessionStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Override
    public synchronized Session createSession(String title) {
        return createSession(title, "");
    }

    @Override public synchronized Session createSession(String title, String workspace) {
        if (title == null || title.isBlank() || title.length() > 100) {
            throw new HarnessException("INVALID", "会话名称需为 1–100 个字符");
        }
        String id = UUID.randomUUID().toString();
        OffsetDateTime created = now();
        checkWorkspace(workspace);
        jdbc.update("INSERT INTO harness_session(id,title,created_at,workspace) VALUES (?,?,?,?)", id, title.strip(), created, workspace);
        return new Session(id, title.strip(), created.toInstant(), workspace, "default", "");
    }

    private static void checkWorkspace(String workspace) {
        if (workspace == null || workspace.length() > 4096) throw new HarnessException("INVALID", "无效工作区路径");
    }

    @Override public synchronized Session updateWorkspace(String sessionId, String workspace) {
        checkWorkspace(workspace);
        return tx.execute(status -> {
            session(sessionId);
            String active = jdbc.queryForObject("SELECT active_run_id FROM harness_session WHERE id=?", String.class, sessionId);
            if (active != null) throw new HarnessException("BUSY", "请先停止当前任务，再切换工作区");
            jdbc.update("UPDATE harness_session SET workspace=? WHERE id=?", workspace, sessionId);
            appendEvent(sessionId, null, "WORKSPACE", null, "会话工作区已更新");
            return session(sessionId);
        });
    }

    @Override
    public synchronized List<Session> sessions() {
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
        var runs = jdbc.query("SELECT * FROM harness_run WHERE session_id=? ORDER BY created_at,id",
                RUN_MAPPER, sessionId);
        long seq = jdbc.queryForObject("SELECT last_seq FROM harness_session WHERE id=?", Long.class, sessionId);
        return new SessionSnapshot(session, runs, seq);
    }

    @Override
    public synchronized Run run(String runId) {
        var rows = jdbc.query("SELECT * FROM harness_run WHERE id=?", RUN_MAPPER, runId);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "执行记录不存在");
        return rows.getFirst();
    }

    private static <T> T decode(String value, Class<T> type) {
        if (value == null) return null;
        try { return JSON.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Invalid stored context data"); }
    }

    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode context data"); }
    }

    @Override
    public synchronized List<Run> contextHistory(String runId) {
        Run current = run(runId);
        return jdbc.query("""
                SELECT r.* FROM harness_run r
                JOIN harness_event e ON e.run_id=r.id AND e.event_type='STATE' AND e.status='QUEUED'
                WHERE r.session_id=? AND e.seq < (
                    SELECT seq FROM harness_event WHERE run_id=? AND event_type='STATE' AND status='QUEUED')
                ORDER BY e.seq
                """, RUN_MAPPER, current.sessionId(), runId);
    }

    @Override
    public synchronized void saveModelRequest(ModelRequest request) {
        tx.executeWithoutResult(status -> {
            Run run = run(request.runId());
            if (run.status() != RunStatus.RUNNING || !run.model().equals(request.model())
                    || !run.scenario().equals(request.scenario())
                    || !run.workspace().equals(request.workspace())
                    || (request.step() == 1 && !run.prompt().equals(request.messages().getLast().content()))) {
                throw new HarnessException("CONFLICT", "请求快照与当前任务不匹配");
            }
            if (request.step() == 1) {
                jdbc.update("INSERT INTO harness_model_request(run_id,request_json) VALUES (?,?)", run.id(), encode(request));
            } else {
                var prior = modelRequests(run.id());
                if (prior.size() != request.step() - 1) throw new HarnessException("CONFLICT", "模型步骤不连续");
                jdbc.update("INSERT INTO harness_model_step(run_id,step,request_json) VALUES (?,?,?)", run.id(), request.step(), encode(request));
            }
            jdbc.update("UPDATE harness_run SET context_summary=? WHERE id=?", encode(request.context()), run.id());
            appendEvent(run.sessionId(), run.id(), "CONTEXT", null, encode(request.context()));
        });
    }

    @Override
    public synchronized ModelRequest modelRequest(String runId) {
        run(runId);
        var rows = jdbc.query("SELECT request_json FROM harness_model_request WHERE run_id=?",
                (rs, row) -> decode(rs.getString(1), ModelRequest.class), runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override public synchronized List<ModelRequest> modelRequests(String runId) {
        var result = new java.util.ArrayList<ModelRequest>();
        var first = modelRequest(runId);
        if (first != null) result.add(first);
        result.addAll(jdbc.query("SELECT request_json FROM harness_model_step WHERE run_id=? ORDER BY step",
                (rs, row) -> decode(rs.getString(1), ModelRequest.class), runId));
        return List.copyOf(result);
    }

    @Override public synchronized Run bindWorkspace(String runId, String workspace) {
        if (run(runId).status() != RunStatus.RUNNING || modelRequest(runId) != null) throw new HarnessException("CONFLICT", "不能更改工作区");
        jdbc.update("UPDATE harness_run SET workspace=? WHERE id=?", workspace, runId);
        return run(runId);
    }

    @Override public synchronized List<ModelMessage> conversation(String runId) {
        Run run = run(runId);
        var rows = jdbc.query("SELECT messages_json FROM harness_conversation WHERE run_id=?",
                (rs, row) -> List.of(decode(rs.getString(1), ModelMessage[].class)), runId);
        return rows.isEmpty() ? List.of(new ModelMessage("user", run.prompt()), new ModelMessage("assistant", run.output())) : rows.getFirst();
    }

    @Override public synchronized void completeRun(String runId, List<ModelMessage> messages) {
        completeRun(runId, messages, "");
    }

    @Override public synchronized void completeRun(String runId, List<ModelMessage> messages, String detail) {
        ModelMessage.validate(messages, false);
        tx.executeWithoutResult(status -> {
            Run current = run(runId);
            if (current.status() != RunStatus.RUNNING || !messages.getFirst().content().equals(current.prompt())) {
                throw new HarnessException("CONFLICT", "完成记录与任务不匹配");
            }
            jdbc.update("INSERT INTO harness_conversation(run_id,messages_json) VALUES (?,?)", runId, encode(messages));
            transition(runId, RunStatus.COMPLETED, detail);
        });
    }

    @Override public synchronized void beginTools(String runId, int step, List<ToolCall> calls) {
        tx.executeWithoutResult(status -> {
            Run run = run(runId);
            if (run.status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止");
            int ordinal = 0;
            for (ToolCall call : calls) {
                jdbc.update("INSERT INTO harness_tool(run_id,step,call_id,name,arguments,status,output,ordinal) VALUES (?,?,?,?,?,'PENDING','',?)",
                        runId, step, call.id(), call.name(), call.arguments(), ordinal++);
            }
            appendEvent(run.sessionId(), runId, "TOOL", null, "工具调用已保存");
        });
    }

    @Override public synchronized void updateTool(String runId, String callId, String next, String output) {
        if (output.length() > 12_000) throw new IllegalArgumentException("Tool output exceeds budget");
        tx.executeWithoutResult(status -> {
            Run run = run(runId);
            if (run.status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止");
            String expected = next.equals("RUNNING") || next.equals("SKIPPED") ? "PENDING" : "RUNNING";
            if (!List.of("RUNNING", "COMPLETED", "FAILED", "SKIPPED").contains(next)) throw new IllegalArgumentException("Invalid tool status");
            int updated = jdbc.update("UPDATE harness_tool SET status=?,output=? WHERE run_id=? AND call_id=? AND status=?", next, output, runId, callId, expected);
            if (updated != 1) throw new HarnessException("CONFLICT", "工具状态不匹配");
            appendEvent(run.sessionId(), runId, "TOOL", null, "工具状态已更新");
        });
    }

    @Override public synchronized List<ToolExecution> tools(String runId) {
        run(runId);
        return jdbc.query("SELECT * FROM harness_tool WHERE run_id=? ORDER BY step,ordinal", (rs, row) ->
                new ToolExecution(runId, rs.getInt("step"), rs.getString("call_id"), rs.getString("name"),
                        rs.getString("arguments"), rs.getString("status"), rs.getString("output")), runId);
    }

    @Override
    public synchronized Submission submit(String sessionId, String requestId, String prompt, String scenario, ModelInfo model) {
        return submit(sessionId, requestId, prompt, scenario, model, ModelSelection.DEFAULT, new ModelSelection("default", ""));
    }

    @Override public synchronized Submission retry(String sessionId, String requestId, String prompt, String scenario, ModelSelection requested) {
        session(sessionId);
        try {
            if (!UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException();
        } catch (Exception error) {
            throw new HarnessException("INVALID", "requestId 必须是标准 UUID");
        }
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) {
            throw new HarnessException("INVALID", "输入需为 1–4000 个字符");
        }
        if (scenario == null || !List.of("NORMAL", "FAIL", "SLOW").contains(scenario)) {
            throw new HarnessException("INVALID", "未知模拟场景");
        }
        var existing = jdbc.query("SELECT * FROM harness_run WHERE session_id=? AND request_id=?",
                    RUN_MAPPER, sessionId, requestId);
            if (!existing.isEmpty()) {
                Run run = existing.getFirst();
                var priorSelection = decode(jdbc.queryForObject("SELECT requested_selection FROM harness_run WHERE id=?", String.class, run.id()), ModelSelection.class);
                if (!run.prompt().equals(prompt) || !run.scenario().equals(scenario) || !requested.equals(priorSelection)) {
                    throw new HarnessException("CONFLICT", "相同 requestId 不能用于不同输入、模型或思考强度");
                }
                return new Submission(run, false);
            }
        return null;
    }

    @Override public synchronized Submission submit(String sessionId, String requestId, String prompt, String scenario,
                                                   ModelInfo model, ModelSelection requested, ModelSelection selected) {
        var retry = retry(sessionId, requestId, prompt, scenario, requested);
        if (retry != null) return retry;
        return tx.execute(status -> {
            if (!model.provider().equals("simulated") && !scenario.equals("NORMAL")) {
                throw new HarnessException("INVALID", "真实模型仅支持 NORMAL 场景");
            }
            if (!model.configured()) throw new HarnessException("INVALID", "请在页面的模型配置中设置模型名称和 API Key");
            String active = jdbc.queryForObject("SELECT active_run_id FROM harness_session WHERE id=?",
                    String.class, sessionId);
            if (active != null) throw new HarnessException("BUSY", "此会话已有正在执行的任务");
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at,provider,model,workspace,model_profile_id,reasoning_effort,requested_selection)
                    VALUES (?,?,?,?,?,'QUEUED','','',?,?,?,?,?,?,?)
                    """, id, sessionId, requestId, prompt, scenario, now(), model.provider(), model.model(), session(sessionId).workspace(),
                    selected.profileId(), selected.reasoningEffort(), encode(requested));
            jdbc.update("UPDATE harness_session SET active_run_id=?,model_profile_id=?,reasoning_effort=? WHERE id=?",
                    id, selected.profileId(), selected.reasoningEffort(), sessionId);
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
                case QUEUED -> next == RunStatus.RUNNING || next == RunStatus.CANCELLING
                        || next == RunStatus.FAILED || next == RunStatus.INTERRUPTED;
                case RUNNING -> next == RunStatus.COMPLETED || next == RunStatus.FAILED
                        || next == RunStatus.CANCELLING || next == RunStatus.INTERRUPTED;
                case CANCELLING -> next == RunStatus.CANCELLED || next == RunStatus.TIMED_OUT
                        || next == RunStatus.INTERRUPTED;
                default -> false;
            };
            if (!allowed) throw new HarnessException("CONFLICT", "非法执行状态转换");
            jdbc.update("UPDATE harness_run SET status=?,detail=? WHERE id=?", next.name(), detail, runId);
            if (!next.active()) {
                jdbc.update("UPDATE harness_tool SET status=?,output=? WHERE run_id=? AND status IN ('PENDING','RUNNING')",
                        next == RunStatus.INTERRUPTED ? "INTERRUPTED" : "CANCELLED", "任务已结束，工具未完成；不会自动重跑", runId);
                jdbc.update("UPDATE harness_session SET active_run_id=NULL WHERE id=? AND active_run_id=?",
                        run.sessionId(), runId);
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
            if (run.output().length() + text.length() > 100_000) throw new IllegalStateException("Output budget exceeded");
            jdbc.update("UPDATE harness_run SET output=? WHERE id=?", run.output() + text, runId);
            appendEvent(run.sessionId(), runId, "TEXT", null, text);
        });
    }

    @Override public synchronized void fileChangesUpdated(String runId) {
        tx.executeWithoutResult(status -> appendEvent(run(runId).sessionId(), runId, "FILE_CHANGE", null, "文件修改记录已更新"));
    }

    @Override public synchronized void commandsUpdated(String runId) {
        tx.executeWithoutResult(status -> appendEvent(run(runId).sessionId(), runId, "COMMAND", null, "命令记录已更新"));
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
        return jdbc.query("""
                SELECT * FROM harness_event WHERE session_id=? AND seq>? ORDER BY seq LIMIT ?
                """, (rs, row) -> new RunEvent(rs.getString("session_id"), rs.getLong("seq"),
                rs.getString("run_id"), rs.getString("event_type"),
                rs.getString("status") == null ? null : RunStatus.valueOf(rs.getString("status")),
                rs.getString("event_text"), instant(rs, "created_at")), sessionId, after, limit);
    }

    @Override
    public synchronized void recoverInterruptedRuns() {
        tx.executeWithoutResult(status -> {
            var interrupted = jdbc.query("SELECT * FROM harness_run WHERE status IN ('QUEUED','RUNNING','CANCELLING')",
                    RUN_MAPPER);
            for (Run run : interrupted) {
                transition(run.id(), RunStatus.INTERRUPTED, "服务已重启，任务中断；不会自动重跑");
            }
        });
    }
}
