package dev.javaharness.core;

import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.memory.LongTermMemory;
import dev.javaharness.spi.model.ContextSummary;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelTurn;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.tool.ToolInterceptor;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Package-private multi-step model and tool execution loop owned by {@link DefaultAgentEngine}.
 * All store writes synchronize on the engine monitor so cancellation and settlement stay ordered.
 */
final class AgentLoopRunner {
    /**
     * One agent's loop. Steps and tools are recorded under {@code run}; tool side effects such as proposals and
     * approvals belong to {@code toolRunId}, the root. A subagent ({@code agent} non-null) shares the root's model,
     * tools and extension instructions and cannot delegate again.
     */
    record Loop(
            Run run,
            String toolRunId,
            String agent,
            DefaultAgentEngine.Task task,
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

    record Toolset(List<ToolDefinition> definitions, boolean spill, boolean delegate, String instructions) {}

    record Outcome(List<ModelMessage> completed, String detail) {}

    private final Object lock;
    private final SessionStore store;
    private final LongTermMemory memories;
    private final List<ToolInterceptor> interceptors;
    private final SubagentRunner subagents;

    AgentLoopRunner(
            Object lock,
            SessionStore store,
            LongTermMemory memories,
            List<ToolInterceptor> interceptors,
            SubagentRunner subagents) {
        this.lock = lock;
        this.store = store;
        this.memories = memories;
        this.interceptors = interceptors;
        this.subagents = subagents;
    }

    /** History gets what remains after output, tool schemas, instructions and a safety margin. */
    static int historyBudget(RunConfiguration configuration, Toolset toolset) {
        TokenCounter counter = configuration.tokenCounter();
        int contextWindowTokens = configuration.contextWindowTokens();
        return contextWindowTokens
                - configuration.maxOutputTokens()
                - new TokenBudget(counter).tools(toolset.definitions())
                - counter.count(toolset.instructions())
                - TokenBudget.safety(contextWindowTokens);
    }

    /**
     * The engine serves read_tool_output itself unless a workspace tool already owns the name or one more tool would
     * exceed the provider limit; without it, outputs reach the model whole. A root also gets delegate_task.
     */
    static Toolset toolset(WorkspaceTools tools, String extensionInstructions, String agent) {
        return toolset(tools, extensionInstructions, agent, true, true);
    }

    static Toolset toolset(
            WorkspaceTools tools,
            String extensionInstructions,
            String agent,
            boolean allowSubagents,
            boolean useDefaultGuidance) {
        boolean toolEnabled = !tools.definitions().isEmpty();
        var definitions = new ArrayList<>(tools.definitions());
        boolean spill = toolEnabled
                && definitions.size() < ToolOutputs.MAX_REQUEST_TOOLS
                && definitions.stream().noneMatch(tool -> tool.name().equals(ToolOutputs.READ));
        if (spill) definitions.add(ToolOutputs.DEFINITION);
        boolean delegate = toolEnabled
                && allowSubagents
                && agent == null
                && definitions.size() < ToolOutputs.MAX_REQUEST_TOOLS
                && definitions.stream().noneMatch(tool -> tool.name().equals(Subagents.DELEGATE));
        if (delegate) definitions.add(Subagents.DEFINITION);
        String guidance = !toolEnabled || !useDefaultGuidance
                ? ""
                : agent != null
                        ? AgentInstructions.forSubagent(agent)
                        : AgentInstructions.forRequest() + (delegate ? AgentInstructions.forDelegation() : "");
        return new Toolset(List.copyOf(definitions), spill, delegate, guidance + extensionInstructions);
    }

    Outcome run(Loop loop) throws Exception {
        Run run = loop.run();
        String runId = run.id();
        DefaultAgentEngine.Task task = loop.task();
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
        long finalReserveNanos = toolEnabled ? Math.min(Duration.ofSeconds(30).toNanos(), loop.timeoutNanos() / 5) : 0;
        String finalInstructions = loop.agent() == null ? loop.extensionInstructions() : toolset.instructions();
        Consumer<String> onText = text -> {
            synchronized (lock) {
                if (task.stopReason == null && !Thread.currentThread().isInterrupted()) {
                    store.appendText(runId, text);
                }
            }
        };
        var messages = new ArrayList<>(loop.messages());
        int historySize = messages.size() - 1;
        var callIds = new HashSet<String>();
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
            synchronized (lock) {
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
                synchronized (lock) {
                    if (task.stopReason == null && !Thread.currentThread().isInterrupted()) {
                        for (String chunk : chunks(reasoning)) store.appendReasoning(runId, currentStep, chunk);
                    }
                }
                reasoning.setLength(0);
                flushedAt[0] = System.nanoTime();
            };
            Consumer<String> onReasoning = text -> {
                reasoning.append(text);
                if (reasoning.length() >= 2048 || System.nanoTime() - flushedAt[0] >= 300_000_000L)
                    flushReasoning.run();
            };
            ModelTurn turn = loop.model().complete(next, cancellation, onText, onReasoning);
            // A failed stream may lose its last unflushed slice; reasoning is display-only.
            flushReasoning.run();
            cancellation.check();
            if (turn.usage() != null)
                synchronized (lock) {
                    cancellation.check();
                    store.recordUsage(runId, step, turn.usage());
                }
            messages.add(new ModelMessage("assistant", turn.text(), turn.calls(), null));
            if (turn.calls().isEmpty()) {
                String detail =
                        !finalRequest ? "" : stalled ? "检测到重复的工具调用且结果无变化，已停止工具并生成收尾回答" : "时间预算不足，已跳过剩余工具并生成收尾回答";
                var completed = new ArrayList<>(messages.subList(historySize, messages.size()));
                // The conversation keeps the attached context and original prompt; recalled memory and images
                // stay only in the request, the context's image placeholders standing in for the latter.
                completed.set(0, new ModelMessage("user", loop.attached() + run.prompt()));
                return new Outcome(List.copyOf(completed), detail);
            }
            if (finalRequest) {
                synchronized (lock) {
                    cancellation.check();
                    store.beginTools(runId, step, turn.calls());
                }
                for (ToolCall call : turn.calls())
                    synchronized (lock) {
                        cancellation.check();
                        store.updateTool(runId, call.id(), "SKIPPED", "收尾请求不执行工具");
                    }
                throw new ModelException("收尾请求仍返回工具调用；请检查服务的工具协议兼容性");
            }
            if (tools.definitions().isEmpty()) throw new ModelException("未配置工作区，无法执行工具");
            if (turn.calls().size() > ToolCall.MAX_BATCH_SIZE) throw new ModelException("单次模型响应返回的工具调用超过解析上限");
            for (ToolCall call : turn.calls()) if (!callIds.add(call.id())) throw new ModelException("模型返回重复的工具调用 ID");
            synchronized (lock) {
                cancellation.check();
                store.beginTools(runId, step, turn.calls());
            }
            // Inside the final reserve, remaining calls are skipped and the next request asks for an answer.
            boolean timeExhausted = toolEnabled && deadlineNanos - System.nanoTime() <= finalReserveNanos;
            // Duplicates inside one response count once, so stopping always follows an earlier warning.
            var stepFingerprints = new HashSet<String>();
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
                                            ? "本模型配置单次最多执行 " + maxToolsPerRequest
                                                    + " 个工具，本调用未执行。请先使用已完成的结果；若仍需此工具，请在下一次响应中重新请求。"
                                            : toolset.delegate()
                                                            && call.name().equals(Subagents.DELEGATE)
                                                            && subagents.startedChildren(task) - childrenBefore
                                                                    >= Subagents.MAX_PARALLEL
                                                    ? "单次响应最多并行 " + Subagents.MAX_PARALLEL
                                                            + " 个子智能体，本调用未执行；需要时请在下一次响应中重新请求。"
                                                    : null;
                    if (skipped != null) {
                        synchronized (lock) {
                            cancellation.check();
                            store.updateTool(runId, call.id(), "SKIPPED", skipped);
                        }
                        results[index] = skipped;
                        continue;
                    }
                    synchronized (lock) {
                        cancellation.check();
                        store.updateTool(runId, call.id(), "RUNNING", "");
                    }
                    // A refused call is recorded as started and failed, so the timeline shows when it was stopped.
                    String refused = refusal(runId, call);
                    if (refused != null) {
                        synchronized (lock) {
                            cancellation.check();
                            store.updateTool(runId, call.id(), "FAILED", refused);
                        }
                        results[index] = refused;
                        continue;
                    }
                    if (toolset.delegate() && call.name().equals(Subagents.DELEGATE)) {
                        delegations.put(
                                index, subagents.delegate(loop, call, deadlineNanos - finalReserveNanos, this::run));
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
                            runId,
                            call,
                            result,
                            spill,
                            stepFingerprints,
                            repeats,
                            results,
                            delegation.getKey(),
                            cancellation)) stalled = true;
                }
            } finally {
                if (delegations.values().stream().anyMatch(future -> !future.isDone())) subagents.stopChildren(task);
                for (var future : delegations.values()) SubagentRunner.awaitQuietly(future);
            }
            // Children may run into the final reserve; then the next request must be the tool-free answer.
            if (!delegations.isEmpty() && deadlineNanos - System.nanoTime() <= finalReserveNanos) timeExhausted = true;
            for (int index = 0; index < turn.calls().size(); index++)
                messages.add(new ModelMessage(
                        "tool",
                        results[index],
                        List.of(),
                        turn.calls().get(index).id()));
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
            Set<String> stepFingerprints,
            Map<String, Integer> repeats,
            String[] results,
            int index,
            ModelProvider.Cancellation cancellation)
            throws InterruptedException {
        for (var interceptor : interceptors) result = interceptor.after(runId, call, result);
        synchronized (lock) {
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
