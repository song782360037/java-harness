package dev.javaharness.adapters.memory;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.memory.LongTermMemory.Entry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** 记忆及关系的本地存储；修改使用版本号防止覆盖其他页面的编辑。 */
public final class JdbcMemoryStore {
    public record Relation(String fromId, String toId, String label) {}

    public record Usage(String memoryId, String title, String content, long revision) {}

    public record Settings(boolean enabled, String modelProfileId, long revision) {}

    public record Job(String runId, String workspaceId, String status, int attempts) {}

    public record Generated(String title, String content, String kind, List<String> tags) {}

    public record Progress(int total, int pending, int running, int completed, int failed, int skipped) {}

    private record Source(
            String id,
            String capturedId,
            String currentId,
            String runWorkspace,
            String liveWorkspace,
            int matchingWorkspaces) {
        /** 新任务按工作区身份校验；旧任务只在目录归属无歧义时回填。 */
        boolean matches() {
            return runWorkspace.equals(liveWorkspace)
                    && (capturedId != null ? capturedId.equals(currentId) : matchingWorkspaces <= 1);
        }
        /** 捕获的身份优先于当前会话绑定。 */
        String workspaceId() {
            return capturedId == null ? currentId : capturedId;
        }
    }

    private static final String SOURCE_QUERY =
            "SELECT r.id,r.memory_workspace_id,COALESCE(s.workspace_id,'') AS current_id,"
                    + "r.workspace AS run_workspace,s.workspace AS live_workspace,"
                    + "(SELECT COUNT(*) FROM harness_workspace w WHERE w.roots=r.workspace) AS matching_workspaces "
                    + "FROM harness_run r JOIN harness_session s ON s.id=r.session_id ";
    private static final RowMapper<Source> SOURCE = (rs, row) -> new Source(
            rs.getString("id"),
            rs.getString("memory_workspace_id"),
            rs.getString("current_id"),
            rs.getString("run_workspace"),
            rs.getString("live_workspace"),
            rs.getInt("matching_workspaces"));

    private static final RowMapper<Entry> ENTRY = (rs, row) -> new Entry(
            rs.getString("id"),
            rs.getString("workspace_id"),
            rs.getString("title"),
            rs.getString("content"),
            rs.getString("kind"),
            rs.getString("tags").isEmpty()
                    ? List.of()
                    : Arrays.asList(rs.getString("tags").split("\n")),
            rs.getString("status"),
            rs.getString("source_session_id"),
            rs.getString("source_run_id"),
            rs.getString("source_excerpt"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
            rs.getLong("revision"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /** 绑定当前进程使用的数据库与事务模板。 */
    public JdbcMemoryStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 按范围和状态分页读取；召回时可同时包含全局已确认记忆。 */
    public synchronized List<Entry> list(String workspaceId, String status, String query, int offset, int limit) {
        return jdbc.query(
                "SELECT * FROM harness_memory WHERE workspace_id=? "
                        + "AND (?='' OR status=?) AND LOWER(title || ' ' || content || ' ' || tags) LIKE ? ESCAPE '\\' "
                        + "ORDER BY updated_at DESC, id LIMIT ? OFFSET ?",
                ENTRY,
                workspaceId,
                status,
                status,
                like(query),
                limit,
                offset);
    }

    /** 返回与分页查询相同条件的总条数。 */
    public synchronized int count(String workspaceId, String status, String query) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_memory WHERE workspace_id=? AND (?='' OR status=?) "
                        + "AND LOWER(title || ' ' || content || ' ' || tags) LIKE ? ESCAPE '\\'",
                Integer.class,
                workspaceId,
                status,
                status,
                like(query));
    }

    /** 在全部有效记忆中排序，避免旧记忆因列表分页而无法召回。 */
    public synchronized List<Entry> search(String workspaceId, List<String> terms, int limit) {
        if (terms.isEmpty()) return List.of();
        var arguments = new ArrayList<Object>();
        var scores = new ArrayList<String>();
        for (String term : terms) {
            scores.add("(CASE WHEN LOWER(title || ' ' || tags) LIKE ? ESCAPE '\\' THEN 3 ELSE 0 END "
                    + "+ CASE WHEN LOWER(content) LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END)");
            arguments.add(like(term));
            arguments.add(like(term));
        }
        arguments.add(workspaceId);
        arguments.add(limit);
        return jdbc.query(
                "SELECT * FROM (SELECT m.*, " + String.join(" + ", scores)
                        + " AS score FROM harness_memory m WHERE status='ACTIVE' AND (workspace_id=? OR workspace_id='')) "
                        + "WHERE score>0 ORDER BY score DESC, updated_at DESC, id LIMIT ?",
                ENTRY,
                arguments.toArray());
    }

    /** 只扩展直接关联且已确认的记忆，范围限制与关键词召回一致。 */
    public synchronized List<Entry> neighbors(String workspaceId, List<String> ids, int limit) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        var arguments = new ArrayList<Object>();
        arguments.add(workspaceId);
        arguments.addAll(ids);
        arguments.addAll(ids);
        arguments.add(limit);
        return jdbc.query(
                "SELECT m.* FROM harness_memory m WHERE m.status='ACTIVE' "
                        + "AND (m.workspace_id=? OR m.workspace_id='') AND EXISTS (SELECT 1 FROM harness_memory_relation r WHERE "
                        + "(r.from_id=m.id AND r.to_id IN (" + placeholders + ")) OR (r.to_id=m.id AND r.from_id IN ("
                        + placeholders + "))) ORDER BY m.updated_at DESC, m.id LIMIT ?",
                ENTRY,
                arguments.toArray());
    }

    /** 读取单条记忆，不存在时返回明确的业务错误。 */
    public synchronized Entry get(String id) {
        var rows = jdbc.query("SELECT * FROM harness_memory WHERE id=?", ENTRY, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "记忆不存在");
        return rows.getFirst();
    }

    /** 创建或更新记忆，可在同一事务内归档被替代的旧记忆。 */
    public synchronized Entry save(
            String id,
            String workspaceId,
            String title,
            String content,
            String kind,
            List<String> tags,
            String status,
            long revision,
            String sourceSessionId,
            String sourceRunId,
            String sourceExcerpt,
            String replacesId,
            long replacesRevision) {
        return tx.execute(transaction -> {
            String fingerprint = fingerprint(content);
            var duplicate = jdbc.queryForList(
                    "SELECT id FROM harness_memory WHERE workspace_id=? AND fingerprint=?",
                    String.class,
                    workspaceId,
                    fingerprint);
            if (!duplicate.isEmpty() && !duplicate.getFirst().equals(id))
                throw new HarnessException("CONFLICT", "该范围已存在相同内容的记忆");
            String savedId = id.isEmpty() ? UUID.randomUUID().toString() : id;
            if (id.isEmpty()) {
                jdbc.update(
                        "INSERT INTO harness_memory (id,workspace_id,title,content,kind,tags,status,fingerprint,"
                                + "source_session_id,source_run_id,source_excerpt,created_at,updated_at,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        savedId,
                        workspaceId,
                        title,
                        content,
                        kind,
                        String.join("\n", tags),
                        status,
                        fingerprint,
                        sourceSessionId,
                        sourceRunId,
                        sourceExcerpt,
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        1L);
            } else {
                Entry before = get(id);
                if (!before.workspaceId().equals(workspaceId)) throw new HarnessException("CONFLICT", "不能移动已有记忆的范围");
                int changed = jdbc.update(
                        "UPDATE harness_memory SET title=?,content=?,kind=?,tags=?,status=?,fingerprint=?,"
                                + "updated_at=?,revision=revision+1 WHERE id=? AND revision=?",
                        title,
                        content,
                        kind,
                        String.join("\n", tags),
                        status,
                        fingerprint,
                        OffsetDateTime.now(),
                        id,
                        revision);
                if (changed == 0) throw new HarnessException("CONFLICT", "记忆已发生变化，请刷新后重试");
                if (!fingerprint(before.content()).equals(fingerprint)) exclude(before.workspaceId(), before.content());
            }
            if (!replacesId.isEmpty()) {
                Entry previous = get(replacesId);
                if (savedId.equals(replacesId)
                        || !previous.workspaceId().equals(workspaceId)
                        || !status.equals("ACTIVE"))
                    throw new HarnessException("INVALID_REQUEST", "只能使用同一范围的已确认记忆替代旧记忆");
                if (jdbc.update(
                                "UPDATE harness_memory SET status='ARCHIVED',updated_at=?,revision=revision+1 "
                                        + "WHERE id=? AND revision=?",
                                OffsetDateTime.now(),
                                replacesId,
                                replacesRevision)
                        == 0) throw new HarnessException("CONFLICT", "旧记忆已发生变化，请刷新后重试");
                jdbc.update(
                        "MERGE INTO harness_memory_relation KEY(from_id,to_id) VALUES (?,?,?)",
                        savedId,
                        replacesId,
                        "替代");
            }
            return get(savedId);
        });
    }

    /** 自动生成直接生效；重复内容和用户删除、纠正的正文不会再次写入。 */
    private void generated(
            String workspaceId,
            String title,
            String content,
            String kind,
            List<String> tags,
            String sessionId,
            String runId,
            String excerpt) {
        if (jdbc.queryForObject(
                        "SELECT (SELECT COUNT(*) FROM harness_memory WHERE workspace_id=? AND fingerprint=?) "
                                + "+ (SELECT COUNT(*) FROM harness_memory_exclusion WHERE workspace_id=? AND fingerprint=?)",
                        Integer.class,
                        workspaceId,
                        fingerprint(content),
                        workspaceId,
                        fingerprint(content))
                > 0) return;
        save("", workspaceId, title, content, kind, tags, "ACTIVE", 0, sessionId, runId, excerpt, "", 0);
    }

    /** 检查版本后删除记忆，关系随外键级联删除。 */
    public synchronized void delete(String id, long revision) {
        tx.executeWithoutResult(status -> {
            Entry entry = get(id);
            if (jdbc.update("DELETE FROM harness_memory WHERE id=? AND revision=?", id, revision) == 0)
                throw new HarnessException("CONFLICT", "记忆已发生变化，请刷新后重试");
            exclude(entry.workspaceId(), entry.content());
        });
    }

    /** 保存正文指纹，不额外保留被删除的正文。 */
    private void exclude(String workspaceId, String content) {
        jdbc.update(
                "MERGE INTO harness_memory_exclusion KEY(workspace_id,fingerprint) VALUES (?,?)",
                workspaceId,
                fingerprint(content));
    }

    /** 读取全局自动记忆设置。 */
    public synchronized Settings settings() {
        return jdbc.queryForObject(
                "SELECT * FROM harness_memory_settings WHERE id=1",
                (rs, row) -> new Settings(
                        rs.getBoolean("enabled"), rs.getString("model_profile_id"), rs.getLong("revision")));
    }

    /** 设置采用乐观锁，避免多页面互相覆盖。 */
    public synchronized Settings saveSettings(boolean enabled, String modelProfileId, long revision) {
        if (jdbc.update(
                        "UPDATE harness_memory_settings SET enabled=?,model_profile_id=?,revision=revision+1 WHERE id=1 AND revision=?",
                        enabled,
                        modelProfileId,
                        revision)
                == 0) throw new HarnessException("CONFLICT", "记忆设置已变化，请重新加载");
        return settings();
    }

    /** 分批发现历史与新完成的主任务，已处理来源永不重复入队；返回本批入队数。 */
    public synchronized int enqueueHistory() {
        return enqueue("");
    }

    /** 新完成的任务立即入队，避免等待全部历史发现完毕。 */
    public synchronized void enqueueRun(String runId) {
        enqueue(runId);
    }

    /** 历史扫描和完成通知共享同一幂等入队规则。 */
    private int enqueue(String runId) {
        return tx.execute(transaction -> {
            var sources = jdbc.query(
                    SOURCE_QUERY + "WHERE r.status='COMPLETED' "
                            + "AND r.provider<>'simulated' AND r.parent_run_id IS NULL AND (?='' OR r.id=?) AND NOT EXISTS "
                            + "(SELECT 1 FROM harness_memory_job j WHERE j.run_id=r.id) ORDER BY r.created_at,r.id LIMIT 100",
                    SOURCE,
                    runId,
                    runId);
            for (Source source : sources)
                jdbc.update(
                        "INSERT INTO harness_memory_job VALUES (?,?,?,?,CURRENT_TIMESTAMP,?)",
                        source.id(),
                        source.workspaceId(),
                        source.matches() ? "PENDING" : "SKIPPED",
                        0,
                        source.matches() ? "" : "来源会话的工作区已变更，或历史归属有歧义");
            return sources.size();
        });
    }

    /** 当前绑定必须仍与任务原始身份一致，防止同目录工作区相互归属。 */
    public synchronized String scope(String runId) {
        var rows = jdbc.query(SOURCE_QUERY + "WHERE r.id=?", SOURCE, runId);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "记忆来源不存在");
        Source source = rows.getFirst();
        if (!source.matches()) throw new HarnessException("CONFLICT", "任务工作区已变更或历史归属有歧义");
        return source.workspaceId();
    }

    /** 服务重启后恢复尚未提交结果的任务。 */
    public synchronized void recoverJobs() {
        jdbc.update(
                "UPDATE harness_memory_job SET status='PENDING',attempts=GREATEST(0,attempts-1),next_attempt_at=? WHERE status='RUNNING'",
                OffsetDateTime.now());
    }

    /** 每次只领取一个到期任务，由单个后台工作线程串行执行。 */
    public synchronized Job claimJob() {
        var rows = jdbc.query(
                "SELECT j.* FROM harness_memory_job j JOIN harness_run r ON r.id=j.run_id "
                        + "WHERE j.status='PENDING' AND j.next_attempt_at<=CURRENT_TIMESTAMP ORDER BY r.created_at DESC,j.run_id LIMIT 1",
                (rs, row) -> new Job(
                        rs.getString("run_id"),
                        rs.getString("workspace_id"),
                        rs.getString("status"),
                        rs.getInt("attempts")));
        if (rows.isEmpty()) return null;
        Job job = rows.getFirst();
        jdbc.update("UPDATE harness_memory_job SET status='RUNNING',attempts=attempts+1 WHERE run_id=?", job.runId());
        return new Job(job.runId(), job.workspaceId(), "RUNNING", job.attempts() + 1);
    }

    /** 记忆写入和完成标记共同提交，避免重启后重复生成或只保存部分结果。 */
    public synchronized void finishJob(Job job, String sessionId, String excerpt, List<Generated> entries) {
        tx.executeWithoutResult(status -> {
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM harness_memory_job WHERE run_id=? AND status='RUNNING'",
                            Integer.class,
                            job.runId())
                    == 0) return;
            for (Generated entry : entries)
                generated(
                        job.workspaceId(),
                        entry.title(),
                        entry.content(),
                        entry.kind(),
                        entry.tags(),
                        sessionId,
                        job.runId(),
                        excerpt);
            jdbc.update("UPDATE harness_memory_job SET status='COMPLETED',detail='' WHERE run_id=?", job.runId());
        });
    }

    /** 失败最多自动重试三次；暂停不消耗尝试次数。 */
    public synchronized void releaseJob(Job job, boolean paused, boolean skipped) {
        String state = skipped ? "SKIPPED" : paused || job.attempts() < 3 ? "PENDING" : "FAILED";
        jdbc.update(
                "UPDATE harness_memory_job SET status=?,attempts=?,next_attempt_at=?,detail=? WHERE run_id=? AND status='RUNNING'",
                state,
                paused ? Math.max(0, job.attempts() - 1) : job.attempts(),
                OffsetDateTime.now().plusSeconds(paused || skipped ? 0 : 15L * job.attempts()),
                skipped ? "来源会话或工作区已变化" : paused ? "" : "生成失败，请检查模型连接或输出格式",
                job.runId());
    }

    /** 只重试失败来源，不重新处理已经完成的历史。 */
    public synchronized void retryFailed() {
        jdbc.update(
                "UPDATE harness_memory_job SET status='PENDING',attempts=0,next_attempt_at=?,detail='' WHERE status='FAILED'",
                OffsetDateTime.now());
    }

    /** 进度包含尚未分批入队的历史，全部来自真实数据库状态。 */
    public synchronized Progress progress() {
        var counts = new java.util.HashMap<String, Integer>();
        jdbc.query("SELECT status,COUNT(*) AS n FROM harness_memory_job GROUP BY status", rs -> {
            counts.put(rs.getString("status"), rs.getInt("n"));
        });
        int total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_run WHERE status='COMPLETED' AND provider<>'simulated' AND parent_run_id IS NULL",
                Integer.class);
        int running = counts.getOrDefault("RUNNING", 0), completed = counts.getOrDefault("COMPLETED", 0);
        int failed = counts.getOrDefault("FAILED", 0), skipped = counts.getOrDefault("SKIPPED", 0);
        return new Progress(
                total,
                Math.max(0, total - running - completed - failed - skipped),
                running,
                completed,
                failed,
                skipped);
    }

    /** 返回一个范围中的显式关系，包括过滤和分页外的端点。 */
    public synchronized List<Relation> relations(String workspaceId) {
        return jdbc.query(
                "SELECT r.* FROM harness_memory_relation r JOIN harness_memory m ON m.id=r.from_id "
                        + "WHERE m.workspace_id=?",
                (rs, row) -> new Relation(rs.getString("from_id"), rs.getString("to_id"), rs.getString("label")),
                workspaceId);
    }

    /** 显式关系只允许连接同一范围内的不同记忆。 */
    public synchronized void relate(String fromId, String toId, String label) {
        if (fromId.equals(toId) || !get(fromId).workspaceId().equals(get(toId).workspaceId()))
            throw new HarnessException("INVALID_REQUEST", "关系必须连接同一范围内的不同记忆");
        jdbc.update("MERGE INTO harness_memory_relation KEY(from_id,to_id) VALUES (?,?,?)", fromId, toId, label);
    }

    /** 删除指定方向的关系。 */
    public synchronized void unrelate(String fromId, String toId) {
        jdbc.update("DELETE FROM harness_memory_relation WHERE from_id=? AND to_id=?", fromId, toId);
    }

    /** 保存模型输入采用的记忆版本，后续修改不覆盖此快照。 */
    public synchronized void recordUsage(String runId, List<Entry> entries) {
        tx.executeWithoutResult(status -> {
            for (Entry entry : entries)
                jdbc.update(
                        "INSERT INTO harness_memory_usage VALUES (?,?,?,?,?)",
                        runId,
                        entry.id(),
                        entry.title(),
                        entry.content(),
                        entry.revision());
        });
    }

    /** 返回某个任务首次请求中的记忆正文快照。 */
    public synchronized List<Usage> usage(String runId) {
        return jdbc.query(
                "SELECT * FROM harness_memory_usage WHERE run_id=? ORDER BY memory_id",
                (rs, row) -> new Usage(
                        rs.getString("memory_id"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getLong("revision")),
                runId);
    }

    /** 内容指纹仅用于去重，保留原始文本用于显示和召回。 */
    private static String fingerprint(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(content.strip().replaceAll("\\s+", " ").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** LIKE 参数转义，搜索文字中的通配符按字面处理。 */
    private static String like(String text) {
        return "%"
                + text.toLowerCase(java.util.Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_") + "%";
    }
}
