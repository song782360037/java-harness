package dev.javaharness.adapters.command;

import dev.javaharness.adapters.approval.ApprovalPolicy;
import dev.javaharness.adapters.approval.ApprovalReviewer;
import dev.javaharness.adapters.workspace.WorkspaceRoots;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.CommandExecution;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** Approval decisions are durable; only the original tool worker may consume an approval. */
public final class CommandService {
    private static final Set<String> ACTIVE = Set.of("REVIEWING", "PENDING", "APPROVED", "RUNNING", "STOPPING");
    /** Still waiting for a decision: the reviewer chain or the user may settle it. */
    private static final Set<String> UNDECIDED = Set.of("REVIEWING", "PENDING");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore sessions;
    private final LocalCommandRunner runner = new LocalCommandRunner();
    private final ApprovalPolicy policy;
    private volatile ApprovalReviewer reviewer;
    private static final RowMapper<CommandExecution> MAPPER = (rs, row) -> new CommandExecution(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("command_text"),
            rs.getString("directory"),
            rs.getString("shell"),
            rs.getString("purpose"),
            rs.getInt("timeout_seconds"),
            rs.getString("status"),
            rs.getString("stdout"),
            rs.getString("stderr"),
            rs.getObject("exit_code", Integer.class),
            rs.getString("detail"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getString("decided_by"));

    public CommandService(JdbcTemplate jdbc, TransactionTemplate tx, SessionStore sessions) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sessions = sessions;
        this.policy = new ApprovalPolicy(jdbc);
        synchronized (sessions) {
            for (var command : jdbc.query(
                    "SELECT * FROM harness_command WHERE status IN ('REVIEWING','PENDING','APPROVED','RUNNING','STOPPING')",
                    MAPPER)) settle(command, "INTERRUPTED", null, "服务中断，执行结果可能不完整；请检查工作区，不会自动重跑");
        }
    }

    /** Enable the optional smart-approval reviewer chain; without it smart escalations wait for the user. */
    public void useReviewer(ApprovalReviewer reviewer) {
        this.reviewer = reviewer;
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

    public CommandExecution propose(
            String runId,
            String callId,
            String workspace,
            String command,
            String directory,
            String purpose,
            int timeoutSeconds) {
        synchronized (sessions) {
            requireRunning(runId, workspace);
            var existing =
                    jdbc.query("SELECT * FROM harness_command WHERE run_id=? AND call_id=?", MAPPER, runId, callId);
            if (!existing.isEmpty()) return existing.getFirst();
            if (command == null
                    || command.isBlank()
                    || command.length() > 8000
                    || command.indexOf('\0') >= 0
                    || purpose == null
                    || purpose.isBlank()
                    || purpose.length() > 1000
                    || timeoutSeconds < 1
                    || timeoutSeconds > 300)
                throw new HarnessException("INVALID", "命令需为 1–8000 字符，用途为 1–1000 字符，超时为 1–300 秒");
            if (list(runId).size() >= 32) throw new HarnessException("INVALID", "单任务最多审批 32 条命令，请分批处理");
            Path resolved = directory(workspace, directory);
            // Rules settle the proposal in the same insert; the worker consumes APPROVED exactly as a user approval.
            var decision = policy.command(runId, command);
            var current = reviewer;
            boolean review = decision.reviewable() && current != null && current.enabled();
            String id = UUID.randomUUID().toString();
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        """
                        INSERT INTO harness_command(id,run_id,call_id,command_text,directory,shell,purpose,timeout_seconds,status,detail,decided_by,created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                        id,
                        runId,
                        callId,
                        command,
                        resolved.toString(),
                        LocalCommandRunner.shell(),
                        purpose,
                        timeoutSeconds,
                        review
                                ? "REVIEWING"
                                : switch (decision.verdict()) {
                                    case APPROVE -> "APPROVED";
                                    case REJECT -> "REJECTED";
                                    case ASK -> "PENDING";
                                },
                        review ? "智能审批中，你也可以直接批准或拒绝" : decision.detail() == null ? "" : decision.detail(),
                        decision.decidedBy(),
                        OffsetDateTime.now(ZoneOffset.UTC));
                sessions.commandsUpdated(runId);
            });
            return get(id);
        }
    }

    public CommandExecution approve(String id) {
        synchronized (sessions) {
            var value = get(id);
            if (Set.of("APPROVED", "RUNNING", "COMPLETED", "FAILED", "TIMED_OUT", "OUTPUT_LIMIT")
                    .contains(value.status())) return value;
            if (!UNDECIDED.contains(value.status())) throw new HarnessException("CONFLICT", "该命令已处理，不能批准");
            Run run = sessions.run(value.runId());
            requireRunning(run.id(), run.workspace());
            verifyDirectory(run.workspace(), value.directory());
            return settle(value, "APPROVED", null, "已批准，等待执行", "USER");
        }
    }

    public CommandExecution reject(String id) {
        synchronized (sessions) {
            var value = get(id);
            if (value.status().equals("REJECTED")) return value;
            if (!UNDECIDED.contains(value.status())) throw new HarnessException("CONFLICT", "只能拒绝待审批命令");
            return settle(value, "REJECTED", null, "用户拒绝，命令未执行；不要自动换一种写法重试", "USER");
        }
    }

    public CommandExecution stop(String id) {
        synchronized (sessions) {
            var value = get(id);
            if (!ACTIVE.contains(value.status())) return value;
            if (UNDECIDED.contains(value.status())) return settle(value, "CANCELLED", null, "已停止等待，命令未执行");
            return settle(value, "STOPPING", null, "正在停止命令");
        }
    }

    public WorkspaceTools.Result awaitAndExecute(CommandExecution proposal, ModelProvider.Cancellation cancellation)
            throws Exception {
        try {
            if (proposal.status().equals("REVIEWING")) review(proposal, cancellation);
            while (true) {
                cancellation.check();
                CommandExecution value;
                synchronized (sessions) {
                    value = get(proposal.id());
                    if (value.status().equals("APPROVED")) {
                        Run run = sessions.run(value.runId());
                        requireRunning(run.id(), run.workspace());
                        verifyDirectory(run.workspace(), value.directory());
                        // Commit intent BEFORE ProcessBuilder.start. API retries never launch a second process.
                        value = settle(value, "RUNNING", null, "正在执行");
                        break;
                    }
                    if (value.status().equals("STOPPING")) value = settle(value, "CANCELLED", null, "命令已停止，未启动");
                    if (!UNDECIDED.contains(value.status())) return result(value);
                }
                Thread.sleep(100);
            }
            cancellation.check();
            var outcome = runner.run(
                    proposal.command(),
                    Path.of(proposal.directory()),
                    proposal.timeoutSeconds(),
                    cancellation,
                    () -> {
                        synchronized (sessions) {
                            return get(proposal.id()).status().equals("STOPPING");
                        }
                    },
                    (out, err) -> saveOutput(proposal.id(), out, err));
            synchronized (sessions) {
                var current = get(proposal.id());
                if (current.status().equals("STOPPING"))
                    return result(settle(current, "CANCELLED", outcome.exitCode(), "命令已停止；已执行的操作不会回滚"));
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
                if (ACTIVE.contains(value.status()))
                    settle(value, "INTERRUPTED", null, "命令启动、输出保存或收尾失败，结果可能不完整；不会自动重跑");
            }
            throw error;
        }
    }

    /** Run the reviewer chain without holding the store lock; a user decision made meanwhile wins. */
    private void review(CommandExecution proposal, ModelProvider.Cancellation cancellation) throws InterruptedException {
        String workspace = sessions.run(proposal.runId()).workspace();
        Path directory = Path.of(proposal.directory());
        String relative = WorkspaceRoots.directories(workspace).stream()
                .map(Path::of)
                .filter(directory::startsWith)
                .findFirst()
                .map(root -> root.relativize(directory).toString().replace('\\', '/'))
                .map(value -> value.isEmpty() ? "." : value)
                .orElse(".");
        var action = new java.util.LinkedHashMap<String, Object>();
        action.put("kind", "command");
        action.put("shell", proposal.shell());
        action.put("command", proposal.command());
        action.put("directory", relative);
        action.put("purpose", proposal.purpose());
        action.put("timeout_seconds", proposal.timeoutSeconds());
        ApprovalPolicy.Decision decision;
        try {
            // A user decision or stop ends the review at the next check instead of waiting for reviewer timeouts.
            decision = reviewer.review(proposal.runId(), "COMMAND", proposal.id(), action, () -> {
                cancellation.check();
                synchronized (sessions) {
                    if (!get(proposal.id()).status().equals("REVIEWING")) throw new ApprovalReviewer.Abandoned();
                }
            });
        } catch (ApprovalReviewer.Abandoned settled) {
            return;
        }
        synchronized (sessions) {
            var value = get(proposal.id());
            if (!value.status().equals("REVIEWING")) return;
            switch (decision.verdict()) {
                case APPROVE -> {
                    Run run = sessions.run(value.runId());
                    requireRunning(run.id(), run.workspace());
                    verifyDirectory(run.workspace(), value.directory());
                    settle(value, "APPROVED", null, decision.detail(), "JUDGE");
                }
                case REJECT -> settle(value, "REJECTED", null, decision.detail() + "；命令未执行，不要换一种写法重试", "JUDGE");
                case ASK -> settle(value, "PENDING", null, decision.detail(), null);
            }
        }
    }

    private void saveOutput(String id, String out, String err) {
        synchronized (sessions) {
            var value = get(id);
            if (!ACTIVE.contains(value.status())
                    || value.stdout().equals(out) && value.stderr().equals(err)) return;
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE harness_command SET stdout=?,stderr=? WHERE id=?", out, err, id);
                sessions.commandsUpdated(value.runId());
            });
        }
    }

    private CommandExecution settle(CommandExecution value, String status, Integer code, String detail) {
        return settle(value, status, code, detail, value.decidedBy());
    }

    private CommandExecution settle(
            CommandExecution value, String status, Integer code, String detail, String decidedBy) {
        return tx.execute(ignored -> {
            jdbc.update(
                    "UPDATE harness_command SET status=?,exit_code=?,detail=?,decided_by=? WHERE id=?",
                    status,
                    code,
                    detail,
                    decidedBy,
                    value.id());
            sessions.commandsUpdated(value.runId());
            return get(value.id());
        });
    }

    private void requireRunning(String runId, String workspace) {
        Run run = sessions.run(runId);
        if (workspace.isEmpty()
                || run.status() != RunStatus.RUNNING
                || !run.workspace().equals(workspace)
                || !sessions.snapshot(run.sessionId()).session().workspace().equals(workspace))
            throw new HarnessException("CONFLICT", "任务已停止或工作区改变，命令不能执行");
    }

    private static Path directory(String workspace, String relative) {
        if (relative == null || relative.length() > 2048) throw new HarnessException("INVALID", "请提供工作区内相对目录");
        try {
            WorkspaceRoots.Target location = WorkspaceRoots.target(workspace, relative);
            Path root = Path.of(location.root()).toRealPath();
            Path input = Path.of(location.relative());
            if (input.isAbsolute() || location.relative().contains(":") || input.startsWith(".."))
                throw new IOException();
            return verifyDirectory(
                    location.root(), root.resolve(input).normalize().toString());
        } catch (IOException | IllegalArgumentException error) {
            throw new HarnessException("INVALID", "执行目录必须是工作区内已存在的普通目录，不支持链接或越界路径");
        }
    }

    private static Path verifyDirectory(String workspace, String directory) {
        try {
            Path target = Path.of(directory).toAbsolutePath().normalize();
            Path configured = WorkspaceRoots.directories(workspace).stream()
                    .map(Path::of)
                    .filter(target::startsWith)
                    .max(java.util.Comparator.comparingInt(Path::getNameCount))
                    .orElseThrow(() -> new IOException("Outside workspace"));
            Path root = configured.toRealPath();
            if (!root.equals(configured.toAbsolutePath().normalize())
                    || !target.startsWith(root)
                    || !Files.isDirectory(target)
                    || !target.toRealPath().equals(target)) throw new IOException();
            Path part = root;
            for (Path name : root.relativize(target)) {
                part = part.resolve(name);
                if (Files.isSymbolicLink(part)
                        || Files.readAttributes(
                                        part,
                                        java.nio.file.attribute.BasicFileAttributes.class,
                                        LinkOption.NOFOLLOW_LINKS)
                                .isOther()) throw new IOException();
            }
            return target;
        } catch (IOException | IllegalArgumentException error) {
            throw new HarnessException("INVALID", "执行目录已改变或不可访问，请重新提出命令");
        }
    }

    private static WorkspaceTools.Result result(CommandExecution value) {
        return new WorkspaceTools.Result(
                value.status().equals("COMPLETED"),
                "命令 " + value.id() + "\n状态：" + value.status() + "\n退出码：" + value.exitCode() + "\n" + value.detail()
                        + "\nstdout:\n" + value.stdout() + "\nstderr:\n" + value.stderr());
    }
}
