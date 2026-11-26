package dev.javaharness.server.service;

import static dev.javaharness.adapters.acp.AcpProcess.JSON;
import static dev.javaharness.server.service.ExternalAgentInteractions.clip;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javaharness.adapters.acp.AcpProcess;
import dev.javaharness.adapters.workspace.WorkspaceRoots;
import dev.javaharness.server.service.ExternalAgentCatalog.Profile;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolCall;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Executes explicit external-agent runs in the ordinary session slot, without invoking the model engine. */
@Service
public class ExternalAgentRuns {
    public static final String SCENARIO = "ACP";
    public static final String PROVIDER = "external-acp";
    private static final Pattern COMMAND =
            Pattern.compile("^/([a-z0-9][a-z0-9-]{0,31})(?=\\s|$)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(30);
    private static final Set<String> BUILT_INS = Set.of("codex", "claude");
    private static final int MAX_RUNNING = 8;
    private static final String CLIENT_VERSION = clientVersion();
    private final SessionStore sessions;
    private final ExternalAgentCatalog catalog;
    private final ExternalAgentInteractions interactions;
    private final JdbcTemplate jdbc;
    private final Map<String, Task> running = new HashMap<>();
    private final List<Consumer<Run>> settledListeners = new CopyOnWriteArrayList<>();
    private boolean closed;

    public record Probe(String status, String name, String version, boolean resumable) {}

    private record Invocation(String alias, String text) {}

    private record Pending(JsonNode rpcId, boolean permission, long expiresAt) {}

    private record ToolState(String id, boolean finished) {}

    private static final class Task {
        volatile boolean cancelled;
        Thread thread;
    }

    public ExternalAgentRuns(
            SessionStore sessions,
            ExternalAgentCatalog catalog,
            ExternalAgentInteractions interactions,
            JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.catalog = catalog;
        this.interactions = interactions;
        this.jdbc = jdbc;
    }

    /** Register a callback notified outside the session store lock once an external run reaches a final status. */
    public void onSettled(Consumer<Run> listener) {
        if (listener != null) settledListeners.add(listener);
    }

    /** Whether this coordinator owns an active external run with the given ID. */
    public boolean owns(String runId) {
        synchronized (sessions) {
            return running.containsKey(runId);
        }
    }

    /** Submit a prompt directly to a configured external agent from an internal orchestrator (flow or group). */
    public SessionStore.Submission submitDirect(String sessionId, String requestId, String prompt, String agentId) {
        synchronized (sessions) {
            var agent = catalog.get(agentId);
            if (agent == null || !agent.enabled()) throw invalid("所选外部智能体未配置或已停用");
            String text = prompt == null ? "" : prompt.strip();
            if (text.isEmpty()) throw invalid("外部智能体任务内容不能为空");
            if (text.length() > 100_000) throw invalid("外部智能体任务文本过长");
            if (closed || running.size() >= MAX_RUNNING) throw new HarnessException("BUSY", "外部智能体任务繁忙");
            var session = sessions.snapshot(sessionId).session();
            var roots = WorkspaceRoots.directories(session.workspace());
            if (roots.isEmpty()) throw invalid("外部智能体需要绑定包含目录的工作区");
            Path cwd = realDirectory(roots.getFirst());
            String workspaceKey = hash(session.workspaceId() + "\n" + session.workspace() + "\n" + cwd);
            var result = sessions.submit(
                    sessionId,
                    requestId,
                    text,
                    SCENARIO,
                    new ModelInfo(PROVIDER, agent.name(), true),
                    new ModelSelection(agent.id(), ""),
                    new ModelSelection(session.modelProfileId(), session.reasoningEffort()));
            if (!result.created()) return result;
            var task = new Task();
            String runId = result.run().id();
            task.thread = Thread.ofVirtual()
                    .name("acp-run-" + runId)
                    .unstarted(() -> execute(result.run(), agent, cwd, workspaceKey, text, task));
            running.put(runId, task);
            task.thread.start();
            return result;
        }
    }

    /** Return null for an ordinary Harness message; exact external retries are resolved before profile changes. */
    public SessionStore.Submission submit(
            String sessionId, String requestId, String prompt, String agentId, boolean hasAttachments) {
        var call = invocation(prompt);
        if (call == null && (agentId == null || agentId.isBlank())) return null;
        if (call == null) throw invalid("外部智能体命令必须位于消息开头");
        synchronized (sessions) {
            var agent = catalog.resolve(call.alias());
            String expected = agentId == null || agentId.isBlank() ? agent == null ? null : agent.id() : agentId;
            if (expected == null && !BUILT_INS.contains(call.alias())) return null;
            if (expected != null) {
                var retry = sessions.retry(sessionId, requestId, prompt, SCENARIO, new ModelSelection(expected, ""));
                if (retry != null) return retry;
            }
            if (agent == null || !agent.id().equals(expected) || !agent.enabled()) throw invalid("外部智能体未配置、已停用或已变更");
            if (hasAttachments) throw invalid("外部智能体不会接收附件或文件引用，请移除后再发送");
            if (call.text().isEmpty()) throw invalid("请输入要交给外部智能体的任务");
            if (call.text().length() > 100_000) throw invalid("外部智能体任务文本过长");
            if (closed || running.size() >= MAX_RUNNING) throw new HarnessException("BUSY", "外部智能体任务繁忙");
            var session = sessions.snapshot(sessionId).session();
            var roots = WorkspaceRoots.directories(session.workspace());
            if (roots.isEmpty()) throw invalid("请先选择外部智能体的工作区");
            Path cwd = realDirectory(roots.getFirst());
            String workspaceKey = hash(session.workspaceId() + "\n" + session.workspace() + "\n" + cwd);
            var result = sessions.submit(
                    sessionId,
                    requestId,
                    prompt,
                    SCENARIO,
                    new ModelInfo(PROVIDER, agent.name(), true),
                    new ModelSelection(agent.id(), ""),
                    new ModelSelection(session.modelProfileId(), session.reasoningEffort()));
            if (!result.created()) return result;
            var task = new Task();
            String runId = result.run().id();
            task.thread = Thread.ofVirtual()
                    .name("acp-run-" + runId)
                    .unstarted(() -> execute(result.run(), agent, cwd, workspaceKey, call.text(), task));
            running.put(runId, task);
            task.thread.start();
            return result;
        }
    }

    /** Check initialize only, without passing project data, invoking a model or claiming authentication. */
    public Probe probe(Profile form) {
        Profile agent = ExternalAgentCatalog.validate(form);
        Path cwd = realDirectory(System.getProperty("java.io.tmpdir"));
        String stderr = "";
        try (var agentProcess = connect(agent, cwd)) {
            try {
                JsonNode info = initialize(agentProcess, new Task());
                var identity = info.path("agentInfo");
                return new Probe(
                        "CONNECTED",
                        clip(identity.path("name").asText(agent.name()), 120),
                        clip(identity.path("version").asText(""), 80),
                        resumeMethod(info) != null);
            } catch (IOException error) {
                stderr = agentProcess.stderrSummary();
                throw error;
            }
        } catch (Stopped error) {
            throw invalid("ACP 连接测试已中止");
        } catch (AcpError error) {
            throw invalid(error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw invalid("ACP 连接测试已中止");
        } catch (IOException error) {
            throw invalid(stderr.isEmpty() ? "ACP 连接失败，未收到有效的初始化响应" : "ACP 连接失败：" + stderr);
        }
    }

    /** Request cancellation; the owner closes its connection before releasing the session slot. */
    public Run cancel(String runId) {
        synchronized (sessions) {
            Run current = sessions.run(runId);
            if (!current.status().active() || current.status() == RunStatus.CANCELLING) return current;
            Task task = running.get(runId);
            if (task == null) return sessions.transition(runId, RunStatus.INTERRUPTED, "外部进程已断开");
            task.cancelled = true;
            return sessions.transition(runId, RunStatus.CANCELLING, "正在停止外部智能体");
        }
    }

    /** Stop and join owned workers when the application shuts down. */
    @PreDestroy
    void close() {
        List<Task> tasks;
        synchronized (sessions) {
            closed = true;
            tasks = List.copyOf(running.values());
            tasks.forEach(task -> task.cancelled = true);
        }
        try {
            for (Task task : tasks) task.thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void execute(Run run, Profile agent, Path cwd, String workspaceKey, String text, Task task) {
        RunStatus result = RunStatus.COMPLETED;
        String detail = "";
        String stderr = "";
        try {
            synchronized (sessions) {
                check(task);
                sessions.transition(run.id(), RunStatus.RUNNING, "");
            }
            try (var connection = connect(agent, cwd)) {
                try {
                    JsonNode initialized = initialize(connection, task);
                    JsonNode setup = JSON.createObjectNode()
                            .put("cwd", cwd.toString())
                            .set("mcpServers", JSON.createArrayNode());
                    String method = resumeMethod(initialized);
                    String remote = method == null ? null : remoteSession(run.sessionId(), agent.id(), workspaceKey);
                    JsonNode state;
                    if (remote == null) {
                        state = call(connection, task, "session/new", setup);
                        remote = state.path("sessionId").asText();
                        if (remote.isBlank() || remote.length() > 512) throw invalid("ACP 返回了无效会话");
                    } else {
                        try {
                            state = call(
                                    connection,
                                    task,
                                    method,
                                    ((com.fasterxml.jackson.databind.node.ObjectNode) setup).put("sessionId", remote));
                        } catch (AcpError error) {
                            if (error.code != -32002) throw error;
                            jdbc.update("""
                                    DELETE FROM harness_external_session
                                    WHERE session_id=? AND agent_id=? AND cwd_hash=? AND remote_id=?
                                    """, run.sessionId(), agent.id(), workspaceKey, remote);
                            throw invalid("外部会话已不存在，下一次发送会建立新会话");
                        }
                    }
                    applyMode(connection, task, agent, remote, state);
                    check(task);
                    String stopped = turn(connection, run.id(), remote, text, task, agent);
                    if (method != null
                            && (stopped.equals("end_turn")
                                    || stopped.equals("max_tokens")
                                    || stopped.equals("max_turn_requests"))) remember(run, agent, workspaceKey, remote);
                    switch (stopped) {
                        case "end_turn" -> {}
                        case "max_tokens", "max_turn_requests" -> detail = "外部智能体达到输出或请求上限";
                        case "cancelled" -> {
                            result = RunStatus.CANCELLED;
                            detail = "外部智能体已停止";
                        }
                        default -> {
                            result = RunStatus.FAILED;
                            detail = "外部智能体未完成任务";
                        }
                    }
                } catch (Exception error) {
                    stderr = connection.stderrSummary();
                    throw error;
                }
            }
        } catch (Stopped error) {
            result = error.timeout ? RunStatus.TIMED_OUT : RunStatus.CANCELLED;
            detail = error.timeout ? "外部智能体执行超时" : "外部智能体已停止";
        } catch (HarnessException | AcpError error) {
            result = RunStatus.FAILED;
            detail = error.getMessage();
        } catch (Exception error) {
            result = RunStatus.FAILED;
            detail = stderr.isEmpty() ? "ACP 连接中断或响应无效" : "ACP 连接中断：" + stderr;
            System.getLogger(ExternalAgentRuns.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "External run {0} failed ({1}): {2}",
                            run.id(),
                            error.getClass().getSimpleName(),
                            stderr.isEmpty() ? String.valueOf(error.getMessage()) : stderr);
        } finally {
            Run settled = null;
            synchronized (sessions) {
                try {
                    interactions.expire(run.id());
                } finally {
                    interactions.forget(run.id());
                    settled = finish(run.id(), task, result, detail);
                }
            }
            if (settled != null) {
                for (Consumer<Run> listener : settledListeners) {
                    try {
                        listener.accept(settled);
                    } catch (RuntimeException error) {
                        System.getLogger(ExternalAgentRuns.class.getName())
                                .log(System.Logger.Level.WARNING, "External run settled listener failed", error);
                    }
                }
            }
        }
    }

    private Run finish(String id, Task task, RunStatus result, String detail) {
        try {
            Run current = sessions.run(id);
            if (!current.status().active()) return current;
            if (task.cancelled) {
                result = RunStatus.CANCELLED;
                detail = "外部智能体已停止";
            }
            if ((result == RunStatus.CANCELLED || result == RunStatus.TIMED_OUT)
                    && current.status() != RunStatus.CANCELLING) sessions.transition(id, RunStatus.CANCELLING, "");
            return sessions.transition(id, result, detail);
        } finally {
            running.remove(id);
        }
    }

    private String turn(AcpProcess connection, String runId, String remote, String text, Task task, Profile agent)
            throws Exception {
        var pending = new HashMap<String, Pending>();
        var tools = new HashMap<String, ToolState>();
        int[] outputLength = {0};
        long seenRevision = interactions.revision(runId);
        int request = connection.request(
                "session/prompt", Map.of("sessionId", remote, "prompt", List.of(Map.of("type", "text", "text", text))));
        long deadline = deadline(TURN_TIMEOUT);
        while (true) {
            boolean timeout = reached(deadline);
            if (task.cancelled || timeout) {
                try {
                    connection.notify("session/cancel", Map.of("sessionId", remote));
                    for (Pending item : pending.values())
                        connection.reply(item.rpcId(), dismissed(item.permission(), true));
                    long grace = deadline(Duration.ofSeconds(2));
                    while (!reached(grace)) {
                        JsonNode frame = connection.poll();
                        if (frame != null && response(frame, request)) break;
                        if (frame != null && frame.has("method") && frame.hasNonNull("id")) {
                            if (frame.path("method").asText().equals("session/request_permission"))
                                connection.reply(frame.get("id"), dismissed(true, true));
                            else connection.unsupported(frame.get("id"));
                        }
                    }
                } catch (IOException ignored) {
                    // The transport may already have closed; retain the original stop reason.
                }
                throw new Stopped(timeout);
            }
            if (!pending.isEmpty()) {
                long currentRevision = interactions.revision(runId);
                boolean expired = pending.values().stream().anyMatch(item -> reached(item.expiresAt()));
                if (currentRevision != seenRevision || expired) {
                    synchronized (sessions) {
                        if (task.cancelled) continue;
                        seenRevision = interactions.revision(runId);
                        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
                            var entry = it.next();
                            Pending item = entry.getValue();
                            var decision = interactions.get(entry.getKey());
                            if (!decision.status().equals("PENDING") || reached(item.expiresAt())) {
                                if (decision.status().equals("PENDING"))
                                    decision = interactions.answer(runId, decision.id(), "decline", null);
                                connection.reply(
                                        item.rpcId(),
                                        decision.answer().isNull()
                                                ? dismissed(item.permission(), true)
                                                : decision.answer());
                                it.remove();
                            }
                        }
                    }
                }
            }
            JsonNode frame = connection.poll();
            if (frame == null) continue;
            if (response(frame, request))
                return result(frame).path("stopReason").asText();
            var params = frame.path("params");
            String method = frame.path("method").asText();
            if (frame.hasNonNull("id") && frame.has("method")) {
                if (!remote.equals(params.path("sessionId").asText())) connection.unsupported(frame.get("id"));
                else openRequest(connection, runId, pending, frame, params, method, agent);
            } else if (method.equals("session/update")
                    && remote.equals(params.path("sessionId").asText())) {
                synchronized (sessions) {
                    if (!task.cancelled) update(runId, tools, outputLength, params.path("update"));
                }
            }
        }
    }

    private void openRequest(
            AcpProcess connection,
            String runId,
            Map<String, Pending> pending,
            JsonNode frame,
            JsonNode params,
            String method,
            Profile agent)
            throws IOException {
        JsonNode id = frame.get("id");
        boolean permission = method.equals("session/request_permission");
        String hostId = null;
        if (permission) {
            var tool = params.path("toolCall");
            String title = tool.path("title").asText("外部工具请求");
            var detail = JSON.createObjectNode();
            for (String field : List.of("rawInput", "locations", "content"))
                if (tool.hasNonNull(field)) detail.set(field, tool.path(field));
            String details = detail.isEmpty() ? "" : detail.toPrettyString();
            boolean canAllow = agent.preset().equals("custom")
                    || agent.workspaceWrite()
                    || !Set.of("edit", "switch_mode").contains(tool.path("kind").asText());
            if (title.length() > 500 || details.length() > 16000) {
                canAllow = false;
                details = "请求详情过长，无法完整展示，不能批准。";
            }
            List<ExternalAgentInteractions.Choice> choices = new ArrayList<>();
            for (JsonNode choice : params.path("options")) {
                String kind = choice.path("kind").asText();
                String option = choice.path("optionId").asText();
                if (choices.size() < 8
                        && !option.isBlank()
                        && option.length() <= 128
                        && (kind.equals("reject_once") || canAllow && kind.equals("allow_once")))
                    choices.add(new ExternalAgentInteractions.Choice(
                            option, clip(choice.path("name").asText(kind), 120), kind.equals("allow_once")));
            }
            if (!choices.isEmpty()
                    && choices.stream()
                                    .map(ExternalAgentInteractions.Choice::id)
                                    .distinct()
                                    .count()
                            == choices.size())
                hostId = interactions.open(runId, "permission", title, details, choices, null);
        } else if (method.equals("elicitation/create")
                && params.path("mode").asText().equals("form")) {
            var schema = params.path("requestedSchema");
            if (ExternalAgentInteractions.supportedForm(schema))
                hostId = interactions.open(
                        runId, "form", "外部智能体追问", params.path("message").asText(), List.of(), schema);
        } else {
            connection.unsupported(id);
            return;
        }
        if (hostId == null) connection.reply(id, dismissed(permission, true));
        else pending.put(hostId, new Pending(id, permission, deadline(Duration.ofMinutes(5))));
    }

    private void update(String runId, Map<String, ToolState> tools, int[] outputLength, JsonNode update) {
        String kind = update.path("sessionUpdate").asText();
        if (kind.equals("agent_message_chunk")
                && update.path("content").path("type").asText().equals("text")) {
            int remaining = 100_000 - outputLength[0];
            if (remaining <= 0) return;
            String chunk = clip(update.path("content").path("text").asText(), remaining);
            for (int start = 0; start < chunk.length(); ) {
                String part = clip(chunk.substring(start), 4096);
                sessions.appendText(runId, part);
                outputLength[0] += part.length();
                start += part.length();
            }
        } else if (kind.equals("tool_call") || kind.equals("tool_call_update")) {
            String remote = update.path("toolCallId").asText();
            if (remote.isBlank() || remote.length() > 512) return;
            ToolState tool = tools.get(remote);
            if (tool == null && kind.equals("tool_call") && tools.size() < 200) {
                String local = "acp-" + (tools.size() + 1);
                String title = clip(update.path("title").asText("外部工具"), 60);
                var payload = new LinkedHashMap<String, Object>();
                payload.put("title", title);
                JsonNode rawInput = update.path("rawInput");
                if (!rawInput.isMissingNode() && !rawInput.isNull()) {
                    String serialized = rawInput.toString();
                    payload.put("input", serialized.length() <= 2000 ? rawInput : clip(serialized, 2000));
                }
                String arguments = ExternalAgentCatalog.write(payload);
                sessions.beginTools(
                        runId, tools.size() + 1, List.of(new ToolCall(local, "external_agent_tool", arguments)));
                sessions.updateTool(runId, local, "RUNNING", "");
                tool = new ToolState(local, false);
                tools.put(remote, tool);
            }
            if (tool != null && !tool.finished()) {
                String status = update.path("status").asText();
                if (status.equals("completed") || status.equals("failed")) {
                    JsonNode output = update.has("rawOutput") ? update.path("rawOutput") : update.path("content");
                    sessions.updateTool(
                            runId,
                            tool.id(),
                            status.equals("completed") ? "COMPLETED" : "FAILED",
                            output.isMissingNode()
                                    ? ""
                                    : clip(output.isTextual() ? output.asText() : output.toPrettyString(), 16000));
                    tools.put(remote, new ToolState(tool.id(), true));
                }
            }
        }
    }

    private static void applyMode(AcpProcess connection, Task task, Profile agent, String remote, JsonNode setup)
            throws Exception {
        String mode = switch (agent.preset()) {
            case "codex" -> agent.workspaceWrite() ? "workspace-write" : "read-only";
            case "claude" -> agent.workspaceWrite() ? "default" : "plan";
            default -> null;
        };
        if (mode == null) return;
        for (JsonNode config : setup.path("configOptions")) {
            if (!"mode".equals(config.path("category").asText())
                    && !"mode".equals(config.path("id").asText())) continue;
            if (mode.equals(config.path("currentValue").asText())) return;
            if (!config.path("id").asText().isBlank()) {
                call(
                        connection,
                        task,
                        "session/set_config_option",
                        Map.of(
                                "sessionId",
                                remote,
                                "configId",
                                config.path("id").asText(),
                                "value",
                                mode));
                return;
            }
        }
        if (mode.equals(setup.path("modes").path("currentModeId").asText())) return;
        for (JsonNode choice : setup.path("modes").path("availableModes")) {
            if (mode.equals(choice.path("id").asText())) {
                call(connection, task, "session/set_mode", Map.of("sessionId", remote, "modeId", mode));
                return;
            }
        }
        throw invalid("适配器未提供所需权限模式，任务没有发送");
    }

    private static JsonNode initialize(AcpProcess process, Task task)
            throws IOException, InterruptedException, Stopped {
        JsonNode result = call(
                process,
                task,
                "initialize",
                Map.of(
                        "protocolVersion",
                        1,
                        "clientCapabilities",
                        Map.of("elicitation", Map.of("form", Map.of())),
                        "clientInfo",
                        Map.of("name", "java-harness", "version", CLIENT_VERSION)));
        if (result.path("protocolVersion").asInt() != 1) throw invalid("此程序不支持 ACP v1");
        return result;
    }

    private static String clientVersion() {
        String version = ExternalAgentRuns.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "0.4.0" : version;
    }

    private static JsonNode call(AcpProcess process, Task task, String method, Object params)
            throws IOException, InterruptedException, Stopped {
        check(task);
        int id = process.request(method, params);
        long deadline = deadline(CONNECT_TIMEOUT);
        while (!reached(deadline)) {
            check(task);
            JsonNode frame = process.poll();
            if (frame == null) continue;
            if (response(frame, id)) return result(frame);
            if (frame.has("method") && frame.hasNonNull("id")) process.unsupported(frame.get("id"));
        }
        throw invalid("等待 ACP 响应超时：" + method);
    }

    private static JsonNode result(JsonNode frame) {
        if (frame.hasNonNull("error")) {
            var error = frame.path("error");
            throw new AcpError(error.path("code").asInt(), error.path("message").asText(""));
        }
        if (!frame.path("result").isObject()) throw invalid("ACP 响应缺少结果");
        return frame.path("result");
    }

    private static boolean response(JsonNode frame, int id) {
        return !frame.has("method")
                && frame.path("id").isIntegralNumber()
                && frame.path("id").intValue() == id;
    }

    private static Object dismissed(boolean permission, boolean cancelled) {
        return permission
                ? Map.of("outcome", Map.of("outcome", "cancelled"))
                : Map.of("action", cancelled ? "cancel" : "decline");
    }

    private static String resumeMethod(JsonNode init) {
        var caps = init.path("agentCapabilities");
        if (caps.path("sessionCapabilities").path("resume").isObject()) return "session/resume";
        return caps.path("loadSession").asBoolean() ? "session/load" : null;
    }

    private String remoteSession(String session, String agent, String cwd) {
        var rows = jdbc.queryForList("""
                SELECT remote_id FROM harness_external_session WHERE session_id=? AND agent_id=? AND cwd_hash=?
                """, String.class, session, agent, cwd);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void remember(Run run, Profile agent, String cwd, String remote) {
        synchronized (sessions) {
            Profile current = catalog.resolve(agent.alias());
            if (current == null || !current.id().equals(agent.id()) || !ExternalAgentCatalog.sameLaunch(current, agent))
                return;
            jdbc.update("""
                    MERGE INTO harness_external_session(session_id,agent_id,cwd_hash,remote_id)
                    KEY(session_id,agent_id,cwd_hash) VALUES (?,?,?,?)
                    """, run.sessionId(), agent.id(), cwd, remote);
        }
    }

    private static Invocation invocation(String prompt) {
        if (prompt == null) return null;
        var match = COMMAND.matcher(prompt);
        return match.find()
                ? new Invocation(match.group(1), prompt.substring(match.end()).strip())
                : null;
    }

    private static AcpProcess connect(Profile agent, Path cwd) {
        return new AcpProcess(agent.executable(), agent.arguments(), agent.preset(), cwd, agent.workspaceWrite());
    }

    private static Path realDirectory(String value) {
        try {
            Path path = Path.of(value).toRealPath();
            if (Files.isDirectory(path)) return path;
        } catch (IOException ignored) {
            // A workspace can disappear after it was configured.
        }
        throw invalid("工作目录不存在或不可访问");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void check(Task task) throws Stopped {
        if (task.cancelled) throw new Stopped(false);
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static boolean reached(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private static HarnessException invalid(String message) {
        return new HarnessException("INVALID", message);
    }

    private static final class Stopped extends Exception {
        final boolean timeout;

        Stopped(boolean timeout) {
            this.timeout = timeout;
        }
    }

    private static final class AcpError extends RuntimeException {
        final int code;

        AcpError(int code, String message) {
            super(formatMessage(code, message));
            this.code = code;
        }

        private static String formatMessage(int code, String message) {
            if (code == -32000) return "外部智能体需要认证，请按适配器文档完成配置";
            String clean =
                    message == null ? "" : message.replaceAll("\\s+", " ").strip();
            return clean.isEmpty()
                    ? "外部智能体拒绝了请求（ACP " + code + "）"
                    : "外部智能体拒绝了请求（ACP " + code + "）：" + clip(clean, 200);
        }
    }
}
