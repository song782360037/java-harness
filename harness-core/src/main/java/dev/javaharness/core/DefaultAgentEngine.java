package dev.javaharness.core;

import dev.javaharness.spi.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/** Single-process coordinator. The gate orders submit, output, cancel and final settlement. */
public final class DefaultAgentEngine implements AgentEngine {
    private final SessionStore store;
    private final ModelProvider model;
    private final Duration timeout;
    private final ConversationContext context;
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
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        this.store = store;
        this.model = model;
        this.timeout = timeout;
        this.context = new ConversationContext(maxContextCharacters);
        store.recoverInterruptedRuns();
    }

    @Override
    public synchronized SessionStore.Submission submit(String sessionId, String requestId,
                                                       String prompt, String scenario) {
        if (closed) throw new HarnessException("UNAVAILABLE", "服务正在停止");
        var submission = store.submit(sessionId, requestId, prompt, scenario, model.info());
        if (!submission.created()) return submission;
        var task = new Task();
        tasks.put(submission.run().id(), task);
        try {
            workers.execute(() -> execute(submission.run(), task));
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

    private void execute(Run run, Task task) {
        ScheduledFuture<?> deadline = null;
        String failure = null;
        try {
            ModelRequest request;
            synchronized (this) {
                task.thread = Thread.currentThread();
                if (task.stopReason != null) return;
                store.transition(run.id(), RunStatus.RUNNING, "");
                deadline = timer.schedule(() -> requestStop(run.id(), task, RunStatus.TIMED_OUT),
                        timeout.toMillis(), TimeUnit.MILLISECONDS);
                request = context.build(run, store.contextHistory(run.id()));
                store.saveModelRequest(request);
            }
            model.stream(request, () -> {
                synchronized (this) {
                    if (task.stopReason != null || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("run stopped");
                    }
                }
            }, text -> {
                synchronized (this) {
                    if (task.stopReason == null && !Thread.currentThread().isInterrupted()) {
                        store.appendText(run.id(), text);
                    }
                }
            });
        } catch (InterruptedException error) {
            failure = "执行被中断";
        } catch (ModelException error) {
            failure = error.getMessage();
        } catch (Exception error) {
            failure = "模型执行失败";
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Run " + run.id() + " failed", error);
        } finally {
            if (deadline != null) deadline.cancel(false);
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
                    store.transition(run.id(), outcome, detail);
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
