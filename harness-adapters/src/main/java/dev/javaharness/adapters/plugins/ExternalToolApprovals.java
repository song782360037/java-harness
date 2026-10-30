package dev.javaharness.adapters.plugins;

import static dev.javaharness.adapters.plugins.PluginJson.*;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable approval for one frozen external call; only its original run worker executes it. */
public final class ExternalToolApprovals {
    /** Called immediately before subscribing to the external tools/call request. */
    @FunctionalInterface
    public interface Dispatch {
        void beforeSend() throws InterruptedException;
    }
    /** Preparation is allowed before dispatch; only the callback records a possible external side effect. */
    @FunctionalInterface
    public interface Operation {
        WorkspaceTools.Result call(Dispatch dispatch) throws Exception;
    }

    public record Approval(
            String id,
            String runId,
            String callId,
            String installationId,
            String pluginName,
            String pluginVersion,
            String serverName,
            String toolName,
            String arguments,
            String identityDigest,
            String status,
            String detail) {}

    private static final RowMapper<Approval> MAPPER = (rs, row) -> new Approval(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("call_id"),
            rs.getString("installation_id"),
            rs.getString("plugin_name"),
            rs.getString("plugin_version"),
            rs.getString("server_name"),
            rs.getString("tool_name"),
            rs.getString("arguments"),
            rs.getString("identity_digest"),
            rs.getString("status"),
            rs.getString("detail"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore sessions;

    public ExternalToolApprovals(JdbcTemplate jdbc, TransactionTemplate tx, SessionStore sessions) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sessions = sessions;
        synchronized (sessions) {
            for (Approval approval : jdbc.query(
                    "SELECT * FROM harness_external_approval WHERE status IN ('PENDING','APPROVED','RUNNING')", MAPPER))
                settle(
                        approval,
                        approval.status().equals("RUNNING") ? "UNKNOWN" : "INTERRUPTED",
                        "服务中断，不会自动重放；已发出调用的结果需要检查");
        }
    }

    /** Query durable decisions without contacting a plugin. */
    public List<Approval> list(String runId) {
        synchronized (sessions) {
            sessions.run(runId);
            return jdbc.query(
                    "SELECT * FROM harness_external_approval WHERE run_id=? ORDER BY created_at,id", MAPPER, runId);
        }
    }

    private Approval get(String id) {
        var rows = jdbc.query("SELECT * FROM harness_external_approval WHERE id=?", MAPPER, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "外部工具审批不存在");
        return rows.getFirst();
    }

    /** Bind an approval decision to the already-persisted immutable call identity. */
    public Approval decide(String id, boolean approved) {
        synchronized (sessions) {
            Approval value = get(id);
            if (value.status().equals(approved ? "APPROVED" : "REJECTED")) return value;
            if (!value.status().equals("PENDING")) throw new HarnessException("CONFLICT", "该调用已处理，不能重新批准");
            requireRunning(value.runId());
            return settle(value, approved ? "APPROVED" : "REJECTED", approved ? "已批准本次固定参数调用" : "用户拒绝，调用未执行；不要换写法重试");
        }
    }

    /** Revoke waiting calls atomically before cancellation is sent to their engine workers. */
    public void revoke(String installationId) {
        synchronized (sessions) {
            for (Approval value : jdbc.query(
                    "SELECT * FROM harness_external_approval WHERE installation_id=? AND status IN ('PENDING','APPROVED')",
                    MAPPER,
                    installationId)) settle(value, "CANCELLED", "插件授权已撤销，调用未执行");
        }
    }

    /** Persist execution intent before dispatch, and record unknown outcomes without retrying. */
    public WorkspaceTools.Result execute(
            String runId,
            ToolCall call,
            String installation,
            String plugin,
            String version,
            String server,
            String tool,
            String identity,
            boolean preauthorized,
            Runnable guard,
            ModelProvider.Cancellation cancellation,
            Operation operation)
            throws Exception {
        Approval proposal;
        synchronized (sessions) {
            requireRunning(runId);
            guard.run();
            if (!jdbc.query(
                            "SELECT * FROM harness_external_approval WHERE run_id=? AND call_id=?",
                            MAPPER,
                            runId,
                            call.id())
                    .isEmpty()) throw new HarnessException("CONFLICT", "同一外部工具调用不能重放");
            if (list(runId).size() >= 128) throw invalid("单任务外部调用超过上限");
            String id = UUID.randomUUID().toString();
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        """
                        INSERT INTO harness_external_approval(id,run_id,call_id,installation_id,plugin_name,plugin_version,server_name,tool_name,arguments,identity_digest,status,detail,created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                        id,
                        runId,
                        call.id(),
                        installation,
                        plugin,
                        version,
                        server,
                        tool,
                        call.arguments(),
                        identity,
                        preauthorized ? "APPROVED" : "PENDING",
                        preauthorized ? "使用已审阅的工具授权" : "等待用户批准；等待计入任务超时",
                        OffsetDateTime.now(ZoneOffset.UTC));
                sessions.pluginsUpdated(runId);
            });
            proposal = get(id);
        }
        var dispatched = new AtomicBoolean();
        try {
            while (true) {
                cancellation.check();
                synchronized (sessions) {
                    Approval current = get(proposal.id());
                    if (current.status().equals("APPROVED")) {
                        requireRunning(runId);
                        guard.run();
                        break;
                    }
                    if (!current.status().equals("PENDING")) return new WorkspaceTools.Result(false, current.detail());
                }
                Thread.sleep(100);
            }
            cancellation.check();
            WorkspaceTools.Result result = operation.call(() -> {
                cancellation.check();
                synchronized (sessions) {
                    Approval current = get(proposal.id());
                    if (!current.status().equals("APPROVED")) throw new HarnessException("CONFLICT", current.detail());
                    requireRunning(runId);
                    guard.run();
                    settle(current, "RUNNING", "外部调用已进入发送阶段；发生中断时结果可能未知");
                    dispatched.set(true);
                }
            });
            cancellation.check();
            synchronized (sessions) {
                requireRunning(runId);
                guard.run();
                settle(get(proposal.id()), result.success() ? "COMPLETED" : "FAILED", "调用结果已记录");
            }
            return result;
        } catch (InterruptedException error) {
            Thread.interrupted();
            synchronized (sessions) {
                Approval current = get(proposal.id());
                if (dispatched.get() || Set.of("PENDING", "APPROVED").contains(current.status()))
                    settle(
                            current,
                            dispatched.get() ? "UNKNOWN" : "CANCELLED",
                            dispatched.get() ? "任务取消，外部结果可能未知；不会重放" : "任务取消，调用未执行");
            }
            throw error;
        } catch (Exception error) {
            synchronized (sessions) {
                Approval current = get(proposal.id());
                if (!dispatched.get() && !Set.of("PENDING", "APPROVED").contains(current.status()))
                    return new WorkspaceTools.Result(false, current.detail());
                String detail = dispatched.get()
                        ? "外部调用未能确认结果，请检查服务；不会自动重试。"
                        : error instanceof HarnessException || error instanceof ModelException
                                ? error.getMessage() + "（调用未执行）"
                                : "外部调用准备失败，调用未执行，请检查插件服务。";
                settle(current, dispatched.get() ? "UNKNOWN" : "FAILED", detail);
                return new WorkspaceTools.Result(false, detail);
            }
        }
    }

    private void requireRunning(String id) {
        if (sessions.run(id).status() != RunStatus.RUNNING) throw new HarnessException("CONFLICT", "任务已停止，不能执行外部工具");
    }

    private Approval settle(Approval value, String status, String detail) {
        return tx.execute(ignored -> {
            jdbc.update(
                    "UPDATE harness_external_approval SET status=?,detail=? WHERE id=?", status, detail, value.id());
            sessions.pluginsUpdated(value.runId());
            return get(value.id());
        });
    }
}
