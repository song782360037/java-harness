package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.bot.BotLibraryTools;
import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.BotFile;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.adapters.bot.BotStore.Playbook;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Summary;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.adapters.settings.ModelProfile;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.run.RunExtension;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.Workspace;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bot 运行协调器：
 * 1. 维护每个 Bot 的聊天循环与回复中排队队列（Queued）；
 * 2. 严格隔离聊天与后台动手任务，通过 RunExtension 注入 BotChatTools / BotLibraryTools；
 * 3. 处理静默跨 Bot 派活与结果汇总、模型不可用自动回退、长历史滚动压缩与灵动岛动态通知。
 * 锁序：BotOrchestrator → AgentEngine → SessionStore，存储事务内不回调调度器。
 */
public final class BotOrchestrator implements BotChatTools.Actions, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BotOrchestrator.class);
    private static final int COMPACT_THRESHOLD = 36;
    private static final int COMPACT_KEEP_RECENT = 14;
    private static final int MAX_NOTICES = 30;

    public record QueuedMessage(String id, String content, Long quoteSeq, String quotePreview, Instant queuedAt) {}

    public record PostResult(boolean queued, Entry entry, QueuedMessage queuedMessage) {}

    public record IslandNotice(
            long id, String kind, String botId, String botName, String title, String detail, Instant createdAt) {}

    private record ResolvedModel(ModelSelection selection, String modelLabel, String fallbackFrom) {}

    private record ActiveChat(String botId, String runId, String modelLabel, String fallbackFrom) {}

    private final BotStore store;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final AgentEngine engine;
    private final SettingsService settings;
    private final Path libraryRoot;
    private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, ActiveChat> activeChats = new HashMap<>();
    private final Map<String, List<QueuedMessage>> queuedByBot = new HashMap<>();
    private final Map<String, String> activeTaskRuns = new HashMap<>();
    private final Deque<IslandNotice> notices = new ArrayDeque<>();
    private final AtomicLong noticeSeq = new AtomicLong();
    private final ObjectMapper json = new ObjectMapper();
    private ExternalAgentRuns externalAgents;
    private MemoryService memories;
    private BotRoutineScheduler routines;
    private BotMiniAppService miniApps;
    private volatile boolean closed;

    public void useExternalAgents(ExternalAgentRuns externalAgents) {
        this.externalAgents = externalAgents;
        if (externalAgents != null) externalAgents.onSettled(this::onExternalRunSettled);
    }

    public void useMemories(MemoryService memories) {
        this.memories = memories;
    }

    public void useRoutines(BotRoutineScheduler routines) {
        this.routines = routines;
    }

    public void useMiniApps(BotMiniAppService miniApps) {
        this.miniApps = miniApps;
    }

    private void onExternalRunSettled(Run run) {
        Task task = store.taskBySession(run.sessionId());
        if (task != null) {
            onTaskRunSettled(task.id(), run);
        }
    }

    public BotOrchestrator(
            BotStore store,
            SessionStore sessions,
            TransactionTemplate tx,
            AgentEngine engine,
            SettingsService settings,
            Path libraryRoot) {
        this.store = store;
        this.sessions = sessions;
        this.tx = tx;
        this.engine = engine;
        this.settings = settings;
        this.libraryRoot = libraryRoot.toAbsolutePath().normalize();
        write(() -> {
            store.interruptActiveTasks();
            store.ensureMeta();
            ensureMainBotLocked();
            return null;
        });
    }

    /** 确保内置主 Bot 存在；在会话存储锁与事务内调用。 */
    private Bot ensureMainBotLocked() {
        Bot existing = store.findMainBot();
        if (existing != null) return existing;
        String chatSessionId = sessions.createSession("Bot · " + BotTemplates.DEFAULT_MAIN_NAME)
                .id();
        return store.insertBot(
                "",
                BotTemplates.DEFAULT_MAIN_NAME,
                BotTemplates.DEFAULT_MAIN_ROLE,
                BotTemplates.DEFAULT_MAIN_PROMPT,
                true,
                "orb",
                "#276649",
                true,
                "",
                "",
                null,
                "",
                null,
                "",
                ApprovalMode.SMART.name(),
                chatSessionId);
    }

    /** SettingsService 扩展钩子：识别 Bot 聊天会话与后台任务会话，注入对应工具与上下文。 */
    public RunExtension decorate(Session session, RunExtension existing) {
        Bot chatBot = store.findBotByChatSession(session.id());
        if (chatBot != null) {
            return decorateChat(chatBot, existing);
        }
        Task task = store.taskBySession(session.id());
        if (task != null) {
            return decorateTask(task, existing);
        }
        return existing;
    }

    private RunExtension decorateChat(Bot bot, RunExtension existing) {
        // Chat sessions strictly never use workspace/plugin/MCP tools; close captured plugin extension cleanly.
        existing.close();
        var meta = store.ensureMeta();
        var roster = store.listBots(false);
        var playbooks = store.playbooks(bot.id());
        var routines = store.routines(bot.id());
        var summary = store.summary(bot.id());
        var recentEntries =
                summary == null ? store.entries(bot.id(), 80) : store.entriesAfter(bot.id(), summary.throughSeq());
        String workspaceName = workspaceName(bot.workspaceId());
        String instructions =
                BotPrompts.chatInstructions(bot, meta.userDisplayName(), roster, playbooks, routines, workspaceName);
        WorkspaceTools chatTools = new BotChatTools(bot.id(), this);
        return new RunExtension() {
            @Override
            public String snapshot() {
                return "{\"botChat\":true}";
            }

            @Override
            public String instructions() {
                return instructions;
            }

            @Override
            public WorkspaceTools open(String runId, WorkspaceTools local, ModelProvider.Cancellation cancellation) {
                return chatTools;
            }

            @Override
            public String context(TokenCounter counter, int maxTokens) {
                String memoryText = "";
                if (memories != null && !bot.workspaceId().isBlank()) {
                    String lastPrompt = recentEntries.isEmpty()
                            ? bot.roleSummary()
                            : recentEntries.getLast().content();
                    try {
                        memoryText = memories.recallInWorkspace(
                                        bot.workspaceId(), lastPrompt, counter, Math.min(1024, maxTokens / 4))
                                .text();
                    } catch (RuntimeException ignored) {
                        // Memory recall is optional.
                    }
                }
                return BotPrompts.chatContext(summary, recentEntries, memoryText, counter, maxTokens);
            }

            @Override
            public boolean allowSubagents() {
                return false;
            }

            @Override
            public boolean useDefaultGuidance() {
                return false;
            }

            @Override
            public void settled(Run run) {
                onChatRunSettled(bot.id(), run);
            }

            @Override
            public void close() {}
        };
    }

    private RunExtension decorateTask(Task task, RunExtension existing) {
        Bot bot = store.getBot(task.botId());
        Bot origin = task.originBotId().equals(task.botId()) ? bot : store.getBot(task.originBotId());
        Playbook playbook = task.playbookId().isBlank() ? null : findPlaybookQuietly(task.playbookId());
        String workspaceName = workspaceName(bot.workspaceId());
        String extra = BotPrompts.taskInstructions(
                bot, playbook, workspaceName, origin.name(), !task.originBotId().equals(task.botId()));
        return new RunExtension() {
            @Override
            public String snapshot() {
                return existing.snapshot();
            }

            @Override
            public String instructions() {
                String base = existing.instructions();
                return base == null || base.isBlank() ? extra : extra + "\n\n" + base;
            }

            @Override
            public void submitted(String runId) {
                existing.submitted(runId);
            }

            @Override
            public WorkspaceTools open(String runId, WorkspaceTools local, ModelProvider.Cancellation cancellation)
                    throws Exception {
                WorkspaceTools baseTools = existing.open(runId, local, cancellation);
                BotLibraryTools.AppTools appTools = miniApps == null
                        ? null
                        : new BotLibraryTools.AppTools() {
                            @Override
                            public String saveApp(
                                    String appId,
                                    String name,
                                    String description,
                                    String html,
                                    String initialDataJson) {
                                MiniApp saved = miniApps.saveApp(
                                        bot.id(), task.id(), appId, name, description, html, initialDataJson);
                                return "已保存小程序 [" + saved.name() + "](app:" + saved.id() + ")（v" + saved.version()
                                        + "），请在最终回复中保留该链接以渲染卡片。";
                            }

                            @Override
                            public String updateAppData(String appId, String dataJson) {
                                MiniApp updated = miniApps.updateAppData(appId, task.id(), dataJson);
                                return "已更新小程序 [" + updated.name() + "](app:" + updated.id() + ") 的数据（仍为 v"
                                        + updated.version() + "）。";
                            }
                        };
                return new BotLibraryTools(
                        baseTools,
                        libraryRoot,
                        (rel, size) -> {
                            write(() -> {
                                store.recordFile(bot.id(), task.id(), rel, size);
                                return null;
                            });
                        },
                        appTools);
            }

            @Override
            public String context(TokenCounter counter, int maxTokens) {
                return existing.context(counter, maxTokens);
            }

            @Override
            public void settled(Run run) {
                existing.settled(run);
                onTaskRunSettled(task.id(), run);
            }

            @Override
            public void close() {
                existing.close();
            }
        };
    }

    /** 发送消息给指定 Bot：空闲立即发起聊天轮次，正在回复时进入排队队列。 */
    public synchronized PostResult postMessage(
            String botId, String requestId, String content, Long quoteSeq, String attachmentsJson) {
        if (closed) throw new HarnessException("UNAVAILABLE", "服务正在停止");
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) throw new HarnessException("INVALID", "消息不能为空");
        if (text.length() > 20_000) throw new HarnessException("INVALID", "消息不能超过 20000 个字符");
        Bot bot = store.getBot(botId);
        if (bot.archived()) throw new HarnessException("CONFLICT", "该 Bot 已归档");

        if (requestId != null && !requestId.isBlank()) {
            Entry existing = store.entryByRequest(botId, requestId);
            if (existing != null) return new PostResult(false, existing, null);
        }

        String quotePreview = resolveQuotePreview(botId, quoteSeq);
        if (activeChats.containsKey(botId)) {
            var queued = new QueuedMessage(UUID.randomUUID().toString(), text, quoteSeq, quotePreview, Instant.now());
            queuedByBot.computeIfAbsent(botId, key -> new ArrayList<>()).add(queued);
            write(() -> {
                store.bumpBotSeq(botId);
                return null;
            });
            return new PostResult(true, null, queued);
        }

        Entry entry = write(() -> store.appendEntry(
                botId, requestId, "USER", text, quoteSeq, quotePreview, attachmentsJson, "[]", "", "", "", ""));
        startChatTurnLocked(bot, buildUserPrompt(text, quoteSeq, quotePreview));
        return new PostResult(false, entry, null);
    }

    /** 停止 Bot 当前正在进行的聊天回复并清空未发出的排队消息。 */
    public synchronized void stopChat(String botId) {
        queuedByBot.remove(botId);
        ActiveChat active = activeChats.get(botId);
        if (active != null && active.runId() != null) {
            try {
                engine.cancel(active.runId());
            } catch (RuntimeException error) {
                log.warn("Could not cancel bot chat run {}", active.runId(), error);
            }
        }
    }

    public synchronized boolean isReplying(String botId) {
        return activeChats.containsKey(botId);
    }

    public synchronized String activeChatRunId(String botId) {
        ActiveChat active = activeChats.get(botId);
        return active == null ? "" : active.runId();
    }

    public synchronized List<QueuedMessage> queuedMessages(String botId) {
        List<QueuedMessage> list = queuedByBot.get(botId);
        return list == null ? List.of() : List.copyOf(list);
    }

    public synchronized List<IslandNotice> recentNotices(long afterId) {
        return notices.stream().filter(n -> n.id() > afterId).toList();
    }

    public synchronized long latestNoticeId() {
        return noticeSeq.get();
    }

    public synchronized void pushNotice(String kind, String botId, String botName, String title, String detail) {
        long id = noticeSeq.incrementAndGet();
        notices.addLast(new IslandNotice(id, kind, botId, botName, title, detail, Instant.now()));
        while (notices.size() > MAX_NOTICES) notices.removeFirst();
        if (botId != null && !botId.isBlank()) {
            try {
                write(() -> {
                    store.bumpBotSeq(botId);
                    return null;
                });
            } catch (RuntimeException ignored) {
                // Ignore if bot was just archived.
            }
        }
    }

    @Override
    public Task startTask(
            String botId,
            String originBotId,
            String title,
            String instruction,
            String playbookId,
            String modelProfileId,
            boolean silentTargetChat) {
        return startTaskInternal(
                botId, originBotId, "", title, instruction, playbookId, modelProfileId, silentTargetChat);
    }

    public Task startRoutineTask(
            String botId,
            String routineId,
            String title,
            String instruction,
            String playbookId,
            String modelProfileId) {
        return startTaskInternal(botId, botId, routineId, title, instruction, playbookId, modelProfileId, false);
    }

    private Task startTaskInternal(
            String botId,
            String originBotId,
            String routineId,
            String title,
            String instruction,
            String playbookId,
            String modelProfileId,
            boolean silentTargetChat) {
        if (closed) throw new HarnessException("UNAVAILABLE", "服务正在停止");
        String cleanTitle =
                title == null ? "" : title.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleanTitle.isEmpty()) cleanTitle = "后台任务";
        if (cleanTitle.length() > 120) cleanTitle = cleanTitle.substring(0, 120);
        String cleanInstruction = instruction == null ? "" : instruction.strip();
        if (cleanInstruction.isEmpty()) throw new HarnessException("INVALID", "任务指令不能为空");

        Bot targetBot = store.getBot(botId);
        boolean useAcp = externalAgents != null
                && !targetBot.externalAgentId().isBlank()
                && !targetBot.workspaceId().isBlank()
                && (modelProfileId == null || modelProfileId.isBlank());
        String chosenProfile =
                modelProfileId != null && !modelProfileId.isBlank() ? modelProfileId : targetBot.taskProfileId();
        ResolvedModel resolved = useAcp
                ? new ResolvedModel(ModelSelection.DEFAULT, "ACP · " + targetBot.externalAgentId(), "")
                : resolveModel(chosenProfile, targetBot.taskReasoningEffort());
        ApprovalMode approvalMode = parseApprovalMode(targetBot.approvalMode());
        String finalTitle = cleanTitle;
        Task task = write(() -> {
            String sessionTitle = ("Bot任务 · " + targetBot.name() + " · " + finalTitle);
            if (sessionTitle.length() > 99) sessionTitle = sessionTitle.substring(0, 99);
            Session session = targetBot.workspaceId().isBlank()
                    ? sessions.createSession(sessionTitle)
                    : sessions.createSessionInWorkspace(sessionTitle, targetBot.workspaceId());
            return store.createTask(
                    targetBot.id(),
                    originBotId,
                    "",
                    routineId,
                    playbookId,
                    finalTitle,
                    cleanInstruction,
                    session.id(),
                    resolved.modelLabel(),
                    resolved.fallbackFrom(),
                    silentTargetChat);
        });

        try {
            SessionStore.Submission submission;
            if (useAcp) {
                Playbook playbook = playbookId == null || playbookId.isBlank() ? null : findPlaybookQuietly(playbookId);
                String acpPrompt = playbook == null
                        ? cleanInstruction
                        : "按做法「" + playbook.name() + "」执行：\n" + playbook.steps() + "\n\n任务指令：\n" + cleanInstruction;
                submission = externalAgents.submitDirect(
                        task.sessionId(), UUID.randomUUID().toString(), acpPrompt, targetBot.externalAgentId());
            } else {
                submission = engine.submit(
                        task.sessionId(),
                        UUID.randomUUID().toString(),
                        cleanInstruction,
                        "NORMAL",
                        resolved.selection(),
                        approvalMode);
            }
            String runId = submission.run().id();
            synchronized (this) {
                activeTaskRuns.put(task.id(), runId);
            }
            write(() -> {
                store.updateTaskRun(task.id(), runId, "RUNNING");
                return null;
            });
            return store.task(task.id());
        } catch (RuntimeException error) {
            String detail = error.getMessage() == null ? "无法启动后台任务" : error.getMessage();
            write(() -> {
                store.finishTask(task.id(), "FAILED", "", detail);
                return null;
            });
            throw error;
        }
    }

    public synchronized Task cancelTask(String taskId) {
        Task task = store.task(taskId);
        String runId = activeTaskRuns.remove(taskId);
        if (runId == null || runId.isBlank()) runId = task.runId();
        if (runId != null && !runId.isBlank()) {
            try {
                if (externalAgents != null && externalAgents.owns(runId)) {
                    externalAgents.cancel(runId);
                } else {
                    engine.cancel(runId);
                }
            } catch (RuntimeException error) {
                log.warn("Could not cancel bot task run {}", runId, error);
            }
        }
        write(() -> {
            Task current = store.task(taskId);
            if ("QUEUED".equals(current.status()) || "RUNNING".equals(current.status())) {
                store.finishTask(taskId, "CANCELLED", current.summary(), "已手动取消");
            }
            return null;
        });
        return store.task(taskId);
    }

    @Override
    public List<Bot> listBots() {
        return store.listBots(false);
    }

    @Override
    public Bot resolveBot(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) throw new HarnessException("INVALID", "未指定 Bot");
        String cleaned = idOrName.strip();
        if (cleaned.startsWith("@")) cleaned = cleaned.substring(1).strip();
        Bot byName = store.findBotByName(cleaned);
        if (byName != null) return byName;
        return store.getBot(cleaned);
    }

    @Override
    public Bot createBot(
            String name, String roleSummary, String systemPrompt, String shape, String color, String workspaceId) {
        String cleanName = validateBotName(name, null);
        String cleanRole = roleSummary == null ? "" : roleSummary.strip();
        String cleanPrompt = systemPrompt == null ? "" : systemPrompt.strip();
        String normalizedShape = BotTemplates.normalizeShape(shape);
        String normalizedColor = BotTemplates.normalizeColor(color);
        String ws = validateWorkspaceId(workspaceId);
        Bot created = write(() -> {
            String chatSessionId = sessions.createSession("Bot · " + cleanName).id();
            return store.insertBot(
                    "",
                    cleanName,
                    cleanRole,
                    cleanPrompt,
                    false,
                    normalizedShape,
                    normalizedColor,
                    false,
                    ws,
                    "",
                    null,
                    "",
                    null,
                    "",
                    ApprovalMode.SMART.name(),
                    chatSessionId);
        });
        pushNotice("BOT_CREATED", created.id(), created.name(), "新成员入职", created.name() + " 已加入团队");
        return created;
    }

    @Override
    public Bot updateBotFromTool(
            String botIdOrName,
            String name,
            String roleSummary,
            String systemPrompt,
            String workspaceId,
            String approvalMode) {
        Bot existing = resolveBot(botIdOrName);
        String nextName = name == null || name.isBlank() ? existing.name() : validateBotName(name, existing.id());
        String nextRole = roleSummary == null ? existing.roleSummary() : roleSummary.strip();
        String nextPrompt = systemPrompt == null ? existing.systemPrompt() : systemPrompt.strip();
        String nextWs = workspaceId == null ? existing.workspaceId() : validateWorkspaceId(workspaceId);
        String nextApproval = approvalMode == null || approvalMode.isBlank()
                ? existing.approvalMode()
                : parseApprovalMode(approvalMode).name();
        Bot updated = write(() -> store.updateBot(new Bot(
                existing.id(),
                existing.sectionId(),
                nextName,
                nextRole,
                nextPrompt,
                existing.main(),
                existing.shape(),
                existing.color(),
                existing.pinned(),
                existing.ordinal(),
                nextWs,
                existing.chatProfileId(),
                existing.chatReasoningEffort(),
                existing.taskProfileId(),
                existing.taskReasoningEffort(),
                existing.externalAgentId(),
                nextApproval,
                existing.chatSessionId(),
                existing.lastSeenAt(),
                existing.createdAt(),
                existing.archived(),
                existing.revision(),
                existing.lastSeq())));
        return updated;
    }

    @Override
    public void archiveBotFromTool(String botIdOrName) {
        Bot target = resolveBot(botIdOrName);
        if (target.main()) throw new HarnessException("CONFLICT", "主 Bot 不能归档");
        write(() -> {
            store.archiveBot(target.id());
            return null;
        });
    }

    @Override
    public Playbook savePlaybookFromTool(
            String botId, String id, String name, String whenToUse, String steps, String notes) {
        String cleanName = name == null ? "" : name.strip();
        String cleanSteps = steps == null ? "" : steps.strip();
        if (cleanName.isEmpty() || cleanName.length() > 80) throw new HarnessException("INVALID", "做法名称需为 1–80 个字符");
        if (cleanSteps.isEmpty()) throw new HarnessException("INVALID", "做法步骤不能为空");
        Bot bot = store.getBot(botId);
        Playbook saved = write(() -> store.savePlaybook(id, botId, cleanName, whenToUse, cleanSteps, notes, ""));
        pushNotice("PLAYBOOK_SAVED", bot.id(), bot.name(), "学会新做法", bot.name() + " 记住了「" + saved.name() + "」");
        return saved;
    }

    @Override
    public void deletePlaybookFromTool(String botId, String playbookId) {
        Playbook existing = store.playbook(playbookId);
        if (!existing.botId().equals(botId)) throw new HarnessException("CONFLICT", "该做法不属于当前 Bot");
        write(() -> {
            store.deletePlaybook(playbookId);
            return null;
        });
    }

    @Override
    public String setModelFromTool(String callerBotId, String targetBotIdOrName, String scope, String modelProfileId) {
        Bot caller = store.getBot(callerBotId);
        Bot target = resolveBot(targetBotIdOrName);
        if (!target.id().equals(caller.id()) && !caller.main()) {
            throw new HarnessException("CONFLICT", "只有主 Bot 可以修改其他 Bot 的模型配置");
        }
        String normalizedScope = scope == null ? "" : scope.strip().toLowerCase();
        if (!"chat".equals(normalizedScope) && !"task".equals(normalizedScope)) {
            throw new HarnessException("INVALID", "target 必须为 chat 或 task");
        }
        String resolvedProfileId = resolveProfileIdOrDefault(modelProfileId);
        write(() -> store.updateBot(new Bot(
                target.id(),
                target.sectionId(),
                target.name(),
                target.roleSummary(),
                target.systemPrompt(),
                target.main(),
                target.shape(),
                target.color(),
                target.pinned(),
                target.ordinal(),
                target.workspaceId(),
                "chat".equals(normalizedScope) ? resolvedProfileId : target.chatProfileId(),
                target.chatReasoningEffort(),
                "task".equals(normalizedScope) ? resolvedProfileId : target.taskProfileId(),
                target.taskReasoningEffort(),
                target.externalAgentId(),
                target.approvalMode(),
                target.chatSessionId(),
                target.lastSeenAt(),
                target.createdAt(),
                target.archived(),
                target.revision(),
                target.lastSeq())));
        String label = resolvedProfileId.isEmpty() ? "继承全局主模型" : resolvedProfileId;
        return "已将 " + target.name() + " 的" + ("chat".equals(normalizedScope) ? "聊天" : "后台任务") + "模型设置为：" + label;
    }

    @Override
    public List<Entry> searchHistory(String botId, String query, int limit) {
        return store.searchEntries(botId, query, limit);
    }

    @Override
    public List<Entry> readHistory(String botId, long aroundSeq, int window) {
        return store.entriesAround(botId, aroundSeq, window);
    }

    @Override
    public List<BotFile> listLibraryFiles(String botId) {
        return store.files(botId);
    }

    @Override
    public Routine createRoutineFromTool(
            String botId,
            String name,
            String instruction,
            String scheduleKind,
            String scheduleTime,
            Integer scheduleWeekday,
            String cron,
            String timezone,
            String playbookId,
            String modelProfileId) {
        if (routines == null) throw new HarnessException("UNAVAILABLE", "例行任务调度器未启用");
        return routines.save(
                botId,
                new BotRoutineScheduler.RoutineDraft(
                        "",
                        name,
                        instruction,
                        playbookId,
                        modelProfileId,
                        null,
                        scheduleKind,
                        scheduleTime,
                        scheduleWeekday,
                        cron,
                        timezone,
                        true,
                        0));
    }

    @Override
    public Routine toggleRoutineFromTool(String botId, String routineId, boolean enabled) {
        if (routines == null) throw new HarnessException("UNAVAILABLE", "例行任务调度器未启用");
        Routine existing = store.routine(routineId);
        if (!existing.botId().equals(botId)) throw new HarnessException("CONFLICT", "该例行任务不属于当前 Bot");
        return routines.toggle(routineId, enabled);
    }

    @Override
    public void deleteRoutineFromTool(String botId, String routineId) {
        if (routines == null) throw new HarnessException("UNAVAILABLE", "例行任务调度器未启用");
        Routine existing = store.routine(routineId);
        if (!existing.botId().equals(botId)) throw new HarnessException("CONFLICT", "该例行任务不属于当前 Bot");
        routines.delete(routineId);
    }

    @Override
    public List<Routine> listRoutinesFromTool(String botId) {
        return store.routines(botId);
    }

    @Override
    public List<MiniApp> listAppsFromTool(String botId) {
        return store.miniApps(botId);
    }

    private void startChatTurnLocked(Bot bot, String prompt) {
        ResolvedModel resolved = resolveModel(bot.chatProfileId(), bot.chatReasoningEffort());
        var submission = engine.submit(
                bot.chatSessionId(),
                UUID.randomUUID().toString(),
                prompt,
                "NORMAL",
                resolved.selection(),
                ApprovalMode.MANUAL);
        activeChats.put(
                bot.id(),
                new ActiveChat(bot.id(), submission.run().id(), resolved.modelLabel(), resolved.fallbackFrom()));
        write(() -> {
            store.bumpBotSeq(bot.id());
            return null;
        });
    }

    private void onChatRunSettled(String botId, Run run) {
        String nextPrompt = null;
        Bot botSnapshot = null;
        ActiveChat finished;
        synchronized (this) {
            finished = activeChats.remove(botId);
            String text = run.output() == null ? "" : run.output().strip();
            String modelLabel = finished != null ? finished.modelLabel() : run.model();
            String fallbackFrom = finished != null ? finished.fallbackFrom() : "";
            botSnapshot = store.getBot(botId);
            Bot currentBot = botSnapshot;
            if (run.status() == RunStatus.COMPLETED) {
                if (!text.isEmpty()) {
                    var parsed = extractSuggestions(text);
                    int beforeLv = currentLevel(botId);
                    write(() -> store.appendEntry(
                            botId,
                            "",
                            "ASSISTANT",
                            parsed.body(),
                            null,
                            "",
                            "[]",
                            parsed.suggestionsJson(),
                            "",
                            run.id(),
                            modelLabel,
                            fallbackFrom));
                    int afterLv = currentLevel(botId);
                    if (afterLv > beforeLv) {
                        pushNotice(
                                "LEVEL_UP", botId, currentBot.name(), "等级提升", currentBot.name() + " 升到了 Lv." + afterLv);
                    } else {
                        pushNotice(
                                "BOT_REPLIED",
                                botId,
                                currentBot.name(),
                                currentBot.name() + " 回复了消息",
                                clip(parsed.body(), 60));
                    }
                }
            } else if (run.status() == RunStatus.FAILED || run.status() == RunStatus.TIMED_OUT) {
                String reason = run.detail() == null || run.detail().isBlank() ? "本次回复未完成" : run.detail();
                write(() -> store.appendEntry(
                        botId,
                        "",
                        "SYSTEM",
                        "回复失败：" + reason,
                        null,
                        "",
                        "[]",
                        "[]",
                        "",
                        run.id(),
                        modelLabel,
                        fallbackFrom));
            } else {
                write(() -> {
                    store.bumpBotSeq(botId);
                    return null;
                });
            }

            List<QueuedMessage> queued = queuedByBot.remove(botId);
            if (!closed && queued != null && !queued.isEmpty()) {
                String merged = mergeQueued(queued);
                Long quoteSeq = queued.getFirst().quoteSeq();
                String quotePreview = queued.getFirst().quotePreview();
                write(() -> store.appendEntry(
                        botId, "", "USER", merged, quoteSeq, quotePreview, "[]", "[]", "", "", "", ""));
                nextPrompt = buildUserPrompt(merged, quoteSeq, quotePreview);
                startChatTurnLocked(currentBot, nextPrompt);
            }
        }
        if (run.status() == RunStatus.COMPLETED) {
            maybeCompactAsync(botId);
        }
    }

    private void onTaskRunSettled(String taskId, Run run) {
        synchronized (this) {
            activeTaskRuns.remove(taskId);
        }
        Task task = store.task(taskId);
        Bot worker = store.getBot(task.botId());
        Bot origin = task.originBotId().equals(task.botId()) ? worker : store.getBot(task.originBotId());
        boolean completed = run.status() == RunStatus.COMPLETED;
        String finalStatus = switch (run.status()) {
            case COMPLETED -> "COMPLETED";
            case CANCELLED -> "CANCELLED";
            case INTERRUPTED -> "INTERRUPTED";
            default -> "FAILED";
        };
        String output = run.output() == null ? "" : run.output().strip();
        String errorDetail = completed
                ? ""
                : (run.detail() == null || run.detail().isBlank() ? "任务未完成（" + finalStatus + "）" : run.detail());

        int beforeLv = currentLevel(worker.id());
        write(() -> {
            store.finishTask(taskId, finalStatus, output, errorDetail);
            if (!task.routineId().isBlank()) {
                try {
                    store.recordRoutineTrigger(task.routineId(), taskId, finalStatus, Instant.now());
                } catch (HarnessException ignored) {
                    // Routine may have been deleted while running.
                }
            }
            if (completed && !task.playbookId().isBlank()) {
                store.incrementPlaybookUse(task.playbookId());
            }
            return null;
        });

        boolean delegated = !task.originBotId().equals(task.botId());
        String resultContent = formatTaskResult(task, worker, delegated, completed, output, errorDetail);
        write(() -> store.appendEntry(
                origin.id(),
                "",
                "TASK_RESULT",
                resultContent,
                null,
                "",
                "[]",
                "[]",
                taskId,
                run.id(),
                task.modelLabel(),
                task.fallbackFrom()));

        if (completed) {
            pushNotice("TASK_DONE", origin.id(), worker.name(), "后台任务完成", worker.name() + " 完成了「" + task.title() + "」");
            int afterLv = currentLevel(worker.id());
            if (afterLv > beforeLv) {
                pushNotice("LEVEL_UP", worker.id(), worker.name(), "等级提升", worker.name() + " 升到了 Lv." + afterLv);
            }
        } else if (!"CANCELLED".equals(finalStatus)) {
            pushNotice(
                    "TASK_FAILED",
                    worker.id(),
                    worker.name(),
                    "后台任务失败",
                    worker.name() + " 的「" + task.title() + "」未跑成：" + clip(errorDetail, 60));
        }

        // Silent delegation: let the origin bot synthesize automatically once the specialist finishes.
        if (delegated && !closed) {
            String synthesisPrompt = "你委派给 [" + worker.name() + "](bot:" + worker.id() + ") 的后台任务「"
                    + task.title() + "」已结束（状态：" + finalStatus + "）。\n"
                    + (completed ? "任务结论如下：\n" + output : "失败原因：" + errorDetail)
                    + "\n请结合当前上下文，用简洁清晰的话向用户汇报结果。";
            synchronized (this) {
                if (activeChats.containsKey(origin.id())) {
                    queuedByBot
                            .computeIfAbsent(origin.id(), key -> new ArrayList<>())
                            .add(new QueuedMessage(
                                    UUID.randomUUID().toString(), synthesisPrompt, null, "", Instant.now()));
                } else {
                    try {
                        startChatTurnLocked(origin, synthesisPrompt);
                    } catch (RuntimeException error) {
                        log.warn("Could not trigger synthesis turn on {}", origin.id(), error);
                    }
                }
            }
        }
    }

    private void maybeCompactAsync(String botId) {
        if (closed) return;
        async.execute(() -> {
            try {
                Summary existing = store.summary(botId);
                long afterSeq = existing == null ? 0L : existing.throughSeq();
                List<Entry> newer = store.entriesAfter(botId, afterSeq);
                if (newer.size() < COMPACT_THRESHOLD) return;
                List<Entry> toCompact = newer.subList(0, newer.size() - COMPACT_KEEP_RECENT);
                if (toCompact.isEmpty()) return;
                long throughSeq = toCompact.getLast().seq();
                RunConfiguration config = settings.snapshot(ModelSelection.DEFAULT);
                if (!config.modelInfo().configured()) return;
                var prompt = new StringBuilder("请把以下历史对话压缩成一段精炼的事实摘要（保留关键决定、产物路径、用户偏好与未完事项，不超过 600 字）：\n\n");
                if (existing != null && !existing.summaryText().isBlank()) {
                    prompt.append("【此前摘要】\n").append(existing.summaryText()).append("\n\n【新增记录】\n");
                }
                for (Entry entry : toCompact) {
                    prompt.append('#')
                            .append(entry.seq())
                            .append(' ')
                            .append(entry.kind())
                            .append(": ")
                            .append(clip(entry.content(), 400))
                            .append('\n');
                }
                try (RunConfiguration.Resources resources = config.resources().open("")) {
                    var request = new ModelRequest(
                            "bot-compact-" + botId,
                            config.modelInfo().model(),
                            "NORMAL",
                            List.of(new ModelMessage("user", prompt.toString())),
                            null,
                            1,
                            "",
                            List.of(),
                            "你是对话摘要器，只输出紧凑客观的中文事实摘要。",
                            true);
                    var turn = resources.model().complete(request, () -> {}, chunk -> {});
                    if (turn.text() != null && !turn.text().isBlank()) {
                        write(() ->
                                store.saveSummary(botId, throughSeq, turn.text().strip()));
                    }
                }
            } catch (Exception error) {
                log.debug("Bot history compaction skipped for {}: {}", botId, error.getMessage());
            }
        });
    }

    private ResolvedModel resolveModel(String profileId, String reasoningEffort) {
        String requested = profileId == null ? "" : profileId.strip();
        if (!requested.isEmpty() && !"default".equals(requested)) {
            try {
                ModelProfile profile = settings.profile(requested);
                if (SettingsService.info(profile.settings()).configured()) {
                    return new ResolvedModel(
                            new ModelSelection(profile.id(), reasoningEffort), profile.displayName(), "");
                }
                return fallbackToDefault(profile.displayName());
            } catch (HarnessException missing) {
                return fallbackToDefault(requested);
            }
        }
        ModelProfile def = settings.profile("default");
        return new ResolvedModel(new ModelSelection("default", reasoningEffort), def.displayName(), "");
    }

    private ResolvedModel fallbackToDefault(String originalLabel) {
        ModelProfile def = settings.profile("default");
        return new ResolvedModel(new ModelSelection("default", null), def.displayName(), originalLabel);
    }

    private String resolveProfileIdOrDefault(String raw) {
        String cleaned = raw == null ? "" : raw.strip();
        if (cleaned.isEmpty() || "default".equalsIgnoreCase(cleaned) || "主模型".equals(cleaned)) return "";
        for (ModelProfile profile : settings.profiles()) {
            if (profile.id().equals(cleaned) || profile.displayName().equalsIgnoreCase(cleaned)) {
                return "default".equals(profile.id()) ? "" : profile.id();
            }
        }
        throw new HarnessException("NOT_FOUND", "找不到指定的模型配置：" + cleaned);
    }

    private String validateBotName(String raw, String selfId) {
        String name = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").strip();
        if (name.isEmpty() || name.length() > 30) throw new HarnessException("INVALID", "Bot 名称需为 1–30 个字符");
        if (name.contains("@") || name.contains(" ")) throw new HarnessException("INVALID", "Bot 名称不能包含空格或 @");
        Bot dup = store.findBotByName(name);
        if (dup != null && !dup.id().equals(selfId)) {
            throw new HarnessException("CONFLICT", "已存在同名 Bot：" + name);
        }
        return name;
    }

    private String validateWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) return "";
        String cleaned = workspaceId.strip();
        boolean exists = sessions.workspaces().stream().anyMatch(ws -> ws.id().equals(cleaned));
        if (!exists) throw new HarnessException("NOT_FOUND", "项目工作区不存在");
        return cleaned;
    }

    private String workspaceName(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) return "";
        return sessions.workspaces().stream()
                .filter(ws -> ws.id().equals(workspaceId))
                .map(Workspace::name)
                .findFirst()
                .orElse("");
    }

    private Playbook findPlaybookQuietly(String playbookId) {
        try {
            return store.playbook(playbookId);
        } catch (HarnessException missing) {
            return null;
        }
    }

    private int currentLevel(String botId) {
        return BotTemplates.experience(
                        store.countActiveTurns(botId),
                        store.playbooks(botId).size(),
                        store.countCompletedPlaybookTasks(botId),
                        store.countCompletedTasks(botId))
                .level();
    }

    private String resolveQuotePreview(String botId, Long quoteSeq) {
        if (quoteSeq == null) return "";
        Entry entry = store.entryBySeq(botId, quoteSeq);
        if (entry == null || entry.deleted()) return "";
        return clip(entry.content().replaceAll("\\s+", " ").strip(), 140);
    }

    private static String buildUserPrompt(String text, Long quoteSeq, String quotePreview) {
        if (quoteSeq == null || quotePreview.isBlank()) return text;
        return "[引用消息 #" + quoteSeq + "：" + quotePreview + "]\n\n" + text;
    }

    private static String mergeQueued(List<QueuedMessage> queued) {
        if (queued.size() == 1) return queued.getFirst().content();
        var sb = new StringBuilder();
        for (int i = 0; i < queued.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(queued.get(i).content());
        }
        return sb.toString();
    }

    private static String formatTaskResult(
            Task task, Bot worker, boolean delegated, boolean completed, String output, String errorDetail) {
        var sb = new StringBuilder();
        if (delegated) {
            sb.append("[")
                    .append(worker.name())
                    .append("](bot:")
                    .append(worker.id())
                    .append(") ");
        }
        sb.append(completed ? "完成了后台任务" : "后台任务未完成")
                .append("「")
                .append(task.title())
                .append("」\n\n");
        if (completed) {
            sb.append(output.isEmpty() ? "（任务已完成，无额外输出）" : output);
        } else {
            sb.append("原因：").append(errorDetail);
            if (!output.isEmpty()) sb.append("\n\n部分输出：\n").append(output);
        }
        return sb.toString();
    }

    private static ApprovalMode parseApprovalMode(String raw) {
        if (raw == null || raw.isBlank()) return ApprovalMode.SMART;
        try {
            return ApprovalMode.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException error) {
            return ApprovalMode.SMART;
        }
    }

    private record ParsedReply(String body, String suggestionsJson) {}

    private ParsedReply extractSuggestions(String raw) {
        String[] lines = raw.split("\\r?\\n");
        if (lines.length == 0) return new ParsedReply(raw, "[]");
        String last = lines[lines.length - 1].strip();
        if ((last.startsWith("[建议:") || last.startsWith("[建议：")) && last.endsWith("]")) {
            String inner = last.substring(4, last.length() - 1).strip();
            var items = new ArrayList<String>();
            for (String part : inner.split("\\|")) {
                String clean = part.strip();
                if (!clean.isEmpty() && items.size() < 3) items.add(clean);
            }
            if (!items.isEmpty()) {
                var body = new StringBuilder();
                for (int i = 0; i < lines.length - 1; i++) {
                    if (i > 0) body.append('\n');
                    body.append(lines[i]);
                }
                try {
                    return new ParsedReply(body.toString().strip(), json.writeValueAsString(items));
                } catch (Exception ignored) {
                    return new ParsedReply(raw, "[]");
                }
            }
        }
        return new ParsedReply(raw, "[]");
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private <T> T write(Supplier<T> work) {
        synchronized (sessions) {
            return tx.execute(status -> work.get());
        }
    }

    @Override
    public void close() {
        closed = true;
        List<String> runsToStop = new ArrayList<>();
        synchronized (this) {
            queuedByBot.clear();
            activeChats.values().forEach(chat -> runsToStop.add(chat.runId()));
            runsToStop.addAll(activeTaskRuns.values());
        }
        for (String runId : runsToStop) {
            if (runId == null || runId.isBlank()) continue;
            try {
                engine.cancel(runId);
            } catch (RuntimeException ignored) {
                // Best effort during shutdown.
            }
        }
        async.shutdownNow();
    }
}
