package dev.javaharness.adapters.automation;

import dev.javaharness.spi.HarnessException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** 定时自动化及其触发历史的 JDBC 存储；并发协调由调用方负责。 */
public final class AutomationStore {
    /**
     * 一个定时自动化；reasoningEffort 为 null 表示使用模型配置的默认强度，nextRunAt 为 null 表示不会自动触发，
     * approvalMode 为每次触发的运行所用的审批模式。
     */
    public record Automation(
            String id,
            String name,
            String prompt,
            String workspaceId,
            String modelProfileId,
            String reasoningEffort,
            String scheduleKind,
            String scheduleTime,
            int scheduleWeekday,
            String cron,
            boolean allowCommands,
            String approvalMode,
            boolean enabled,
            Instant nextRunAt,
            Instant createdAt,
            long revision) {}

    /** 一次触发记录；runStatus 来自关联运行，会话被删除后为 null。 */
    public record Execution(
            String id,
            String automationId,
            String trigger,
            Instant scheduledFor,
            Instant startedAt,
            String status,
            String detail,
            String sessionId,
            String runId,
            String runStatus) {}

    private static final RowMapper<Automation> AUTOMATION = (rs, row) -> new Automation(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("prompt"),
            rs.getString("workspace_id"),
            rs.getString("model_profile_id"),
            rs.getString("reasoning_effort"),
            rs.getString("schedule_kind"),
            rs.getString("schedule_time"),
            rs.getInt("schedule_weekday"),
            rs.getString("cron"),
            rs.getBoolean("allow_commands"),
            rs.getString("approval_mode"),
            rs.getBoolean("enabled"),
            instant(rs.getObject("next_run_at", OffsetDateTime.class)),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getLong("revision"));
    private static final RowMapper<Execution> EXECUTION = (rs, row) -> new Execution(
            rs.getString("id"),
            rs.getString("automation_id"),
            rs.getString("trigger_kind"),
            instant(rs.getObject("scheduled_for", OffsetDateTime.class)),
            rs.getObject("started_at", OffsetDateTime.class).toInstant(),
            rs.getString("status"),
            rs.getString("detail"),
            rs.getString("session_id"),
            rs.getString("run_id"),
            rs.getString("run_status"));
    private static final String EXECUTIONS =
            "SELECT e.*, r.status AS run_status FROM harness_automation_run e LEFT JOIN harness_run r ON r.id=e.run_id ";

    private final JdbcTemplate jdbc;

    public AutomationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Automation> list() {
        return jdbc.query("SELECT * FROM harness_automation ORDER BY created_at, id", AUTOMATION);
    }

    public Automation get(String id) {
        var rows = jdbc.query("SELECT * FROM harness_automation WHERE id=?", AUTOMATION, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "自动化不存在");
        return rows.getFirst();
    }

    /** Enabled automations whose next trigger time has arrived. */
    public List<Automation> due(Instant now) {
        return jdbc.query(
                "SELECT * FROM harness_automation WHERE enabled AND next_run_at<=? ORDER BY next_run_at, id",
                AUTOMATION,
                time(now));
    }

    /** Insert when the id is empty, otherwise update when the revision still matches. */
    public Automation save(Automation value) {
        if (value.id().isEmpty()) {
            String id = UUID.randomUUID().toString();
            jdbc.update(
                    """
                    INSERT INTO harness_automation(id,name,prompt,workspace_id,model_profile_id,reasoning_effort,schedule_kind,
                        schedule_time,schedule_weekday,cron,allow_commands,approval_mode,enabled,next_run_at,created_at,revision)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)
                    """,
                    id,
                    value.name(),
                    value.prompt(),
                    value.workspaceId(),
                    value.modelProfileId(),
                    value.reasoningEffort(),
                    value.scheduleKind(),
                    value.scheduleTime(),
                    value.scheduleWeekday(),
                    value.cron(),
                    value.allowCommands(),
                    value.approvalMode(),
                    value.enabled(),
                    time(value.nextRunAt()),
                    time(value.createdAt()));
            return get(id);
        }
        int updated = jdbc.update(
                """
                UPDATE harness_automation SET name=?,prompt=?,workspace_id=?,model_profile_id=?,reasoning_effort=?,schedule_kind=?,
                    schedule_time=?,schedule_weekday=?,cron=?,allow_commands=?,approval_mode=?,enabled=?,next_run_at=?,
                    revision=revision+1
                WHERE id=? AND revision=?
                """,
                value.name(),
                value.prompt(),
                value.workspaceId(),
                value.modelProfileId(),
                value.reasoningEffort(),
                value.scheduleKind(),
                value.scheduleTime(),
                value.scheduleWeekday(),
                value.cron(),
                value.allowCommands(),
                value.approvalMode(),
                value.enabled(),
                time(value.nextRunAt()),
                value.id(),
                value.revision());
        if (updated == 0) {
            get(value.id());
            throw new HarnessException("CONFLICT", "自动化已被修改，请刷新后重试");
        }
        return get(value.id());
    }

    /** Advance the next trigger time without changing the user-visible revision. */
    public void schedule(String id, Instant next) {
        jdbc.update("UPDATE harness_automation SET next_run_at=? WHERE id=?", time(next), id);
    }

    /** Delete the automation and its history; sessions it created are kept. */
    public void delete(String id, long revision) {
        if (jdbc.update("DELETE FROM harness_automation WHERE id=? AND revision=?", id, revision) == 0) {
            get(id);
            throw new HarnessException("CONFLICT", "自动化已被修改，请刷新后重试");
        }
    }

    public Execution record(
            String automationId,
            String trigger,
            Instant scheduledFor,
            Instant startedAt,
            String status,
            String detail,
            String sessionId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO harness_automation_run(id,automation_id,trigger_kind,scheduled_for,started_at,status,detail,session_id)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                id,
                automationId,
                trigger,
                time(scheduledFor),
                time(startedAt),
                status,
                truncate(detail),
                sessionId);
        return execution(id);
    }

    public Execution attachRun(String id, String runId) {
        jdbc.update("UPDATE harness_automation_run SET run_id=? WHERE id=?", runId, id);
        return execution(id);
    }

    public Execution fail(String id, String detail) {
        jdbc.update("UPDATE harness_automation_run SET status='FAILED',detail=? WHERE id=?", truncate(detail), id);
        return execution(id);
    }

    /** Newest first, at most the given count; a skipped summary sorts after the run triggered in the same check. */
    public List<Execution> executions(String automationId, int limit) {
        get(automationId);
        return jdbc.query(
                EXECUTIONS + "WHERE e.automation_id=? ORDER BY e.started_at DESC, "
                        + "CASE WHEN e.status='SKIPPED' THEN 1 ELSE 0 END, e.id DESC LIMIT ?",
                EXECUTION,
                automationId,
                limit);
    }

    /** The newest trigger that started a run, or null. */
    public Execution latestStarted(String automationId) {
        var rows = jdbc.query(
                EXECUTIONS
                        + "WHERE e.automation_id=? AND e.status='STARTED' ORDER BY e.started_at DESC, e.id DESC LIMIT 1",
                EXECUTION,
                automationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Execution execution(String id) {
        return jdbc.query(EXECUTIONS + "WHERE e.id=?", EXECUTION, id).getFirst();
    }

    private static String truncate(String value) {
        String text = value == null ? "" : value;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static OffsetDateTime time(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
