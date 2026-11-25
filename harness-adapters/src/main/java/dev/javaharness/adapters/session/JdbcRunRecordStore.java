package dev.javaharness.adapters.session;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelImage;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelUsage;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunInput;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.run.RunStep;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.session.SessionUsage;
import dev.javaharness.spi.session.UsageEntry;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolExecution;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Package-private persistence helper for run requests, compacted context, conversations, tool executions,
 * reasoning steps, and token usage ledgers. Invoked exclusively under {@link JdbcSessionStore}'s monitor lock.
 */
final class JdbcRunRecordStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    JdbcRunRecordStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Sum reported step usage across the session; steps without a report are skipped. */
    SessionUsage usage(String sessionId) {
        var totals = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(s.input_tokens),0) i, COALESCE(SUM(s.output_tokens),0) o,
                       COALESCE(SUM(s.cached_tokens),0) c, COALESCE(SUM(s.generation_millis),0) d
                FROM harness_step s JOIN harness_run r ON r.id=s.run_id
                WHERE r.session_id=? AND s.input_tokens IS NOT NULL
                """,
                (rs, row) -> new long[] {rs.getLong("i"), rs.getLong("o"), rs.getLong("c"), rs.getLong("d")},
                sessionId);
        var last = jdbc.queryForList("""
                SELECT input_tokens FROM harness_step
                WHERE run_id=(SELECT id FROM harness_run WHERE session_id=? AND parent_run_id IS NULL
                              ORDER BY created_at DESC, id DESC LIMIT 1)
                  AND input_tokens IS NOT NULL
                ORDER BY step DESC LIMIT 1
                """, Integer.class, sessionId);
        return new SessionUsage(totals[0], totals[1], totals[2], totals[3], last.isEmpty() ? 0 : last.getFirst());
    }

    List<Run> contextHistory(Run current, RowMapper<Run> runMapper) {
        return jdbc.query("""
                SELECT r.* FROM harness_run r
                JOIN harness_event e ON e.run_id=r.id AND e.event_type='STATE' AND e.status='QUEUED'
                WHERE r.session_id=? AND r.parent_run_id IS NULL AND r.scenario <> 'ACP' AND e.seq < (
                    SELECT seq FROM harness_event WHERE run_id=? AND event_type='STATE' AND status='QUEUED')
                ORDER BY e.seq
                """, runMapper, current.sessionId(), current.id());
    }

    /** 首个请求的用户消息须为原始提问，或群聊/Bot 上下文/召回记忆/附带内容块加原始提问。 */
    private static boolean prompted(String content, String prompt) {
        return content.equals(prompt)
                || (content.startsWith("<retrieved_memory>")
                                || content.startsWith("<group_chat>")
                                || content.startsWith("<bot_chat>")
                                || content.startsWith(RunInput.TAG))
                        && content.endsWith(prompt);
    }

    /** 会话记录中的用户消息须为原始提问，或附带内容块加原始提问；召回记忆和群聊上下文不进入会话记录。 */
    private static boolean attached(String content, String prompt) {
        return content.equals(prompt) || content.startsWith(RunInput.TAG) && content.endsWith(prompt);
    }

    /** Snapshots keep image names and types only; the image bytes are already kept with the attachments. */
    private static ModelRequest withoutImageData(ModelRequest request) {
        if (request.messages().stream().allMatch(message -> message.images().isEmpty())) return request;
        var messages = request.messages().stream()
                .map(message -> message.images().isEmpty()
                        ? message
                        : new ModelMessage(
                                message.role(),
                                message.content(),
                                message.toolCalls(),
                                message.toolCallId(),
                                message.images().stream()
                                        .map(ModelImage::withoutData)
                                        .toList()))
                .toList();
        return new ModelRequest(
                request.runId(),
                request.model(),
                request.scenario(),
                messages,
                request.context(),
                request.step(),
                request.workspace(),
                request.tools(),
                request.instructions(),
                request.finalAnswerOnly(),
                request.reasoningEffort());
    }

    void saveModelRequest(
            ModelRequest request, Function<String, Run> runLookup, JdbcWorkspaceStore.EventAppender events) {
        tx.executeWithoutResult(status -> {
            Run run = runLookup.apply(request.runId());
            if (run.status() != RunStatus.RUNNING
                    || !run.model().equals(request.model())
                    || !run.scenario().equals(request.scenario())
                    || !run.workspace().equals(request.workspace())
                    || (request.step() == 1
                            && !prompted(request.messages().getLast().content(), run.prompt()))) {
                throw new HarnessException("CONFLICT", "请求快照与当前任务不匹配");
            }
            if (request.step() == 1) {
                jdbc.update(
                        "INSERT INTO harness_model_request(run_id,request_json) VALUES (?,?)",
                        run.id(),
                        JdbcSessionStore.encode(withoutImageData(request)));
            } else {
                var prior = modelRequests(run.id(), runLookup);
                if (prior.size() != request.step() - 1) throw new HarnessException("CONFLICT", "模型步骤不连续");
                jdbc.update(
                        "INSERT INTO harness_model_step(run_id,step,request_json) VALUES (?,?,?)",
                        run.id(),
                        request.step(),
                        JdbcSessionStore.encode(withoutImageData(request)));
            }
            // A request snapshot starts the step; later text belongs to it until the next snapshot.
            OffsetDateTime started = JdbcSessionStore.now();
            jdbc.update(
                    "UPDATE harness_step SET finished_at=? WHERE run_id=? AND finished_at IS NULL", started, run.id());
            jdbc.update(
                    "INSERT INTO harness_step(run_id,step,output_start,started_at) VALUES (?,?,?,?)",
                    run.id(),
                    request.step(),
                    run.output().length(),
                    started);
            jdbc.update(
                    "UPDATE harness_run SET context_summary=? WHERE id=?",
                    JdbcSessionStore.encode(request.context()),
                    run.id());
            events.append(run.sessionId(), run.id(), "CONTEXT", null, JdbcSessionStore.encode(request.context()));
        });
    }

    SessionStore.CompactedHistory compactedHistory(String sessionId, String workspace) {
        var rows = jdbc.query(
                "SELECT through_run_id,covered_turns,summary FROM harness_context_summary WHERE session_id=? AND workspace=?",
                (rs, row) -> new SessionStore.CompactedHistory(rs.getString(1), rs.getInt(2), rs.getString(3)),
                sessionId,
                workspace);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void saveCompactedHistory(String sessionId, String workspace, SessionStore.CompactedHistory history) {
        jdbc.update(
                "MERGE INTO harness_context_summary KEY(session_id) VALUES (?,?,?,?,?,?)",
                sessionId,
                workspace,
                history.throughRunId(),
                history.coveredTurns(),
                history.summary(),
                JdbcSessionStore.now());
    }

    ModelRequest modelRequest(String runId, Function<String, Run> runLookup) {
        runLookup.apply(runId);
        var rows = jdbc.query(
                "SELECT request_json FROM harness_model_request WHERE run_id=?",
                (rs, row) -> JdbcSessionStore.decode(rs.getString(1), ModelRequest.class),
                runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<ModelRequest> modelRequests(String runId, Function<String, Run> runLookup) {
        var result = new ArrayList<ModelRequest>();
        var first = modelRequest(runId, runLookup);
        if (first != null) result.add(first);
        result.addAll(jdbc.query(
                "SELECT request_json FROM harness_model_step WHERE run_id=? ORDER BY step",
                (rs, row) -> JdbcSessionStore.decode(rs.getString(1), ModelRequest.class),
                runId));
        return List.copyOf(result);
    }

    List<ModelMessage> conversation(Run run) {
        var rows = jdbc.query(
                "SELECT messages_json FROM harness_conversation WHERE run_id=?",
                (rs, row) -> List.of(JdbcSessionStore.decode(rs.getString(1), ModelMessage[].class)),
                run.id());
        return rows.isEmpty()
                ? List.of(new ModelMessage("user", run.prompt()), new ModelMessage("assistant", run.output()))
                : rows.getFirst();
    }

    void saveCompletedConversation(Run current, List<ModelMessage> messages) {
        if (current.status() != RunStatus.RUNNING
                || !attached(messages.getFirst().content(), current.prompt())) {
            throw new HarnessException("CONFLICT", "完成记录与任务不匹配");
        }
        jdbc.update(
                "INSERT INTO harness_conversation(run_id,messages_json) VALUES (?,?)",
                current.id(),
                JdbcSessionStore.encode(messages));
    }

    void beginTools(
            String runId,
            int step,
            List<ToolCall> calls,
            Function<String, Run> runLookup,
            JdbcWorkspaceStore.EventAppender events) {
        tx.executeWithoutResult(status -> {
            Run run = runLookup.apply(runId);
            if (run.status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止");
            int ordinal = 0;
            for (ToolCall call : calls) {
                jdbc.update(
                        "INSERT INTO harness_tool(run_id,step,call_id,name,arguments,status,output,ordinal) VALUES (?,?,?,?,?,'PENDING','',?)",
                        runId,
                        step,
                        call.id(),
                        call.name(),
                        call.arguments(),
                        ordinal++);
            }
            events.append(run.sessionId(), runId, "TOOL", null, "工具调用已保存");
        });
    }

    void updateTool(
            String runId,
            String callId,
            String next,
            String output,
            Function<String, Run> runLookup,
            JdbcWorkspaceStore.EventAppender events) {
        // Full results are kept for read_tool_output; command and MCP output is capped near 32000 at the source.
        if (output.length() > 64_000) throw new IllegalArgumentException("Tool output exceeds budget");
        tx.executeWithoutResult(status -> {
            Run run = runLookup.apply(runId);
            if (run.status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止");
            String expected = next.equals("RUNNING") || next.equals("SKIPPED") ? "PENDING" : "RUNNING";
            if (!List.of("RUNNING", "COMPLETED", "FAILED", "SKIPPED").contains(next))
                throw new IllegalArgumentException("Invalid tool status");
            String time = next.equals("RUNNING") ? "started_at" : "finished_at";
            int updated = jdbc.update(
                    "UPDATE harness_tool SET status=?,output=?," + time
                            + "=? WHERE run_id=? AND call_id=? AND status=?",
                    next,
                    output,
                    JdbcSessionStore.now(),
                    runId,
                    callId,
                    expected);
            if (updated != 1) throw new HarnessException("CONFLICT", "工具状态不匹配");
            events.append(run.sessionId(), runId, "TOOL", null, "工具状态已更新");
        });
    }

    List<ToolExecution> tools(String runId) {
        return jdbc.query(
                "SELECT * FROM harness_tool WHERE run_id=? ORDER BY step,ordinal",
                (rs, row) -> new ToolExecution(
                        runId,
                        rs.getInt("step"),
                        rs.getString("call_id"),
                        rs.getString("name"),
                        rs.getString("arguments"),
                        rs.getString("status"),
                        rs.getString("output"),
                        JdbcSessionStore.optionalInstant(rs, "started_at"),
                        JdbcSessionStore.optionalInstant(rs, "finished_at")),
                runId);
    }

    void appendReasoning(
            String runId,
            int step,
            String text,
            Function<String, Run> runLookup,
            JdbcWorkspaceStore.EventAppender events) {
        if (text == null || text.length() > 4096) throw new IllegalArgumentException("Invalid reasoning chunk");
        if (text.isEmpty()) return;
        tx.executeWithoutResult(status -> {
            Run run = runLookup.apply(runId);
            if (run.status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止接收输出");
            var stored = jdbc.queryForList(
                    "SELECT reasoning FROM harness_step WHERE run_id=? AND step=?", String.class, runId, step);
            if (stored.isEmpty()) throw new HarnessException("CONFLICT", "模型步骤不存在");
            // Reasoning is display-only: an over-long trace is cut at the budget and later chunks are ignored.
            int remaining = 100_000 - stored.getFirst().length();
            if (remaining <= 0) return;
            int end = Math.min(remaining, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String kept = text.substring(0, end) + (end < text.length() ? "…" : "");
            jdbc.update(
                    "UPDATE harness_step SET reasoning=?,reasoning_at=? WHERE run_id=? AND step=?",
                    stored.getFirst() + kept,
                    JdbcSessionStore.now(),
                    runId,
                    step);
            // Clients refetch steps; the event only signals a change.
            events.append(run.sessionId(), runId, "REASONING", null, "");
        });
    }

    void recordUsage(
            String runId,
            int step,
            ModelUsage usage,
            Function<String, Run> runLookup,
            JdbcWorkspaceStore.EventAppender events) {
        tx.executeWithoutResult(status -> {
            Run run = runLookup.apply(runId);
            if (run.status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止接收输出");
            int updated = jdbc.update(
                    """
                    UPDATE harness_step SET input_tokens=?,output_tokens=?,cached_tokens=?,generation_millis=?
                    WHERE run_id=? AND step=?
                    """,
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.cachedTokens(),
                    usage.durationMillis(),
                    runId,
                    step);
            if (updated == 0) throw new HarnessException("CONFLICT", "模型步骤不存在");
            jdbc.update(
                    """
                    MERGE INTO harness_usage_ledger(run_id,step,provider,model,input_tokens,output_tokens,cached_tokens,recorded_at)
                    KEY(run_id,step) VALUES (?,?,?,?,?,?,?,?)
                    """,
                    runId,
                    step,
                    run.provider(),
                    run.model(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.cachedTokens(),
                    JdbcSessionStore.now());
            // Clients refetch the snapshot; the event only signals a change.
            events.append(run.sessionId(), runId, "USAGE", null, "");
        });
    }

    List<UsageEntry> usageSince(Instant from) {
        return jdbc.query(
                """
                SELECT recorded_at,provider,model,input_tokens,output_tokens,cached_tokens
                FROM harness_usage_ledger WHERE recorded_at>=? ORDER BY recorded_at
                """,
                (rs, row) -> new UsageEntry(
                        JdbcSessionStore.instant(rs, "recorded_at"),
                        rs.getString("provider"),
                        rs.getString("model"),
                        rs.getInt("input_tokens"),
                        rs.getInt("output_tokens"),
                        rs.getInt("cached_tokens")),
                from.atOffset(ZoneOffset.UTC));
    }

    List<RunStep> steps(String runId) {
        return jdbc.query(
                "SELECT * FROM harness_step WHERE run_id=? ORDER BY step",
                (rs, row) -> new RunStep(
                        rs.getInt("step"),
                        rs.getInt("output_start"),
                        rs.getString("reasoning"),
                        JdbcSessionStore.instant(rs, "started_at"),
                        JdbcSessionStore.optionalInstant(rs, "reasoning_at"),
                        JdbcSessionStore.optionalInstant(rs, "finished_at")),
                runId);
    }

    /** 在普通可见会话的标题与顶层对话内容中检索关键字，每个会话保留最相关的一条命中摘要。 */
    List<SessionStore.SearchHit> searchSessions(String query, int limit, RowMapper<Session> sessionMapper) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) return List.of();
        int capped = Math.max(1, Math.min(limit, 50));
        String pattern = "%" + escapeLike(normalized) + "%";
        var hits = new LinkedHashMap<String, SessionStore.SearchHit>();
        var titleMatches = jdbc.query("""
                SELECT * FROM harness_session
                WHERE group_id IS NULL AND flow_id IS NULL AND bot_id IS NULL AND bot_task_id IS NULL
                  AND LOWER(title) LIKE LOWER(?) ESCAPE '\\'
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, sessionMapper, pattern, capped);
        for (Session session : titleMatches) {
            hits.put(session.id(), new SessionStore.SearchHit(session, "title", session.title(), session.createdAt()));
        }
        if (hits.size() < capped) {
            var runMatches = jdbc.query(
                    """
                    SELECT s.id, s.title, s.created_at, s.workspace, s.model_profile_id, s.reasoning_effort,
                           s.workspace_id, s.approval_mode,
                           r.prompt AS matched_prompt, r.output AS matched_output, r.created_at AS matched_at
                    FROM harness_run r
                    JOIN harness_session s ON s.id = r.session_id
                    WHERE s.group_id IS NULL AND s.flow_id IS NULL AND s.bot_id IS NULL AND s.bot_task_id IS NULL
                      AND r.parent_run_id IS NULL
                      AND (LOWER(r.prompt) LIKE LOWER(?) ESCAPE '\\' OR LOWER(r.output) LIKE LOWER(?) ESCAPE '\\')
                    ORDER BY r.created_at DESC, r.id DESC
                    LIMIT ?
                    """,
                    (rs, row) -> {
                        Session session = sessionMapper.mapRow(rs, row);
                        String prompt = rs.getString("matched_prompt");
                        String output = rs.getString("matched_output");
                        Instant matchedAt = JdbcSessionStore.instant(rs, "matched_at");
                        boolean inPrompt = containsIgnoreCase(prompt, normalized);
                        String matchedIn = inPrompt ? "prompt" : "output";
                        String source = inPrompt ? prompt : output;
                        return new SessionStore.SearchHit(session, matchedIn, snippet(source, normalized), matchedAt);
                    },
                    pattern,
                    pattern,
                    capped * 3);
            for (SessionStore.SearchHit hit : runMatches) {
                hits.putIfAbsent(hit.session().id(), hit);
                if (hits.size() >= capped) break;
            }
        }
        return List.copyOf(hits.values());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean containsIgnoreCase(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static String snippet(String text, String query) {
        if (text == null || text.isBlank()) return "";
        String flat = text.replaceAll("\\s+", " ").strip();
        int max = 110;
        if (flat.length() <= max) return flat;
        int index = flat.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (index < 0) index = 0;
        int start = Math.max(0, index - 36);
        int end = Math.min(flat.length(), start + max);
        if (end - start < max && start > 0) start = Math.max(0, end - max);
        if (start > 0 && Character.isLowSurrogate(flat.charAt(start))) start++;
        if (end < flat.length() && Character.isHighSurrogate(flat.charAt(end - 1))) end--;
        return (start > 0 ? "…" : "") + flat.substring(start, end).strip() + (end < flat.length() ? "…" : "");
    }
}
