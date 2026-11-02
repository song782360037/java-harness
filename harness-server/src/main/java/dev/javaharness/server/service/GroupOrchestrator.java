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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 群聊协作调度：一条用户消息开启一轮。讨论模式下被 @ 的成员优先发言，否则由隐藏主持人安排下一批（可并行）成员，
 * 直到主持人结束、达到发言上限或用户停止；任务模式下主持人先把需求拆成任务 DAG，依赖完成的任务并行执行，
 * 失败的任务重试一次，仍失败则跳过其下游。成员每次发言都是其隐藏会话中的一次普通运行。
 * 锁顺序为调度器 → 引擎 → 会话存储，存储锁内不回调调度器。
 */
public final class GroupOrchestrator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GroupOrchestrator.class);
    private static final int MAX_ATTEMPTS = 2;
    // Task statuses that end a task and every task depending on it.
    private static final Set<String> UNFINISHED = Set.of("FAILED", "SKIPPED", "CANCELLED", "INTERRUPTED");

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
        // A TASKS round runs its plan instead of the speaker queue; planned becomes true once the plan is stored.
        boolean taskMode;
        boolean planned;
        // The turn limit cancelled tasks of this round; kept until the round ends to explain why.
        boolean limited;
        final List<TaskState> tasks = new ArrayList<>();
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

    /**
     * A speaking member, the round it was asked in and the task it works on (null when discussing); replies to a
     * superseded round start no new turns.
     */
    private record Speaker(Member member, String roundId, TaskState task) {}

    /** A planned task; status mirrors its stored row, result is the final reply of its last attempt. */
    private static final class TaskState {
        final String id;
        final String key;
        final String title;
        final String instruction;
        final String memberId;
        final String memberName;
        final List<String> dependsOn;
        String status;
        int attempts;
        String runId;
        String result = "";

        TaskState(GroupStore.Task row) {
            id = row.id();
            key = row.key();
            title = row.title();
            instruction = row.instruction();
            memberId = row.memberId();
            memberName = row.memberName();
            dependsOn = row.dependsOn();
            status = row.status();
            attempts = row.attempts();
            runId = row.runId();
        }
    }

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
        boolean taskMode = "TASKS".equals(groups.get(groupId).mode());
        var members = enabled(groupId);
        if (members.isEmpty()) throw new HarnessException("INVALID", "请先添加并启用至少一位成员");
        Active current = active.get(groupId);
        if (current != null && current.stopping) throw new HarnessException("BUSY", "正在停止本轮协作，请稍后再发送");
        Active round = current == null ? new Active(groupId) : current;
        var mentioned = GroupPrompts.mentions(text, members);
        var rows = new ArrayList<GroupStore.Task>();
        Message message = write(() -> {
            if (current != null) groups.updateRound(current.roundId, "SUPERSEDED", current.turns, "用户发送了新消息");
            String roundId = groups.startRound(groupId).id();
            Message added = groups.addMessage(groupId, requestId, roundId, "USER", null, "用户", text, null);
            // In task mode the mentioned members answer directly, in parallel, without a host plan.
            if (taskMode)
                for (Member member : mentioned)
                    rows.add(groups.addTask(
                            groupId,
                            roundId,
                            rows.size(),
                            "m" + (rows.size() + 1),
                            "回应用户",
                            GroupPrompts.mentionedByUser(),
                            member.id(),
                            member.name(),
                            List.of()));
            return added;
        });
        // Cancelled only after the commit, so a failed post leaves the old plan intact in memory and storage.
        if (current != null) {
            try {
                cancelPending(current, "用户发送了新消息");
            } catch (RuntimeException error) {
                log.warn("Group {} could not cancel the superseded plan", groupId, error);
            }
        }
        // Only a committed round becomes the in-memory one.
        round.roundId = message.roundId();
        // Members still speaking finish; the old plan is dropped and the new message is planned afresh.
        round.turns = 0;
        round.queued.clear();
        round.taskMode = taskMode;
        round.limited = false;
        round.tasks.clear();
        for (GroupStore.Task row : rows) round.tasks.add(new TaskState(row));
        round.planned = !rows.isEmpty();
        abandonPlan(round);
        active.put(groupId, round);
        if (!taskMode)
            for (Member member : mentioned) round.queued.add(new Turn(member.id(), GroupPrompts.mentionedByUser()));
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
                Speaker speaker = round.running.remove(runId);
                runGroups.remove(runId);
                try {
                    if (speaker.task() != null) saveTask(speaker.task(), "CANCELLED", "已停止");
                } catch (RuntimeException ignored) {
                    // The round still has to stop; restart recovery interrupts the task row.
                }
            } catch (RuntimeException error) {
                log.warn("Group {} could not cancel run {}", groupId, runId, error);
            }
        }
        advance(round);
    }

    /**
     * A member run reached its final status: publish its reply, then queue mentioned members (discussing) or settle
     * its task (task mode), and continue.
     */
    public synchronized void settled(Run run) {
        String groupId = runGroups.remove(run.id());
        Active round = groupId == null ? null : active.get(groupId);
        if (round == null) return;
        Speaker speaker = round.running.remove(run.id());
        if (speaker == null) return;
        Member member = speaker.member();
        TaskState task = speaker.task();
        // Only the current, unstopped round reacts to a reply; superseded tasks just record their outcome.
        boolean current = !round.stopping && speaker.roundId().equals(round.roundId);
        try {
            if (run.status() == RunStatus.COMPLETED) {
                String reply = sessions.conversation(run.id()).getLast().content();
                boolean silent = GroupPrompts.silent(reply);
                if (silent) {
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
                    if (task == null && current)
                        for (Member mentioned : GroupPrompts.mentions(reply, enabled(groupId)))
                            if (!mentioned.id().equals(member.id()))
                                round.queued.add(new Turn(mentioned.id(), GroupPrompts.mentionedBy(member.name())));
                }
                if (task != null) {
                    task.result = silent ? "" : reply.strip();
                    saveTask(task, "DONE", "");
                }
            } else {
                String detail = run.detail().isBlank() ? "" : "：" + run.detail();
                String outcome = label(run.status()) + detail;
                boolean retry = task != null
                        && current
                        && run.status() != RunStatus.CANCELLED
                        && task.attempts < MAX_ATTEMPTS;
                String text = task == null
                        ? member.name() + " 未完成发言（" + outcome + "）"
                        : member.name() + " 未完成任务「" + task.title + "」（" + outcome + "）" + (retry ? "，将重试一次" : "");
                update(() -> groups.addMessage(
                        groupId, null, speaker.roundId(), "SYSTEM", member.id(), member.name(), text, run.id()));
                if (task != null)
                    saveTask(
                            task,
                            retry ? "PENDING" : run.status() == RunStatus.CANCELLED ? "CANCELLED" : "FAILED",
                            retry ? "第 " + task.attempts + " 次尝试未完成，等待重试" : clip(outcome));
            }
        } catch (RuntimeException error) {
            log.warn("Group {} could not record run {}", groupId, run.id(), error);
            // A task left running would block its downstream tasks and never settle again.
            if (task != null && task.status.equals("RUNNING")) {
                try {
                    saveTask(task, "FAILED", "记录结果失败");
                } catch (RuntimeException ignored) {
                    // Memory already moved on; restart recovery interrupts the task row.
                }
            }
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
            for (var entry : round.running.entrySet()) {
                runGroups.remove(entry.getKey());
                try {
                    engine.cancel(entry.getKey());
                } catch (RuntimeException ignored) {
                    // Best effort: the round is already failing.
                }
                if (entry.getValue().task() != null) {
                    try {
                        saveTask(entry.getValue().task(), "CANCELLED", "协作调度出错");
                    } catch (RuntimeException ignored) {
                        // Left for the restart recovery to interrupt.
                    }
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
        if (active.get(round.groupId) != round || round.planning) return;
        if (round.taskMode) {
            stepTasks(round);
            return;
        }
        if (!round.running.isEmpty()) return;
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

    /**
     * Run the plan: tasks whose upstream failed are skipped, ready tasks launch up to the parallel and turn limits,
     * and the round completes once nothing is running. Planning waits for speakers of a superseded round.
     */
    private void stepTasks(Active round) {
        if (round.stopping) {
            if (round.running.isEmpty()) finish(round, "STOPPED", "已停止");
            return;
        }
        int maxTurns;
        try {
            maxTurns = groups.get(round.groupId).maxTurns();
        } catch (RuntimeException error) {
            // Ending now would orphan running tasks; the next settlement tries again.
            if (round.running.isEmpty()) finish(round, "FAILED", "群聊不存在");
            return;
        }
        if (!round.planned) {
            if (round.running.isEmpty()) plan(round, maxTurns);
            return;
        }
        do {
            skipBlocked(round);
            if (round.turns >= maxTurns && round.tasks.stream().anyMatch(task -> task.status.equals("PENDING"))) {
                cancelPending(round, "已达到本轮发言上限");
                round.limited = true;
            }
        } while (launchReady(round, maxTurns));
        if (!round.running.isEmpty()) return;
        long unfinished =
                round.tasks.stream().filter(task -> !task.status.equals("DONE")).count();
        finish(
                round,
                "COMPLETED",
                round.limited ? "已达到本轮发言上限 " + maxTurns + " 次" : unfinished > 0 ? unfinished + " 个任务未完成" : "");
    }

    /** Pending tasks with an upstream task that will never finish are skipped, transitively. */
    private void skipBlocked(Active round) {
        var byKey = new HashMap<String, TaskState>();
        for (TaskState task : round.tasks) byKey.put(task.key, task);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TaskState task : round.tasks)
                if (task.status.equals("PENDING")
                        && task.dependsOn.stream()
                                .anyMatch(key -> UNFINISHED.contains(byKey.get(key).status))) {
                    saveTask(task, "SKIPPED", "上游任务未完成");
                    changed = true;
                }
        }
    }

    /**
     * Launch ready tasks; true when any task changed status, which may unblock or skip others. A member runs one task
     * at a time in its hidden session, so a task of a member still speaking (in any round) waits for that run.
     */
    private boolean launchReady(Active round, int maxTurns) {
        var byKey = new HashMap<String, TaskState>();
        for (TaskState task : round.tasks) byKey.put(task.key, task);
        boolean changed = false;
        for (TaskState task : round.tasks) {
            if (round.running.size() >= GroupPrompts.MAX_PARALLEL || round.turns >= maxTurns) break;
            if (!task.status.equals("PENDING")
                    || !task.dependsOn.stream()
                            .allMatch(key -> byKey.get(key).status.equals("DONE"))
                    || round.running.values().stream()
                            .anyMatch(speaker -> speaker.member().id().equals(task.memberId))) continue;
            changed = true;
            Member member;
            try {
                member = groups.member(task.memberId);
            } catch (HarnessException error) {
                member = null;
            }
            if (member == null || !member.enabled()) {
                saveTask(task, "FAILED", "成员已移除或停用");
                update(() -> groups.addMessage(
                        round.groupId,
                        null,
                        round.roundId,
                        "SYSTEM",
                        null,
                        "系统",
                        task.memberName + " 已移除或停用，无法执行任务「" + task.title + "」",
                        null));
                continue;
            }
            var upstream = task.dependsOn.stream()
                    .map(byKey::get)
                    .map(value -> new GroupPrompts.Upstream(value.title, value.memberName, value.result))
                    .toList();
            round.turns++;
            task.attempts++;
            Member speaker = member;
            try {
                Run run = engine.submit(
                                member.sessionId(),
                                UUID.randomUUID().toString(),
                                GroupPrompts.taskPrompt(task.title, task.instruction, upstream),
                                "NORMAL",
                                new ModelSelection(member.modelProfileId(), member.reasoningEffort()),
                                ApprovalMode.valueOf(member.approvalMode()))
                        .run();
                round.running.put(run.id(), new Speaker(member, round.roundId, task));
                runGroups.put(run.id(), round.groupId);
                task.runId = run.id();
                saveTask(task, "RUNNING", "");
                update(() -> groups.updateRound(round.roundId, "RUNNING", round.turns, ""));
            } catch (RuntimeException error) {
                String reason = error instanceof HarnessException value ? value.getMessage() : "提交运行失败";
                if (!(error instanceof HarnessException))
                    log.warn("Group {} task submission failed", round.groupId, error);
                saveTask(task, "FAILED", clip(reason));
                update(() -> groups.addMessage(
                        round.groupId,
                        null,
                        round.roundId,
                        "SYSTEM",
                        speaker.id(),
                        speaker.name(),
                        speaker.name() + " 无法执行任务「" + task.title + "」：" + reason,
                        null));
            }
        }
        return changed;
    }

    /** Pending tasks of the round will not run; running ones settle on their own. */
    private void cancelPending(Active round, String detail) {
        for (TaskState task : round.tasks)
            if (task.status.equals("PENDING")) saveTask(task, "CANCELLED", detail);
    }

    /** Record a task's new status; memory changes first so scheduling continues even when the write fails. */
    private void saveTask(TaskState task, String status, String detail) {
        task.status = status;
        update(() -> groups.updateTask(task.id, status, task.attempts, task.runId, detail));
    }

    /** Fits the task detail column. */
    private static String clip(String text) {
        return GroupPrompts.clip(text, 499);
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
                round.running.put(run.id(), new Speaker(member, round.roundId, null));
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
        boolean taskMode = round.taskMode;
        round.planning = true;
        try {
            planners.execute(() -> {
                synchronized (this) {
                    if (round.planToken != token) return;
                    round.planner = Thread.currentThread();
                }
                GroupPrompts.Plan result = null;
                GroupPrompts.TaskPlan tasks = null;
                String failure = null;
                try {
                    if (taskMode)
                        tasks = GroupPrompts.parseTasks(
                                host(configuration, GroupPrompts.taskInstructions(members), messages, GroupPrompts.taskRequest()),
                                members);
                    else
                        result = GroupPrompts.parse(
                                host(
                                        configuration,
                                        GroupPrompts.hostInstructions(members),
                                        messages,
                                        GroupPrompts.progress(turns, maxTurns)),
                                members);
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
                    else if (tasks != null) accept(round, tasks);
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

    /** Store a task plan and start it; a host question ends the round so the user can answer. */
    private void accept(Active round, GroupPrompts.TaskPlan plan) {
        try {
            if (!plan.question().isEmpty()) {
                update(() -> groups.addMessage(
                        round.groupId, null, round.roundId, "SYSTEM", null, "系统", "主持人需要你补充：" + plan.question(), null));
                finish(round, "COMPLETED", "");
                return;
            }
            var rows = new ArrayList<GroupStore.Task>();
            write(() -> {
                for (var task : plan.tasks())
                    rows.add(groups.addTask(
                            round.groupId,
                            round.roundId,
                            rows.size(),
                            task.key(),
                            task.title(),
                            task.instruction(),
                            task.member().id(),
                            task.member().name(),
                            task.dependsOn()));
                return null;
            });
            for (GroupStore.Task row : rows) round.tasks.add(new TaskState(row));
            round.planned = true;
        } catch (RuntimeException error) {
            log.warn("Group {} could not store the plan of round {}", round.groupId, round.roundId, error);
            finish(round, "FAILED", "保存任务计划失败");
            return;
        }
        advance(round);
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
    private String host(RunConfiguration configuration, String instructions, List<Message> messages, String footer)
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
            int budget =
                    window - configuration.maxOutputTokens() - counter.count(instructions) - TokenBudget.safety(window);
            if (budget < 256) throw new HarnessException("INVALID", "主持人模型的上下文窗口过小");
            String prompt = GroupPrompts.hostPrompt(messages, footer, counter, budget);
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
            return text;
        }
    }

    private void finish(Active round, String status, String detail) {
        if (active.get(round.groupId) == round) active.remove(round.groupId);
        abandonPlan(round);
        try {
            update(() -> {
                cancelPending(round, status.equals("STOPPED") ? "已停止" : "本轮已结束");
                groups.updateRound(round.roundId, status, round.turns, detail);
            });
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
