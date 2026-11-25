package dev.javaharness.adapters.bot;

import dev.javaharness.spi.HarnessException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Bot 工作台 JDBC 存储；并发协调锁与事务由调用方（BotService / BotOrchestrator）负责。 */
public final class BotStore {
    public record Section(String id, String name, int ordinal, boolean collapsed, Instant createdAt) {}

    public record Meta(boolean onboarded, String userDisplayName, Instant updatedAt) {}

    public record Bot(
            String id,
            String sectionId,
            String name,
            String roleSummary,
            String systemPrompt,
            boolean main,
            String shape,
            String color,
            boolean pinned,
            int ordinal,
            String workspaceId,
            String chatProfileId,
            String chatReasoningEffort,
            String taskProfileId,
            String taskReasoningEffort,
            String externalAgentId,
            String approvalMode,
            String chatSessionId,
            Instant lastSeenAt,
            Instant createdAt,
            boolean archived,
            long revision,
            long lastSeq) {}

    public record Entry(
            String botId,
            long seq,
            String id,
            String requestId,
            String kind,
            String content,
            Long quoteSeq,
            String quotePreview,
            String attachmentsJson,
            String suggestionsJson,
            String taskId,
            String runId,
            String modelLabel,
            String fallbackFrom,
            boolean deleted,
            Instant createdAt) {}

    public record Summary(String botId, long throughSeq, String summaryText, Instant updatedAt) {}

    public record Task(
            String id,
            String botId,
            String originBotId,
            String delegatedByEntryId,
            String routineId,
            String playbookId,
            String title,
            String instruction,
            String sessionId,
            String runId,
            String status,
            String summary,
            String errorDetail,
            String modelLabel,
            String fallbackFrom,
            boolean silentTargetChat,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt) {}

    public record Playbook(
            String id,
            String botId,
            String name,
            String whenToUse,
            String steps,
            String notes,
            String sourceSkillId,
            int useCount,
            Instant createdAt,
            Instant updatedAt,
            long revision) {}

    public record Routine(
            String id,
            String botId,
            String name,
            String instruction,
            String playbookId,
            String modelProfileId,
            String reasoningEffort,
            String scheduleKind,
            String scheduleTime,
            int scheduleWeekday,
            String cron,
            String timezone,
            boolean enabled,
            Instant nextRunAt,
            Instant lastRunAt,
            String lastTaskId,
            String lastStatus,
            Instant createdAt,
            long revision) {}

    public record BotFile(
            String id, String botId, String taskId, String relativePath, long sizeBytes, Instant updatedAt) {}

    public record MiniApp(
            String id,
            String botId,
            String name,
            String description,
            int version,
            String htmlPath,
            String dataPath,
            String lastTaskId,
            Instant createdAt,
            Instant updatedAt) {}

    public record LayoutItem(String botId, String sectionId, boolean pinned, int ordinal) {}

    private static final RowMapper<Section> SECTION = (rs, row) -> new Section(
            rs.getString("id"),
            rs.getString("name"),
            rs.getInt("ordinal"),
            rs.getBoolean("collapsed"),
            instant(rs.getObject("created_at", OffsetDateTime.class)));

    private static final RowMapper<Meta> META = (rs, row) -> new Meta(
            rs.getBoolean("onboarded"),
            str(rs.getString("user_display_name")),
            instant(rs.getObject("updated_at", OffsetDateTime.class)));

    private static final RowMapper<Bot> BOT = (rs, row) -> new Bot(
            rs.getString("id"),
            str(rs.getString("section_id")),
            rs.getString("name"),
            str(rs.getString("role_summary")),
            str(rs.getString("system_prompt")),
            rs.getBoolean("is_main"),
            str(rs.getString("shape")),
            str(rs.getString("color")),
            rs.getBoolean("pinned"),
            rs.getInt("ordinal"),
            str(rs.getString("workspace_id")),
            str(rs.getString("chat_profile_id")),
            rs.getString("chat_reasoning_effort"),
            str(rs.getString("task_profile_id")),
            rs.getString("task_reasoning_effort"),
            str(rs.getString("external_agent_id")),
            str(rs.getString("approval_mode")),
            rs.getString("chat_session_id"),
            instant(rs.getObject("last_seen_at", OffsetDateTime.class)),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            rs.getBoolean("archived"),
            rs.getLong("revision"),
            rs.getLong("last_seq"));

    private static final RowMapper<Entry> ENTRY = (rs, row) -> {
        long quote = rs.getLong("quote_seq");
        Long quoteSeq = rs.wasNull() ? null : quote;
        return new Entry(
                rs.getString("bot_id"),
                rs.getLong("seq"),
                rs.getString("id"),
                rs.getString("request_id"),
                rs.getString("kind"),
                str(rs.getString("content")),
                quoteSeq,
                str(rs.getString("quote_preview")),
                str(rs.getString("attachments_json")),
                str(rs.getString("suggestions_json")),
                str(rs.getString("task_id")),
                str(rs.getString("run_id")),
                str(rs.getString("model_label")),
                str(rs.getString("fallback_from")),
                rs.getBoolean("deleted"),
                instant(rs.getObject("created_at", OffsetDateTime.class)));
    };

    private static final RowMapper<Summary> SUMMARY = (rs, row) -> new Summary(
            rs.getString("bot_id"),
            rs.getLong("through_seq"),
            str(rs.getString("summary_text")),
            instant(rs.getObject("updated_at", OffsetDateTime.class)));

    private static final RowMapper<Task> TASK = (rs, row) -> new Task(
            rs.getString("id"),
            rs.getString("bot_id"),
            rs.getString("origin_bot_id"),
            str(rs.getString("delegated_by_entry_id")),
            str(rs.getString("routine_id")),
            str(rs.getString("playbook_id")),
            str(rs.getString("title")),
            str(rs.getString("instruction")),
            rs.getString("session_id"),
            str(rs.getString("run_id")),
            rs.getString("status"),
            str(rs.getString("summary")),
            str(rs.getString("error_detail")),
            str(rs.getString("model_label")),
            str(rs.getString("fallback_from")),
            rs.getBoolean("silent_target_chat"),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class)),
            optionalInstant(rs.getObject("finished_at", OffsetDateTime.class)));

    private static final RowMapper<Playbook> PLAYBOOK = (rs, row) -> new Playbook(
            rs.getString("id"),
            rs.getString("bot_id"),
            rs.getString("name"),
            str(rs.getString("when_to_use")),
            str(rs.getString("steps")),
            str(rs.getString("notes")),
            str(rs.getString("source_skill_id")),
            rs.getInt("use_count"),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class)),
            rs.getLong("revision"));

    private static final RowMapper<Routine> ROUTINE = (rs, row) -> new Routine(
            rs.getString("id"),
            rs.getString("bot_id"),
            rs.getString("name"),
            str(rs.getString("instruction")),
            str(rs.getString("playbook_id")),
            str(rs.getString("model_profile_id")),
            rs.getString("reasoning_effort"),
            rs.getString("schedule_kind"),
            str(rs.getString("schedule_time")),
            rs.getInt("schedule_weekday"),
            str(rs.getString("cron")),
            str(rs.getString("timezone")),
            rs.getBoolean("enabled"),
            optionalInstant(rs.getObject("next_run_at", OffsetDateTime.class)),
            optionalInstant(rs.getObject("last_run_at", OffsetDateTime.class)),
            str(rs.getString("last_task_id")),
            str(rs.getString("last_status")),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            rs.getLong("revision"));

    private static final RowMapper<BotFile> BOT_FILE = (rs, row) -> new BotFile(
            rs.getString("id"),
            rs.getString("bot_id"),
            str(rs.getString("task_id")),
            rs.getString("relative_path"),
            rs.getLong("size_bytes"),
            instant(rs.getObject("updated_at", OffsetDateTime.class)));

    private static final RowMapper<MiniApp> MINI_APP = (rs, row) -> new MiniApp(
            rs.getString("id"),
            rs.getString("bot_id"),
            rs.getString("name"),
            str(rs.getString("description")),
            rs.getInt("version"),
            rs.getString("html_path"),
            rs.getString("data_path"),
            str(rs.getString("last_task_id")),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    public BotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private static Instant optionalInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Meta ensureMeta() {
        var rows = jdbc.query("SELECT * FROM harness_bot_meta WHERE id='default'", META);
        if (!rows.isEmpty()) return rows.getFirst();
        OffsetDateTime time = now();
        jdbc.update(
                "INSERT INTO harness_bot_meta(id,onboarded,user_display_name,updated_at) VALUES ('default',FALSE,'',?)",
                time);
        return new Meta(false, "", time.toInstant());
    }

    public Meta updateMeta(boolean onboarded, String userDisplayName) {
        ensureMeta();
        OffsetDateTime time = now();
        jdbc.update(
                "UPDATE harness_bot_meta SET onboarded=?,user_display_name=?,updated_at=? WHERE id='default'",
                onboarded,
                str(userDisplayName),
                time);
        return new Meta(onboarded, str(userDisplayName), time.toInstant());
    }

    public List<Section> sections() {
        return jdbc.query("SELECT * FROM harness_bot_section ORDER BY ordinal, created_at", SECTION);
    }

    public Section createSection(String name) {
        String id = UUID.randomUUID().toString();
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(ordinal),-1) FROM harness_bot_section", Integer.class);
        OffsetDateTime time = now();
        jdbc.update(
                "INSERT INTO harness_bot_section(id,name,ordinal,collapsed,created_at) VALUES (?,?,?,FALSE,?)",
                id,
                name,
                (max == null ? 0 : max + 1),
                time);
        return section(id);
    }

    public Section section(String id) {
        var rows = jdbc.query("SELECT * FROM harness_bot_section WHERE id=?", SECTION, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "分组不存在");
        return rows.getFirst();
    }

    public Section updateSection(String id, String name, Boolean collapsed, Integer ordinal) {
        Section existing = section(id);
        jdbc.update(
                "UPDATE harness_bot_section SET name=?,collapsed=?,ordinal=? WHERE id=?",
                name == null ? existing.name() : name,
                collapsed == null ? existing.collapsed() : collapsed,
                ordinal == null ? existing.ordinal() : ordinal,
                id);
        return section(id);
    }

    public void deleteSection(String id) {
        section(id);
        jdbc.update("UPDATE harness_bot SET section_id=NULL WHERE section_id=?", id);
        jdbc.update("DELETE FROM harness_bot_section WHERE id=?", id);
    }

    public List<Bot> listBots(boolean includeArchived) {
        String sql = includeArchived
                ? "SELECT * FROM harness_bot ORDER BY is_main DESC, pinned DESC, ordinal, created_at"
                : "SELECT * FROM harness_bot WHERE archived=FALSE ORDER BY is_main DESC, pinned DESC, ordinal, created_at";
        return jdbc.query(sql, BOT);
    }

    public Bot getBot(String id) {
        var rows = jdbc.query("SELECT * FROM harness_bot WHERE id=?", BOT, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "Bot 不存在");
        return rows.getFirst();
    }

    public Bot findMainBot() {
        var rows = jdbc.query("SELECT * FROM harness_bot WHERE is_main=TRUE AND archived=FALSE LIMIT 1", BOT);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Bot findBotByName(String name) {
        if (name == null || name.isBlank()) return null;
        var rows = jdbc.query(
                "SELECT * FROM harness_bot WHERE archived=FALSE AND LOWER(name)=LOWER(?) LIMIT 1", BOT, name.strip());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Bot findBotByChatSession(String sessionId) {
        var rows = jdbc.query("SELECT * FROM harness_bot WHERE chat_session_id=? LIMIT 1", BOT, sessionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Bot insertBot(
            String sectionId,
            String name,
            String roleSummary,
            String systemPrompt,
            boolean main,
            String shape,
            String color,
            boolean pinned,
            String workspaceId,
            String chatProfileId,
            String chatReasoningEffort,
            String taskProfileId,
            String taskReasoningEffort,
            String externalAgentId,
            String approvalMode,
            String chatSessionId) {
        String id = UUID.randomUUID().toString();
        OffsetDateTime time = now();
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(ordinal),-1) FROM harness_bot WHERE archived=FALSE", Integer.class);
        jdbc.update(
                """
                INSERT INTO harness_bot(
                    id,section_id,name,role_summary,system_prompt,is_main,shape,color,pinned,ordinal,
                    workspace_id,chat_profile_id,chat_reasoning_effort,task_profile_id,task_reasoning_effort,
                    external_agent_id,approval_mode,chat_session_id,last_seen_at,created_at,archived,revision,last_seq)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE,1,1)
                """,
                id,
                nullable(sectionId),
                name,
                str(roleSummary),
                str(systemPrompt),
                main,
                shape,
                color,
                pinned,
                (max == null ? 0 : max + 1),
                nullable(workspaceId),
                nullable(chatProfileId),
                chatReasoningEffort,
                nullable(taskProfileId),
                taskReasoningEffort,
                str(externalAgentId),
                approvalMode,
                chatSessionId,
                time,
                time);
        attachChatSession(chatSessionId, id);
        return getBot(id);
    }

    public Bot updateBot(Bot updated) {
        int rows = jdbc.update(
                """
                UPDATE harness_bot
                SET section_id=?,name=?,role_summary=?,system_prompt=?,shape=?,color=?,pinned=?,ordinal=?,
                    workspace_id=?,chat_profile_id=?,chat_reasoning_effort=?,task_profile_id=?,task_reasoning_effort=?,
                    external_agent_id=?,approval_mode=?,revision=revision+1,last_seq=last_seq+1
                WHERE id=? AND revision=?
                """,
                nullable(updated.sectionId()),
                updated.name(),
                str(updated.roleSummary()),
                str(updated.systemPrompt()),
                updated.shape(),
                updated.color(),
                updated.pinned(),
                updated.ordinal(),
                nullable(updated.workspaceId()),
                nullable(updated.chatProfileId()),
                updated.chatReasoningEffort(),
                nullable(updated.taskProfileId()),
                updated.taskReasoningEffort(),
                str(updated.externalAgentId()),
                updated.approvalMode(),
                updated.id(),
                updated.revision());
        if (rows == 0) throw new HarnessException("CONFLICT", "Bot 已更新，请刷新后重试");
        return getBot(updated.id());
    }

    public void archiveBot(String id) {
        Bot bot = getBot(id);
        if (bot.main()) throw new HarnessException("CONFLICT", "主 Bot 不能删除或归档");
        jdbc.update("UPDATE harness_bot SET archived=TRUE,revision=revision+1,last_seq=last_seq+1 WHERE id=?", id);
    }

    public void markSeen(String id) {
        jdbc.update("UPDATE harness_bot SET last_seen_at=?,last_seq=last_seq+1 WHERE id=?", now(), id);
    }

    public void updateLayout(List<LayoutItem> items) {
        for (LayoutItem item : items) {
            jdbc.update(
                    "UPDATE harness_bot SET section_id=?,pinned=?,ordinal=?,last_seq=last_seq+1 WHERE id=?",
                    nullable(item.sectionId()),
                    item.pinned(),
                    item.ordinal(),
                    item.botId());
        }
    }

    public void bumpBotSeq(String botId) {
        jdbc.update("UPDATE harness_bot SET last_seq=last_seq+1 WHERE id=?", botId);
    }

    public void attachChatSession(String sessionId, String botId) {
        jdbc.update("UPDATE harness_session SET bot_id=? WHERE id=?", botId, sessionId);
    }

    public void attachTaskSession(String sessionId, String botId, String taskId) {
        jdbc.update("UPDATE harness_session SET bot_id=?,bot_task_id=? WHERE id=?", botId, taskId, sessionId);
    }

    public boolean isBotSession(String sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_session WHERE id=? AND (bot_id IS NOT NULL OR bot_task_id IS NOT NULL)",
                Integer.class,
                sessionId);
        return count != null && count > 0;
    }

    public Entry entryByRequest(String botId, String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        var rows =
                jdbc.query("SELECT * FROM harness_bot_entry WHERE bot_id=? AND request_id=?", ENTRY, botId, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Entry entryBySeq(String botId, long seq) {
        var rows = jdbc.query("SELECT * FROM harness_bot_entry WHERE bot_id=? AND seq=?", ENTRY, botId, seq);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Entry appendEntry(
            String botId,
            String requestId,
            String kind,
            String content,
            Long quoteSeq,
            String quotePreview,
            String attachmentsJson,
            String suggestionsJson,
            String taskId,
            String runId,
            String modelLabel,
            String fallbackFrom) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0) FROM harness_bot_entry WHERE bot_id=?", Long.class, botId);
        long nextSeq = (max == null ? 0L : max) + 1L;
        String id = UUID.randomUUID().toString();
        OffsetDateTime time = now();
        jdbc.update(
                """
                INSERT INTO harness_bot_entry(
                    bot_id,seq,id,request_id,kind,content,quote_seq,quote_preview,
                    attachments_json,suggestions_json,task_id,run_id,model_label,fallback_from,deleted,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE,?)
                """,
                botId,
                nextSeq,
                id,
                nullable(requestId),
                kind,
                str(content),
                quoteSeq,
                str(quotePreview),
                attachmentsJson == null || attachmentsJson.isBlank() ? "[]" : attachmentsJson,
                suggestionsJson == null || suggestionsJson.isBlank() ? "[]" : suggestionsJson,
                nullable(taskId),
                nullable(runId),
                str(modelLabel),
                str(fallbackFrom),
                time);
        bumpBotSeq(botId);
        return entryBySeq(botId, nextSeq);
    }

    public List<Entry> entries(String botId, int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        var rows = jdbc.query("""
                SELECT * FROM (
                    SELECT * FROM harness_bot_entry
                    WHERE bot_id=? AND deleted=FALSE
                    ORDER BY seq DESC
                    LIMIT ?
                ) recent ORDER BY seq ASC
                """, ENTRY, botId, capped);
        return rows;
    }

    public List<Entry> entriesAfter(String botId, long afterSeq) {
        return jdbc.query(
                "SELECT * FROM harness_bot_entry WHERE bot_id=? AND deleted=FALSE AND seq>? ORDER BY seq ASC",
                ENTRY,
                botId,
                afterSeq);
    }

    public List<Entry> entriesAround(String botId, long centerSeq, int window) {
        long from = Math.max(1L, centerSeq - window);
        long to = centerSeq + window;
        return jdbc.query(
                "SELECT * FROM harness_bot_entry WHERE bot_id=? AND deleted=FALSE AND seq BETWEEN ? AND ? ORDER BY seq ASC",
                ENTRY,
                botId,
                from,
                to);
    }

    public List<Entry> searchEntries(String botId, String query, int limit) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) return List.of();
        String pattern =
                "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("""
                SELECT * FROM harness_bot_entry
                WHERE bot_id=? AND deleted=FALSE AND LOWER(content) LIKE LOWER(?) ESCAPE '\\'
                ORDER BY seq DESC
                LIMIT ?
                """, ENTRY, botId, pattern, Math.max(1, Math.min(limit, 30)));
    }

    public void softDeleteEntries(String botId, List<Long> seqs) {
        if (seqs == null || seqs.isEmpty()) return;
        for (Long seq : seqs) {
            if (seq != null) {
                jdbc.update("UPDATE harness_bot_entry SET deleted=TRUE WHERE bot_id=? AND seq=?", botId, seq);
            }
        }
        bumpBotSeq(botId);
    }

    public int countActiveTurns(String botId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_bot_entry WHERE bot_id=? AND deleted=FALSE AND kind='ASSISTANT'",
                Integer.class,
                botId);
        return count == null ? 0 : count;
    }

    public String lastPreview(String botId) {
        var rows = jdbc.query("""
                SELECT content FROM harness_bot_entry
                WHERE bot_id=? AND deleted=FALSE AND kind IN ('ASSISTANT','TASK_RESULT','USER')
                ORDER BY seq DESC LIMIT 1
                """, (rs, row) -> str(rs.getString("content")), botId);
        if (rows.isEmpty()) return "";
        String text = rows.getFirst().replaceAll("\\s+", " ").strip();
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    public Summary summary(String botId) {
        var rows = jdbc.query("SELECT * FROM harness_bot_summary WHERE bot_id=?", SUMMARY, botId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Summary saveSummary(String botId, long throughSeq, String text) {
        OffsetDateTime time = now();
        int updated = jdbc.update(
                "UPDATE harness_bot_summary SET through_seq=?,summary_text=?,updated_at=? WHERE bot_id=?",
                throughSeq,
                str(text),
                time,
                botId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO harness_bot_summary(bot_id,through_seq,summary_text,updated_at) VALUES (?,?,?,?)",
                    botId,
                    throughSeq,
                    str(text),
                    time);
        }
        return new Summary(botId, throughSeq, str(text), time.toInstant());
    }

    public Task createTask(
            String botId,
            String originBotId,
            String delegatedByEntryId,
            String routineId,
            String playbookId,
            String title,
            String instruction,
            String sessionId,
            String modelLabel,
            String fallbackFrom,
            boolean silentTargetChat) {
        String id = UUID.randomUUID().toString();
        OffsetDateTime time = now();
        jdbc.update(
                """
                INSERT INTO harness_bot_task(
                    id,bot_id,origin_bot_id,delegated_by_entry_id,routine_id,playbook_id,
                    title,instruction,session_id,run_id,status,summary,error_detail,
                    model_label,fallback_from,silent_target_chat,created_at,updated_at,finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,NULL,'QUEUED','','',?,?,?,?,?,NULL)
                """,
                id,
                botId,
                originBotId,
                nullable(delegatedByEntryId),
                nullable(routineId),
                nullable(playbookId),
                title,
                str(instruction),
                sessionId,
                str(modelLabel),
                str(fallbackFrom),
                silentTargetChat,
                time,
                time);
        attachTaskSession(sessionId, botId, id);
        bumpBotSeq(botId);
        if (!botId.equals(originBotId)) bumpBotSeq(originBotId);
        return task(id);
    }

    public Task task(String id) {
        var rows = jdbc.query("SELECT * FROM harness_bot_task WHERE id=?", TASK, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "后台任务不存在");
        return rows.getFirst();
    }

    public Task taskBySession(String sessionId) {
        var rows = jdbc.query("SELECT * FROM harness_bot_task WHERE session_id=? LIMIT 1", TASK, sessionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Task> tasksForBot(String botId, int limit) {
        return jdbc.query("""
                SELECT * FROM harness_bot_task
                WHERE bot_id=? OR origin_bot_id=?
                ORDER BY created_at DESC LIMIT ?
                """, TASK, botId, botId, Math.max(1, Math.min(limit, 100)));
    }

    public List<Task> activeTasksForBot(String botId) {
        return jdbc.query("""
                SELECT * FROM harness_bot_task
                WHERE bot_id=? AND status IN ('QUEUED','RUNNING')
                ORDER BY created_at ASC
                """, TASK, botId);
    }

    public List<Task> allActiveTasks() {
        return jdbc.query(
                "SELECT * FROM harness_bot_task WHERE status IN ('QUEUED','RUNNING') ORDER BY created_at ASC", TASK);
    }

    public Task latestFailedTaskAfter(String botId, Instant after) {
        var rows = jdbc.query("""
                SELECT * FROM harness_bot_task
                WHERE bot_id=? AND status IN ('FAILED','INTERRUPTED') AND finished_at IS NOT NULL AND finished_at > ?
                ORDER BY finished_at DESC LIMIT 1
                """, TASK, botId, OffsetDateTime.ofInstant(after, ZoneOffset.UTC));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int countCompletedTasks(String botId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_bot_task WHERE bot_id=? AND status='COMPLETED'", Integer.class, botId);
        return count == null ? 0 : count;
    }

    public int countCompletedPlaybookTasks(String botId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_bot_task WHERE bot_id=? AND status='COMPLETED' AND playbook_id IS NOT NULL",
                Integer.class,
                botId);
        return count == null ? 0 : count;
    }

    public void updateTaskRun(String taskId, String runId, String status) {
        Task existing = task(taskId);
        jdbc.update(
                "UPDATE harness_bot_task SET run_id=?,status=?,updated_at=? WHERE id=?",
                nullable(runId),
                status,
                now(),
                taskId);
        bumpBotSeq(existing.botId());
        if (!existing.botId().equals(existing.originBotId())) bumpBotSeq(existing.originBotId());
    }

    public void finishTask(String taskId, String status, String summary, String errorDetail) {
        Task existing = task(taskId);
        OffsetDateTime time = now();
        jdbc.update("""
                UPDATE harness_bot_task
                SET status=?,summary=?,error_detail=?,updated_at=?,finished_at=?
                WHERE id=?
                """, status, str(summary), str(errorDetail), time, time, taskId);
        bumpBotSeq(existing.botId());
        if (!existing.botId().equals(existing.originBotId())) bumpBotSeq(existing.originBotId());
    }

    public void interruptActiveTasks() {
        OffsetDateTime time = now();
        jdbc.update("""
                UPDATE harness_bot_task
                SET status='INTERRUPTED',error_detail='服务重启，后台任务已中断',updated_at=?,finished_at=?
                WHERE status IN ('QUEUED','RUNNING')
                """, time, time);
    }

    public List<Playbook> playbooks(String botId) {
        return jdbc.query(
                "SELECT * FROM harness_bot_playbook WHERE bot_id=? ORDER BY created_at DESC", PLAYBOOK, botId);
    }

    public Playbook playbook(String id) {
        var rows = jdbc.query("SELECT * FROM harness_bot_playbook WHERE id=?", PLAYBOOK, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "做法不存在");
        return rows.getFirst();
    }

    public Playbook savePlaybook(
            String id, String botId, String name, String whenToUse, String steps, String notes, String sourceSkillId) {
        OffsetDateTime time = now();
        if (id == null || id.isBlank()) {
            String newId = UUID.randomUUID().toString();
            jdbc.update(
                    """
                    INSERT INTO harness_bot_playbook(
                        id,bot_id,name,when_to_use,steps,notes,source_skill_id,use_count,created_at,updated_at,revision)
                    VALUES (?,?,?,?,?,?,?,0,?,?,1)
                    """, newId, botId, name, str(whenToUse), str(steps), str(notes), str(sourceSkillId), time, time);
            bumpBotSeq(botId);
            return playbook(newId);
        }
        Playbook existing = playbook(id);
        jdbc.update("""
                UPDATE harness_bot_playbook
                SET name=?,when_to_use=?,steps=?,notes=?,updated_at=?,revision=revision+1
                WHERE id=?
                """, name, str(whenToUse), str(steps), str(notes), time, id);
        bumpBotSeq(existing.botId());
        return playbook(id);
    }

    public void deletePlaybook(String id) {
        Playbook existing = playbook(id);
        jdbc.update("DELETE FROM harness_bot_playbook WHERE id=?", id);
        bumpBotSeq(existing.botId());
    }

    public void incrementPlaybookUse(String id) {
        if (id == null || id.isBlank()) return;
        jdbc.update("UPDATE harness_bot_playbook SET use_count=use_count+1 WHERE id=?", id);
    }

    public List<Routine> routines(String botId) {
        return jdbc.query("SELECT * FROM harness_bot_routine WHERE bot_id=? ORDER BY created_at DESC", ROUTINE, botId);
    }

    public Routine routine(String id) {
        var rows = jdbc.query("SELECT * FROM harness_bot_routine WHERE id=?", ROUTINE, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "例行任务不存在");
        return rows.getFirst();
    }

    public List<Routine> dueRoutines(Instant now) {
        return jdbc.query("""
                SELECT r.* FROM harness_bot_routine r
                JOIN harness_bot b ON b.id = r.bot_id
                WHERE r.enabled=TRUE AND b.archived=FALSE
                  AND r.next_run_at IS NOT NULL AND r.next_run_at <= ?
                ORDER BY r.next_run_at ASC
                """, ROUTINE, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    public Routine saveRoutine(Routine routine) {
        OffsetDateTime time = now();
        OffsetDateTime next =
                routine.nextRunAt() == null ? null : OffsetDateTime.ofInstant(routine.nextRunAt(), ZoneOffset.UTC);
        if (routine.id() == null || routine.id().isBlank()) {
            String id = UUID.randomUUID().toString();
            jdbc.update(
                    """
                    INSERT INTO harness_bot_routine(
                        id,bot_id,name,instruction,playbook_id,model_profile_id,reasoning_effort,
                        schedule_kind,schedule_time,schedule_weekday,cron,timezone,enabled,
                        next_run_at,last_run_at,last_task_id,last_status,created_at,revision)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,'',?,1)
                    """,
                    id,
                    routine.botId(),
                    routine.name(),
                    str(routine.instruction()),
                    nullable(routine.playbookId()),
                    nullable(routine.modelProfileId()),
                    routine.reasoningEffort(),
                    routine.scheduleKind(),
                    str(routine.scheduleTime()),
                    routine.scheduleWeekday(),
                    str(routine.cron()),
                    str(routine.timezone()),
                    routine.enabled(),
                    next,
                    time);
            bumpBotSeq(routine.botId());
            return routine(id);
        }
        Routine existing = routine(routine.id());
        int rows = jdbc.update(
                """
                UPDATE harness_bot_routine
                SET name=?,instruction=?,playbook_id=?,model_profile_id=?,reasoning_effort=?,
                    schedule_kind=?,schedule_time=?,schedule_weekday=?,cron=?,timezone=?,
                    enabled=?,next_run_at=?,revision=revision+1
                WHERE id=? AND revision=?
                """,
                routine.name(),
                str(routine.instruction()),
                nullable(routine.playbookId()),
                nullable(routine.modelProfileId()),
                routine.reasoningEffort(),
                routine.scheduleKind(),
                str(routine.scheduleTime()),
                routine.scheduleWeekday(),
                str(routine.cron()),
                str(routine.timezone()),
                routine.enabled(),
                next,
                routine.id(),
                routine.revision() > 0 ? routine.revision() : existing.revision());
        if (rows == 0) throw new HarnessException("CONFLICT", "例行任务已更新，请刷新后重试");
        bumpBotSeq(existing.botId());
        return routine(routine.id());
    }

    public void updateRoutineSchedule(String id, Instant nextRunAt) {
        OffsetDateTime next = nextRunAt == null ? null : OffsetDateTime.ofInstant(nextRunAt, ZoneOffset.UTC);
        jdbc.update("UPDATE harness_bot_routine SET next_run_at=? WHERE id=?", next, id);
    }

    public void recordRoutineTrigger(String id, String taskId, String status, Instant ranAt) {
        Routine existing = routine(id);
        jdbc.update(
                "UPDATE harness_bot_routine SET last_run_at=?,last_task_id=?,last_status=? WHERE id=?",
                OffsetDateTime.ofInstant(ranAt, ZoneOffset.UTC),
                nullable(taskId),
                str(status),
                id);
        bumpBotSeq(existing.botId());
    }

    public void deleteRoutine(String id) {
        Routine existing = routine(id);
        jdbc.update("DELETE FROM harness_bot_routine WHERE id=?", id);
        bumpBotSeq(existing.botId());
    }

    public boolean hasActiveTaskForRoutine(String routineId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_bot_task WHERE routine_id=? AND status IN ('QUEUED','RUNNING')",
                Integer.class,
                routineId);
        return count != null && count > 0;
    }

    public List<BotFile> files(String botId) {
        return jdbc.query("SELECT * FROM harness_bot_file WHERE bot_id=? ORDER BY updated_at DESC", BOT_FILE, botId);
    }

    public List<MiniApp> miniApps(String botId) {
        return jdbc.query(
                "SELECT * FROM harness_bot_mini_app WHERE bot_id=? ORDER BY updated_at DESC", MINI_APP, botId);
    }

    public MiniApp miniApp(String appId) {
        var rows = jdbc.query("SELECT * FROM harness_bot_mini_app WHERE id=?", MINI_APP, appId);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "小程序不存在");
        return rows.getFirst();
    }

    public MiniApp upsertMiniApp(
            String appId,
            String botId,
            String name,
            String description,
            String htmlPath,
            String dataPath,
            String taskId) {
        OffsetDateTime time = now();
        var existingRows = appId == null || appId.isBlank()
                ? List.<MiniApp>of()
                : jdbc.query("SELECT * FROM harness_bot_mini_app WHERE id=?", MINI_APP, appId);
        if (existingRows.isEmpty()) {
            String id = (appId == null || appId.isBlank()) ? UUID.randomUUID().toString() : appId;
            jdbc.update("""
                    INSERT INTO harness_bot_mini_app(
                        id,bot_id,name,description,version,html_path,data_path,last_task_id,created_at,updated_at)
                    VALUES (?,?,?,?,1,?,?,?,?,?)
                    """, id, botId, name, str(description), htmlPath, dataPath, nullable(taskId), time, time);
            bumpBotSeq(botId);
            return miniApp(id);
        }
        MiniApp existing = existingRows.getFirst();
        jdbc.update("""
                UPDATE harness_bot_mini_app
                SET name=?,description=?,version=version+1,html_path=?,data_path=?,last_task_id=?,updated_at=?
                WHERE id=?
                """, name, str(description), htmlPath, dataPath, nullable(taskId), time, existing.id());
        bumpBotSeq(existing.botId());
        return miniApp(existing.id());
    }

    public MiniApp touchMiniAppData(String appId, String taskId) {
        MiniApp existing = miniApp(appId);
        OffsetDateTime time = now();
        jdbc.update(
                "UPDATE harness_bot_mini_app SET last_task_id=?,updated_at=? WHERE id=?",
                nullable(taskId == null || taskId.isBlank() ? existing.lastTaskId() : taskId),
                time,
                appId);
        bumpBotSeq(existing.botId());
        return miniApp(appId);
    }

    public void deleteMiniApp(String appId) {
        MiniApp existing = miniApp(appId);
        jdbc.update("DELETE FROM harness_bot_mini_app WHERE id=?", appId);
        bumpBotSeq(existing.botId());
    }

    public void recordFile(String botId, String taskId, String relativePath, long sizeBytes) {
        OffsetDateTime time = now();
        int updated = jdbc.update(
                "UPDATE harness_bot_file SET task_id=?,size_bytes=?,updated_at=? WHERE bot_id=? AND relative_path=?",
                nullable(taskId),
                sizeBytes,
                time,
                botId,
                relativePath);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO harness_bot_file(id,bot_id,task_id,relative_path,size_bytes,updated_at) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    botId,
                    nullable(taskId),
                    relativePath,
                    sizeBytes,
                    time);
        }
        bumpBotSeq(botId);
    }

    /** Coalesced global version across all bots, tasks and hidden bot sessions for SSE polling. */
    public String globalVersion() {
        Long botSeq = jdbc.queryForObject("SELECT COALESCE(SUM(last_seq),0) FROM harness_bot", Long.class);
        Long eventSeq = jdbc.queryForObject("""
                SELECT COALESCE(SUM(last_seq),0) FROM harness_session
                WHERE bot_id IS NOT NULL OR bot_task_id IS NOT NULL
                """, Long.class);
        Long taskCount = jdbc.queryForObject("SELECT COUNT(*) FROM harness_bot_task", Long.class);
        return (botSeq == null ? 0L : botSeq) + ":" + (eventSeq == null ? 0L : eventSeq) + ":"
                + (taskCount == null ? 0L : taskCount);
    }
}
