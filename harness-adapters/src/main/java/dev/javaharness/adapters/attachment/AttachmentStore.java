package dev.javaharness.adapters.attachment;

import dev.javaharness.spi.HarnessException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** 用户随消息上传的文件与图片；上传时未绑定运行，提交创建运行后绑定，会话删除时随运行级联删除。 */
public final class AttachmentStore {
    /** 附件元数据；runId 为 null 表示尚未随消息发送。 */
    public record Attachment(String id, String runId, String name, String mediaType, int size, Instant createdAt) {}

    public record Stored(Attachment attachment, byte[] content) {}

    /** Uploads never sent are dropped after a day. */
    static final Duration ORPHAN_AGE = Duration.ofDays(1);

    private static final String COLUMNS = "id,run_id,name,media_type,size_bytes,created_at";
    private static final RowMapper<Attachment> ATTACHMENT = (rs, row) -> new Attachment(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("name"),
            rs.getString("media_type"),
            rs.getInt("size_bytes"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public AttachmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 保存一个未绑定的附件，并顺带清理过期的未发送附件。 */
    public Attachment save(String name, String mediaType, byte[] content) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("DELETE FROM harness_attachment WHERE run_id IS NULL AND created_at < ?", now.minus(ORPHAN_AGE));
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO harness_attachment(id,name,media_type,size_bytes,content,created_at) VALUES (?,?,?,?,?,?)",
                id,
                name,
                mediaType,
                content.length,
                content,
                now);
        return new Attachment(id, null, name, mediaType, content.length, now.toInstant());
    }

    /** 按给定顺序读取附件及内容；任一不存在时整体失败。 */
    public List<Stored> load(List<String> ids) {
        var result = new ArrayList<Stored>();
        for (String id : ids) result.add(content(id));
        return result;
    }

    public Stored content(String id) {
        var rows = jdbc.query(
                "SELECT " + COLUMNS + ",content FROM harness_attachment WHERE id=?",
                (rs, row) -> new Stored(ATTACHMENT.mapRow(rs, row), rs.getBytes("content")),
                id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "附件不存在或已过期，请重新上传");
        return rows.getFirst();
    }

    /** 把尚未绑定的附件按顺序绑定到新建的运行；已绑定的附件保持原归属。 */
    public void bind(String runId, List<String> ids) {
        for (int i = 0; i < ids.size(); i++)
            jdbc.update(
                    "UPDATE harness_attachment SET run_id=?, ordinal=? WHERE id=? AND run_id IS NULL",
                    runId,
                    i,
                    ids.get(i));
    }

    /** 会话内各运行已发送的附件，按运行与上传顺序排列，不含内容。 */
    public List<Attachment> forSession(String sessionId) {
        return jdbc.query(
                "SELECT a.id,a.run_id,a.name,a.media_type,a.size_bytes,a.created_at FROM harness_attachment a"
                        + " JOIN harness_run r ON r.id=a.run_id WHERE r.session_id=? ORDER BY a.run_id,a.ordinal",
                ATTACHMENT,
                sessionId);
    }
}
