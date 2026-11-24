package dev.javaharness.core;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.memory.LongTermMemory;
import dev.javaharness.spi.model.ContextSummary;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Package-private coordinator for delegating, executing, stopping, and reporting subagent runs. */
final class SubagentRunner {
    // A subagent needs room for a few model steps and its own final answer.
    private static final long MIN_SUBAGENT_NANOS = Duration.ofSeconds(15).toNanos();
    private static final int MAX_SUBAGENT_RESULT = 60_000;

    interface Control {
        void requestStop(String runId, DefaultAgentEngine.Task task, RunStatus reason);

        ModelProvider.Cancellation cancellation(DefaultAgentEngine.Task task);

        void settle(String runId, DefaultAgentEngine.Task task, String failure, AgentLoopRunner.Outcome outcome);
    }

    @FunctionalInterface
    interface LoopExecutor {
        AgentLoopRunner.Outcome run(AgentLoopRunner.Loop loop) throws Exception;
    }

    private final Object lock;
    private final SessionStore store;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final Map<String, DefaultAgentEngine.Task> tasks;
    private final Control control;

    SubagentRunner(
            Object lock,
            SessionStore store,
            ExecutorService workers,
            ScheduledExecutorService timer,
            Map<String, DefaultAgentEngine.Task> tasks,
            Control control) {
        this.lock = lock;
        this.store = store;
        this.workers = workers;
        this.timer = timer;
        this.tasks = tasks;
        this.control = control;
    }

    /**
     * Start one subagent run for a delegate_task call. Its deadline ends where the parent's final reserve begins;
     * invalid arguments, a short remaining budget or a stopped parent complete at once with a failed result.
     */
    Future<WorkspaceTools.Result> delegate(
            AgentLoopRunner.Loop parent, ToolCall call, long childDeadlineNanos, LoopExecutor executor)
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
        var task = new DefaultAgentEngine.Task();
        Run child;
        synchronized (lock) {
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
                    () -> control.requestStop(childId, task, RunStatus.TIMED_OUT), remaining, TimeUnit.NANOSECONDS);
            ScheduledFuture<?> timed = deadline;
            return workers.submit(() ->
                    runSubagent(parent, child, task, request.agent(), remaining, childDeadlineNanos, timed, executor));
        } catch (RejectedExecutionException error) {
            if (deadline != null) deadline.cancel(false);
            control.settle(childId, task, "执行器不可用", null);
            return CompletableFuture.completedFuture(new WorkspaceTools.Result(false, "执行器不可用，子智能体未启动"));
        }
    }

    private WorkspaceTools.Result runSubagent(
            AgentLoopRunner.Loop parent,
            Run child,
            DefaultAgentEngine.Task task,
            String agent,
            long timeoutNanos,
            long deadlineNanos,
            ScheduledFuture<?> deadline,
            LoopExecutor executor) {
        String failure = null;
        AgentLoopRunner.Outcome outcome = null;
        try {
            synchronized (lock) {
                task.thread = Thread.currentThread();
            }
            ModelProvider.Cancellation cancellation = control.cancellation(task);
            cancellation.check();
            WorkspaceTools tools = Subagents.tools(parent.tools(), agent);
            outcome = executor.run(new AgentLoopRunner.Loop(
                    child,
                    parent.toolRunId(),
                    agent,
                    task,
                    cancellation,
                    parent.model(),
                    tools,
                    parent.configuration(),
                    parent.extensionInstructions(),
                    AgentLoopRunner.toolset(tools, parent.extensionInstructions(), agent),
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
            control.settle(child.id(), task, failure, outcome);
        }
        return report(store.run(child.id()), agent, outcome);
    }

    /** The parent sees the child's final answer, or its status and partial output, marked as subagent output. */
    private static WorkspaceTools.Result report(Run child, String agent, AgentLoopRunner.Outcome outcome) {
        if (child.status() == RunStatus.COMPLETED) {
            String answer = outcome.completed().getLast().content();
            return new WorkspaceTools.Result(
                    true,
                    "[子智能体 " + agent + " 已完成；以下为其回答，按工具结果对待]\n" + (answer.isBlank() ? "（子智能体没有给出文字回答）" : cap(answer)));
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

    int startedChildren(DefaultAgentEngine.Task task) {
        synchronized (lock) {
            return task.children.size();
        }
    }

    void stopChildren(DefaultAgentEngine.Task task) {
        synchronized (lock) {
            for (String child : task.children) {
                DefaultAgentEngine.Task value = tasks.get(child);
                if (value != null) control.requestStop(child, value, RunStatus.CANCELLED);
            }
        }
    }

    /** Wait for a child to settle even while the parent is being interrupted, so children settle before it. */
    static void awaitQuietly(Future<?> future) {
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
}
