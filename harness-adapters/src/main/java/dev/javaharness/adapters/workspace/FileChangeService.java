package dev.javaharness.adapters.workspace;

import dev.javaharness.adapters.approval.ApprovalPolicy;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.FileChange;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coordinates file reviews with the single-process session store. Files and SQL are not one transaction.
 * Smart and full-access runs write edits outside protected paths immediately and keep them revertible;
 * manual runs and protected paths keep the review-after-run flow.
 */
public final class FileChangeService {
    /** Version-control, tooling and agent-instruction locations that change how later commands or runs behave. */
    private static final Set<String> PROTECTED_DIRECTORIES = Set.of(
            ".mvn", ".vscode", ".husky", ".github", ".gitlab", ".claude", ".codex", ".agents", ".devcontainer",
            ".cargo", ".yarn", ".gradle");
    private static final Set<String> PROTECTED_FILES = Set.of(
            ".gitconfig", ".gitmodules", ".gitattributes", ".npmrc", ".yarnrc", ".yarnrc.yml", "bunfig.toml",
            ".envrc", ".bashrc", ".zshrc", ".profile", ".bash_profile", ".pre-commit-config.yaml", "lefthook.yml",
            ".mcp.json", ".claude.json", ".gitlab-ci.yml", "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat", "agents.md",
            "claude.md");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore sessions;
    private final ApprovalPolicy policy;
    private static final RowMapper<FileChange> MAPPER = (rs, row) -> new FileChange(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("path"),
            rs.getString("operation"),
            rs.getString("before_content"),
            rs.getString("after_content"),
            rs.getString("status"),
            rs.getString("detail"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getString("decided_by"));

    public FileChangeService(JdbcTemplate jdbc, TransactionTemplate tx, SessionStore sessions) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sessions = sessions;
        this.policy = new ApprovalPolicy(jdbc);
        synchronized (sessions) {
            // Never guess whether a write completed, or replay a write after process loss.
            for (FileChange change : jdbc.query(
                    "SELECT * FROM harness_file_change WHERE status IN ('APPLYING','REVERTING')", MAPPER))
                settle(change, "UNCERTAIN", "应用过程中服务中断，请检查磁盘文件；不会自动重试");
        }
    }

    public List<FileChange> list(String runId) {
        synchronized (sessions) {
            sessions.run(runId);
            return jdbc.query("SELECT * FROM harness_file_change WHERE run_id=? ORDER BY created_at,id", MAPPER, runId);
        }
    }

    /** 按 id 读取一条文件修改。 */
    public FileChange find(String id) {
        synchronized (sessions) {
            return get(id);
        }
    }

    private FileChange get(String id) {
        var rows = jdbc.query("SELECT * FROM harness_file_change WHERE id=?", MAPPER, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "文件修改不存在");
        return rows.getFirst();
    }

    public FileChange propose(
            String runId,
            String callId,
            String workspace,
            String path,
            String operation,
            String oldText,
            String newText) {
        synchronized (sessions) {
            Run run = sessions.run(runId);
            if (run.status() != RunStatus.RUNNING || !run.workspace().equals(workspace) || workspace.isEmpty())
                throw new HarnessException("CONFLICT", "任务已停止或工作区不匹配，不能生成修改");
            var existing =
                    jdbc.query("SELECT * FROM harness_file_change WHERE run_id=? AND call_id=?", MAPPER, runId, callId);
            if (!existing.isEmpty()) return existing.getFirst();
            if (list(runId).size() >= 32) throw new HarnessException("INVALID", "每个任务最多生成 32 个文件修改，请分批处理");
            if (!Set.of("replace", "create").contains(operation))
                throw new HarnessException("INVALID", "只支持 replace 或 create");
            try {
                WorkspaceRoots.Target location = WorkspaceRoots.target(workspace, path);
                if (path.length() > 2048) throw new IOException("Path too long");
                var files = new WorkspaceFileEditor(location.root());
                Path target = files.resolve(location.relative());
                String before = null;
                String after;
                if (operation.equals("create")) {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                        throw new HarnessException("CONFLICT", "新建目标已存在，请读取后提出替换修改");
                    if (oldText != null && !oldText.isEmpty())
                        throw new HarnessException("INVALID", "新建文件时 old_text 必须为空");
                    WorkspaceFileEditor.validate(newText);
                    after = newText;
                } else {
                    before = files.read(target);
                    after = WorkspaceFileEditor.replace(before, oldText, newText);
                }
                String id = UUID.randomUUID().toString();
                String capturedBefore = before;
                ApprovalMode mode = policy.mode(runId);
                boolean write = mode != ApprovalMode.MANUAL && !protectedPath(location.relative());
                tx.executeWithoutResult(status -> {
                    jdbc.update(
                            """
                            INSERT INTO harness_file_change(id,run_id,call_id,path,operation,before_content,after_content,status,detail,decided_by,created_at)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?)
                            """,
                            id,
                            runId,
                            callId,
                            path,
                            operation,
                            capturedBefore,
                            after,
                            // Commit intent before touching the workspace. A crash leaves a durable uncertain state.
                            write ? "APPLYING" : "PENDING",
                            write ? "正在写入" : mode != ApprovalMode.MANUAL ? "受保护路径，任务结束后需要人工审核" : "",
                            write ? mode == ApprovalMode.FULL_ACCESS ? "FULL_ACCESS" : "RULE" : null,
                            OffsetDateTime.now(ZoneOffset.UTC));
                    sessions.fileChangesUpdated(runId);
                });
                FileChange change = get(id);
                if (!write) return change;
                try {
                    files.apply(location.relative(), capturedBefore, after);
                } catch (WorkspaceFileEditor.FileConflict | FileAlreadyExistsException error) {
                    return settle(change, "CONFLICT", "写入前文件发生变化，未覆盖；请重新读取后再修改");
                } catch (IOException | RuntimeException error) {
                    return settle(change, "UNCERTAIN", "无法确认写入结果，请检查文件与目录权限；不会自动重试");
                }
                return settle(change, "APPLIED", "已直接写入工作区，可在差异卡片中撤销");
            } catch (IOException | IllegalArgumentException error) {
                throw new HarnessException(
                        "INVALID", "无法生成修改：路径须在工作区内且父目录已存在；仅支持 1 MiB 内 UTF-8 文本；old_text 必须唯一匹配原文，修改不能为空操作");
            }
        }
    }

    public FileChange reject(String id) {
        synchronized (sessions) {
            FileChange change = get(id);
            if (change.status().equals("REJECTED")) return change;
            requirePending(change);
            return settle(change, "REJECTED", "已拒绝，未写入文件");
        }
    }

    /** A group chat member's change waits until no member of that group is speaking. */
    private boolean groupBusy(String sessionId) {
        return jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM harness_session s JOIN harness_session o ON o.group_id=s.group_id
                        WHERE s.id=? AND o.active_run_id IS NOT NULL
                        """,
                        Integer.class,
                        sessionId)
                > 0;
    }

    public FileChange apply(String id) {
        synchronized (sessions) {
            FileChange change = get(id);
            if (change.status().equals("APPLIED")) return change;
            requirePending(change);
            Run run = sessions.run(change.runId());
            var snapshot = sessions.snapshot(run.sessionId());
            if (run.status() != RunStatus.COMPLETED)
                throw new HarnessException("CONFLICT", "仅可应用已正常完成任务的修改；中断或失败后请重新生成");
            if (snapshot.runs().stream().anyMatch(value -> value.status().active()))
                throw new HarnessException("BUSY", "请等待当前会话任务结束，再应用修改");
            if (groupBusy(run.sessionId())) throw new HarnessException("BUSY", "请等待群聊成员发言结束，再应用修改");
            if (!snapshot.session().workspace().equals(run.workspace()))
                throw new HarnessException("CONFLICT", "会话工作区已改变，请切回原工作区后审核");
            WorkspaceFileEditor files;
            WorkspaceRoots.Target location = WorkspaceRoots.target(run.workspace(), change.path());
            try {
                files = new WorkspaceFileEditor(location.root());
                Path path = files.resolve(location.relative());
                if (!files.matches(path, change.beforeContent()))
                    return settle(change, "CONFLICT", "文件已被修改、删除或新建，未覆盖；请重新生成修改");
            } catch (IOException | IllegalArgumentException error) {
                return settle(change, "CONFLICT", "原文件或目录不可访问、编码改变或存在链接，未写入；请检查后重新生成");
            }
            // Commit intent before touching the workspace. A crash leaves a durable uncertain state.
            settle(change, "APPLYING", "正在应用");
            try {
                files.apply(location.relative(), change.beforeContent(), change.afterContent());
            } catch (WorkspaceFileEditor.FileConflict | FileAlreadyExistsException error) {
                return settle(change, "CONFLICT", "应用前文件发生变化，未覆盖；请重新生成修改");
            } catch (IOException | RuntimeException error) {
                return settle(change, "UNCERTAIN", "无法确认应用结果，请检查文件与目录权限；不会自动重试");
            }
            return settle(change, "APPLIED", "已应用到工作区");
        }
    }

    /** Restore an applied edit while the session is idle and the file still holds the edited content. */
    public FileChange revert(String id) {
        synchronized (sessions) {
            FileChange change = get(id);
            if (change.status().equals("REVERTED")) return change;
            if (!change.status().equals("APPLIED")) throw new HarnessException("CONFLICT", "只能撤销已写入的修改");
            Run run = sessions.run(change.runId());
            var snapshot = sessions.snapshot(run.sessionId());
            if (snapshot.runs().stream().anyMatch(value -> value.status().active()))
                throw new HarnessException("BUSY", "请等待当前会话任务结束，再撤销修改");
            if (groupBusy(run.sessionId())) throw new HarnessException("BUSY", "请等待群聊成员发言结束，再撤销修改");
            if (!snapshot.session().workspace().equals(run.workspace()))
                throw new HarnessException("CONFLICT", "会话工作区已改变，请切回原工作区后撤销");
            WorkspaceRoots.Target location = WorkspaceRoots.target(run.workspace(), change.path());
            WorkspaceFileEditor files;
            try {
                files = new WorkspaceFileEditor(location.root());
                if (!files.matches(files.resolve(location.relative()), change.afterContent()))
                    throw new HarnessException(
                            "CONFLICT", "文件之后又被修改（可能是同一任务的后续修改），请先撤销后面的修改或手动处理");
            } catch (IOException | IllegalArgumentException error) {
                throw new HarnessException("CONFLICT", "文件或目录不可访问、编码改变或存在链接，未撤销");
            }
            String applied = change.detail();
            settle(change, "REVERTING", "正在撤销");
            try {
                if (change.beforeContent() == null) {
                    // Created by this edit: re-check right before deleting so a newer save is never removed.
                    Path path = files.resolve(location.relative());
                    if (!files.matches(path, change.afterContent())) throw new WorkspaceFileEditor.FileConflict();
                    Files.delete(path);
                } else files.apply(location.relative(), change.afterContent(), change.beforeContent());
            } catch (WorkspaceFileEditor.FileConflict error) {
                settle(change, "APPLIED", applied);
                throw new HarnessException("CONFLICT", "撤销前文件发生变化，未覆盖；请检查文件");
            } catch (IOException | RuntimeException error) {
                return settle(change, "UNCERTAIN", "无法确认撤销结果，请检查文件；不会自动重试");
            }
            return settle(change, "REVERTED", "已撤销，文件恢复为修改前的内容");
        }
    }

    /** Protected locations always wait for review after the run, whatever the approval mode. */
    static boolean protectedPath(String relative) {
        // Windows 8.3 short names (GITHUB~1) may alias a protected directory, so they always wait for review.
        if (relative.matches("(.*/)?[^/]*~\\d[^/]*(/.*)?")) return true;
        String[] parts = relative.toLowerCase(Locale.ROOT).split("/");
        for (int i = 0; i < parts.length - 1; i++) if (PROTECTED_DIRECTORIES.contains(parts[i])) return true;
        return PROTECTED_FILES.contains(parts[parts.length - 1]);
    }

    private static void requirePending(FileChange change) {
        if (!change.status().equals("PENDING")) throw new HarnessException("CONFLICT", "该修改已经处理，请刷新记录");
    }

    private FileChange settle(FileChange change, String status, String detail) {
        return tx.execute(ignored -> {
            jdbc.update("UPDATE harness_file_change SET status=?,detail=? WHERE id=?", status, detail, change.id());
            sessions.fileChangesUpdated(change.runId());
            return get(change.id());
        });
    }
}
