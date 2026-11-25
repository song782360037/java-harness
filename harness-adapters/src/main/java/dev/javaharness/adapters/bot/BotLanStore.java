package dev.javaharness.adapters.bot;

import dev.javaharness.spi.HarnessException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Bot 局域网移动访问设置与已配对设备存储（仅存设备令牌的 SHA-256 哈希）。 */
public final class BotLanStore {
    public record LanSettings(
            boolean enabled, int port, boolean allowRemoteApproval, long revision, Instant updatedAt) {}

    public record Device(
            String id, String name, String userAgent, Instant createdAt, Instant lastSeenAt, boolean revoked) {}

    public record PendingBotCommand(
            String commandId, String runId, String botId, String taskTitle, String commandText, String purpose) {}

    private static final RowMapper<LanSettings> SETTINGS = (rs, row) -> new LanSettings(
            rs.getBoolean("enabled"),
            rs.getInt("port"),
            rs.getBoolean("allow_remote_approval"),
            rs.getLong("revision"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private static final RowMapper<Device> DEVICE = (rs, row) -> new Device(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("user_agent") == null ? "" : rs.getString("user_agent"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
            rs.getBoolean("revoked"));

    private static final RowMapper<PendingBotCommand> PENDING_CMD = (rs, row) -> new PendingBotCommand(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("bot_id"),
            rs.getString("title"),
            rs.getString("command_text"),
            rs.getString("purpose"));

    private final JdbcTemplate jdbc;

    public BotLanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public LanSettings ensureSettings() {
        var rows = jdbc.query("SELECT * FROM harness_bot_lan_settings WHERE id='default'", SETTINGS);
        if (!rows.isEmpty()) return rows.getFirst();
        OffsetDateTime time = now();
        jdbc.update("""
                INSERT INTO harness_bot_lan_settings(id,enabled,port,allow_remote_approval,revision,updated_at)
                VALUES ('default',FALSE,4317,FALSE,1,?)
                """, time);
        return new LanSettings(false, 4317, false, 1, time.toInstant());
    }

    public LanSettings saveSettings(boolean enabled, int port, boolean allowRemoteApproval, long revision) {
        LanSettings current = ensureSettings();
        long expected = revision > 0 ? revision : current.revision();
        OffsetDateTime time = now();
        int updated = jdbc.update("""
                UPDATE harness_bot_lan_settings
                SET enabled=?,port=?,allow_remote_approval=?,revision=revision+1,updated_at=?
                WHERE id='default' AND revision=?
                """, enabled, port, allowRemoteApproval, time, expected);
        if (updated == 0) throw new HarnessException("CONFLICT", "设备设置已更新，请刷新后重试");
        return ensureSettings();
    }

    public List<Device> devices() {
        return jdbc.query(
                "SELECT * FROM harness_bot_lan_device WHERE revoked=FALSE ORDER BY last_seen_at DESC, created_at DESC",
                DEVICE);
    }

    public Device registerDevice(String name, String userAgent, String tokenHash) {
        String id = UUID.randomUUID().toString();
        OffsetDateTime time = now();
        String ua = userAgent == null ? "" : (userAgent.length() > 300 ? userAgent.substring(0, 300) : userAgent);
        jdbc.update("""
                INSERT INTO harness_bot_lan_device(id,name,user_agent,token_hash,created_at,last_seen_at,revoked)
                VALUES (?,?,?,?,?,?,FALSE)
                """, id, name, ua, tokenHash, time, time);
        return device(id);
    }

    public Device device(String id) {
        var rows = jdbc.query("SELECT * FROM harness_bot_lan_device WHERE id=?", DEVICE, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "设备不存在");
        return rows.getFirst();
    }

    public Device findActiveByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) return null;
        var rows = jdbc.query(
                "SELECT * FROM harness_bot_lan_device WHERE token_hash=? AND revoked=FALSE LIMIT 1", DEVICE, tokenHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void touchDevice(String id) {
        jdbc.update("UPDATE harness_bot_lan_device SET last_seen_at=? WHERE id=?", now(), id);
    }

    public void revokeDevice(String id) {
        device(id);
        jdbc.update("UPDATE harness_bot_lan_device SET revoked=TRUE WHERE id=?", id);
    }

    /** 仅查询归属 Bot 后台任务隐藏会话的待审批命令，防止越权操作普通编码会话命令。 */
    public List<PendingBotCommand> pendingBotCommands() {
        return jdbc.query("""
                SELECT c.id, c.run_id, t.bot_id, t.title, c.command_text, c.purpose
                FROM harness_command c
                JOIN harness_run r ON r.id = c.run_id
                JOIN harness_bot_task t ON t.session_id = r.session_id
                WHERE c.status = 'PENDING' AND r.status = 'RUNNING'
                ORDER BY c.created_at ASC
                """, PENDING_CMD);
    }

    public boolean isBotTaskCommand(String commandId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM harness_command c
                JOIN harness_run r ON r.id = c.run_id
                JOIN harness_bot_task t ON t.session_id = r.session_id
                WHERE c.id = ?
                """, Integer.class, commandId);
        return count != null && count > 0;
    }
}
