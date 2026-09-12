package dev.javaharness.adapters;

import dev.javaharness.spi.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Approval decisions are durable; only the original tool worker may consume an approval. */
public final class CommandService {
    private static final Set<String> ACTIVE = Set.of("PENDING", "APPROVED", "RUNNING", "STOPPING");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore sessions;
    private final LocalCommandRunner runner = new LocalCommandRunner();
    private static final RowMapper<CommandExecution> MAPPER = (rs, row) -> new CommandExecution(rs.getString("id"),
            rs.getString("run_id"), rs.getString("command_text"), rs.getString("directory"), rs.getString("shell"),
            rs.getString("purpose"), rs.getInt("timeout_seconds"), rs.getString("status"), rs.getString("stdout"),
            rs.getString("stderr"), rs.getObject("exit_code", Integer.class), rs.getString("detail"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    public CommandService(JdbcTemplate jdbc, TransactionTemplate tx, SessionStore sessions) {
        this.jdbc = jdbc; this.tx = tx; this.sessions = sessions;
        synchronized (sessions) {
            for (var command : jdbc.query("SELECT * FROM harness_command WHERE status IN ('PENDING','APPROVED','RUNNING','STOPPING')", MAPPER))
                settle(command, "INTERRUPTED", null, "服务中断，执行结果可能不完整；请检查工作区，不会自动重跑");
        }
    }

    public List<CommandExecution> list(String runId) {
        synchronized (sessions) {
            sessions.run(runId);
            return jdbc.query("SELECT * FROM harness_command WHERE run_id=? ORDER BY created_at,id", MAPPER, runId);
        }
    }
    private CommandExecution get(String id) {
        var values = jdbc.query("SELECT * FROM harness_command WHERE id=?", MAPPER, id);
        if (values.isEmpty()) throw new HarnessException("NOT_FOUND", "命令不存在");
        return values.getFirst();
    }

    public CommandExecution propose(String runId, String callId, String workspace, String command,
                                    String directory, String purpose, int timeoutSeconds) {
        synchronized (sessions) {
            requireRunning(runId, workspace);
            var existing = jdbc.query("SELECT * FROM harness_command WHERE run_id=? AND call_id=?", MAPPER, runId, callId);
            if (!existing.isEmpty()) return existing.getFirst();
            if (command == null || command.isBlank() || command.length() > 8000 || command.indexOf('\0') >= 0
                    || purpose == null || purpose.isBlank() || purpose.length() > 1000 || timeoutSeconds < 1 || timeoutSeconds > 300)
                throw new HarnessException("INVALID", "命令需为 1–8000 字符，用途为 1–1000 字符，超时为 1–300 秒");
            if (list(runId).size() >= 32) throw new HarnessException("INVALID", "单任务最多审批 32 条命令，请分批处理");
            Path resolved = directory(workspace, directory);
            String id = UUID.randomUUID().toString();
            tx.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO harness_command(id,run_id,call_id,command_text,directory,shell,purpose,timeout_seconds,status,created_at)
                        VALUES (?,?,?,?,?,?,?,?,'PENDING',?)
                        """, id, runId, callId, command, resolved.toString(), LocalCommandRunner.shell(), purpose,
                        timeoutSeconds, OffsetDateTime.now(ZoneOffset.UTC));
                sessions.commandsUpdated(runId);
            });
            return get(id);
        }
    }

    public CommandExecution approve(String id) {
        synchronized (sessions) {
            var value = get(id);
            if (Set.of("APPROVED", "RUNNING", "COMPLETED", "FAILED", "TIMED_OUT", "OUTPUT_LIMIT").contains(value.status())) return value;
            if (!value.status().equals("PENDING")) throw new HarnessException("CONFLICT", "该命令已处理，不能批准");
            Run run = sessions.run(value.runId()); requireRunning(run.id(), run.workspace());
            verifyDirectory(run.workspace(), value.directory());
            return settle(value, "APPROVED", null, "已批准，等待执行");
        }
    }
    public CommandExecution reject(String id) {
        synchronized (sessions) {
            var value = get(id);
            if (value.status().equals("REJECTED")) return value;
            if (!value.status().equals("PENDING")) throw new HarnessException("CONFLICT", "只能拒绝待审批命令");
            return settle(value, "REJECTED", null, "用户拒绝，命令未执行；不要自动换一种写法重试");
        }
    }
    public CommandExecution stop(String id) {
        synchronized (sessions) {
            var value = get(id);
            if (!ACTIVE.contains(value.status())) return value;
            if (value.status().equals("PENDING")) return settle(value, "CANCELLED", null, "已停止等待，命令未执行");
            return settle(value, "STOPPING", null, "正在停止命令");
        }
    }

    public WorkspaceTools.Result awaitAndExecute(CommandExecution proposal, ModelProvider.Cancellation cancellation) throws Exception {
        try {
            while (true) {
                cancellation.check();
                CommandExecution value;
                synchronized (sessions) {
                    value = get(proposal.id());
                    if (value.status().equals("APPROVED")) {
                        Run run = sessions.run(value.runId()); requireRunning(run.id(), run.workspace());
                        verifyDirectory(run.workspace(), value.directory());
                        // Commit intent BEFORE ProcessBuilder.start. API retries never launch a second process.
                        value = settle(value, "RUNNING", null, "正在执行");
                        break;
                    }
                    if (value.status().equals("STOPPING")) value = settle(value, "CANCELLED", null, "命令已停止，未启动");
                    if (!value.status().equals("PENDING")) return result(value);
                }
                Thread.sleep(100);
            }
            cancellation.check();
            var outcome = runner.run(proposal.command(), Path.of(proposal.directory()), proposal.timeoutSeconds(), cancellation,
                    () -> { synchronized (sessions) { return get(proposal.id()).status().equals("STOPPING"); } },
                    (out, err) -> saveOutput(proposal.id(), out, err));
            synchronized (sessions) {
                var current = get(proposal.id());
                if (current.status().equals("STOPPING")) return result(settle(current, "CANCELLED", outcome.exitCode(), "命令已停止；已执行的操作不会回滚"));
                return result(settle(current, outcome.status(), outcome.exitCode(), outcome.detail()));
            }
        } catch (InterruptedException error) {
            // Clear interrupt for JDBC settlement; the engine still owns the run's cancellation reason.
            Thread.interrupted();
            synchronized (sessions) {
                var value = get(proposal.id());
                if (ACTIVE.contains(value.status())) settle(value, "CANCELLED", null, "任务停止或超时，命令已取消；已执行的操作不会回滚");
            }
            throw error;
        } catch (Exception error) {
            synchronized (sessions) {
                var value = get(proposal.id());
                if (ACTIVE.contains(value.status())) settle(value, "INTERRUPTED", null, "命令启动、输出保存或收尾失败，结果可能不完整；不会自动重跑");
            }
            throw error;
        }
    }

    private void saveOutput(String id, String out, String err) {
        synchronized (sessions) {
            var value = get(id);
            if (!ACTIVE.contains(value.status()) || value.stdout().equals(out) && value.stderr().equals(err)) return;
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE harness_command SET stdout=?,stderr=? WHERE id=?", out, err, id);
                sessions.commandsUpdated(value.runId());
            });
        }
    }
    private CommandExecution settle(CommandExecution value, String status, Integer code, String detail) {
        return tx.execute(ignored -> {
            jdbc.update("UPDATE harness_command SET status=?,exit_code=?,detail=? WHERE id=?", status, code, detail, value.id());
            sessions.commandsUpdated(value.runId());
            return get(value.id());
        });
    }
    private void requireRunning(String runId, String workspace) {
        Run run = sessions.run(runId);
        if (workspace.isEmpty() || run.status() != RunStatus.RUNNING || !run.workspace().equals(workspace)
                || !sessions.snapshot(run.sessionId()).session().workspace().equals(workspace))
            throw new HarnessException("CONFLICT", "任务已停止或工作区改变，命令不能执行");
    }
    private static Path directory(String workspace, String relative) {
        if (relative == null || relative.length() > 2048) throw new HarnessException("INVALID", "请提供工作区内相对目录");
        try {
            Path root = Path.of(workspace).toRealPath();
            Path input = Path.of(relative);
            if (input.isAbsolute() || relative.contains(":") || input.startsWith("..")) throw new IOException();
            return verifyDirectory(root.toString(), root.resolve(input).normalize().toString());
        } catch (IOException | IllegalArgumentException error) { throw new HarnessException("INVALID", "执行目录必须是工作区内已存在的普通目录，不支持链接或越界路径"); }
    }
    private static Path verifyDirectory(String workspace, String directory) {
        try {
            Path root = Path.of(workspace).toRealPath(), target = Path.of(directory).toAbsolutePath().normalize();
            if (!root.equals(Path.of(workspace).toAbsolutePath().normalize()) || !target.startsWith(root)
                    || !Files.isDirectory(target) || !target.toRealPath().equals(target)) throw new IOException();
            Path part = root;
            for (Path name : root.relativize(target)) {
                part = part.resolve(name);
                if (Files.isSymbolicLink(part) || Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) throw new IOException();
            }
            return target;
        } catch (IOException | IllegalArgumentException error) { throw new HarnessException("INVALID", "执行目录已改变或不可访问，请重新提出命令"); }
    }
    private static WorkspaceTools.Result result(CommandExecution value) {
        return new WorkspaceTools.Result(value.status().equals("COMPLETED"), "命令 " + value.id() + "\n状态：" + value.status()
                + "\n退出码：" + value.exitCode() + "\n" + value.detail() + "\nstdout:\n" + value.stdout() + "\nstderr:\n" + value.stderr());
    }
}
