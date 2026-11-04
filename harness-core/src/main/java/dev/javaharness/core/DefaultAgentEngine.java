package dev.javaharness.core;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.memory.LongTermMemory;
import dev.javaharness.spi.model.ContextSummary;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.ModelTurn;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.run.RunExtension;
import dev.javaharness.spi.run.RunInput;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.tool.ToolInterceptor;
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
    private static final long COMPACTION_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();
    // After a failed compaction a session truncates for a while instead of waiting on the model every run.
    private static final long COMPACTION_BACKOFF_NANOS = Duration.ofMinutes(10).toNanos();
    private final SessionStore store;
    private final RunConfigurationSource configurations;
    private final LongTermMemory memories;
    private final List<ToolInterceptor> interceptors;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("run-deadline").factory());
    private final Map<String, Task> tasks = new HashMap<>();
    // Session id to the nanoTime until which compaction is skipped; guarded by this.
    private final Map<String, Long> compactionBackoff = new HashMap<>();
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
            LongTermMemory.Context memory,
            String attached,
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
        this(store, configurations, LongTermMemory.disabled());
    }

    /** 注入长期记忆服务；既有嵌入和测试构造器保持禁用记忆。 */
    public DefaultAgentEngine(SessionStore store, RunConfigurationSource configurations, LongTermMemory memories) {
        this(store, configurations, memories, List.of());
    }

    /** 额外注入工具拦截器，按顺序作用于每一次工具执行。 */
    public DefaultAgentEngine(
            SessionStore store,
            RunConfigurationSource configurations,
            LongTermMemory memories,
            List<ToolInterceptor> interceptors) {
        this.store = store;
        this.configurations = configurations;
        this.memories = memories;
        this.interceptors = List.copyOf(interceptors);
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
        return submit(sessionId, requestId, prompt, scenario, selection, null);
    }

    @Override
    public synchronized SessionStore.Submission submit(
            String sessionId,
            String requestId,
            String prompt,
            String scenario,
            ModelSelection selection,
            ApprovalMode approvalMode) {
        return submit(sessionId, requestId, prompt, scenario, selection, approvalMode, RunInput.EMPTY);
    }

    @Override
    public synchronized SessionStore.Submission submit(
            String sessionId,
            String requestId,
            String prompt,
            String scenario,
            ModelSelection selection,
            ApprovalMode approvalMode,
            RunInput input) {
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
                        extension.snapshot(),
                        approvalMode);
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
            workers.execute(() -> execute(submission.run(), task, configuration, extension, input));
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

    private void execute(Run run, Task task, RunConfiguration configuration, RunExtension extension, RunInput input) {
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
            String extensionInstructions = extension.instructions();
            Toolset toolset = toolset(tools, extensionInstructions, null);
            TokenCounter counter = configuration.tokenCounter();
            int historyBudget = historyBudget(configuration, toolset);
            synchronized (this) {
                cancellation.check();
                run = store.bindWorkspace(run.id(), tools.workspace());
            }
            int memoryBudget = Math.min(2048, Math.max(0, (historyBudget - counter.count(run.prompt()) - 256) / 4));
            LongTermMemory.Context memory;
            try {
                memory = memories.recall(run, counter, memoryBudget);
            } catch (RuntimeException error) {
                // Memory is optional reference material; a recall failure must not fail the run.
                memory = LongTermMemory.Context.EMPTY;
                System.getLogger(getClass().getName())
                        .log(System.Logger.Level.WARNING, "Memory recall skipped for " + run.id());
            }
            // Memory rides on the current user message, so it shares the history budget.
            historyBudget -= counter.count(memory.text());
            // Attached files and images ride on the current user message as well.
            historyBudget -= counter.count(input.context()) + input.images().size() * TokenBudget.IMAGE_TOKENS;
            if (!input.empty() && historyBudget < counter.count(run.prompt()) + 256)
                throw new ModelException("引用的文件、附件或图片超出模型上下文窗口，请减少引用内容或提高上下文窗口");
            if (historyBudget < 256) throw new ModelException("模型上下文窗口不足以容纳系统指引、工具定义和输出预留，请提高上下文窗口或降低最大输出");
            // A shared context (a group chat transcript) replaces the session history and rides on the prompt too.
            String shared = extension.context(counter, Math.max(0, historyBudget - counter.count(run.prompt()) - 256));
            if (shared != null) historyBudget -= counter.count(shared);
            if (shared != null && historyBudget < counter.count(run.prompt()) + 256)
                throw new ModelException("模型上下文窗口不足以容纳群聊上下文和本次任务，请提高上下文窗口或减少成员职责描述");
            var context = new ConversationContext(counter, historyBudget);
            ConversationContext.History history;
            SessionStore.CompactedHistory stored;
            String sessionId = run.sessionId();
            boolean backoff;
            synchronized (this) {
                if (task.stopReason != null) return;
                history = context.history(
                        run,
                        shared == null ? store.contextHistory(run.id()) : List.of(),
                        prior -> store.conversation(prior.id()));
                stored = shared == null ? store.compactedHistory(sessionId, run.workspace()) : null;
                Long until = compactionBackoff.get(sessionId);
                backoff = until != null && System.nanoTime() - until < 0;
            }
            // Compaction calls the model, so it runs outside the engine lock; a group chat has no session history.
            // During backoff a stored summary is still reused; only new summaries are skipped.
            ConversationContext.Compaction compaction =
                    shared == null ? (previous, turns, maxTokens) -> null : null;
            if (shared == null && !backoff) {
                var compactor = new ContextCompactor(
                        resources.model(),
                        run.model(),
                        counter,
                        configuration.contextWindowTokens(),
                        configuration.maxOutputTokens(),
                        workers,
                        Math.min(COMPACTION_TIMEOUT_NANOS, (deadlineNanos - System.nanoTime()) / 4));
                compaction = (previous, turns, maxTokens) -> {
                    String summary = compactor.summarize(previous, turns, maxTokens);
                    if (summary == null || summary.isBlank()) {
                        synchronized (this) {
                            compactionBackoff.put(sessionId, System.nanoTime() + COMPACTION_BACKOFF_NANOS);
                        }
                    }
                    return summary;
                };
            }
            var built = context.build(history, stored, compaction);
            ModelRequest request = built.request();
            synchronized (this) {
                cancellation.check();
                if (built.updated() != null) {
                    compactionBackoff.remove(sessionId);
                    try {
                        store.saveCompactedHistory(sessionId, run.workspace(), built.updated());
                    } catch (RuntimeException error) {
                        // The summary is a cache; this request still carries it.
                        System.getLogger(getClass().getName())
                                .log(System.Logger.Level.WARNING, "Context summary not saved for " + run.id(), error);
                    }
                }
            }
            var messages = new ArrayList<>(request.messages());
            String prefix = (shared == null ? "" : shared) + memory.text();
            if (!prefix.isEmpty() || !input.empty())
                messages.set(
                        messages.size() - 1,
                        new ModelMessage(
                                "user", prefix + input.context() + run.prompt(), List.of(), null, input.images()));
            outcome = loop(new Loop(
                    run,
                    run.id(),
                    null,
                    task,
                    cancellation,
                    resources.model(),
                    tools,
                    configuration,
                    extensionInstructions,
                    toolset,
                    messages,
                    request.context(),
                    memory,
                    input.context(),
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
            try {
                settle(run.id(), task, failure, outcome);
            } finally {
                // Notify even when settlement failed, so owners such as group chats never wait forever.
                try {
                    extension.settled(store.run(run.id()));
                } catch (RuntimeException error) {
                    System.getLogger(getClass().getName())
                            .log(System.Logger.Level.WARNING, "Settled notification failed for " + run.id(), error);
                }
            }
            if (outcome != null && failure == null && task.stopReason == null) {
                try {
                    memories.completed(store.run(run.id()));
                } catch (RuntimeException error) {
                    System.getLogger(getClass().getName())
                            .log(System.Logger.Level.INFO, "Memory extraction not scheduled for " + run.id());
                }
            }
        }
    }

    /** History gets what remains after output, tool schemas, instructions and a safety margin. */
    private static int historyBudget(RunConfiguration configuration, Toolset toolset) {
        TokenCounter counter = configuration.tokenCounter();
        int contextWindowTokens = configuration.contextWindowTokens();
        return contextWindowTokens
                - configuration.maxOutputTokens()
                - new TokenBudget(counter).tools(toolset.definitions())
                - counter.count(toolset.instructions())
                - TokenBudget.safety(contextWindowTokens);
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
                    contextWindowTokens,
                    loop.history().compactedTurns());
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
                if (step == 1 && !loop.memory().entries().isEmpty()) {
                    try {
                        memories.recordUsage(runId, loop.memory().entries());
                    } catch (RuntimeException error) {
                        System.getLogger(getClass().getName())
                                .log(System.Logger.Level.WARNING, "Memory usage not recorded for " + runId);
                    }
                }
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
                var completed = new ArrayList<>(messages.subList(historySize, messages.size()));
                // The conversation keeps the attached context and original prompt; recalled memory and images
                // stay only in the request, the context's image placeholders standing in for the latter.
                completed.set(0, new ModelMessage("user", loop.attached() + run.prompt()));
                return new Outcome(List.copyOf(completed), detail);
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
                    // A refused call is recorded as started and failed, so the timeline shows when it was stopped.
                    String refused = refusal(runId, call);
                    if (refused != null) {
                        synchronized (this) {
                            cancellation.check();
                            store.updateTool(runId, call.id(), "FAILED", refused);
                        }
                        results[index] = refused;
                        continue;
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

    /** The first interceptor reason to refuse a call, or null when every interceptor lets it run. */
    private String refusal(String runId, ToolCall call) {
        for (var interceptor : interceptors) {
            String reason = interceptor.before(runId, call);
            if (reason != null) return reason;
        }
        return null;
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
        for (var interceptor : interceptors) result = interceptor.after(runId, call, result);
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
                    LongTermMemory.Context.EMPTY,
                    "",
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
