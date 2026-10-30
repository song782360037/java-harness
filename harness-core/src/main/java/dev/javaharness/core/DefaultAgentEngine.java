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
    }

    private void execute(Run run, Task task, RunConfiguration configuration, RunExtension extension) {
        ScheduledFuture<?> deadline = null;
        RunConfiguration.Resources resources = null;
        String failure = null;
        String completionDetail = "";
        java.util.List<ModelMessage> completed = null;
        try {
            ModelRequest request;
            synchronized (this) {
                task.thread = Thread.currentThread();
                if (task.stopReason != null) return;
                store.transition(run.id(), RunStatus.RUNNING, "");
                final String timedRunId = run.id();
                deadline = timer.schedule(
                        () -> requestStop(timedRunId, task, RunStatus.TIMED_OUT),
                        configuration.timeout().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            resources = configuration.resources().open(run.workspace());
            ModelProvider model = resources.model();
            // In-loop store writes re-check the stop reason under the engine lock,
            // so a stopped run records nothing further except its final status.
            ModelProvider.Cancellation cancellation = () -> {
                synchronized (this) {
                    if (task.stopReason != null || Thread.currentThread().isInterrupted())
                        throw new InterruptedException("run stopped");
                }
            };
            WorkspaceTools tools = extension.open(run.id(), resources.tools(), cancellation);
            int maxToolsPerRequest = configuration.maxToolsPerRequest();
            int contextWindowTokens = configuration.contextWindowTokens();
            int maxOutputTokens = configuration.maxOutputTokens();
            TokenCounter counter = configuration.tokenCounter();
            TokenBudget budget = new TokenBudget(counter);
            boolean toolEnabled = !tools.definitions().isEmpty();
            String normalInstructions = (toolEnabled ? AgentInstructions.forRequest() : "") + extension.instructions();
            // History gets what remains after output, tool schemas, instructions and a safety margin.
            int reserved = maxOutputTokens + budget.tools(tools.definitions()) + counter.count(normalInstructions);
            int historyBudget = contextWindowTokens - reserved - TokenBudget.safety(contextWindowTokens);
            long timeoutNanos = configuration.timeout().toNanos();
            long deadlineNanos = System.nanoTime() + timeoutNanos;
            // With tools, keep up to 30 s (at most a fifth of the timeout) for a final tool-free answer.
            long finalReserveNanos =
                    toolEnabled ? Math.min(Duration.ofSeconds(30).toNanos(), timeoutNanos / 5) : 0;
            if (historyBudget < 256) throw new ModelException("模型上下文窗口不足以容纳系统指引、工具定义和输出预留，请提高上下文窗口或降低最大输出");
            synchronized (this) {
                if (task.stopReason != null) return;
                run = store.bindWorkspace(run.id(), tools.workspace());
                request = new ConversationContext(counter, historyBudget)
                        .build(run, store.contextHistory(run.id()), prior -> store.conversation(prior.id()));
            }
            final String runId = run.id();
            java.util.function.Consumer<String> onText = text -> {
                synchronized (this) {
                    if (task.stopReason == null && !Thread.currentThread().isInterrupted()) {
                        store.appendText(runId, text);
                    }
                }
            };
            var messages = new java.util.ArrayList<>(request.messages());
            int historySize = messages.size() - 1;
            var callIds = new java.util.HashSet<String>();
            boolean finalRequest = false;
            for (int step = 1; ; step = Math.incrementExact(step)) {
                cancellation.check();
                String instructions = finalRequest
                        ? extension.instructions() + AgentInstructions.forFinalAnswer()
                        : normalInstructions;
                List<ToolDefinition> requestTools = finalRequest ? List.of() : tools.definitions();
                int characters = instructions.length()
                        + messages.stream().mapToInt(ModelMessage::characters).sum();
                int tokens = budget.input(messages, instructions, requestTools);
                if (tokens + maxOutputTokens + TokenBudget.safety(tokens) > contextWindowTokens)
                    throw new ModelException("工具轮次超过上下文 token 容量（约 " + tokens + "），请缩小问题范围或提高上下文窗口");
                var summary = new ContextSummary(
                        request.context().includedTurns(),
                        request.context().droppedTurns(),
                        request.context().excludedTurns(),
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
                ModelTurn turn = model.complete(next, cancellation, onText, onReasoning);
                // A failed stream may lose its last unflushed slice; reasoning is display-only.
                flushReasoning.run();
                cancellation.check();
                messages.add(new ModelMessage("assistant", turn.text(), turn.calls(), null));
                if (turn.calls().isEmpty()) {
                    if (finalRequest) completionDetail = "时间预算不足，已跳过剩余工具并生成收尾回答";
                    completed = java.util.List.copyOf(messages.subList(historySize, messages.size()));
                    break;
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
                for (int index = 0; index < turn.calls().size(); index++) {
                    ToolCall call = turn.calls().get(index);
                    if (!timeExhausted && toolEnabled && deadlineNanos - System.nanoTime() <= finalReserveNanos)
                        timeExhausted = true;
                    WorkspaceTools.Result result;
                    if (timeExhausted) {
                        result = new WorkspaceTools.Result(false, "剩余时间不足以继续执行工具，本调用未执行；请基于已有证据生成收尾回答。");
                        synchronized (this) {
                            cancellation.check();
                            store.updateTool(runId, call.id(), "SKIPPED", result.output());
                        }
                    } else if (index >= maxToolsPerRequest) {
                        result = new WorkspaceTools.Result(
                                false,
                                "本模型配置单次最多执行 " + maxToolsPerRequest + " 个工具，本调用未执行。请先使用已完成的结果；若仍需此工具，请在下一次响应中重新请求。");
                        synchronized (this) {
                            cancellation.check();
                            store.updateTool(runId, call.id(), "SKIPPED", result.output());
                        }
                    } else {
                        synchronized (this) {
                            cancellation.check();
                            store.updateTool(runId, call.id(), "RUNNING", "");
                        }
                        result = tools.execute(runId, call, cancellation);
                        synchronized (this) {
                            cancellation.check();
                            store.updateTool(
                                    runId, call.id(), result.success() ? "COMPLETED" : "FAILED", result.output());
                        }
                    }
                    messages.add(new ModelMessage("tool", result.output(), java.util.List.of(), call.id()));
                }
                if (timeExhausted) finalRequest = true;
            }
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
            synchronized (this) {
                // A cancellation racing with settlement must not interrupt its database transaction.
                Thread.interrupted();
                try {
                    RunStatus outcome = task.stopReason != null
                            ? task.stopReason
                            : failure == null ? RunStatus.COMPLETED : RunStatus.FAILED;
                    String detail = switch (outcome) {
                        case CANCELLED -> "已停止";
                        case TIMED_OUT -> "超过执行时间限制";
                        case FAILED -> failure;
                        default -> "";
                    };
                    if (outcome == RunStatus.COMPLETED) store.completeRun(run.id(), completed, completionDetail);
                    else store.transition(run.id(), outcome, detail);
                } finally {
                    tasks.remove(run.id());
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            tasks.forEach((id, task) -> requestStop(id, task, RunStatus.CANCELLED));
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
        var result = new java.util.ArrayList<String>();
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(offset + 4096, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            result.add(text.subSequence(offset, end).toString());
            offset = end;
        }
        return result;
    }
}
