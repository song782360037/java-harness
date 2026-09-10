package dev.javaharness.adapters;

import dev.javaharness.spi.*;
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
    private static final RowMapper<Run> RUN_MAPPER = (rs, row) -> new Run(
            rs.getString("id"), rs.getString("session_id"), rs.getString("request_id"),
            rs.getString("prompt"), rs.getString("scenario"), RunStatus.valueOf(rs.getString("status")),
            rs.getString("output"), rs.getString("detail"), instant(rs, "created_at"));
    private static final RowMapper<Session> SESSION_MAPPER = (rs, row) ->
            new Session(rs.getString("id"), rs.getString("title"), instant(rs, "created_at"));

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
        if (title == null || title.isBlank() || title.length() > 100) {
            throw new HarnessException("INVALID", "会话名称需为 1–100 个字符");
        }
        String id = UUID.randomUUID().toString();
        OffsetDateTime created = now();
        jdbc.update("INSERT INTO harness_session(id,title,created_at) VALUES (?,?,?)", id, title.strip(), created);
        return new Session(id, title.strip(), created.toInstant());
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

    @Override
    public synchronized Submission submit(String sessionId, String requestId, String prompt, String scenario) {
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
        return tx.execute(status -> {
            var existing = jdbc.query("SELECT * FROM harness_run WHERE session_id=? AND request_id=?",
                    RUN_MAPPER, sessionId, requestId);
            if (!existing.isEmpty()) {
                Run run = existing.getFirst();
                if (!run.prompt().equals(prompt) || !run.scenario().equals(scenario)) {
                    throw new HarnessException("CONFLICT", "相同 requestId 不能用于不同输入");
                }
                return new Submission(run, false);
            }
            String active = jdbc.queryForObject("SELECT active_run_id FROM harness_session WHERE id=?",
                    String.class, sessionId);
            if (active != null) throw new HarnessException("BUSY", "此会话已有正在执行的任务");
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at)
                    VALUES (?,?,?,?,?,'QUEUED','','',?)
                    """, id, sessionId, requestId, prompt, scenario, now());
            jdbc.update("UPDATE harness_session SET active_run_id=? WHERE id=?", id, sessionId);
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
