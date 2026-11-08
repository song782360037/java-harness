package dev.javaharness.adapters.group;

import dev.javaharness.spi.HarnessException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** 群聊、成员、协作轮次、计划任务与群消息的 JDBC 存储；并发协调与事务由调用方负责。 */
public final class GroupStore {
    /**
     * 一个群聊；workspaceId 为空表示只讨论，hostReasoningEffort 为 null 表示使用主持人模型配置的默认强度，
     * mode 为 DISCUSS（自由发言）或 TASKS（任务编排）。
     */
    public record Group(
            String id,
            String name,
            String color,
            String workspaceId,
            String hostProfileId,
            String hostReasoningEffort,
            int maxTurns,
            String mode,
            Instant createdAt,
            long revision) {}

    /** 一个 AI 成员；每个成员通过自己的隐藏会话发言，template 为空表示自定义成员。 */
    public record Member(
            String id,
            String groupId,
            String sessionId,
            String name,
            String template,
            String color,
            String focus,
            String modelProfileId,
            String reasoningEffort,
            String approvalMode,
            int ordinal,
            boolean enabled) {}

    /** 群消息；author 为 USER、MEMBER 或 SYSTEM，runId 指向成员发言的运行。 */
    public record Message(
            String id,
            long seq,
            String roundId,
            String author,
            String memberId,
            String authorName,
            String content,
            String runId,
            Instant createdAt) {}

    /** 一轮协作；status 为 RUNNING、COMPLETED、STOPPED、SUPERSEDED、FAILED 或 INTERRUPTED。 */
    public record Round(String id, String groupId, String status, int turns, String detail, Instant createdAt) {}

    /**
     * 任务模式中一轮计划的一个任务；dependsOn 为同一轮的任务 key，status 为 PENDING、RUNNING、DONE、FAILED、
     * SKIPPED、CANCELLED 或 INTERRUPTED，runId 指向最近一次尝试的运行。
     */
    public record Task(
            String id,
            String roundId,
            int ordinal,
            String key,
            String title,
            String instruction,
            String memberId,
            String memberName,
            List<String> dependsOn,
            String status,
            int attempts,
            String runId,
            String detail,
            Instant createdAt) {}

    private static final RowMapper<Group> GROUP = (rs, row) -> new Group(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("color"),
            rs.getString("workspace_id") == null ? "" : rs.getString("workspace_id"),
            rs.getString("host_profile_id"),
            rs.getString("host_reasoning_effort"),
            rs.getInt("max_turns"),
            rs.getString("mode"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getLong("revision"));
    private static final RowMapper<Member> MEMBER = (rs, row) -> new Member(
            rs.getString("id"),
            rs.getString("group_id"),
            rs.getString("session_id"),
            rs.getString("name"),
            rs.getString("template"),
            rs.getString("color"),
            rs.getString("focus"),
            rs.getString("model_profile_id"),
            rs.getString("reasoning_effort"),
            rs.getString("approval_mode"),
            rs.getInt("ordinal"),
            rs.getBoolean("enabled"));
    private static final RowMapper<Message> MESSAGE = (rs, row) -> new Message(
            rs.getString("id"),
            rs.getLong("seq"),
            rs.getString("round_id"),
            rs.getString("author"),
            rs.getString("member_id"),
            rs.getString("author_name"),
            rs.getString("content"),
            rs.getString("run_id"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());
    private static final RowMapper<Round> ROUND = (rs, row) -> new Round(
            rs.getString("id"),
            rs.getString("group_id"),
            rs.getString("status"),
            rs.getInt("turns"),
            rs.getString("detail"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());
    private static final RowMapper<Task> TASK = (rs, row) -> new Task(
            rs.getString("id"),
            rs.getString("round_id"),
            rs.getInt("ordinal"),
            rs.getString("task_key"),
            rs.getString("title"),
            rs.getString("instruction"),
            rs.getString("member_id"),
            rs.getString("member_name"),
            rs.getString("depends_on").isEmpty()
                    ? List.of()
                    : List.of(rs.getString("depends_on").split(",")),
            rs.getString("status"),
            rs.getInt("attempts"),
            rs.getString("run_id"),
            rs.getString("detail"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public GroupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public List<Group> list() {
        return jdbc.query("SELECT * FROM harness_group ORDER BY created_at DESC, id DESC", GROUP);
    }

    public Group get(String id) {
        var rows = jdbc.query("SELECT * FROM harness_group WHERE id=?", GROUP, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "群聊不存在");
        return rows.getFirst();
    }

    /** Insert when the id is empty, otherwise update when the revision still matches. */
    public Group save(Group value) {
        if (value.id().isEmpty()) {
            String id = UUID.randomUUID().toString();
            jdbc.update(
                    """
                    INSERT INTO harness_group(id,name,color,workspace_id,host_profile_id,host_reasoning_effort,max_turns,mode,created_at,revision)
                    VALUES (?,?,?,?,?,?,?,?,?,1)
                    """,
                    id,
                    value.name(),
                    value.color(),
                    value.workspaceId().isEmpty() ? null : value.workspaceId(),
                    value.hostProfileId(),
                    value.hostReasoningEffort(),
                    value.maxTurns(),
                    value.mode(),
                    now());
            return get(id);
        }
        int updated = jdbc.update(
                """
                UPDATE harness_group SET name=?,color=?,workspace_id=?,host_profile_id=?,host_reasoning_effort=?,max_turns=?,
                    mode=?,revision=revision+1,last_seq=last_seq+1 WHERE id=? AND revision=?
                """,
                value.name(),
                value.color(),
                value.workspaceId().isEmpty() ? null : value.workspaceId(),
                value.hostProfileId(),
                value.hostReasoningEffort(),
                value.maxTurns(),
                value.mode(),
                value.id(),
                value.revision());
        if (updated == 0) {
            get(value.id());
            throw new HarnessException("CONFLICT", "群聊已被更新，请重新打开后再保存");
        }
        return get(value.id());
    }

    /** Remove the group's own rows; member sessions are deleted by the caller first. */
    public void delete(String id) {
        jdbc.update("DELETE FROM harness_group_message WHERE group_id=?", id);
        jdbc.update("DELETE FROM harness_group_task WHERE group_id=?", id);
        jdbc.update("DELETE FROM harness_group_round WHERE group_id=?", id);
        jdbc.update("DELETE FROM harness_group_member WHERE group_id=?", id);
        jdbc.update("DELETE FROM harness_group WHERE id=?", id);
    }

    public List<Member> members(String groupId) {
        return jdbc.query("SELECT * FROM harness_group_member WHERE group_id=? ORDER BY ordinal, id", MEMBER, groupId);
    }

    public Member member(String id) {
        var rows = jdbc.query("SELECT * FROM harness_group_member WHERE id=?", MEMBER, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "成员不存在");
        return rows.getFirst();
    }

    /** The member speaking through a hidden session, or null for an ordinary session. */
    public Member memberBySession(String sessionId) {
        var rows = jdbc.query("SELECT * FROM harness_group_member WHERE session_id=?", MEMBER, sessionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Insert when the id is empty, otherwise update in place; a duplicate name is rejected. */
    public Member saveMember(Member value) {
        var duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_group_member WHERE group_id=? AND name=? AND id<>?",
                Integer.class,
                value.groupId(),
                value.name(),
                value.id());
        if (duplicate > 0) throw new HarnessException("CONFLICT", "群里已有同名成员");
        String id = value.id().isEmpty() ? UUID.randomUUID().toString() : value.id();
        if (value.id().isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO harness_group_member(id,group_id,session_id,name,template,color,focus,model_profile_id,
                        reasoning_effort,approval_mode,ordinal,enabled)
                    VALUES (?,?,?,?,?,?,?,?,?,?,
                        (SELECT COALESCE(MAX(ordinal),0)+1 FROM harness_group_member WHERE group_id=?),?)
                    """,
                    id,
                    value.groupId(),
                    value.sessionId(),
                    value.name(),
                    value.template(),
                    value.color(),
                    value.focus(),
                    value.modelProfileId(),
                    value.reasoningEffort(),
                    value.approvalMode(),
                    value.groupId(),
                    value.enabled());
            jdbc.update("UPDATE harness_session SET group_id=? WHERE id=?", value.groupId(), value.sessionId());
        } else {
            jdbc.update(
                    """
                    UPDATE harness_group_member SET name=?,color=?,focus=?,model_profile_id=?,reasoning_effort=?,
                        approval_mode=?,enabled=? WHERE id=?
                    """,
                    value.name(),
                    value.color(),
                    value.focus(),
                    value.modelProfileId(),
                    value.reasoningEffort(),
                    value.approvalMode(),
                    value.enabled(),
                    id);
        }
        touch(value.groupId());
        return member(id);
    }

    public void deleteMember(String id) {
        Member member = member(id);
        jdbc.update("DELETE FROM harness_group_member WHERE id=?", id);
        touch(member.groupId());
    }

    /** Signal clients that the group changed; returns the new sequence. */
    public long touch(String groupId) {
        jdbc.update("UPDATE harness_group SET last_seq=last_seq+1 WHERE id=?", groupId);
        return lastSeq(groupId);
    }

    public long lastSeq(String groupId) {
        var rows = jdbc.queryForList("SELECT last_seq FROM harness_group WHERE id=?", Long.class, groupId);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "群聊不存在");
        return rows.getFirst();
    }

    public List<Message> messages(String groupId) {
        return jdbc.query("SELECT * FROM harness_group_message WHERE group_id=? ORDER BY seq", MESSAGE, groupId);
    }

    /** The user message posted with this request id, or null. */
    public Message messageByRequest(String groupId, String requestId) {
        var rows = jdbc.query(
                "SELECT * FROM harness_group_message WHERE group_id=? AND request_id=?", MESSAGE, groupId, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Message addMessage(
            String groupId,
            String requestId,
            String roundId,
            String author,
            String memberId,
            String authorName,
            String content,
            String runId) {
        long seq = touch(groupId);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO harness_group_message(group_id,seq,id,request_id,round_id,author,member_id,author_name,content,run_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, groupId, seq, id, requestId, roundId, author, memberId, authorName, content, runId, now());
        return jdbc.queryForObject(
                "SELECT * FROM harness_group_message WHERE group_id=? AND seq=?", MESSAGE, groupId, seq);
    }

    public Round startRound(String groupId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO harness_group_round(id,group_id,status,turns,detail,created_at) VALUES (?,?,'RUNNING',0,'',?)",
                id,
                groupId,
                now());
        touch(groupId);
        return round(id);
    }

    public Round round(String id) {
        return jdbc.queryForObject("SELECT * FROM harness_group_round WHERE id=?", ROUND, id);
    }

    /** The newest round of the group, or null before the first message. */
    public Round latestRound(String groupId) {
        var rows = jdbc.query(
                "SELECT * FROM harness_group_round WHERE group_id=? ORDER BY created_at DESC, id DESC LIMIT 1",
                ROUND,
                groupId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void updateRound(String id, String status, int turns, String detail) {
        Round round = round(id);
        jdbc.update(
                "UPDATE harness_group_round SET status=?,turns=?,detail=?,state_updated_at=? WHERE id=?",
                status,
                turns,
                detail,
                now(),
                id);
        touch(round.groupId());
    }

    /**
     * Rounds and unfinished tasks left by a previous process end as interrupted; their member runs are recovered by
     * the engine.
     */
    public void interruptRounds() {
        for (String groupId : jdbc.queryForList(
                """
                SELECT group_id FROM harness_group_round WHERE status='RUNNING'
                UNION SELECT group_id FROM harness_group_task WHERE status IN ('PENDING','RUNNING')
                """,
                String.class)) {
            jdbc.update(
                    "UPDATE harness_group_round SET status='INTERRUPTED',detail='服务已重启，协作中断；不会自动继续',state_updated_at=? WHERE group_id=? AND status='RUNNING'",
                    now(),
                    groupId);
            jdbc.update(
                    "UPDATE harness_group_task SET status='INTERRUPTED',detail='服务已重启' WHERE group_id=? AND status IN ('PENDING','RUNNING')",
                    groupId);
            touch(groupId);
        }
    }

    /** Add a planned task as PENDING; the caller validates keys, members and dependencies. */
    public Task addTask(
            String groupId,
            String roundId,
            int ordinal,
            String key,
            String title,
            String instruction,
            String memberId,
            String memberName,
            List<String> dependsOn) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO harness_group_task(id,group_id,round_id,ordinal,task_key,title,instruction,member_id,member_name,
                    depends_on,status,attempts,run_id,detail,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',0,NULL,'',?)
                """,
                id,
                groupId,
                roundId,
                ordinal,
                key,
                title,
                instruction,
                memberId,
                memberName,
                String.join(",", dependsOn),
                now());
        touch(groupId);
        return jdbc.queryForObject("SELECT * FROM harness_group_task WHERE id=?", TASK, id);
    }

    public void updateTask(String id, String status, int attempts, String runId, String detail) {
        String groupId = jdbc.queryForObject("SELECT group_id FROM harness_group_task WHERE id=?", String.class, id);
        jdbc.update(
                "UPDATE harness_group_task SET status=?,attempts=?,run_id=?,detail=? WHERE id=?",
                status,
                attempts,
                runId,
                detail,
                id);
        touch(groupId);
    }

    /** The tasks of a round in plan order; empty for a discussion round. */
    public List<Task> tasks(String roundId) {
        return jdbc.query("SELECT * FROM harness_group_task WHERE round_id=? ORDER BY ordinal", TASK, roundId);
    }

    /** A member run shown in the group: an active speaker or the run behind a message. */
    public record RunState(String id, String sessionId, String status, String workspace, long activity) {}

    // Events that change what the group shows; streamed text and reasoning stay out of the transcript.
    private static final String VISIBLE_EVENTS = "('STATE','FILE_CHANGE','COMMAND','PLUGIN','WORKSPACE')";

    /**
     * Speaking runs and runs behind messages; activity is the session's latest visible event sequence, so clients
     * refresh a run's approval cards only when they may have changed.
     */
    public List<RunState> runs(String groupId) {
        return jdbc.query(
                """
                SELECT r.id,r.session_id,r.status,r.workspace,
                    COALESCE((SELECT MAX(e.seq) FROM harness_event e WHERE e.session_id=r.session_id
                        AND e.event_type IN %s),0) AS activity
                FROM harness_run r JOIN harness_session s ON s.id=r.session_id
                WHERE s.group_id=? AND r.parent_run_id IS NULL
                  AND (r.status IN ('QUEUED','RUNNING','CANCELLING')
                       OR r.id IN (SELECT run_id FROM harness_group_message WHERE group_id=? AND run_id IS NOT NULL))
                ORDER BY r.created_at, r.id
                """.formatted(VISIBLE_EVENTS),
                (rs, row) -> new RunState(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("status"),
                        rs.getString("workspace"),
                        rs.getLong("activity")),
                groupId,
                groupId);
    }

    /** Sum of the member sessions' latest visible event sequences; a change means the group view may differ. */
    public long memberActivity(String groupId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM((SELECT MAX(e.seq) FROM harness_event e WHERE e.session_id=s.id
                    AND e.event_type IN %s)),0)
                FROM harness_session s WHERE s.group_id=?
                """.formatted(VISIBLE_EVENTS), Long.class, groupId);
    }
}
