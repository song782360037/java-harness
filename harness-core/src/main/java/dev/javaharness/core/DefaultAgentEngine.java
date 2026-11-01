package dev.javaharness.core;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.model.ContextSummary;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.ModelTurn;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.run.RunExtension;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Single-process coordinator. The gate orders submit, output, cancel and final settlement. */
public final class DefaultAgentEngine implements AgentEngine {
    private static final int DEFAULT_MAX_TOOLS_PER_REQUEST = 32;
    // A subagent needs room for a few model steps and its own final answer.
    private static final long MIN_SUBAGENT_NANOS = Duration.ofSeconds(15).toNanos();
    private static final int MAX_SUBAGENT_RESULT = 60_000;
    private final SessionStore store;
    private final RunConfigurationSource configurations;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("run-deadline").factory());
    private final Map<String, Task> tasks = new HashMap<>();
    private boolean closed;

    private static final class Task {
        Thread thread;
        RunStatus stopReason;
        // Subagent runs started by this task; stopping the task stops them first.
        final List<String> children = new ArrayList<>();
    }

    /**
     * One agent's loop. Steps and tools are recorded under {@code run}; tool side effects such as proposals and
     * approvals belong to {@code toolRunId}, the root. A subagent ({@code agent} non-null) shares the root's model,
     * tools and extension instructions and cannot delegate again.
     */
    private record Loop(
            Run run,
            String toolRunId,
            String agent,
            Task task,
            ModelProvider.Cancellation cancellation,
            ModelProvider model,
            WorkspaceTools tools,
            RunConfiguration configuration,
            String extensionInstructions,
            Toolset toolset,
            List<ModelMessage> messages,
            ContextSummary history,
            long timeoutNanos,
            long deadlineNanos) {}

    private record Toolset(List<ToolDefinition> definitions, boolean spill, boolean delegate, String instructions) {}

    private record Outcome(List<ModelMessage> completed, String detail) {}

    public DefaultAgentEngine(SessionStore store, ModelProvider model, Duration timeout) {
        this(store, model, timeout, ConversationContext.DEFAULT_MAX_TOKENS);
    }

    public DefaultAgentEngine(SessionStore store, ModelProvider model, Duration timeout, int maxContextTokens) {
        this(store, model, timeout, maxContextTokens, WorkspaceTools.disabled(), DEFAULT_MAX_TOOLS_PER_REQUEST);
    }

    public DefaultAgentEngine(
            SessionStore store,
            ModelProvider model,
            Duration timeout,
            int maxContextTokens,
            WorkspaceTools tools,
            int maxToolsPerRequest) {
        this(store, fixedConfiguration(model, timeout, maxContextTokens, tools, maxToolsPerRequest));
    }

    private static RunConfigurationSource fixedConfiguration(
            ModelProvider model, Duration timeout, int maxContextTokens, WorkspaceTools tools, int maxToolsPerRequest) {
        var fixed = new RunConfiguration(
                model.info(),
                timeout,
                maxContextTokens,
                RunConfiguration.outputFor(maxContextTokens),
                maxToolsPerRequest,
                TokenCounter.approximate(),
                workspace -> new RunConfiguration.Resources(model, tools, () -> {}));
        return () -> fixed;
    }

    public DefaultAgentEngine(SessionStore store, RunConfigurationSource configurations) {
        this.store = store;
        this.configurations = configurations;
        store.recoverInterruptedRuns();
    }

    @Override
    public synchronized SessionStore.Submission submit(
            String sessionId, String requestId, String prompt, String scenario) {
        return submit(sessionId, requestId, prompt, scenario, ModelSelection.DEFAULT);
    }

    @Override
    public synchronized SessionStore.Submission submit(
            String sessionId, String requestId, String prompt, String scenario, ModelSelection selection) {
        if (closed) throw new HarnessException("UNAVAILABLE", "服务正在停止");
        // A retried request id returns the existing run without capturing new configuration.
        var retry = store.retry(sessionId, requestId, prompt, scenario, selection);
        if (retry != null) return retry;
        RunConfiguration configuration = configurations.snapshot(selection);
        RunExtension extension;
        SessionStore.Submission submission;
        // The store is the shared coordination lock (plugins, commands and file changes lock it too),
        // so the extension snapshot and the persisted run see the same session state.
        synchronized (store) {
            extension = configurations.extensions(store.snapshot(sessionId).session(), prompt);
            try {
                submission = store.submit(
                        sessionId,
                        requestId,
                        prompt,
                        scenario,
                        configuration.modelInfo(),
                        selection,
                        configuration.selection(),
                        extension.snapshot());
                if (submission.created()) extension.submitted(submission.run().id());
            } catch (RuntimeException error) {
                extension.close();
                throw error;
            }
        }
        if (!submission.created()) {
            extension.close();
            return submission;
        }
        var task = new Task();
        tasks.put(submission.run().id(), task);
        try {
            workers.execute(() -> execute(submission.run(), task, configuration, extension));
        } catch (RejectedExecutionException error) {
            tasks.remove(submission.run().id());
            extension.close();
            store.transition(submission.run().id(), RunStatus.FAILED, "执行器不可用");
            throw new HarnessException("UNAVAILABLE", "执行器不可用");
        }
        return submission;
    }

    @Override
    public synchronized Run cancel(String runId) {
        Run run = store.run(runId);
        if (!run.status().active()) return run;
        Task task = tasks.get(runId);
        if (task == null) throw new HarnessException("CONFLICT", "任务未由当前进程持有");
        requestStop(runId, task, RunStatus.CANCELLED);
        return store.run(runId);
    }

    private synchronized void requestStop(String runId, Task task, RunStatus reason) {
        if (tasks.get(runId) != task || task.stopReason != null) return;
        store.transition(runId, RunStatus.CANCELLING, reason == RunStatus.TIMED_OUT ? "执行超时，等待收尾" : "正在停止");
        task.stopReason = reason;
        if (task.thread != null) task.thread.interrupt();
        for (String child : task.children) {
            Task value = tasks.get(child);
            if (value != null) requestStop(child, value, RunStatus.CANCELLED);
        }
    }

    /** In-loop store writes re-check the stop reason under the engine lock, so a stopped run records nothing further. */
    private ModelProvider.Cancellation cancellation(Task task) {
        return () -> {
            synchronized (this) {
                if (task.stopReason != null || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("run stopped");
            }
        };
    }

    private void execute(Run run, Task task, RunConfiguration configuration, RunExtension extension) {
        ScheduledFuture<?> deadline = null;
        RunConfiguration.Resources resources = null;
        String failure = null;
        Outcome outcome = null;
        long timeoutNanos = configuration.timeout().toNanos();
        long deadlineNanos;
        try {
            synchronized (this) {
                task.thread = Thread.currentThread();
                if (task.stopReason != null) return;
                store.transition(run.id(), RunStatus.RUNNING, "");
                // The loop's budget starts with the deadline timer, so children never outlive the parent's reserve.
                deadlineNanos = System.nanoTime() + timeoutNanos;
                final String timedRunId = run.id();
                deadline = timer.schedule(
                        () -> requestStop(timedRunId, task, RunStatus.TIMED_OUT),
                        configuration.timeout().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            resources = configuration.resources().open(run.workspace());
            ModelProvider.Cancellation cancellation = cancellation(task);
            WorkspaceTools tools = extension.open(run.id(), resources.tools(), cancellation);
            Toolset toolset = toolset(tools, extension.instructions(), null);
            TokenCounter counter = configuration.tokenCounter();
            int contextWindowTokens = configuration.contextWindowTokens();
            // History gets what remains after output, tool schemas, instructions and a safety margin.
            int reserved = configuration.maxOutputTokens()
                    + new TokenBudget(counter).tools(toolset.definitions())
                    + counter.count(toolset.instructions());
            int historyBudget = contextWindowTokens - reserved - TokenBudget.safety(contextWindowTokens);
            if (historyBudget < 256) throw new ModelException("模型上下文窗口不足以容纳系统指引、工具定义和输出预留，请提高上下文窗口或降低最大输出");
            ModelRequest request;
            synchronized (this) {
                if (task.stopReason != null) return;
                run = store.bindWorkspace(run.id(), tools.workspace());
                request = new ConversationContext(counter, historyBudget)
                        .build(run, store.contextHistory(run.id()), prior -> store.conversation(prior.id()));
            }
            outcome = loop(new Loop(
                    run,
                    run.id(),
                    null,
                    task,
                    cancellation,
                    resources.model(),
                    tools,
                    configuration,
                    extension.instructions(),
                    toolset,
                    request.messages(),
                    request.context(),
                    timeoutNanos,
                    deadlineNanos));
        } catch (InterruptedException error) {
            failure = "执行被中断";
        } catch (ModelException error) {
            failure = error.getMessage();
        } catch (HarnessException error) {
            failure = error.getMessage();
        } catch (Exception error) {
            failure = "模型执行失败";
            System.getLogger(getClass().getName())
                    .log(System.Logger.Level.WARNING, "Run " + run.id() + " failed", error);
        } finally {
            if (deadline != null) deadline.cancel(false);
            try {
                extension.close();
            } catch (RuntimeException error) {
                if (failure == null) failure = "释放插件资源失败";
            }
            if (resources != null) {
                try {
                    resources.close();
                } catch (RuntimeException error) {
                    if (failure == null) failure = "释放模型连接失败";
                }
            }
            settle(run.id(), task, failure, outcome);
        }
    }

    /** Record the final status once the worker has stopped touching the run. */
    private synchronized void settle(String runId, Task task, String failure, Outcome outcome) {
        // A cancellation racing with settlement must not interrupt its database transaction.
        Thread.interrupted();
        try {
            // An Error escaping the worker leaves neither a failure nor an outcome; it must not settle as completed.
            if (task.stopReason == null && failure == null && outcome == null) failure = "模型执行失败";
            RunStatus status = task.stopReason != null
                    ? task.stopReason
                    : failure == null ? RunStatus.COMPLETED : RunStatus.FAILED;
            String detail = switch (status) {
                case CANCELLED -> "已停止";
                case TIMED_OUT -> "超过执行时间限制";
                case FAILED -> failure;
                default -> "";
            };
            if (status == RunStatus.COMPLETED) store.completeRun(runId, outcome.completed(), outcome.detail());
            else store.transition(runId, status, detail);
        } finally {
            tasks.remove(runId);
        }
    }

    /**
     * The engine serves read_tool_output itself unless a workspace tool already owns the name or one more tool would
     * exceed the provider limit; without it, outputs reach the model whole. A root also gets delegate_task.
     */
    private static Toolset toolset(WorkspaceTools tools, String extensionInstructions, String agent) {
        boolean toolEnabled = !tools.definitions().isEmpty();
        var definitions = new ArrayList<>(tools.definitions());
        boolean spill = toolEnabled
                && definitions.size() < ToolOutputs.MAX_REQUEST_TOOLS
                && definitions.stream().noneMatch(tool -> tool.name().equals(ToolOutputs.READ));
        if (spill) definitions.add(ToolOutputs.DEFINITION);
        boolean delegate = toolEnabled
                && agent == null
                && definitions.size() < ToolOutputs.MAX_REQUEST_TOOLS
                && definitions.stream().noneMatch(tool -> tool.name().equals(Subagents.DELEGATE));
        if (delegate) definitions.add(Subagents.DEFINITION);
        String guidance = !toolEnabled
                ? ""
                : agent != null
                        ? AgentInstructions.forSubagent(agent)
                        : AgentInstructions.forRequest() + (delegate ? AgentInstructions.forDelegation() : "");
        return new Toolset(List.copyOf(definitions), spill, delegate, guidance + extensionInstructions);
    }

    private Outcome loop(Loop loop) throws Exception {
        Run run = loop.run();
        String runId = run.id();
        Task task = loop.task();
        ModelProvider.Cancellation cancellation = loop.cancellation();
        WorkspaceTools tools = loop.tools();
        RunConfiguration configuration = loop.configuration();
        Toolset toolset = loop.toolset();
        boolean spill = toolset.spill();
        int maxToolsPerRequest = configuration.maxToolsPerRequest();
        int contextWindowTokens = configuration.contextWindowTokens();
        int maxOutputTokens = configuration.maxOutputTokens();
        TokenBudget budget = new TokenBudget(configuration.tokenCounter());
        boolean toolEnabled = !tools.definitions().isEmpty();
        long deadlineNanos = loop.deadlineNanos();
        // With tools, keep up to 30 s (at most a fifth of the timeout) for a final tool-free answer.
        long finalReserveNanos =
                toolEnabled ? Math.min(Duration.ofSeconds(30).toNanos(), loop.timeoutNanos() / 5) : 0;
        String finalInstructions = loop.agent() == null ? loop.extensionInstructions() : toolset.instructions();
        java.util.function.Consumer<String> onText = text -> {
            synchronized (this) {
                if (task.stopReason == null && !Thread.currentThread().isInterrupted()) {
                    store.appendText(runId, text);
                }
            }
        };
        var messages = new ArrayList<>(loop.messages());
        int historySize = messages.size() - 1;
        var callIds = new java.util.HashSet<String>();
        // Identical name, arguments and result seen again means the call produced no new information.
        var repeats = new HashMap<String, Integer>();
        boolean finalRequest = false;
        boolean stalled = false;
        for (int step = 1; ; step = Math.incrementExact(step)) {
            cancellation.check();
            String instructions = finalRequest
                    ? finalInstructions
                            + (stalled ? AgentInstructions.forStalledTools() : AgentInstructions.forFinalAnswer())
                    : toolset.instructions();
            List<ToolDefinition> requestTools = finalRequest ? List.of() : toolset.definitions();
            int characters = instructions.length()
                    + messages.stream().mapToInt(ModelMessage::characters).sum();
            int tokens = budget.input(messages, instructions, requestTools);
            if (tokens + maxOutputTokens + TokenBudget.safety(tokens) > contextWindowTokens)
                throw new ModelException("工具轮次超过上下文 token 容量（约 " + tokens + "），请缩小问题范围或提高上下文窗口");
            var summary = new ContextSummary(
                    loop.history().includedTurns(),
                    loop.history().droppedTurns(),
                    loop.history().excludedTurns(),
                    characters,
                    0,
                    tokens,
                    contextWindowTokens);
            var next = new ModelRequest(
                    runId,
                    run.model(),
                    run.scenario(),
                    messages,
                    summary,
                    step,
                    tools.workspace(),
                    requestTools,
                    instructions,
                    finalRequest,
                    configuration.selection().reasoningEffort());
            synchronized (this) {
                cancellation.check();
                store.saveModelRequest(next);
            }
            // Reasoning streams token by token; batch it so each write and event covers ~2 KB or 300 ms.
            final int currentStep = step;
            var reasoning = new StringBuilder();
            long[] flushedAt = {System.nanoTime()};
            Runnable flushReasoning = () -> {
                synchronized (this) {
                    if (task.stopReason == null && !Thread.currentThread().isInterrupted()) {
                        for (String chunk : chunks(reasoning)) store.appendReasoning(runId, currentStep, chunk);
                    }
                }
                reasoning.setLength(0);
                flushedAt[0] = System.nanoTime();
            };
            java.util.function.Consumer<String> onReasoning = text -> {
                reasoning.append(text);
                if (reasoning.length() >= 2048 || System.nanoTime() - flushedAt[0] >= 300_000_000L)
                    flushReasoning.run();
            };
            ModelTurn turn = loop.model().complete(next, cancellation, onText, onReasoning);
            // A failed stream may lose its last unflushed slice; reasoning is display-only.
            flushReasoning.run();
            cancellation.check();
            if (turn.usage() != null)
                synchronized (this) {
                    cancellation.check();
                    store.recordUsage(runId, step, turn.usage());
                }
            messages.add(new ModelMessage("assistant", turn.text(), turn.calls(), null));
            if (turn.calls().isEmpty()) {
                String detail = !finalRequest
                        ? ""
                        : stalled ? "检测到重复的工具调用且结果无变化，已停止工具并生成收尾回答" : "时间预算不足，已跳过剩余工具并生成收尾回答";
                return new Outcome(List.copyOf(messages.subList(historySize, messages.size())), detail);
            }
            if (finalRequest) {
                synchronized (this) {
                    cancellation.check();
                    store.beginTools(runId, step, turn.calls());
                }
                for (ToolCall call : turn.calls())
                    synchronized (this) {
                        cancellation.check();
                        store.updateTool(runId, call.id(), "SKIPPED", "收尾请求不执行工具");
                    }
                throw new ModelException("收尾请求仍返回工具调用；请检查服务的工具协议兼容性");
            }
            if (tools.definitions().isEmpty()) throw new ModelException("未配置工作区，无法执行工具");
            if (turn.calls().size() > ToolCall.MAX_BATCH_SIZE) throw new ModelException("单次模型响应返回的工具调用超过解析上限");
            for (ToolCall call : turn.calls())
                if (!callIds.add(call.id())) throw new ModelException("模型返回重复的工具调用 ID");
            synchronized (this) {
                cancellation.check();
                store.beginTools(runId, step, turn.calls());
            }
            // Inside the final reserve, remaining calls are skipped and the next request asks for an answer.
            boolean timeExhausted = toolEnabled && deadlineNanos - System.nanoTime() <= finalReserveNanos;
            // Duplicates inside one response count once, so stopping always follows an earlier warning.
            var stepFingerprints = new java.util.HashSet<String>();
            var results = new String[turn.calls().size()];
            // Delegations start in place and run alongside the rest of the batch; their results join in order.
            var delegations = new LinkedHashMap<Integer, Future<WorkspaceTools.Result>>();
            // Only children actually started count toward the parallel cap; only this thread adds them.
            int childrenBefore = task.children.size();
            try {
                for (int index = 0; index < turn.calls().size(); index++) {
                    ToolCall call = turn.calls().get(index);
                    if (!timeExhausted && toolEnabled && deadlineNanos - System.nanoTime() <= finalReserveNanos)
                        timeExhausted = true;
                    String skipped = timeExhausted
                            ? "剩余时间不足以继续执行工具，本调用未执行；请基于已有证据生成收尾回答。"
                            : stalled
                                    ? "已检测到重复的工具调用且结果无变化，本调用未执行；请基于已有证据生成收尾回答。"
                                    : index >= maxToolsPerRequest
                                            ? "本模型配置单次最多执行 " + maxToolsPerRequest + " 个工具，本调用未执行。请先使用已完成的结果；若仍需此工具，请在下一次响应中重新请求。"
                                            : toolset.delegate()
                                                            && call.name().equals(Subagents.DELEGATE)
                                                            && startedChildren(task) - childrenBefore
                                                                    >= Subagents.MAX_PARALLEL
                                                    ? "单次响应最多并行 " + Subagents.MAX_PARALLEL + " 个子智能体，本调用未执行；需要时请在下一次响应中重新请求。"
                                                    : null;
                    if (skipped != null) {
                        synchronized (this) {
                            cancellation.check();
                            store.updateTool(runId, call.id(), "SKIPPED", skipped);
                        }
                        results[index] = skipped;
                        continue;
                    }
                    synchronized (this) {
                        cancellation.check();
                        store.updateTool(runId, call.id(), "RUNNING", "");
                    }
                    if (toolset.delegate() && call.name().equals(Subagents.DELEGATE)) {
                        delegations.put(index, delegate(loop, call, deadlineNanos - finalReserveNanos));
                        continue;
                    }
                    WorkspaceTools.Result result = spill && call.name().equals(ToolOutputs.READ)
                            ? ToolOutputs.read(call.arguments(), store.tools(runId))
                            : tools.execute(
                                    loop.toolRunId(),
                                    loop.agent() == null ? call : Subagents.scoped(runId, call),
                                    cancellation);
                    if (finishTool(runId, call, result, spill, stepFingerprints, repeats, results, index, cancellation))
                        stalled = true;
                }
                for (var delegation : delegations.entrySet()) {
                    ToolCall call = turn.calls().get(delegation.getKey());
                    WorkspaceTools.Result result;
                    try {
                        result = delegation.getValue().get();
                    } catch (ExecutionException error) {
                        result = new WorkspaceTools.Result(false, "子智能体执行失败");
                    }
                    if (finishTool(
                            runId, call, result, spill, stepFingerprints, repeats, results, delegation.getKey(),
                            cancellation)) stalled = true;
                }
            } finally {
                if (delegations.values().stream().anyMatch(future -> !future.isDone())) stopChildren(task);
                for (var future : delegations.values()) awaitQuietly(future);
            }
            // Children may run into the final reserve; then the next request must be the tool-free answer.
            if (!delegations.isEmpty() && deadlineNanos - System.nanoTime() <= finalReserveNanos) timeExhausted = true;
            for (int index = 0; index < turn.calls().size(); index++)
                messages.add(new ModelMessage("tool", results[index], List.of(), turn.calls().get(index).id()));
            if (timeExhausted || stalled) finalRequest = true;
        }
    }

    /** Persist one tool result and return whether repeats now count as stalled; the model-visible text goes to results. */
    private boolean finishTool(
            String runId,
            ToolCall call,
            WorkspaceTools.Result result,
            boolean spill,
            java.util.Set<String> stepFingerprints,
            Map<String, Integer> repeats,
            String[] results,
            int index,
            ModelProvider.Cancellation cancellation)
            throws InterruptedException {
        synchronized (this) {
            cancellation.check();
            store.updateTool(runId, call.id(), result.success() ? "COMPLETED" : "FAILED", result.output());
        }
        String visible = spill ? ToolOutputs.forModel(call.id(), result.output()) : result.output();
        // Counts are never reset: a changed workspace changes the result, so only calls that
        // learn nothing new repeat. Polling an unchanged status three times also ends tool use.
        String fingerprint = ToolOutputs.fingerprint(call, result);
        int seen = stepFingerprints.add(fingerprint)
                ? repeats.merge(fingerprint, 1, Integer::sum)
                : Math.max(2, repeats.get(fingerprint));
        if (seen >= 2) visible += "\n[本调用与本任务中之前的调用参数和结果完全相同，没有获得新信息；请换一种方法，或基于已有证据直接回答]";
        results[index] = visible;
        return seen >= 3;
    }

    /**
     * Start one subagent run for a delegate_task call. Its deadline ends where the parent's final reserve begins;
     * invalid arguments, a short remaining budget or a stopped parent complete at once with a failed result.
     */
    private Future<WorkspaceTools.Result> delegate(Loop parent, ToolCall call, long childDeadlineNanos)
            throws InterruptedException {
        Subagents.Request request;
        try {
            request = Subagents.parse(call.arguments());
        } catch (IllegalArgumentException error) {
            return CompletableFuture.completedFuture(new WorkspaceTools.Result(false, error.getMessage()));
        }
        long remaining = childDeadlineNanos - System.nanoTime();
        if (remaining < MIN_SUBAGENT_NANOS)
            return CompletableFuture.completedFuture(
                    new WorkspaceTools.Result(false, "剩余时间不足以启动子智能体，本调用未执行；请直接使用工具或基于已有证据回答。"));
        var task = new Task();
        Run child;
        synchronized (this) {
            parent.cancellation().check();
            child = store.startSubagent(parent.run().id(), call.id(), request.agent(), request.prompt());
            tasks.put(child.id(), task);
            parent.task().children.add(child.id());
        }
        final String childId = child.id();
        ScheduledFuture<?> deadline = null;
        try {
            // Both executors reject once close() has begun; the child then settles here instead of starting.
            deadline = timer.schedule(
                    () -> requestStop(childId, task, RunStatus.TIMED_OUT), remaining, TimeUnit.NANOSECONDS);
            ScheduledFuture<?> timed = deadline;
            return workers.submit(
                    () -> runSubagent(parent, child, task, request.agent(), remaining, childDeadlineNanos, timed));
        } catch (RejectedExecutionException error) {
            if (deadline != null) deadline.cancel(false);
            settle(childId, task, "执行器不可用", null);
            return CompletableFuture.completedFuture(new WorkspaceTools.Result(false, "执行器不可用，子智能体未启动"));
        }
    }

    private WorkspaceTools.Result runSubagent(
            Loop parent,
            Run child,
            Task task,
            String agent,
            long timeoutNanos,
            long deadlineNanos,
            ScheduledFuture<?> deadline) {
        String failure = null;
        Outcome outcome = null;
        try {
            synchronized (this) {
                task.thread = Thread.currentThread();
            }
            ModelProvider.Cancellation cancellation = cancellation(task);
            cancellation.check();
            WorkspaceTools tools = Subagents.tools(parent.tools(), agent);
            outcome = loop(new Loop(
                    child,
                    parent.toolRunId(),
                    agent,
                    task,
                    cancellation,
                    parent.model(),
                    tools,
                    parent.configuration(),
                    parent.extensionInstructions(),
                    toolset(tools, parent.extensionInstructions(), agent),
                    List.of(new ModelMessage("user", child.prompt())),
                    new ContextSummary(0, 0, 0, 0, 0),
                    timeoutNanos,
                    deadlineNanos));
        } catch (InterruptedException error) {
            failure = "执行被中断";
        } catch (ModelException error) {
            failure = error.getMessage();
        } catch (HarnessException error) {
            failure = error.getMessage();
        } catch (Exception error) {
            failure = "模型执行失败";
            System.getLogger(getClass().getName())
                    .log(System.Logger.Level.WARNING, "Subagent run " + child.id() + " failed", error);
        } finally {
            deadline.cancel(false);
            settle(child.id(), task, failure, outcome);
        }
        return report(store.run(child.id()), agent, outcome);
    }

    /** The parent sees the child's final answer, or its status and partial output, marked as subagent output. */
    private static WorkspaceTools.Result report(Run child, String agent, Outcome outcome) {
        if (child.status() == RunStatus.COMPLETED) {
            String answer = outcome.completed().getLast().content();
            return new WorkspaceTools.Result(
                    true,
                    "[子智能体 " + agent + " 已完成；以下为其回答，按工具结果对待]\n"
                            + (answer.isBlank() ? "（子智能体没有给出文字回答）" : cap(answer)));
        }
        String state = switch (child.status()) {
            case CANCELLED -> "已停止";
            case TIMED_OUT -> "超时";
            default -> "失败";
        };
        String partial = child.output().isBlank() ? "" : "\n以下为已生成的部分输出，可能不完整：\n" + cap(child.output());
        return new WorkspaceTools.Result(false, "[子智能体 " + agent + " " + state + "：" + child.detail() + "]" + partial);
    }

    private static String cap(String text) {
        if (text.length() <= MAX_SUBAGENT_RESULT) return text;
        int end = MAX_SUBAGENT_RESULT;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\n[子智能体输出过长，其余部分已省略]";
    }

    private synchronized int startedChildren(Task task) {
        return task.children.size();
    }

    private synchronized void stopChildren(Task task) {
        for (String child : task.children) {
            Task value = tasks.get(child);
            if (value != null) requestStop(child, value, RunStatus.CANCELLED);
        }
    }

    /** Wait for a child to settle even while the parent is being interrupted, so children settle before it. */
    private static void awaitQuietly(Future<?> future) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    future.get();
                    return;
                } catch (InterruptedException error) {
                    interrupted = true;
                } catch (ExecutionException error) {
                    return;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            List.copyOf(tasks.entrySet()).forEach(entry -> requestStop(entry.getKey(), entry.getValue(), RunStatus.CANCELLED));
        }
        workers.shutdown();
        timer.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException error) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Split into store-sized chunks without separating a surrogate pair. */
    private static List<String> chunks(CharSequence text) {
        var result = new ArrayList<String>();
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(offset + 4096, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            result.add(text.subSequence(offset, end).toString());
            offset = end;
        }
        return result;
    }
}
