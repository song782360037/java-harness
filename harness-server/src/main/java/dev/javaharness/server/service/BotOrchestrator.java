package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.attachment.AttachmentStore;
import dev.javaharness.adapters.bot.BotLibraryTools;
import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.BotFile;
import dev.javaharness.adapters.bot.BotStore.BotLimits;
import dev.javaharness.adapters.bot.BotStore.BotSkill;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.adapters.bot.BotStore.Fact;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.adapters.bot.BotStore.Playbook;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Summary;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.adapters.bot.BotStore.WebSearchSettings;
import dev.javaharness.adapters.bot.BotWebTools;
import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.adapters.command.CommandWorkspaceTools;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.adapters.settings.ModelProfile;
import dev.javaharness.adapters.settings.ProviderSettings;
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
import dev.javaharness.spi.run.RunInput;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.Workspace;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bot 运行协调器：
 * 1. 维护每个 Bot 的聊天循环、真流式快照、多模态附件与回复中排队队列（Queued）；
 * 2. 严格隔离聊天与后台动手任务，注入 BotChatTools / BotLibraryTools / BotWebTools / 资料库审批命令；
 * 3. 管理任务并发队列与超时保护、双轨长期记忆、静默跨 Bot 委派、变更审计与灵动岛动态通知。
 * 锁序：BotOrchestrator → AgentEngine → SessionStore，存储事务内不回调调度器。
 */
public final class BotOrchestrator implements BotChatTools.Actions, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BotOrchestrator.class);
    private static final int COMPACT_THRESHOLD = 36;
    private static final int COMPACT_KEEP_RECENT = 14;
    private static final int MAX_NOTICES = 30;

    public record QueuedMessage(
            String id,
            String content,
            Long quoteSeq,
            String quotePreview,
            List<String> attachmentIds,
            String attachmentsJson,
            Instant queuedAt) {}

    public record PostResult(boolean queued, Entry entry, QueuedMessage queuedMessage) {}

    public record IslandNotice(
            long id, String kind, String botId, String botName, String title, String detail, Instant createdAt) {}

    public record ActiveChatStream(String botId, String runId, String status, String output) {}

    private record ResolvedModel(ModelSelection selection, String modelLabel, String fallbackFrom) {}

    private record ActiveChat(String botId, String runId, String modelLabel, String fallbackFrom) {}

    private record PendingTaskLaunch(
            String taskId,
            String sessionId,
            String targetBotId,
            String externalAgentId,
            boolean useAcp,
            String playbookId,
            String instruction,
            ModelSelection selection,
            ApprovalMode approvalMode) {}

    private record PreparedAttachments(RunInput runInput, String attachmentsJson, String libraryNote) {}

    private record ProgressStamp(String signature, long lastChangeNanos) {}

    private final BotStore store;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final AgentEngine engine;
    private final SettingsService settings;
    private final Path libraryRoot;
    private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("harness-bot-task-deadlines").factory());

    private final Map<String, ActiveChat> activeChats = new HashMap<>();
    private final Map<String, List<QueuedMessage>> queuedByBot = new HashMap<>();
    private final Map<String, String> activeTaskRuns = new HashMap<>();
    private final Deque<PendingTaskLaunch> waitingTasks = new ArrayDeque<>();
    private final Map<String, ScheduledFuture<?>> taskTimers = new HashMap<>();
    private final Map<String, ProgressStamp> runProgress = new ConcurrentHashMap<>();
    private final Set<String> silenceTimedOutRuns = ConcurrentHashMap.newKeySet();
    private final Deque<IslandNotice> notices = new ArrayDeque<>();
    private final AtomicLong noticeSeq = new AtomicLong();
    private final ObjectMapper json = new ObjectMapper();
    private ExternalAgentRuns externalAgents;
    private MemoryService memories;
    private BotRoutineScheduler routines;
    private BotMiniAppService miniApps;
    private BotSkillService skillService;
    private CommandService commands;
    private ComposerInputService composerInputs;
    private LocalSecretCipher secretCipher;
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

    public void useSkills(BotSkillService skillService) {
        this.skillService = skillService;
    }

    public void useCommands(CommandService commands) {
        this.commands = commands;
        if (commands != null) {
            BotLimits limits = store.ensureLimits();
            commands.useMaxCommandTimeoutSeconds(limits.commandTimeoutSeconds());
        }
    }

    public void useComposerInputs(ComposerInputService composerInputs) {
        this.composerInputs = composerInputs;
    }

    public void useSecretCipher(LocalSecretCipher secretCipher) {
        this.secretCipher = secretCipher;
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
            store.ensureLimits();
            store.ensureWebSearchSettings();
            ensureMainBotLocked();
            return null;
        });
        deadlines.scheduleWithFixedDelay(this::checkModelSilence, 2, 2, TimeUnit.SECONDS);
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
        var skills = bot.main() ? store.allSkills() : store.skillsForBot(bot.id());
        var routinesList = store.routines(bot.id());
        var userFacts = store.facts("USER", "");
        var botFacts = store.facts("BOT", bot.id());
        var miniAppList = store.miniApps(bot.id());
        var activeTasks = store.activeTasksForBot(bot.id());
        var summary = store.summary(bot.id());
        var recentEntries =
                summary == null ? store.entries(bot.id(), 80) : store.entriesAfter(bot.id(), summary.throughSeq());
        String workspaceName = workspaceName(bot.workspaceId());
        String instructions = BotPrompts.joinSections(BotPrompts.chatSections(
                bot,
                meta.userDisplayName(),
                roster,
                playbooks,
                skills,
                routinesList,
                userFacts,
                botFacts,
                miniAppList,
                activeTasks,
                workspaceName));
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
        var skills = bot.main() ? store.allSkills() : store.skillsForBot(bot.id());
        var userFacts = store.facts("USER", "");
        var botFacts = store.facts("BOT", bot.id());
        String workspaceName = workspaceName(bot.workspaceId());
        String extra = BotPrompts.taskInstructions(
                bot, playbook, skills, userFacts, botFacts, workspaceName, origin.name(), !task.originBotId().equals(task.botId()));
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
                if (bot.workspaceId().isBlank() && commands != null) {
                    Files.createDirectories(libraryRoot);
                    Path realLib = libraryRoot.toRealPath();
                    commands.useBotLibraryRoot(realLib);
                    baseTools = new CommandWorkspaceTools(baseTools, commands, realLib.toString(), "");
                }
                BotLibraryTools.AppTools appTools = miniApps == null
                        ? null
                        : new BotLibraryTools.AppTools() {
                            @Override
                            public String saveApp(
                                    String appId,
                                    String name,
                                    String description,
                                    String icon,
                                    String color,
                                    String changeSummary,
                                    String html,
                                    String initialDataJson) {
                                MiniApp saved = miniApps.saveApp(
                                        bot.id(),
                                        task.id(),
                                        appId,
                                        name,
                                        description,
                                        icon,
                                        color,
                                        changeSummary,
                                        html,
                                        initialDataJson);
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
                var lastExplicitStatusNanos = new AtomicLong(0L);
                WorkspaceTools withLibrary = new BotLibraryTools(
                        baseTools,
                        libraryRoot,
                        (rel, size) -> write(() -> {
                            store.recordFile(bot.id(), task.id(), rel, size);
                            return null;
                        }),
                        appTools,
                        statusText -> {
                            lastExplicitStatusNanos.set(System.nanoTime());
                            write(() -> {
                                store.updateTaskStatusText(task.id(), statusText);
                                return null;
                            });
                        },
                        skillService == null ? null : wanted -> skillService.useSkill(bot.id(), wanted));
                WorkspaceTools withWeb = new BotWebTools(withLibrary, BotOrchestrator.this::currentSearchConfig);
                return wrapAutoTaskProgress(withWeb, task.id(), lastExplicitStatusNanos);
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

    /** 发送消息给指定 Bot（兼容旧签名）。 */
    public synchronized PostResult postMessage(
            String botId, String requestId, String content, Long quoteSeq, String attachmentsJson) {
        return postMessage(botId, requestId, content, quoteSeq, List.of(), attachmentsJson);
    }

    /** 发送消息给指定 Bot（支持多模态附件）：空闲立即发起聊天轮次，正在回复时进入排队队列。 */
    public synchronized PostResult postMessage(
            String botId,
            String requestId,
            String content,
            Long quoteSeq,
            List<String> attachmentIds,
            String fallbackAttachmentsJson) {
        return postMessage(botId, requestId, content, quoteSeq, attachmentIds, fallbackAttachmentsJson, "", false);
    }

    /** 发送消息给指定 Bot（支持多模态附件与「正在查看小程序」上下文注入）。 */
    public synchronized PostResult postMessage(
            String botId,
            String requestId,
            String content,
            Long quoteSeq,
            List<String> attachmentIds,
            String fallbackAttachmentsJson,
            String viewingMiniAppId,
            boolean fromMiniApp) {
        if (closed) throw new HarnessException("UNAVAILABLE", "服务正在停止");
        String text = content == null ? "" : content.strip();
        List<String> ids = attachmentIds == null ? List.of() : attachmentIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (text.isEmpty() && ids.isEmpty()) throw new HarnessException("INVALID", "消息或附件不能同时为空");
        if (text.length() > 20_000) throw new HarnessException("INVALID", "消息不能超过 20000 个字符");
        Bot bot = store.getBot(botId);
        if (bot.archived()) throw new HarnessException("CONFLICT", "该 Bot 已归档");

        if (requestId != null && !requestId.isBlank()) {
            Entry existing = store.entryByRequest(botId, requestId);
            if (existing != null) return new PostResult(false, existing, null);
        }

        String quotePreview = resolveQuotePreview(botId, quoteSeq);
        PreparedAttachments prepared = prepareAttachments(bot, ids, fallbackAttachmentsJson);
        String miniAppContextNote = buildMiniAppContextNote(viewingMiniAppId, fromMiniApp);
        String combinedNote = combineNotes(prepared.libraryNote(), miniAppContextNote);
        if (activeChats.containsKey(botId)) {
            var queued = new QueuedMessage(
                    UUID.randomUUID().toString(),
                    text,
                    quoteSeq,
                    quotePreview,
                    ids,
                    prepared.attachmentsJson(),
                    Instant.now());
            queuedByBot.computeIfAbsent(botId, key -> new ArrayList<>()).add(queued);
            write(() -> {
                store.bumpBotSeq(botId);
                return null;
            });
            return new PostResult(true, null, queued);
        }

        Entry entry = write(() -> store.appendEntry(
                botId,
                requestId,
                "USER",
                text,
                quoteSeq,
                quotePreview,
                prepared.attachmentsJson(),
                "[]",
                "",
                "",
                "",
                ""));
        String prompt = buildUserPrompt(text, quoteSeq, quotePreview, combinedNote);
        startChatTurnLocked(bot, prompt, prepared.runInput(), ids);
        return new PostResult(false, entry, null);
    }

    /** 停止 Bot 当前正在进行的聊天回复（保留已生成内容，不清空排队中的后续消息）。 */
    public synchronized void stopChat(String botId) {
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

    public synchronized boolean hasLiveWork() {
        return !activeChats.isEmpty() || !activeTaskRuns.isEmpty();
    }

    public synchronized String activeChatRunId(String botId) {
        ActiveChat active = activeChats.get(botId);
        return active == null ? "" : active.runId();
    }

    /** 获取当前所有正在流式回复中的聊天输出快照，供 SSE 高频推送（不触发全量快照拉取）。 */
    public List<ActiveChatStream> activeChatSnapshots() {
        List<ActiveChat> copy;
        synchronized (this) {
            if (activeChats.isEmpty()) return List.of();
            copy = List.copyOf(activeChats.values());
        }
        var result = new ArrayList<ActiveChatStream>(copy.size());
        for (ActiveChat chat : copy) {
            try {
                Run run = sessions.run(chat.runId());
                result.add(new ActiveChatStream(chat.botId(), run.id(), run.status().name(), run.output()));
            } catch (HarnessException ignored) {
                // Run not visible yet.
            }
        }
        return result;
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

    /** 限流上限被调高时，唤醒排队中的后台任务并更新命令超时上限。 */
    public void onLimitsUpdated() {
        BotLimits limits = store.ensureLimits();
        if (commands != null) {
            commands.useMaxCommandTimeoutSeconds(limits.commandTimeoutSeconds());
        }
        drainWaitingTasks();
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
                botId, originBotId, "", title, instruction, playbookId, modelProfileId, "", silentTargetChat);
    }

    @Override
    public Task startTask(
            String botId,
            String originBotId,
            String title,
            String instruction,
            String playbookId,
            String modelProfileId,
            String miniAppId,
            boolean silentTargetChat) {
        return startTaskInternal(
                botId, originBotId, "", title, instruction, playbookId, modelProfileId, miniAppId, silentTargetChat);
    }

    public Task startRoutineTask(
            String botId,
            String routineId,
            String title,
            String instruction,
            String playbookId,
            String modelProfileId) {
        return startTaskInternal(botId, botId, routineId, title, instruction, playbookId, modelProfileId, "", false);
    }

    private Task startTaskInternal(
            String botId,
            String originBotId,
            String routineId,
            String title,
            String instruction,
            String playbookId,
            String modelProfileId,
            String miniAppId,
            boolean silentTargetChat) {
        if (closed) throw new HarnessException("UNAVAILABLE", "服务正在停止");
        String cleanTitle =
                title == null ? "" : title.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleanTitle.isEmpty()) cleanTitle = "后台任务";
        if (cleanTitle.length() > 120) cleanTitle = cleanTitle.substring(0, 120);
        String rawInstruction = instruction == null ? "" : instruction.strip();
        if (rawInstruction.isEmpty()) throw new HarnessException("INVALID", "任务指令不能为空");
        String cleanInstruction = enrichWithMiniAppContext(rawInstruction, miniAppId);

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

        var launch = new PendingTaskLaunch(
                task.id(),
                task.sessionId(),
                targetBot.id(),
                targetBot.externalAgentId(),
                useAcp,
                playbookId == null ? "" : playbookId,
                cleanInstruction,
                resolved.selection(),
                approvalMode);

        boolean launchNow;
        int maxConcurrent = Math.max(1, store.ensureLimits().maxConcurrentTasks());
        synchronized (this) {
            if (activeTaskRuns.size() < maxConcurrent) {
                activeTaskRuns.put(task.id(), "");
                launchNow = true;
            } else {
                waitingTasks.addLast(launch);
                launchNow = false;
            }
        }
        if (launchNow) {
            launchTaskNow(launch);
        }
        return store.task(task.id());
    }

    private void launchTaskNow(PendingTaskLaunch launch) {
        try {
            SessionStore.Submission submission;
            if (launch.useAcp()) {
                Playbook playbook = launch.playbookId().isBlank() ? null : findPlaybookQuietly(launch.playbookId());
                String acpPrompt = playbook == null
                        ? launch.instruction()
                        : "按做法「" + playbook.name() + "」执行：\n" + playbook.steps() + "\n\n任务指令：\n" + launch.instruction();
                submission = externalAgents.submitDirect(
                        launch.sessionId(), UUID.randomUUID().toString(), acpPrompt, launch.externalAgentId());
            } else {
                submission = engine.submit(
                        launch.sessionId(),
                        UUID.randomUUID().toString(),
                        launch.instruction(),
                        "NORMAL",
                        launch.selection(),
                        launch.approvalMode());
            }
            String runId = submission.run().id();
            int timeoutMinutes = Math.max(1, store.ensureLimits().taskTimeoutMinutes());
            synchronized (this) {
                activeTaskRuns.put(launch.taskId(), runId);
                ScheduledFuture<?> future = deadlines.schedule(
                        () -> timeoutTask(launch.taskId(), runId), timeoutMinutes, TimeUnit.MINUTES);
                taskTimers.put(launch.taskId(), future);
            }
            write(() -> {
                store.updateTaskRun(launch.taskId(), runId, "RUNNING");
                return null;
            });
        } catch (RuntimeException error) {
            synchronized (this) {
                activeTaskRuns.remove(launch.taskId());
                cancelTaskTimerLocked(launch.taskId());
            }
            String detail = error.getMessage() == null ? "无法启动后台任务" : error.getMessage();
            write(() -> {
                store.finishTask(launch.taskId(), "FAILED", "", detail);
                return null;
            });
            drainWaitingTasks();
            throw error;
        }
    }

    private void timeoutTask(String taskId, String runId) {
        if (closed) return;
        synchronized (this) {
            if (!runId.equals(activeTaskRuns.get(taskId))) return;
        }
        log.warn("Bot background task {} timed out, cancelling run {}", taskId, runId);
        try {
            if (externalAgents != null && externalAgents.owns(runId)) {
                externalAgents.cancel(runId);
            } else {
                engine.cancel(runId);
            }
        } catch (RuntimeException error) {
            log.warn("Failed to cancel timed-out bot task {}", taskId, error);
        }
    }

    private void drainWaitingTasks() {
        if (closed) return;
        int maxConcurrent = Math.max(1, store.ensureLimits().maxConcurrentTasks());
        while (true) {
            PendingTaskLaunch next = null;
            synchronized (this) {
                if (activeTaskRuns.size() >= maxConcurrent) return;
                while (!waitingTasks.isEmpty()) {
                    PendingTaskLaunch candidate = waitingTasks.pollFirst();
                    Task current = store.task(candidate.taskId());
                    if ("QUEUED".equals(current.status())) {
                        activeTaskRuns.put(candidate.taskId(), "");
                        next = candidate;
                        break;
                    }
                }
            }
            if (next == null) return;
            try {
                launchTaskNow(next);
            } catch (RuntimeException error) {
                log.warn("Failed to launch queued bot task {}", next.taskId(), error);
            }
        }
    }

    private void cancelTaskTimerLocked(String taskId) {
        ScheduledFuture<?> future = taskTimers.remove(taskId);
        if (future != null) future.cancel(false);
    }

    public synchronized Task cancelTask(String taskId) {
        Task task = store.task(taskId);
        if (!"QUEUED".equals(task.status()) && !"RUNNING".equals(task.status())) {
            // 容错：已结束的任务不再报错（对齐 nengyong-bot beta.2 修复）
            return task;
        }
        waitingTasks.removeIf(item -> item.taskId().equals(taskId));
        String runId = activeTaskRuns.remove(taskId);
        cancelTaskTimerLocked(taskId);
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
        async.execute(this::drainWaitingTasks);
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
        Bot updated = write(() -> {
            Bot saved = store.updateBot(new Bot(
                    existing.id(),
                    existing.sectionId(),
                    nextName,
                    nextRole,
                    nextPrompt,
                    existing.main(),
                    existing.shape(),
                    existing.color(),
                    existing.avatarPath(),
                    existing.outfitJson(),
                    existing.hidden(),
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
                    existing.lastSeq()));
            long seq = store.latestEntrySeq(existing.id());
            if (!nextName.equals(existing.name())) {
                store.recordChange(
                        existing.id(), seq, "update_my_name", "NAME_UPDATED", "改名为「" + nextName + "」", existing.name(), nextName);
            }
            if (!nextPrompt.equals(existing.systemPrompt())) {
                store.recordChange(
                        existing.id(),
                        seq,
                        "update_my_instructions",
                        "PROMPT_UPDATED",
                        clip(nextPrompt, 120),
                        existing.systemPrompt(),
                        nextPrompt);
            }
            return saved;
        });
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
        Playbook before = id == null || id.isBlank() ? null : findPlaybookQuietly(id);
        Playbook saved = write(() -> {
            Playbook p = store.savePlaybook(id, botId, cleanName, whenToUse, cleanSteps, notes, "");
            store.recordChange(
                    botId,
                    store.latestEntrySeq(botId),
                    "save_playbook",
                    "PLAYBOOK_SAVED",
                    (before == null ? "新增做法「" : "更新做法「") + p.name() + "」",
                    before == null ? "" : before.steps(),
                    p.steps());
            return p;
        });
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
                target.avatarPath(),
                target.outfitJson(),
                target.hidden(),
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
    public Routine setRoutinePlaybookFromTool(String botId, String routineId, String playbookId) {
        Routine existing = store.routine(routineId);
        if (!existing.botId().equals(botId)) throw new HarnessException("CONFLICT", "该例行任务不属于当前 Bot");
        String cleanPlaybook = playbookId == null ? "" : playbookId.strip();
        if (!cleanPlaybook.isEmpty()) {
            Playbook pb = store.playbook(cleanPlaybook);
            if (!pb.botId().equals(botId)) throw new HarnessException("CONFLICT", "该做法不属于当前 Bot");
        }
        return write(() -> store.updateRoutinePlaybook(routineId, cleanPlaybook));
    }

    @Override
    public List<Routine> listRoutinesFromTool(String botId) {
        return store.routines(botId);
    }

    @Override
    public List<MiniApp> listAppsFromTool(String botId) {
        return store.miniApps(botId);
    }

    @Override
    public Fact rememberFromTool(String botId, String scope, String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty() || clean.length() > 500) throw new HarnessException("INVALID", "记忆内容需为 1–500 个字符");
        String normalizedScope = "USER".equalsIgnoreCase(scope) ? "USER" : "BOT";
        Bot bot = store.getBot(botId);
        int maxPerScope = store.ensureLimits().maxFactsPerScope();
        Fact saved = write(() -> {
            Fact created = store.addFact(normalizedScope, botId, clean, maxPerScope);
            store.recordChange(
                    botId,
                    store.latestEntrySeq(botId),
                    "remember",
                    "FACT_ADDED",
                    ("USER".equals(normalizedScope) ? "[用户共享] " : "[专属] ") + clip(clean, 120),
                    "",
                    clean);
            return created;
        });
        pushNotice("FACT_SAVED", bot.id(), bot.name(), "记住新信息", clip(clean, 60));
        return saved;
    }

    @Override
    public void forgetFromTool(String botId, String factId) {
        Fact existing = store.fact(factId);
        if ("BOT".equals(existing.scope()) && !existing.botId().equals(botId)) {
            throw new HarnessException("CONFLICT", "不能删除其他 Bot 的专属记忆");
        }
        write(() -> {
            store.deleteFact(factId);
            store.recordChange(
                    botId,
                    store.latestEntrySeq(botId),
                    "forget",
                    "FACT_REMOVED",
                    clip(existing.content(), 120),
                    existing.content(),
                    "");
            return null;
        });
    }

    @Override
    public Task stopTaskFromTool(String botId, String taskId) {
        Task existing = store.task(taskId);
        if (!existing.originBotId().equals(botId) && !existing.botId().equals(botId)) {
            throw new HarnessException("CONFLICT", "该后台任务不是你发起的，无法停止");
        }
        return cancelTask(taskId);
    }

    @Override
    public String listBotLevelsFromTool() {
        List<Bot> bots = store.listBots(false);
        record RankRow(Bot bot, BotTemplates.Experience exp) {}
        var rows = new ArrayList<RankRow>(bots.size());
        for (Bot bot : bots) {
            var exp = BotTemplates.experience(
                    store.countActiveTurns(bot.id()),
                    store.playbooks(bot.id()).size(),
                    store.countCompletedPlaybookTasks(bot.id()),
                    store.countCompletedTasks(bot.id()));
            rows.add(new RankRow(bot, exp));
        }
        rows.sort(Comparator.comparingInt((RankRow r) -> r.exp().xp()).reversed());
        var sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            sb.append(i + 1)
                    .append(". [")
                    .append(row.bot().name())
                    .append("](bot:")
                    .append(row.bot().id())
                    .append(") Lv.")
                    .append(row.exp().level())
                    .append("（")
                    .append(row.exp().tier())
                    .append("，")
                    .append(row.exp().xp())
                    .append(" XP）\n");
        }
        return sb.isEmpty() ? "暂无团队成员" : sb.toString().strip();
    }

    @Override
    public String installSkillFromTool(String botId, String targetBotIdOrName, String source) {
        if (skillService == null) throw new HarnessException("UNAVAILABLE", "技能服务未启用");
        BotSkillService.SkillOfferView offer = skillService.offerFromChat(botId, targetBotIdOrName, source);
        var sb = new StringBuilder();
        sb.append("已在聊天中生成待确认安装卡（offer_id=").append(offer.offerId()).append("），目标 Bot：")
                .append(offer.targetBotName()).append("，共发现 ").append(offer.skills().size()).append(" 个技能：\n");
        for (var s : offer.skills()) {
            sb.append("- ").append(s.name()).append("：").append(s.description());
            if (!s.scripts().isEmpty()) {
                sb.append("（含 ").append(s.scripts().size()).append(" 个可执行脚本）");
            }
            if (s.replaces() != null) sb.append(" [将覆盖已有版本]");
            sb.append('\n');
        }
        if (!offer.skipped().isEmpty()) {
            sb.append("已跳过无法安装的项：\n");
            for (var sk : offer.skipped()) {
                sb.append("- ").append(sk.folder()).append("：").append(sk.reason()).append('\n');
            }
        }
        sb.append("请用简短的话向用户说明这些技能的作用及是否含脚本，提示用户在卡片上勾选并点击「装上」，不要声称已经安装完成。");
        return sb.toString();
    }

    @Override
    public String removeSkillFromTool(String botId, String targetBotIdOrName, List<String> names) {
        if (skillService == null) throw new HarnessException("UNAVAILABLE", "技能服务未启用");
        List<String> removed = skillService.removeSkills(botId, targetBotIdOrName, names);
        return "已卸载技能：" + String.join("、", removed);
    }

    private WorkspaceTools wrapAutoTaskProgress(
            WorkspaceTools inner, String taskId, AtomicLong lastExplicitStatusNanos) {
        return new WorkspaceTools() {
            @Override
            public String workspace() {
                return inner.workspace();
            }

            @Override
            public List<ToolDefinition> definitions() {
                return inner.definitions();
            }

            @Override
            public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
                return execute("", call, cancellation);
            }

            @Override
            public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
                if (call != null && !"set_status".equals(call.name())) {
                    long sinceExplicit = System.nanoTime() - lastExplicitStatusNanos.get();
                    if (lastExplicitStatusNanos.get() == 0L || sinceExplicit > TimeUnit.SECONDS.toNanos(12)) {
                        String label = friendlyToolStatus(call.name());
                        if (!label.isEmpty()) {
                            try {
                                write(() -> {
                                    store.updateTaskStatusText(taskId, label);
                                    return null;
                                });
                            } catch (RuntimeException ignored) {
                                // Best effort progress hint.
                            }
                        }
                    }
                }
                return inner.execute(runId, call, cancellation);
            }
        };
    }

    private static String friendlyToolStatus(String toolName) {
        if (toolName == null || toolName.isBlank()) return "";
        return switch (toolName) {
            case "web_search" -> "联网检索资料中…";
            case "web_fetch" -> "抓取网页正文中…";
            case "library_list", "library_search" -> "查阅资料库目录…";
            case "library_read" -> "阅读资料库文档…";
            case "library_write", "library_edit" -> "撰写与更新资料库文件…";
            case "save_app" -> "构建小程序页面…";
            case "update_app_data" -> "更新小程序数据…";
            case "use_skill" -> "加载技能包指引…";
            case "propose_command" -> "执行命令（按策略审批）…";
            case "read_file", "list_directory", "search_files" -> "检索工作区代码…";
            case "write_file", "edit_file" -> "修改工作区文件…";
            default -> toolName.contains("__") ? "调用扩展工具 " + toolName + "…" : "调用工具 " + toolName + "…";
        };
    }

    private void checkModelSilence() {
        if (closed || !hasLiveWork()) return;
        int silenceSec;
        try {
            silenceSec = Math.max(10, store.ensureLimits().modelSilenceSeconds());
        } catch (RuntimeException error) {
            return;
        }
        long silenceLimitNanos = TimeUnit.SECONDS.toNanos(silenceSec);
        long now = System.nanoTime();
        List<String> activeChatRunIds;
        List<Map.Entry<String, String>> activeTasksCopy;
        synchronized (this) {
            activeChatRunIds = activeChats.values().stream()
                    .map(ActiveChat::runId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            activeTasksCopy = activeTaskRuns.entrySet().stream()
                    .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                    .map(e -> Map.entry(e.getKey(), e.getValue()))
                    .toList();
        }

        Set<String> waitingApprovalRunIds = new java.util.HashSet<>();
        try {
            for (var pending : store.allPendingBotApprovals()) {
                if (pending.runId() != null) waitingApprovalRunIds.add(pending.runId());
            }
        } catch (RuntimeException ignored) {
            // Best effort.
        }

        for (String runId : activeChatRunIds) {
            checkSingleRunSilence(runId, now, silenceLimitNanos, waitingApprovalRunIds);
        }
        for (var entry : activeTasksCopy) {
            checkSingleRunSilence(entry.getValue(), now, silenceLimitNanos, waitingApprovalRunIds);
        }
    }

    private void checkSingleRunSilence(
            String runId, long now, long silenceLimitNanos, Set<String> waitingApprovalRunIds) {
        if (waitingApprovalRunIds.contains(runId)) {
            runProgress.put(runId, new ProgressStamp("APPROVAL_WAIT", now));
            return;
        }
        try {
            Run run = sessions.run(runId);
            if (!run.status().active()) {
                runProgress.remove(runId);
                return;
            }
            var steps = sessions.steps(runId);
            var tools = sessions.tools(runId);
            long progressChars = run.output() == null ? 0 : run.output().length();
            for (var step : steps) {
                progressChars += step.reasoning() == null ? 0 : step.reasoning().length();
                if (step.finishedAt() != null) progressChars += 1;
            }
            int completedTools = 0;
            for (var tool : tools) {
                if (tool.output() != null) progressChars += tool.output().length();
                if (!"RUNNING".equalsIgnoreCase(tool.status()) && !"PENDING".equalsIgnoreCase(tool.status())) {
                    completedTools++;
                }
            }
            String sig = run.status() + ":" + steps.size() + ":" + tools.size() + ":" + completedTools + ":" + progressChars;
            ProgressStamp prev = runProgress.get(runId);
            if (prev == null || !prev.signature().equals(sig)) {
                runProgress.put(runId, new ProgressStamp(sig, now));
                return;
            }
            if (now - prev.lastChangeNanos() >= silenceLimitNanos) {
                silenceTimedOutRuns.add(runId);
                runProgress.remove(runId);
                log.warn("Bot run {} silent beyond threshold, cancelling", runId);
                if (externalAgents != null && externalAgents.owns(runId)) {
                    externalAgents.cancel(runId);
                } else {
                    engine.cancel(runId);
                }
            }
        } catch (RuntimeException ignored) {
            // Run may have settled concurrently.
        }
    }

    private PreparedAttachments prepareAttachments(Bot bot, List<String> ids, String fallbackJson) {
        if (ids == null || ids.isEmpty() || composerInputs == null) {
            return new PreparedAttachments(
                    RunInput.EMPTY,
                    fallbackJson == null || fallbackJson.isBlank() ? "[]" : fallbackJson,
                    "");
        }
        RunInput runInput = composerInputs.resolve(bot.chatSessionId(), "", List.of(), ids);
        var items = new ArrayList<Map<String, Object>>();
        var libraryPaths = new ArrayList<String>();
        for (String id : ids) {
            AttachmentStore.Stored stored = composerInputs.content(id);
            AttachmentStore.Attachment att = stored.attachment();
            String savedRel = saveAttachmentToLibrary(bot.id(), att.id(), att.name(), stored.content());
            if (!savedRel.isEmpty()) libraryPaths.add(savedRel);
            var meta = new LinkedHashMap<String, Object>();
            meta.put("id", att.id());
            meta.put("name", att.name());
            meta.put("mediaType", att.mediaType());
            meta.put("size", att.size());
            meta.put("libraryPath", savedRel);
            items.add(meta);
        }
        String attachmentsJson;
        try {
            attachmentsJson = json.writeValueAsString(items);
        } catch (Exception error) {
            attachmentsJson = "[]";
        }
        String note = libraryPaths.isEmpty()
                ? ""
                : "（用户附上的文件已同步存入资料库，后台任务可用 library_read 读取：" + String.join("、", libraryPaths) + "）";
        return new PreparedAttachments(runInput, attachmentsJson, note);
    }

    private String saveAttachmentToLibrary(String botId, String attachmentId, String originalName, byte[] content) {
        try {
            String safeName = (originalName == null ? "file" : originalName)
                    .replace('\\', '/')
                    .replaceAll("^.*/", "")
                    .replaceAll("[^\\p{L}\\p{N}._ ()-]", "_");
            if (safeName.isBlank()) safeName = "file";
            String shortId = attachmentId.length() > 8 ? attachmentId.substring(0, 8) : attachmentId;
            Path dir = libraryRoot.resolve("uploads").resolve(shortId).normalize();
            Path target = dir.resolve(safeName).normalize();
            if (!target.startsWith(libraryRoot)) return "";
            Files.createDirectories(dir);
            Files.write(target, content);
            String rel = libraryRoot.relativize(target).toString().replace('\\', '/');
            write(() -> {
                store.recordFile(botId, "", rel, content.length);
                return null;
            });
            return rel;
        } catch (IOException error) {
            log.warn("Could not mirror attachment {} to bot library", attachmentId, error);
            return "";
        }
    }

    private BotWebTools.SearchConfig currentSearchConfig() {
        WebSearchSettings cfg = store.ensureWebSearchSettings();
        String decryptedKey = "";
        if (!cfg.apiKeyEncrypted().isBlank() && secretCipher != null) {
            try {
                decryptedKey = secretCipher.decrypt(cfg.apiKeyEncrypted());
            } catch (RuntimeException error) {
                log.warn("Could not decrypt bot web search API key");
            }
        }
        String deepSeekKey = "";
        try {
            deepSeekKey = settings.providers().stream()
                    .filter(p -> p.baseUrl() != null
                            && p.baseUrl().toLowerCase().contains("deepseek")
                            && p.apiKey() != null
                            && !p.apiKey().isBlank())
                    .map(ProviderSettings::apiKey)
                    .findFirst()
                    .orElse("");
        } catch (RuntimeException ignored) {
            // Optional auto-reuse.
        }
        return new BotWebTools.SearchConfig(
                cfg.provider(),
                cfg.endpointUrl(),
                decryptedKey,
                deepSeekKey,
                cfg.fallbackFreeEnabled(),
                cfg.maxResults(),
                cfg.timeoutSeconds());
    }

    private void startChatTurnLocked(Bot bot, String prompt, RunInput runInput, List<String> attachmentIds) {
        ResolvedModel resolved = resolveModel(bot.chatProfileId(), bot.chatReasoningEffort());
        RunInput effectiveInput = runInput == null ? RunInput.EMPTY : runInput;
        var submission = engine.submit(
                bot.chatSessionId(),
                UUID.randomUUID().toString(),
                prompt,
                "NORMAL",
                resolved.selection(),
                ApprovalMode.MANUAL,
                effectiveInput);
        if (composerInputs != null && attachmentIds != null && !attachmentIds.isEmpty()) {
            composerInputs.bind(submission.run().id(), attachmentIds);
        }
        activeChats.put(
                bot.id(),
                new ActiveChat(bot.id(), submission.run().id(), resolved.modelLabel(), resolved.fallbackFrom()));
        write(() -> {
            store.bumpBotSeq(bot.id());
            return null;
        });
    }

    private void onChatRunSettled(String botId, Run run) {
        Bot botSnapshot;
        ActiveChat finished;
        synchronized (this) {
            finished = activeChats.remove(botId);
            runProgress.remove(run.id());
            boolean silenceTimeout = silenceTimedOutRuns.remove(run.id());
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
            } else if (silenceTimeout) {
                if (!text.isEmpty()) {
                    var parsed = extractSuggestions(text);
                    write(() -> store.appendEntry(
                            botId,
                            "",
                            "ASSISTANT",
                            parsed.body(),
                            null,
                            "",
                            "[]",
                            "[]",
                            "",
                            run.id(),
                            modelLabel.isBlank() ? "已超时" : modelLabel + " · 已超时",
                            fallbackFrom));
                }
                int silenceSec = store.ensureLimits().modelSilenceSeconds();
                write(() -> store.appendEntry(
                        botId,
                        "",
                        "SYSTEM",
                        "模型连续 " + silenceSec + " 秒无响应，已自动停止本次回复",
                        null,
                        "",
                        "[]",
                        "[]",
                        "",
                        run.id(),
                        modelLabel,
                        fallbackFrom));
            } else if (run.status() == RunStatus.CANCELLED) {
                if (!text.isEmpty()) {
                    var parsed = extractSuggestions(text);
                    String stoppedLabel = modelLabel.isBlank() ? "已停止" : modelLabel + " · 已停止";
                    write(() -> store.appendEntry(
                            botId,
                            "",
                            "ASSISTANT",
                            parsed.body(),
                            null,
                            "",
                            "[]",
                            "[]",
                            "",
                            run.id(),
                            stoppedLabel,
                            fallbackFrom));
                } else {
                    write(() -> {
                        store.bumpBotSeq(botId);
                        return null;
                    });
                }
            } else if (run.status() == RunStatus.FAILED || run.status() == RunStatus.TIMED_OUT) {
                if (run.status() == RunStatus.TIMED_OUT && !text.isEmpty()) {
                    var parsed = extractSuggestions(text);
                    write(() -> store.appendEntry(
                            botId,
                            "",
                            "ASSISTANT",
                            parsed.body(),
                            null,
                            "",
                            "[]",
                            "[]",
                            "",
                            run.id(),
                            modelLabel.isBlank() ? "已超时" : modelLabel + " · 已超时",
                            fallbackFrom));
                }
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
                var mergedIds = new ArrayList<String>();
                for (QueuedMessage q : queued) {
                    if (q.attachmentIds() != null) mergedIds.addAll(q.attachmentIds());
                }
                PreparedAttachments prepared = prepareAttachments(currentBot, mergedIds, "[]");
                write(() -> store.appendEntry(
                        botId,
                        "",
                        "USER",
                        merged,
                        quoteSeq,
                        quotePreview,
                        prepared.attachmentsJson(),
                        "[]",
                        "",
                        "",
                        "",
                        ""));
                String nextPrompt = buildUserPrompt(merged, quoteSeq, quotePreview, prepared.libraryNote());
                startChatTurnLocked(currentBot, nextPrompt, prepared.runInput(), mergedIds);
            }
        }
        if (run.status() == RunStatus.COMPLETED) {
            maybeCompactAsync(botId);
        }
    }

    private void onTaskRunSettled(String taskId, Run run) {
        synchronized (this) {
            activeTaskRuns.remove(taskId);
            cancelTaskTimerLocked(taskId);
        }
        runProgress.remove(run.id());
        boolean silenceTimeout = silenceTimedOutRuns.remove(run.id());
        Task task = store.task(taskId);
        Bot worker = store.getBot(task.botId());
        Bot origin = task.originBotId().equals(task.botId()) ? worker : store.getBot(task.originBotId());
        boolean completed = !silenceTimeout && run.status() == RunStatus.COMPLETED;
        String finalStatus = silenceTimeout
                ? "FAILED"
                : switch (run.status()) {
                    case COMPLETED -> "COMPLETED";
                    case CANCELLED -> "CANCELLED";
                    case INTERRUPTED -> "INTERRUPTED";
                    default -> "FAILED";
                };
        String output = run.output() == null ? "" : run.output().strip();
        String errorDetail = completed
                ? ""
                : silenceTimeout
                        ? "模型连续 " + store.ensureLimits().modelSilenceSeconds() + " 秒无响应，已自动停止"
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
                                    UUID.randomUUID().toString(),
                                    synthesisPrompt,
                                    null,
                                    "",
                                    List.of(),
                                    "[]",
                                    Instant.now()));
                } else {
                    try {
                        startChatTurnLocked(origin, synthesisPrompt, RunInput.EMPTY, List.of());
                    } catch (RuntimeException error) {
                        log.warn("Could not trigger synthesis turn on {}", origin.id(), error);
                    }
                }
            }
        }

        async.execute(this::drainWaitingTasks);
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
                        Bot bot = store.getBot(botId);
                        write(() ->
                                store.saveSummary(botId, throughSeq, turn.text().strip()));
                        pushNotice(
                                "COMPACTED",
                                botId,
                                bot.name(),
                                "对话已自动压缩",
                                "整理了前 " + toCompact.size() + " 条历史消息");
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

    private String buildMiniAppContextNote(String viewingMiniAppId, boolean fromMiniApp) {
        if (viewingMiniAppId == null || viewingMiniAppId.isBlank()) {
            return fromMiniApp ? "[提示：该消息来自小程序内直接发起]" : "";
        }
        try {
            MiniApp app = store.miniApp(viewingMiniAppId.strip());
            var sb = new StringBuilder();
            sb.append(fromMiniApp ? "[提示：用户正在小程序内直接发起请求：" : "[提示：用户当前屏幕上正打开着小程序：");
            sb.append("《").append(app.name()).append("》（app_id=").append(app.id())
                    .append("，当前版本 v").append(app.version()).append("）。");
            sb.append("若用户要求修改、扩展或修复它，请在 start_task 时传入 mini_app_id=\"").append(app.id()).append("\"。]");
            return sb.toString();
        } catch (HarnessException ignored) {
            return "";
        }
    }

    private String enrichWithMiniAppContext(String instruction, String miniAppId) {
        if (miniAppId == null || miniAppId.isBlank()) return instruction;
        try {
            MiniApp app = store.miniApp(miniAppId.strip());
            return instruction + "\n\n[目标小程序上下文：名称=" + app.name()
                    + "，app_id=" + app.id()
                    + "，当前版本=v" + app.version()
                    + "，HTML 路径=" + app.htmlPath()
                    + "，数据路径=" + app.dataPath()
                    + "。请先用 library_read 查看当前页面与数据，修改后用 save_app(app_id=\"" + app.id()
                    + "\", change=\"一句话改动说明\", ...) 保存新版本。]";
        } catch (HarnessException ignored) {
            return instruction;
        }
    }

    private static String combineNotes(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + "\n" + second;
    }

    private static String buildUserPrompt(String text, Long quoteSeq, String quotePreview, String libraryNote) {
        var sb = new StringBuilder();
        if (quoteSeq != null && !quotePreview.isBlank()) {
            sb.append("[引用消息 #").append(quoteSeq).append("：").append(quotePreview).append("]\n\n");
        }
        if (!text.isEmpty()) {
            sb.append(text);
        }
        if (libraryNote != null && !libraryNote.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(libraryNote);
        }
        return sb.isEmpty() ? "（用户发送了附件）" : sb.toString();
    }

    private static String mergeQueued(List<QueuedMessage> queued) {
        if (queued.size() == 1) return queued.getFirst().content();
        var sb = new StringBuilder();
        for (int i = 0; i < queued.size(); i++) {
            if (i > 0 && !queued.get(i).content().isBlank()) sb.append("\n\n");
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
            waitingTasks.clear();
            taskTimers.values().forEach(f -> f.cancel(false));
            taskTimers.clear();
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
        deadlines.shutdownNow();
        async.shutdownNow();
    }
}
