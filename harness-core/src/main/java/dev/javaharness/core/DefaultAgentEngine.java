package dev.javaharness.core;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.memory.LongTermMemory;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
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
import dev.javaharness.spi.tool.ToolInterceptor;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Single-process coordinator. The gate orders submit, output, cancel and final settlement. */
public final class DefaultAgentEngine implements AgentEngine {
    private static final int DEFAULT_MAX_TOOLS_PER_REQUEST = 32;
    private static final long COMPACTION_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();
    // After a failed compaction a session truncates for a while instead of waiting on the model every run.
    private static final long COMPACTION_BACKOFF_NANOS = Duration.ofMinutes(10).toNanos();
    private final SessionStore store;
    private final RunConfigurationSource configurations;
    private final LongTermMemory memories;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("run-deadline").factory());
    private final Map<String, Task> tasks = new HashMap<>();
    // Session id to the nanoTime until which compaction is skipped; guarded by this.
    private final Map<String, Long> compactionBackoff = new HashMap<>();
    private final AgentLoopRunner loopRunner;
    private boolean closed;

    static final class Task {
        Thread thread;
        RunStatus stopReason;
        // Subagent runs started by this task; stopping the task stops them first.
        final List<String> children = new ArrayList<>();
    }

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
        var subagents = new SubagentRunner(this, store, workers, timer, tasks, new SubagentRunner.Control() {
            @Override
            public void requestStop(String runId, Task task, RunStatus reason) {
                DefaultAgentEngine.this.requestStop(runId, task, reason);
            }

            @Override
            public ModelProvider.Cancellation cancellation(Task task) {
                return DefaultAgentEngine.this.cancellation(task);
            }

            @Override
            public void settle(String runId, Task task, String failure, AgentLoopRunner.Outcome outcome) {
                DefaultAgentEngine.this.settle(runId, task, failure, outcome);
            }
        });
        this.loopRunner = new AgentLoopRunner(this, store, memories, List.copyOf(interceptors), subagents);
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
        if (task == null) return store.transition(runId, RunStatus.INTERRUPTED, "执行任务已结束，已清理残留状态；不会自动重跑");
        requestStop(runId, task, RunStatus.CANCELLED);
        return store.run(runId);
    }

    synchronized void requestStop(String runId, Task task, RunStatus reason) {
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
    ModelProvider.Cancellation cancellation(Task task) {
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
        AgentLoopRunner.Outcome outcome = null;
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
            AgentLoopRunner.Toolset toolset = AgentLoopRunner.toolset(
                    tools, extensionInstructions, null, extension.allowSubagents(), extension.useDefaultGuidance());
            TokenCounter counter = configuration.tokenCounter();
            int historyBudget = AgentLoopRunner.historyBudget(configuration, toolset);
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
            // 插件命令会把输入放进指引，所以这里也提示精简输入。
            if (historyBudget < 256) throw new ModelException("模型上下文窗口不足以容纳系统指引、工具定义和输出预留，请精简输入、提高上下文窗口或降低最大输出");
            // 输入长度不设固定上限，只在放不进模型上下文时拒绝；先判断正文，再判断附件，报错才指向真正的原因。
            int promptTokens = counter.count(run.prompt());
            if (historyBudget < promptTokens + 256)
                throw new ModelException("本次输入约 " + promptTokens + " tokens，超出模型上下文窗口，请精简内容或提高上下文窗口");
            // Attached files and images ride on the current user message as well.
            historyBudget -= counter.count(input.context()) + input.images().size() * TokenBudget.IMAGE_TOKENS;
            if (!input.empty() && historyBudget < promptTokens + 256)
                throw new ModelException("引用的文件、附件或图片超出模型上下文窗口，请减少引用内容或提高上下文窗口");
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
            ConversationContext.Compaction compaction = shared == null ? (previous, turns, maxTokens) -> null : null;
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
            outcome = loopRunner.run(new AgentLoopRunner.Loop(
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
                    Run completed = store.run(run.id());
                    if (completed.status() == RunStatus.COMPLETED) memories.completed(completed);
                } catch (RuntimeException error) {
                    System.getLogger(getClass().getName())
                            .log(System.Logger.Level.INFO, "Memory extraction not scheduled for " + run.id());
                }
            }
        }
    }

    /** Record the final status once the worker has stopped touching the run. */
    synchronized void settle(String runId, Task task, String failure, AgentLoopRunner.Outcome outcome) {
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
        } catch (RuntimeException error) {
            System.getLogger(getClass().getName())
                    .log(System.Logger.Level.WARNING, "Settlement failed for " + runId, error);
            Run run = store.run(runId);
            if (run.status().active()) {
                // CANCELLING cannot transition to FAILED; INTERRUPTED also releases its session slot.
                store.transition(
                        runId,
                        run.status() == RunStatus.CANCELLING ? RunStatus.INTERRUPTED : RunStatus.FAILED,
                        "任务结算失败，执行已结束；请重新提交");
            }
        } finally {
            tasks.remove(runId);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            List.copyOf(tasks.entrySet())
                    .forEach(entry -> requestStop(entry.getKey(), entry.getValue(), RunStatus.CANCELLED));
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
}
