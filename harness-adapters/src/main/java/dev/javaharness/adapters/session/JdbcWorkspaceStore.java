package dev.javaharness.adapters.session;

import dev.javaharness.adapters.workspace.WorkspaceRoots;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** Package-private workspace persistence helper owned by {@link JdbcSessionStore} under its monitor lock. */
final class JdbcWorkspaceStore {
    @FunctionalInterface
    interface EventAppender {
        void append(String sessionId, String runId, String type, RunStatus state, String text);
    }

    private static final RowMapper<Workspace> WORKSPACE_MAPPER = (rs, row) -> new Workspace(
            rs.getString("id"),
            rs.getString("name"),
            WorkspaceRoots.directories(rs.getString("roots")),
            rs.getString("icon"),
            rs.getString("color"),
            rs.getLong("revision"));
    private static final List<String> ICONS = List.of(
            "folder",
            "code",
            "branch",
            "cube",
            "reasoning",
            "coffee",
            "flask",
            "rocket",
            "bug",
            "star",
            "music",
            "heart",
            "globe",
            "terminal",
            "gamepad");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    JdbcWorkspaceStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    static void checkWorkspace(String workspace) {
        if (workspace == null || workspace.length() > 131072) throw new HarnessException("INVALID", "无效工作区路径");
    }

    /** Reuse migrated directory groups, keeping old single-root tool paths valid. */
    String legacyWorkspace(String roots) {
        var existing = jdbc.query("SELECT * FROM harness_workspace WHERE roots=? ORDER BY id", WORKSPACE_MAPPER, roots);
        if (!existing.isEmpty()) return existing.getFirst().id();
        List<String> directories = WorkspaceRoots.directories(roots);
        String first = directories.getFirst();
        String[] parts = first.split("[\\\\/]");
        String name = parts.length == 0 ? first : parts[parts.length - 1];
        if (name.length() > 100) name = name.substring(0, 100);
        String id = UUID.randomUUID().toString();
        // Historical snapshots are preserved verbatim; only new/edited directories are validated.
        jdbc.update(
                "INSERT INTO harness_workspace(id,name,roots,icon,color,revision) VALUES (?,?,?,'folder','#4b705b',1)",
                id,
                name,
                roots);
        return id;
    }

    List<Workspace> workspaces() {
        return jdbc.query("SELECT * FROM harness_workspace ORDER BY name,id", WORKSPACE_MAPPER);
    }

    Workspace workspace(String id) {
        var rows = jdbc.query("SELECT * FROM harness_workspace WHERE id=?", WORKSPACE_MAPPER, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "工作区不存在");
        return rows.getFirst();
    }

    /** Persist workspace metadata and update every bound session atomically under the session lock. */
    Workspace saveWorkspace(
            String id,
            String name,
            List<String> directories,
            String icon,
            String color,
            long revision,
            Supplier<List<Session>> allSessions,
            EventAppender events) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 100)
            throw new HarnessException("INVALID", "工作区名称需为 1–100 个字符");
        if (icon == null || !ICONS.contains(icon) || color == null || !color.matches("#[0-9a-fA-F]{6}"))
            throw new HarnessException("INVALID", "请选择有效的工作区图标和颜色");
        String roots = WorkspaceRoots.encode(directories);
        return tx.execute(status -> {
            String key = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
            if (id == null || id.isEmpty()) {
                jdbc.update(
                        "INSERT INTO harness_workspace(id,name,roots,icon,color,revision) VALUES (?,?,?,?,?,1)",
                        key,
                        normalized,
                        roots,
                        icon,
                        color);
            } else {
                Workspace current = workspace(key);
                if (current.revision() != revision) throw new HarnessException("CONFLICT", "工作区已被更新，请重新打开后再保存");
                if (!WorkspaceRoots.encode(current.directories()).equals(roots)) {
                    Integer active = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM harness_session WHERE workspace_id=? AND active_run_id IS NOT NULL",
                            Integer.class,
                            key);
                    Integer pending = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM harness_file_change c JOIN harness_run r ON r.id=c.run_id WHERE r.workspace=? AND c.status IN ('PENDING','APPLYING')",
                            Integer.class,
                            WorkspaceRoots.encode(current.directories()));
                    if (active > 0 || pending > 0) throw new HarnessException("BUSY", "工作区有运行中的任务或待审核修改，请处理完成后再调整目录");
                }
                jdbc.update(
                        "UPDATE harness_workspace SET name=?,roots=?,icon=?,color=?,revision=revision+1 WHERE id=?",
                        normalized,
                        roots,
                        icon,
                        color,
                        key);
                for (Session session : allSessions.get()) {
                    if (!session.workspaceId().equals(key)) continue;
                    jdbc.update("UPDATE harness_session SET workspace=? WHERE id=?", roots, session.id());
                    events.append(session.id(), null, "WORKSPACE", null, "工作区已更新");
                }
            }
            return workspace(key);
        });
    }
}
