package dev.javaharness.core;

import dev.javaharness.spi.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/** Single-process coordinator. The gate orders submit, output, cancel and final settlement. */
public final class DefaultAgentEngine implements AgentEngine {
    private static final int MAX_TOOLS_PER_STEP = 8;
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
        this(store, model, timeout, ConversationContext.DEFAULT_MAX_CHARACTERS);
    }

    public DefaultAgentEngine(SessionStore store, ModelProvider model, Duration timeout, int maxContextCharacters) {
        this(store, model, timeout, maxContextCharacters, WorkspaceTools.disabled(), 0);
    }

    public DefaultAgentEngine(SessionStore store, ModelProvider model, Duration timeout, int maxContextCharacters, WorkspaceTools tools, int maxSteps) {
        this(store, fixedConfiguration(model, timeout, maxContextCharacters, tools, maxSteps));
    }

    private static RunConfigurationSource fixedConfiguration(ModelProvider model, Duration timeout, int maxCharacters, WorkspaceTools tools, int maxSteps) {
        var fixed = new RunConfiguration(model.info(), timeout, maxCharacters, maxSteps,
                workspace -> new RunConfiguration.Resources(model, tools, () -> {}));
        return () -> fixed;
    }

    public DefaultAgentEngine(SessionStore store, RunConfigurationSource configurations) {
        this.store = store;
        this.configurations = configurations;
        store.recoverInterruptedRuns();
    }

    @Override
    public synchronized SessionStore.Submission submit(String sessionId, String requestId,
                                                       String prompt, String scenario) {
        return submit(sessionId, requestId, prompt, scenario, ModelSelection.DEFAULT);
    }

    @Override public synchronized SessionStore.Submission submit(String sessionId, String requestId, String prompt,
                                                                String scenario, ModelSelection selection) {
        if (closed) throw new HarnessException("UNAVAILABLE", "服务正在停止");
        var retry = store.retry(sessionId, requestId, prompt, scenario, selection);
        if (retry != null) return retry;
        RunConfiguration configuration = configurations.snapshot(selection);
        var submission = store.submit(sessionId, requestId, prompt, scenario, configuration.modelInfo(), selection, configuration.selection());
        if (!submission.created()) return submission;
        var task = new Task();
        tasks.put(submission.run().id(), task);
        try {
            workers.execute(() -> execute(submission.run(), task, configuration));
        } catch (RejectedExecutionException error) {
            tasks.remove(submission.run().id());
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

    private void execute(Run run, Task task, RunConfiguration configuration) {
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
                deadline = timer.schedule(() -> requestStop(timedRunId, task, RunStatus.TIMED_OUT),
                        configuration.timeout().toMillis(), TimeUnit.MILLISECONDS);
            }
            resources = configuration.resources().open(run.workspace());
            ModelProvider model = resources.model();
            WorkspaceTools tools = resources.tools();
            int maxSteps = configuration.maxSteps();
            int maxContextCharacters = configuration.contextMaxCharacters();
            synchronized (this) {
                if (task.stopReason != null) return;
                run = store.bindWorkspace(run.id(), tools.workspace());
                request = new ConversationContext(maxContextCharacters).build(run, store.contextHistory(run.id()), prior -> store.conversation(prior.id()));
            }
            final String runId = run.id();
            ModelProvider.Cancellation cancellation = () -> {
                synchronized (this) {
                    if (task.stopReason != null || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("run stopped");
                    }
                }
            };
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
            for (int step = 1; maxSteps == 0 || step <= maxSteps; step = Math.incrementExact(step)) {
                cancellation.check();
                boolean toolEnabled = !tools.definitions().isEmpty();
                boolean finalAnswerOnly = toolEnabled && maxSteps > 0 && step == maxSteps;
                String instructions = toolEnabled ? AgentInstructions.forStep(step, maxSteps) : "";
                int characters = instructions.length() + messages.stream().mapToInt(ModelMessage::characters).sum();
                if (characters > maxContextCharacters) throw new ModelException("工具轮次超过上下文容量，请缩小问题范围或提高字符预算");
                var summary = new ContextSummary(request.context().includedTurns(), request.context().droppedTurns(),
                        request.context().excludedTurns(), characters, maxContextCharacters);
                var next = new ModelRequest(runId, run.model(), run.scenario(), messages, summary, step,
                        tools.workspace(), tools.definitions(), instructions, finalAnswerOnly, configuration.selection().reasoningEffort());
                synchronized (this) { cancellation.check(); store.saveModelRequest(next); }
                ModelTurn turn = model.complete(next, cancellation, onText);
                cancellation.check();
                messages.add(new ModelMessage("assistant", turn.text(), turn.calls(), null));
                if (turn.calls().isEmpty()) {
                    if (finalAnswerOnly && turn.text().isBlank()) throw new ModelException("模型在收尾阶段未返回文字回答");
                    if (finalAnswerOnly) completionDetail = "已到达工具探索步数上限，本次回答基于已有证据；请注意回答中的未确认项";
                    completed = java.util.List.copyOf(messages.subList(historySize, messages.size()));
                    break;
                }
                if (tools.definitions().isEmpty()) throw new ModelException("未配置工作区，无法执行工具");
                if (turn.calls().size() > ToolCall.MAX_BATCH_SIZE) throw new ModelException("单步模型返回的工具调用超过 32 个解析上限");
                for (ToolCall call : turn.calls()) if (!callIds.add(call.id())) throw new ModelException("模型返回重复的工具调用 ID");
                synchronized (this) { cancellation.check(); store.beginTools(runId, step, turn.calls()); }
                if (finalAnswerOnly) {
                    for (ToolCall call : turn.calls()) synchronized (this) {
                        cancellation.check();
                        store.updateTool(runId, call.id(), "SKIPPED", "本步仅允许输出回答，工具未执行");
                    }
                    throw new ModelException("已通过 tool_choice=none 要求收尾，但模型仍返回工具调用；请检查服务的工具协议兼容性");
                }
                for (int index = 0; index < turn.calls().size(); index++) {
                    ToolCall call = turn.calls().get(index);
                    WorkspaceTools.Result result;
                    if (index >= MAX_TOOLS_PER_STEP) {
                        result = new WorkspaceTools.Result(false, "本步最多执行 8 个工具，本调用未执行。请先使用已完成的结果；若仍需此工具，请在下一步重新请求，每步最多请求 8 个工具。");
                        synchronized (this) { cancellation.check(); store.updateTool(runId, call.id(), "SKIPPED", result.output()); }
                    } else {
                        synchronized (this) { cancellation.check(); store.updateTool(runId, call.id(), "RUNNING", ""); }
                        result = tools.execute(call, cancellation);
                        synchronized (this) {
                            cancellation.check();
                            store.updateTool(runId, call.id(), result.success() ? "COMPLETED" : "FAILED", result.output());
                        }
                    }
                    messages.add(new ModelMessage("tool", result.output(), java.util.List.of(), call.id()));
                }
            }
        } catch (InterruptedException error) {
            failure = "执行被中断";
        } catch (ModelException error) {
            failure = error.getMessage();
        } catch (Exception error) {
            failure = "模型执行失败";
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Run " + run.id() + " failed", error);
        } finally {
            if (deadline != null) deadline.cancel(false);
            if (resources != null) {
                try { resources.close(); }
                catch (RuntimeException error) { if (failure == null) failure = "释放模型连接失败"; }
            }
            synchronized (this) {
                // A cancellation racing with settlement must not interrupt its database transaction.
                Thread.interrupted();
                try {
                    RunStatus outcome = task.stopReason != null ? task.stopReason
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
}
