package dev.javaharness.server.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 跨会话的轻量任务快照，不加载回答、命令输出或附件。 */
@Service
public class TaskActivityService {
    public record Task(
            String id,
            String sessionId,
            String groupId,
            String title,
            String source,
            String status,
            String attentionKey,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record Snapshot(List<Task> active, List<Task> recent, OffsetDateTime observedAt) {}

    private record Attention(String rootId, String groupId, String kind, String key) {}

    private final JdbcTemplate jdbc;

    public TaskActivityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 只读一致性事务取快照，不持有流式写入与取消所需的运行协调锁。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public Snapshot snapshot() {
        var attention = jdbc.query(
                """
                    SELECT COALESCE(r.parent_run_id,r.id) root_id,s.group_id,a.kind,a.id
                    FROM (
                        SELECT run_id,'APPROVAL' kind,id FROM harness_command WHERE status='PENDING'
                        UNION ALL SELECT run_id,'APPROVAL',id FROM harness_external_approval WHERE status='PENDING'
                        UNION ALL SELECT run_id,'ANSWER',id FROM harness_question WHERE status='PENDING'
                    ) a JOIN harness_run r ON r.id=a.run_id JOIN harness_session s ON s.id=r.session_id
                    WHERE r.status='RUNNING'
                    ORDER BY a.id
                    """,
                (rs, n) -> new Attention(
                        rs.getString("root_id"), rs.getString("group_id"), rs.getString("kind"), rs.getString("id")));
        var active = new ArrayList<Task>();
        active.addAll(runs(true));
        active.addAll(rounds(true));
        active.replaceAll(task -> withAttention(task, attention));
        active.sort(Comparator.comparingInt((Task task) -> task.status().startsWith("WAITING") ? 0 : 1)
                .thenComparing(Task::createdAt));
        var recent = new ArrayList<Task>();
        recent.addAll(runs(false));
        recent.addAll(rounds(false));
        recent.sort(Comparator.comparing(Task::updatedAt).reversed().thenComparing(Task::id));
        return new Snapshot(List.copyOf(active), recent.stream().limit(50).toList(), OffsetDateTime.now());
    }

    /** 子智能体在父任务下汇总；自动化保留其名称与来源。 */
    private List<Task> runs(boolean active) {
        return jdbc.query(
                """
                SELECT r.id,r.session_id,s.title,r.status,r.created_at,
                    r.state_updated_at updated_at,
                    (SELECT a.name FROM harness_automation_run ar JOIN harness_automation a ON a.id=ar.automation_id
                        WHERE ar.run_id=r.id LIMIT 1) automation_name
                FROM harness_run r JOIN harness_session s ON s.id=r.session_id
                WHERE r.parent_run_id IS NULL AND s.group_id IS NULL AND r.status
                """ + (active ? " IN ('QUEUED','RUNNING','CANCELLING')" : " NOT IN ('QUEUED','RUNNING','CANCELLING')")
                        + " ORDER BY updated_at DESC,r.id" + (active ? "" : " LIMIT 50"),
                (rs, n) -> {
                    String automation = rs.getString("automation_name");
                    return new Task(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            "",
                            automation == null ? rs.getString("title") : automation,
                            automation == null ? "SESSION" : "AUTOMATION",
                            rs.getString("status"),
                            "",
                            time(rs, "created_at"),
                            time(rs, "updated_at"));
                });
    }

    /** 群聊以轮次呈现，包含尚未产生成员 Run 的主持人规划阶段。 */
    private List<Task> rounds(boolean active) {
        return jdbc.query(
                """
                SELECT q.id,q.group_id,g.name,q.status,q.created_at,
                    q.state_updated_at updated_at
                FROM harness_group_round q JOIN harness_group g ON g.id=q.group_id WHERE q.status
                """ + (active ? "='RUNNING'" : "<>'RUNNING'")
                        + " ORDER BY updated_at DESC,q.id" + (active ? "" : " LIMIT 50"),
                (rs, n) -> new Task(
                        rs.getString("id"),
                        "",
                        rs.getString("group_id"),
                        rs.getString("name"),
                        "GROUP",
                        rs.getString("status"),
                        "",
                        time(rs, "created_at"),
                        time(rs, "updated_at")));
    }

    /** 优先显示需要用户介入的状态，并用不可变操作 ID 防止重复通知。 */
    private Task withAttention(Task task, List<Attention> all) {
        if (task.status().equals("CANCELLING")) return task;
        var pending = all.stream()
                .filter(value -> task.groupId().isEmpty()
                        ? task.id().equals(value.rootId())
                        : task.groupId().equals(value.groupId()))
                .toList();
        if (pending.isEmpty()) return task;
        boolean approval = pending.stream().anyMatch(value -> value.kind().equals("APPROVAL"));
        String key = pending.stream()
                .map(Attention::key)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return new Task(
                task.id(),
                task.sessionId(),
                task.groupId(),
                task.title(),
                task.source(),
                approval ? "WAITING_APPROVAL" : "WAITING_ANSWER",
                key,
                task.createdAt(),
                task.updatedAt());
    }

    private OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }
}
