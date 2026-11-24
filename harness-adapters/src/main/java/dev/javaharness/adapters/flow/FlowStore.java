package dev.javaharness.adapters.flow;

import dev.javaharness.spi.HarnessException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** 流水线、事项与步骤的 JDBC 存储；流程定义以 JSON 文本保存，并发协调与事务由调用方负责。 */
public final class FlowStore {
    /** 一条流水线；workspaceId 为空表示岗位只能讨论，definition 为节点与连线的 JSON。 */
    public record Flow(
            String id,
            String name,
            String description,
            String workspaceId,
            String definition,
            Instant createdAt,
            long revision) {}

    /**
     * 一次触发产生的事项；definition 为触发时的流程快照，status 为 RUNNING、WAITING、COMPLETED、FAILED 或 STOPPED，
     * nodeKey 为当前所在节点，steps 为已经过的节点次数。
     */
    public record Item(
            String id,
            String flowId,
            String title,
            String input,
            String sessionId,
            String definition,
            String status,
            String nodeKey,
            String detail,
            int steps,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * 事项经过节点的一次记录；status 为 RUNNING、WAITING、DONE、FAILED、CANCELLED 或 INTERRUPTED，
     * outcome 为离开节点时选择的分支，summary 为岗位的回复或人工备注。
     */
    public record Step(
            String id,
            String itemId,
            int seq,
            String nodeKey,
            String nodeName,
            String nodeType,
            String status,
            String outcome,
            String summary,
            String detail,
            String runId,
            Instant createdAt) {}

    /** 流水线的在途事项数量。 */
    public record Counts(int running, int waiting) {}

    /** 岗位运行中等待用户介入的事项；kind 为 APPROVAL（待审批）或 ANSWER（待回答）。 */
    public record Pending(String itemId, String flowId, String kind) {}

    private static final RowMapper<Flow> FLOW = (rs, row) -> new Flow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("workspace_id") == null ? "" : rs.getString("workspace_id"),
            rs.getString("definition"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getLong("revision"));
    private static final RowMapper<Item> ITEM = (rs, row) -> new Item(
            rs.getString("id"),
            rs.getString("flow_id"),
            rs.getString("title"),
            rs.getString("input"),
            rs.getString("session_id"),
            rs.getString("definition"),
            rs.getString("status"),
            rs.getString("node_key"),
            rs.getString("detail"),
            rs.getInt("steps"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    private static final RowMapper<Step> STEP = (rs, row) -> new Step(
            rs.getString("id"),
            rs.getString("item_id"),
            rs.getInt("seq"),
            rs.getString("node_key"),
            rs.getString("node_name"),
            rs.getString("node_type"),
            rs.getString("status"),
            rs.getString("outcome"),
            rs.getString("summary"),
            rs.getString("detail"),
            rs.getString("run_id"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public FlowStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public List<Flow> list() {
        return jdbc.query("SELECT * FROM harness_flow ORDER BY created_at DESC, id DESC", FLOW);
    }

    public Flow get(String id) {
        var rows = jdbc.query("SELECT * FROM harness_flow WHERE id=?", FLOW, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "流水线不存在");
        return rows.getFirst();
    }

    /** Insert when the id is empty, otherwise update when the revision still matches. */
    public Flow save(Flow value) {
        String workspace = value.workspaceId().isEmpty() ? null : value.workspaceId();
        if (value.id().isEmpty()) {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO harness_flow(id,name,description,workspace_id,definition,created_at,revision)
                    VALUES (?,?,?,?,?,?,1)
                    """, id, value.name(), value.description(), workspace, value.definition(), now());
            return get(id);
        }
        int updated = jdbc.update(
                """
                UPDATE harness_flow SET name=?,description=?,workspace_id=?,definition=?,revision=revision+1,
                    last_seq=last_seq+1 WHERE id=? AND revision=?
                """, value.name(), value.description(), workspace, value.definition(), value.id(), value.revision());
        if (updated == 0) {
            get(value.id());
            throw new HarnessException("CONFLICT", "流水线已被更新，请重新打开后再保存");
        }
        return get(value.id());
    }

    /** Remove the flow's own rows; item sessions are deleted by the caller first. */
    public void delete(String id) {
        jdbc.update(
                "DELETE FROM harness_flow_step WHERE item_id IN (SELECT id FROM harness_flow_item WHERE flow_id=?)",
                id);
        jdbc.update("DELETE FROM harness_flow_item WHERE flow_id=?", id);
        jdbc.update("DELETE FROM harness_flow WHERE id=?", id);
    }

    /** Changes whenever the flow, one of its items or a step changed. */
    public long lastSeq(String flowId) {
        var rows = jdbc.queryForList("SELECT last_seq FROM harness_flow WHERE id=?", Long.class, flowId);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "流水线不存在");
        return rows.getFirst();
    }

    private void touch(String flowId) {
        jdbc.update("UPDATE harness_flow SET last_seq=last_seq+1 WHERE id=?", flowId);
    }

    public Counts counts(String flowId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN status='RUNNING' THEN 1 ELSE 0 END),0) running,
                    COALESCE(SUM(CASE WHEN status='WAITING' THEN 1 ELSE 0 END),0) waiting
                FROM harness_flow_item WHERE flow_id=?
                """, (rs, row) -> new Counts(rs.getInt("running"), rs.getInt("waiting")), flowId);
    }

    /** Create a RUNNING item standing on the trigger node and mark its session as hidden. */
    public Item addItem(
            String flowId, String title, String input, String sessionId, String definition, String nodeKey) {
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = now();
        jdbc.update("""
                INSERT INTO harness_flow_item(id,flow_id,title,input,session_id,definition,status,node_key,detail,steps,
                    created_at,updated_at)
                VALUES (?,?,?,?,?,?,'RUNNING',?,'',0,?,?)
                """, id, flowId, title, input, sessionId, definition, nodeKey, now, now);
        jdbc.update("UPDATE harness_session SET flow_id=? WHERE id=?", flowId, sessionId);
        touch(flowId);
        return item(id);
    }

    public Item item(String id) {
        var rows = jdbc.query("SELECT * FROM harness_flow_item WHERE id=?", ITEM, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "事项不存在");
        return rows.getFirst();
    }

    /** The item running in a hidden session, or null for an ordinary session. */
    public Item itemBySession(String sessionId) {
        var rows = jdbc.query("SELECT * FROM harness_flow_item WHERE session_id=?", ITEM, sessionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The newest items of a flow. */
    public List<Item> items(String flowId, int limit) {
        return jdbc.query(
                "SELECT * FROM harness_flow_item WHERE flow_id=? ORDER BY created_at DESC, id DESC LIMIT ?",
                ITEM,
                flowId,
                limit);
    }

    /** Every item of every flow that waits for the user, oldest first. */
    public List<Item> waiting() {
        return jdbc.query("SELECT * FROM harness_flow_item WHERE status='WAITING' ORDER BY updated_at, id", ITEM);
    }

    /**
     * Travelling items whose running agent waits for the user inside the run: a command or external tool to
     * approve, or a question to answer. An approval takes precedence over a question.
     */
    public List<Pending> pending() {
        return jdbc.query(
                """
                SELECT i.id,i.flow_id,MAX(a.kind) kind
                FROM (
                    SELECT run_id,'APPROVAL' kind FROM harness_command WHERE status='PENDING'
                    UNION ALL SELECT run_id,'APPROVAL' FROM harness_external_approval WHERE status='PENDING'
                    UNION ALL SELECT run_id,'ANSWER' FROM harness_question WHERE status='PENDING'
                    UNION ALL SELECT run_id,CASE WHEN interaction_kind='permission' THEN 'APPROVAL' ELSE 'ANSWER' END
                        FROM harness_external_interaction WHERE status='PENDING'
                ) a JOIN harness_run r ON r.id=a.run_id
                    JOIN harness_run root ON root.id=COALESCE(r.parent_run_id,r.id)
                    JOIN harness_flow_item i ON i.session_id=root.session_id
                WHERE r.status='RUNNING' AND i.status='RUNNING'
                GROUP BY i.id,i.flow_id ORDER BY i.id
                """, (rs, row) -> new Pending(rs.getString("id"), rs.getString("flow_id"), rs.getString("kind")));
    }

    public List<String> sessionIds(String flowId) {
        return jdbc.queryForList("SELECT session_id FROM harness_flow_item WHERE flow_id=?", String.class, flowId);
    }

    public void updateItem(String id, String status, String nodeKey, String detail, int steps) {
        jdbc.update(
                "UPDATE harness_flow_item SET status=?,node_key=?,detail=?,steps=?,updated_at=? WHERE id=?",
                status,
                nodeKey,
                detail,
                steps,
                now(),
                id);
        touch(item(id).flowId());
    }

    public void deleteItem(String id) {
        Item item = item(id);
        jdbc.update("DELETE FROM harness_flow_step WHERE item_id=?", id);
        jdbc.update("DELETE FROM harness_flow_item WHERE id=?", id);
        touch(item.flowId());
    }

    public Step addStep(String itemId, String nodeKey, String nodeName, String nodeType, String status, String runId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO harness_flow_step(id,item_id,seq,node_key,node_name,node_type,status,outcome,summary,detail,
                    run_id,created_at)
                VALUES (?,?,(SELECT COALESCE(MAX(seq),0)+1 FROM harness_flow_step WHERE item_id=?),?,?,?,?,'','','',?,?)
                """, id, itemId, itemId, nodeKey, nodeName, nodeType, status, runId, now());
        touch(item(itemId).flowId());
        return jdbc.queryForObject("SELECT * FROM harness_flow_step WHERE id=?", STEP, id);
    }

    public void updateStep(String id, String status, String outcome, String summary, String detail) {
        jdbc.update(
                "UPDATE harness_flow_step SET status=?,outcome=?,summary=?,detail=? WHERE id=?",
                status,
                outcome,
                summary,
                detail,
                id);
        touch(jdbc.queryForObject(
                "SELECT i.flow_id FROM harness_flow_step s JOIN harness_flow_item i ON i.id=s.item_id WHERE s.id=?",
                String.class,
                id));
    }

    public List<Step> steps(String itemId) {
        return jdbc.query("SELECT * FROM harness_flow_step WHERE item_id=? ORDER BY seq", STEP, itemId);
    }

    /** The newest step of an item, or null before the first node. */
    public Step lastStep(String itemId) {
        var rows =
                jdbc.query("SELECT * FROM harness_flow_step WHERE item_id=? ORDER BY seq DESC LIMIT 1", STEP, itemId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Items left running by a previous process wait for the user, who can retry the node or pick a branch; their
     * runs are recovered by the engine.
     */
    public void recover() {
        jdbc.update("""
                UPDATE harness_flow_step SET status='INTERRUPTED',detail='服务已重启' WHERE status='RUNNING'
                    AND item_id IN (SELECT id FROM harness_flow_item WHERE status='RUNNING')
                """);
        jdbc.update("""
                UPDATE harness_flow SET last_seq=last_seq+1
                WHERE id IN (SELECT flow_id FROM harness_flow_item WHERE status='RUNNING')
                """);
        jdbc.update(
                "UPDATE harness_flow_item SET status='WAITING',detail='服务已重启，当前环节已中断，可重试或直接选择下一步',updated_at=? WHERE status='RUNNING'",
                now());
    }
}
