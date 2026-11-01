package dev.javaharness.server.service;

import dev.javaharness.adapters.group.GroupStore;
import dev.javaharness.adapters.group.GroupStore.Group;
import dev.javaharness.adapters.group.GroupStore.Member;
import dev.javaharness.adapters.group.GroupStore.Message;
import dev.javaharness.core.TokenBudget;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ContextSummary;
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
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 群聊协作调度：一条用户消息开启一轮，被 @ 的成员优先发言，否则由隐藏主持人安排下一批（可并行）成员，
 * 直到主持人结束、达到发言上限或用户停止。成员每次发言都是其隐藏会话中的一次普通运行。
 * 锁顺序为调度器 → 引擎 → 会话存储，存储锁内不回调调度器。
 */
public final class GroupOrchestrator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GroupOrchestrator.class);

    private final GroupStore groups;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final AgentEngine engine;
    private final RunConfigurationSource configurations;
    private final ExecutorService planners = Executors.newVirtualThreadPerTaskExecutor();
    // Active rounds by group, and the group of every member run still speaking.
    private final Map<String, Active> active = new HashMap<>();
    private final Map<String, String> runGroups = new HashMap<>();
    private boolean closed;

    /** In-memory progress of a group's current round. */
    private static final class Active {
        final String groupId;
        String roundId;
        int turns;
        final Map<String, Speaker> running = new LinkedHashMap<>();
        final Deque<Turn> queued = new ArrayDeque<>();
        // A host call in flight; a newer token marks its result stale.
        boolean planning;
        Thread planner;
        long planToken;
        boolean stopping;

        Active(String groupId) {
            this.groupId = groupId;
        }
    }

    private record Turn(String memberId, String prompt) {}

    /** A speaking member and the round it was asked in; replies to a superseded round start no new turns. */
    private record Speaker(Member member, String roundId) {}

    public GroupOrchestrator(
            GroupStore groups,
            SessionStore sessions,
            TransactionTemplate tx,
            AgentEngine engine,
            RunConfigurationSource configurations) {
        this.groups = groups;
        this.sessions = sessions;
        this.tx = tx;
        this.engine = engine;
        this.configurations = configurations;
        // The engine already marked the member runs of a previous process as interrupted.
        update(groups::interruptRounds);
    }

    public synchronized boolean busy(String groupId) {
        return active.containsKey(groupId);
    }

    /** Post a user message and start a round; a retried request id returns the original message. */
    public synchronized Message post(String groupId, String requestId, String content) {
        if (closed) throw new HarnessException("UNAVAILABLE", "服务正在停止");
        try {
            if (!UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException();
        } catch (Exception error) {
            throw new HarnessException("INVALID", "requestId 必须是标准 UUID");
        }
        String text = content == null ? "" : content.strip();
        if (text.isEmpty() || text.length() > 4000) throw new HarnessException("INVALID", "消息需为 1–4000 个字符");
        Message existing = groups.messageByRequest(groupId, requestId);
        if (existing != null) return existing;
        groups.get(groupId);
        var members = enabled(groupId);
        if (members.isEmpty()) throw new HarnessException("INVALID", "请先添加并启用至少一位成员");
        Active current = active.get(groupId);
        if (current != null && current.stopping) throw new HarnessException("BUSY", "正在停止本轮协作，请稍后再发送");
        Active round = current == null ? new Active(groupId) : current;
        Message message = write(() -> {
            if (current != null) groups.updateRound(current.roundId, "SUPERSEDED", current.turns, "用户发送了新消息");
            String roundId = groups.startRound(groupId).id();
            return groups.addMessage(groupId, requestId, roundId, "USER", null, "用户", text, null);
        });
        // Only a committed round becomes the in-memory one.
        round.roundId = message.roundId();
        // Members still speaking finish; the old plan is dropped and the new message is planned afresh.
        round.turns = 0;
        round.queued.clear();
        abandonPlan(round);
        active.put(groupId, round);
        for (Member member : GroupPrompts.mentions(text, members))
            round.queued.add(new Turn(member.id(), GroupPrompts.mentionedByUser()));
        advance(round);
        return message;
    }

    /** Stop the round: queued turns are dropped and speaking members are cancelled. */
    public synchronized void stop(String groupId) {
        Active round = active.get(groupId);
        if (round == null) return;
        round.stopping = true;
        round.queued.clear();
        abandonPlan(round);
        for (String runId : List.copyOf(round.running.keySet())) {
            try {
                engine.cancel(runId);
            } catch (HarnessException error) {
                // The engine no longer holds an active run only when its settlement failed; stop waiting for it.
                log.warn("Group {} stops waiting for run {}", groupId, runId, error);
                round.running.remove(runId);
                runGroups.remove(runId);
            } catch (RuntimeException error) {
                log.warn("Group {} could not cancel run {}", groupId, runId, error);
            }
        }
        advance(round);
    }

    /** A member run reached its final status: publish its reply, queue mentioned members, then continue. */
    public synchronized void settled(Run run) {
        String groupId = runGroups.remove(run.id());
        Active round = groupId == null ? null : active.get(groupId);
        if (round == null) return;
        Speaker speaker = round.running.remove(run.id());
        if (speaker == null) return;
        Member member = speaker.member();
        try {
            if (run.status() == RunStatus.COMPLETED) {
                String reply = sessions.conversation(run.id()).getLast().content();
                if (GroupPrompts.silent(reply)) {
                    update(() -> groups.touch(groupId));
                } else {
                    update(() -> groups.addMessage(
                            groupId,
                            null,
                            speaker.roundId(),
                            "MEMBER",
                            member.id(),
                            member.name(),
                            reply.strip(),
                            run.id()));
                    if (!round.stopping && speaker.roundId().equals(round.roundId))
                        for (Member mentioned : GroupPrompts.mentions(reply, enabled(groupId)))
                            if (!mentioned.id().equals(member.id()))
                                round.queued.add(new Turn(mentioned.id(), GroupPrompts.mentionedBy(member.name())));
                }
            } else {
                String detail = run.detail().isBlank() ? "" : "：" + run.detail();
                update(() -> groups.addMessage(
                        groupId,
                        null,
                        speaker.roundId(),
                        "SYSTEM",
                        member.id(),
                        member.name(),
                        member.name() + " 未完成发言（" + label(run.status()) + detail + "）",
                        run.id()));
            }
        } catch (RuntimeException error) {
            log.warn("Group {} could not record run {}", groupId, run.id(), error);
        }
        advance(round);
    }

    private static String label(RunStatus status) {
        return switch (status) {
            case CANCELLED -> "已停止";
            case TIMED_OUT -> "超时";
            case INTERRUPTED -> "已中断";
            default -> "失败";
        };
    }

    /** Move the round forward; an unexpected failure ends the round instead of leaving it busy forever. */
    private void advance(Active round) {
        try {
            step(round);
        } catch (RuntimeException error) {
            log.warn("Group {} round {} failed", round.groupId, round.roundId, error);
            for (String runId : round.running.keySet()) {
                runGroups.remove(runId);
                try {
                    engine.cancel(runId);
                } catch (RuntimeException ignored) {
                    // Best effort: the round is already failing.
                }
            }
            round.running.clear();
            finish(round, "FAILED", "协作调度出错，请重试");
        }
    }

    /** Wait for speakers and plans, launch queued turns, else ask the host. */
    private void step(Active round) {
        // After close, rounds stay running in the database and end as interrupted on the next start.
        if (closed) return;
        if (active.get(round.groupId) != round || !round.running.isEmpty() || round.planning) return;
        if (round.stopping) {
            finish(round, "STOPPED", "已停止");
            return;
        }
        int maxTurns;
        try {
            maxTurns = groups.get(round.groupId).maxTurns();
        } catch (RuntimeException error) {
            finish(round, "FAILED", "群聊不存在");
            return;
        }
        while (round.running.isEmpty()) {
            if (round.turns >= maxTurns) {
                finish(round, "COMPLETED", "已达到本轮发言上限 " + maxTurns + " 次");
                return;
            }
            if (round.queued.isEmpty()) {
                plan(round, maxTurns);
                return;
            }
            launchBatch(round, maxTurns);
        }
    }

    /** Launch up to the parallel limit of distinct queued members; members that cannot speak are reported. */
    private void launchBatch(Active round, int maxTurns) {
        var batch = new LinkedHashMap<String, Turn>();
        while (!round.queued.isEmpty()
                && batch.size() < GroupPrompts.MAX_PARALLEL
                && round.turns + batch.size() < maxTurns) {
            Turn turn = round.queued.poll();
            batch.putIfAbsent(turn.memberId(), turn);
        }
        for (Turn turn : batch.values()) {
            Member member;
            try {
                member = groups.member(turn.memberId());
            } catch (HarnessException error) {
                continue;
            }
            if (!member.enabled()) continue;
            round.turns++;
            try {
                Run run = engine.submit(
                                member.sessionId(),
                                UUID.randomUUID().toString(),
                                turn.prompt(),
                                "NORMAL",
                                new ModelSelection(member.modelProfileId(), member.reasoningEffort()),
                                ApprovalMode.valueOf(member.approvalMode()))
                        .run();
                round.running.put(run.id(), new Speaker(member, round.roundId));
                runGroups.put(run.id(), round.groupId);
                update(() -> groups.updateRound(round.roundId, "RUNNING", round.turns, ""));
            } catch (RuntimeException error) {
                String reason = error instanceof HarnessException value ? value.getMessage() : "提交运行失败";
                if (!(error instanceof HarnessException))
                    log.warn("Group {} member submission failed", round.groupId, error);
                update(() -> groups.addMessage(
                        round.groupId,
                        null,
                        round.roundId,
                        "SYSTEM",
                        member.id(),
                        member.name(),
                        member.name() + " 无法发言：" + reason,
                        null));
            }
        }
    }

    /** Ask the host on a planner thread; a stale result (newer message or stop) is discarded. */
    private void plan(Active round, int maxTurns) {
        long token = ++round.planToken;
        List<Member> members;
        List<Message> messages;
        RunConfiguration configuration;
        try {
            Group group = groups.get(round.groupId);
            members = enabled(round.groupId);
            messages = groups.messages(round.groupId);
            // Resolved here so interrupting the planner can only hit the model call, never a database read.
            configuration =
                    configurations.snapshot(new ModelSelection(group.hostProfileId(), group.hostReasoningEffort()));
        } catch (HarnessException error) {
            finish(round, "FAILED", error.getMessage());
            return;
        }
        if (members.isEmpty()) {
            finish(round, "COMPLETED", "没有可发言的成员");
            return;
        }
        int turns = round.turns;
        round.planning = true;
        try {
            planners.execute(() -> {
                synchronized (this) {
                    if (round.planToken != token) return;
                    round.planner = Thread.currentThread();
                }
                GroupPrompts.Plan result = null;
                String failure = null;
                try {
                    result = host(configuration, members, messages, turns, maxTurns);
                } catch (InterruptedException error) {
                    failure = "主持人调度已取消";
                } catch (HarnessException | IllegalArgumentException error) {
                    failure = error.getMessage();
                } catch (Exception error) {
                    failure = "主持人调度失败";
                    log.warn("Group {} host planning failed", round.groupId, error);
                }
                synchronized (this) {
                    Thread.interrupted();
                    if (round.planToken != token) return;
                    round.planning = false;
                    round.planner = null;
                    if (failure != null) finish(round, "FAILED", failure);
                    else if (result.end() || result.speakers().isEmpty()) finish(round, "COMPLETED", "");
                    else {
                        for (var speaker : result.speakers())
                            round.queued.add(new Turn(speaker.member().id(), GroupPrompts.assigned(speaker.task())));
                        advance(round);
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            round.planning = false;
            finish(round, "FAILED", "服务正在停止");
        }
    }

    /** Invalidate an in-flight host call and interrupt it. */
    private void abandonPlan(Active round) {
        round.planToken++;
        round.planning = false;
        Thread planner = round.planner;
        round.planner = null;
        if (planner != null && planner != Thread.currentThread()) planner.interrupt();
    }

    /** One tool-free host request with the host model; it must answer within the profile's run timeout. */
    private GroupPrompts.Plan host(
            RunConfiguration configuration, List<Member> members, List<Message> messages, int turns, int maxTurns)
            throws Exception {
        if (!configuration.modelInfo().configured()) throw new HarnessException("INVALID", "主持人模型未配置完成，请在群设置中选择可用的模型");
        long deadline = System.nanoTime() + configuration.timeout().toNanos();
        ModelProvider.Cancellation cancellation = () -> {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new InterruptedException("host stopped");
        };
        try (RunConfiguration.Resources resources = configuration.resources().open("")) {
            TokenCounter counter = configuration.tokenCounter();
            int window = configuration.contextWindowTokens();
            String instructions = GroupPrompts.hostInstructions(members);
            int budget =
                    window - configuration.maxOutputTokens() - counter.count(instructions) - TokenBudget.safety(window);
            if (budget < 256) throw new HarnessException("INVALID", "主持人模型的上下文窗口过小");
            String prompt = GroupPrompts.hostPrompt(messages, turns, maxTurns, counter, budget);
            var message = new ModelMessage("user", prompt);
            var request = new ModelRequest(
                    UUID.randomUUID().toString(),
                    configuration.modelInfo().model(),
                    "NORMAL",
                    List.of(message),
                    new ContextSummary(0, 0, 0, prompt.length(), 0, new TokenBudget(counter).message(message), window),
                    1,
                    "",
                    List.of(),
                    instructions,
                    false,
                    configuration.selection().reasoningEffort());
            String text = resources
                    .model()
                    .complete(request, cancellation, chunk -> {})
                    .text();
            cancellation.check();
            return GroupPrompts.parse(text, members);
        }
    }

    private void finish(Active round, String status, String detail) {
        if (active.get(round.groupId) == round) active.remove(round.groupId);
        abandonPlan(round);
        try {
            update(() -> groups.updateRound(round.roundId, status, round.turns, detail));
        } catch (RuntimeException error) {
            log.warn("Group {} could not finish round {}", round.groupId, round.roundId, error);
        }
    }

    private List<Member> enabled(String groupId) {
        return groups.members(groupId).stream().filter(Member::enabled).toList();
    }

    private void update(Runnable change) {
        write(() -> {
            change.run();
            return null;
        });
    }

    /** Group writes share the session store's coordination lock and one transaction. */
    private <T> T write(Supplier<T> change) {
        synchronized (sessions) {
            return tx.execute(status -> change.get());
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            for (Active round : active.values()) abandonPlan(round);
        }
        planners.shutdownNow();
    }
}
