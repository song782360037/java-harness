package dev.javaharness.server.service;

import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.BotFile;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.adapters.bot.BotStore.LayoutItem;
import dev.javaharness.adapters.bot.BotStore.Meta;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.adapters.bot.BotStore.Playbook;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Section;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.adapters.plugins.PluginService;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.session.SessionSnapshot;
import dev.javaharness.spi.session.SessionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** Bot 工作台视图组装与管理服务（团队、分组、排序、引导、做法与任务详情）。 */
public final class BotService {
    public record BotDraft(
            String sectionId,
            String name,
            String roleSummary,
            String systemPrompt,
            String shape,
            String color,
            Boolean pinned,
            String workspaceId,
            String chatProfileId,
            String chatReasoningEffort,
            String taskProfileId,
            String taskReasoningEffort,
            String externalAgentId,
            String approvalMode,
            long revision) {}

    public record OnboardDraft(String mainBotName, String userDisplayName, List<String> templateKeys) {}

    public record PlaybookDraft(String id, String name, String whenToUse, String steps, String notes) {}

    public record BotCard(
            Bot bot,
            String status,
            String preview,
            String failedReason,
            int activeTaskCount,
            List<Task> activeTasks,
            int routineCount,
            int playbookCount,
            int queuedCount,
            BotTemplates.Experience experience) {}

    public record WorkspaceSnapshot(
            Meta meta,
            List<Section> sections,
            List<BotCard> bots,
            List<BotTemplates.RoleTemplate> templates,
            List<BotOrchestrator.IslandNotice> notices,
            String version) {}

    public record StreamingReply(String runId, String status, String output) {}

    public record ThreadSnapshot(
            BotCard card,
            List<Entry> entries,
            StreamingReply streaming,
            List<BotOrchestrator.QueuedMessage> queued,
            List<Task> tasks,
            List<Playbook> playbooks,
            List<Routine> routines,
            List<MiniApp> apps,
            List<BotFile> files,
            String version) {}

    public record TaskDetail(Task task, SessionSnapshot sessionSnapshot) {}

    public record SkillSummary(String id, String title, String description, String category, boolean installed) {}

    public record FileContent(String relativePath, long sizeBytes, String content, boolean truncated) {}

    private static final int MAX_PREVIEW_BYTES = 200_000;

    private final BotStore store;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final SettingsService settings;
    private final BotOrchestrator orchestrator;
    private final Path libraryRoot;
    private ExtensionCatalogService extensions;
    private ExternalAgentCatalog externalAgents;

    public BotService(
            BotStore store,
            SessionStore sessions,
            TransactionTemplate tx,
            SettingsService settings,
            BotOrchestrator orchestrator,
            Path libraryRoot) {
        this.store = store;
        this.sessions = sessions;
        this.tx = tx;
        this.settings = settings;
        this.orchestrator = orchestrator;
        this.libraryRoot = libraryRoot.toAbsolutePath().normalize();
    }

    public void usePlugins(PluginService plugins) {
        this.extensions = plugins == null ? null : new ExtensionCatalogService(plugins, sessions);
    }

    public void useExternalAgents(ExternalAgentCatalog externalAgents) {
        this.externalAgents = externalAgents;
    }

    /** 保护普通会话接口，禁止直接改名、删除或提交 Bot 隐藏会话。 */
    public void requireOrdinary(String sessionId) {
        if (store.isBotSession(sessionId)) {
            throw new HarnessException("CONFLICT", "Bot 专用会话只能在 Bot 工作台中管理");
        }
    }

    public String globalVersion() {
        return store.globalVersion() + ":" + orchestrator.latestNoticeId();
    }

    public WorkspaceSnapshot workspaceSnapshot() {
        var bots = store.listBots(false);
        var cards = new ArrayList<BotCard>(bots.size());
        for (Bot bot : bots) {
            cards.add(buildCard(bot));
        }
        return new WorkspaceSnapshot(
                store.ensureMeta(),
                store.sections(),
                List.copyOf(cards),
                BotTemplates.STARTER_ROLES,
                orchestrator.recentNotices(0),
                globalVersion());
    }

    public ThreadSnapshot threadSnapshot(String botId) {
        Bot bot = store.getBot(botId);
        BotCard card = buildCard(bot);
        StreamingReply streaming = null;
        String activeRunId = orchestrator.activeChatRunId(botId);
        if (!activeRunId.isBlank()) {
            try {
                Run run = sessions.run(activeRunId);
                streaming = new StreamingReply(run.id(), run.status().name(), run.output());
            } catch (HarnessException ignored) {
                // Run not persisted yet.
            }
        }
        return new ThreadSnapshot(
                card,
                store.entries(botId, 200),
                streaming,
                orchestrator.queuedMessages(botId),
                store.tasksForBot(botId, 30),
                store.playbooks(botId),
                store.routines(botId),
                store.miniApps(botId),
                store.files(botId),
                globalVersion());
    }

    public WorkspaceSnapshot onboard(OnboardDraft draft) {
        String mainName = draft.mainBotName() == null ? "" : draft.mainBotName().strip();
        String userName =
                draft.userDisplayName() == null ? "" : draft.userDisplayName().strip();
        List<String> keys = draft.templateKeys() == null ? List.of() : draft.templateKeys();
        write(() -> {
            store.updateMeta(true, userName);
            Bot main = store.findMainBot();
            if (main != null && !mainName.isEmpty() && !mainName.equals(main.name())) {
                validateUniqueName(mainName, main.id());
                store.updateBot(new Bot(
                        main.id(),
                        main.sectionId(),
                        mainName,
                        main.roleSummary(),
                        main.systemPrompt(),
                        true,
                        main.shape(),
                        main.color(),
                        main.pinned(),
                        main.ordinal(),
                        main.workspaceId(),
                        main.chatProfileId(),
                        main.chatReasoningEffort(),
                        main.taskProfileId(),
                        main.taskReasoningEffort(),
                        main.externalAgentId(),
                        main.approvalMode(),
                        main.chatSessionId(),
                        main.lastSeenAt(),
                        main.createdAt(),
                        false,
                        main.revision(),
                        main.lastSeq()));
            }
            for (String key : keys) {
                BotTemplates.RoleTemplate template = BotTemplates.find(key);
                if (template == null) continue;
                if (store.findBotByName(template.name()) != null) continue;
                String chatSessionId =
                        sessions.createSession("Bot · " + template.name()).id();
                store.insertBot(
                        "",
                        template.name(),
                        template.roleSummary(),
                        template.systemPrompt(),
                        false,
                        template.shape(),
                        template.color(),
                        false,
                        "",
                        "",
                        null,
                        "",
                        null,
                        "",
                        ApprovalMode.SMART.name(),
                        chatSessionId);
            }
            return null;
        });
        return workspaceSnapshot();
    }

    public BotCard createBot(BotDraft draft) {
        String name = validateUniqueName(draft.name(), null);
        String ws = validateWorkspace(draft.workspaceId());
        String chatProfile = validateProfile(draft.chatProfileId());
        String taskProfile = validateProfile(draft.taskProfileId());
        String approval = parseApproval(draft.approvalMode());
        Bot created = write(() -> {
            String chatSessionId = sessions.createSession("Bot · " + name).id();
            return store.insertBot(
                    draft.sectionId(),
                    name,
                    draft.roleSummary(),
                    draft.systemPrompt(),
                    false,
                    BotTemplates.normalizeShape(draft.shape()),
                    BotTemplates.normalizeColor(draft.color()),
                    Boolean.TRUE.equals(draft.pinned()),
                    ws,
                    chatProfile,
                    draft.chatReasoningEffort(),
                    taskProfile,
                    draft.taskReasoningEffort(),
                    draft.externalAgentId(),
                    approval,
                    chatSessionId);
        });
        orchestrator.pushNotice("BOT_CREATED", created.id(), created.name(), "新成员入职", created.name() + " 已加入团队");
        return buildCard(created);
    }

    public BotCard updateBot(String id, BotDraft draft) {
        Bot existing = store.getBot(id);
        String name =
                draft.name() == null || draft.name().isBlank() ? existing.name() : validateUniqueName(draft.name(), id);
        String ws = draft.workspaceId() == null ? existing.workspaceId() : validateWorkspace(draft.workspaceId());
        String chatProfile =
                draft.chatProfileId() == null ? existing.chatProfileId() : validateProfile(draft.chatProfileId());
        String taskProfile =
                draft.taskProfileId() == null ? existing.taskProfileId() : validateProfile(draft.taskProfileId());
        String approval = draft.approvalMode() == null ? existing.approvalMode() : parseApproval(draft.approvalMode());
        long rev = draft.revision() > 0 ? draft.revision() : existing.revision();
        Bot updated = write(() -> store.updateBot(new Bot(
                existing.id(),
                draft.sectionId() == null ? existing.sectionId() : draft.sectionId(),
                name,
                draft.roleSummary() == null
                        ? existing.roleSummary()
                        : draft.roleSummary().strip(),
                draft.systemPrompt() == null
                        ? existing.systemPrompt()
                        : draft.systemPrompt().strip(),
                existing.main(),
                draft.shape() == null ? existing.shape() : BotTemplates.normalizeShape(draft.shape()),
                draft.color() == null ? existing.color() : BotTemplates.normalizeColor(draft.color()),
                draft.pinned() == null ? existing.pinned() : draft.pinned(),
                existing.ordinal(),
                ws,
                chatProfile,
                draft.chatReasoningEffort() == null ? existing.chatReasoningEffort() : draft.chatReasoningEffort(),
                taskProfile,
                draft.taskReasoningEffort() == null ? existing.taskReasoningEffort() : draft.taskReasoningEffort(),
                draft.externalAgentId() == null ? existing.externalAgentId() : draft.externalAgentId(),
                approval,
                existing.chatSessionId(),
                existing.lastSeenAt(),
                existing.createdAt(),
                existing.archived(),
                rev,
                existing.lastSeq())));
        return buildCard(updated);
    }

    public void archiveBot(String id) {
        if (orchestrator.isReplying(id) || !store.activeTasksForBot(id).isEmpty()) {
            throw new HarnessException("BUSY", "该 Bot 正在工作，请先停止任务再归档");
        }
        write(() -> {
            store.archiveBot(id);
            return null;
        });
    }

    public Section createSection(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 60) throw new HarnessException("INVALID", "分组名称需为 1–60 个字符");
        return write(() -> store.createSection(clean));
    }

    public Section updateSection(String id, String name, Boolean collapsed, Integer ordinal) {
        String clean = name == null ? null : name.strip();
        if (clean != null && (clean.isEmpty() || clean.length() > 60)) {
            throw new HarnessException("INVALID", "分组名称需为 1–60 个字符");
        }
        return write(() -> store.updateSection(id, clean, collapsed, ordinal));
    }

    public void deleteSection(String id) {
        write(() -> {
            store.deleteSection(id);
            return null;
        });
    }

    public void updateLayout(List<LayoutItem> items) {
        if (items == null) return;
        write(() -> {
            store.updateLayout(items);
            return null;
        });
    }

    public void markSeen(String botId) {
        store.getBot(botId);
        write(() -> {
            store.markSeen(botId);
            return null;
        });
    }

    public void deleteEntries(String botId, List<Long> seqs) {
        store.getBot(botId);
        write(() -> {
            store.softDeleteEntries(botId, seqs);
            return null;
        });
    }

    public Playbook savePlaybook(String botId, PlaybookDraft draft) {
        return orchestrator.savePlaybookFromTool(
                botId, draft.id(), draft.name(), draft.whenToUse(), draft.steps(), draft.notes());
    }

    public void deletePlaybook(String playbookId) {
        write(() -> {
            store.deletePlaybook(playbookId);
            return null;
        });
    }

    public TaskDetail taskDetail(String taskId) {
        Task task = store.task(taskId);
        SessionSnapshot snapshot = sessions.snapshot(task.sessionId());
        return new TaskDetail(task, snapshot);
    }

    public List<SkillSummary> availableSkills() {
        if (extensions == null) return List.of();
        return extensions.items().stream()
                .filter(item -> "skill".equals(item.kind()))
                .map(item -> new SkillSummary(
                        item.id(),
                        item.title(),
                        item.description(),
                        item.category(),
                        !item.installationId().isEmpty()))
                .toList();
    }

    public Playbook importSkillAsPlaybook(String botId, String skillItemId) {
        if (extensions == null) throw new HarnessException("UNAVAILABLE", "技能目录未启用");
        if (skillItemId == null || skillItemId.isBlank()) throw new HarnessException("INVALID", "请选择要导入的技能");
        Bot bot = store.getBot(botId);
        var preview = extensions.preview(skillItemId.strip());
        if (!"skill".equals(preview.item().kind())) throw new HarnessException("INVALID", "只能导入技能类型扩展");
        String title = preview.item().title().strip();
        if (title.length() > 80) title = title.substring(0, 80);
        String finalTitle = title;
        String desc = preview.item().description();
        String steps = preview.content() == null || preview.content().isBlank()
                ? desc
                : preview.content().strip();
        Playbook saved = write(() -> store.savePlaybook("", botId, finalTitle, desc, steps, "", skillItemId.strip()));
        orchestrator.pushNotice(
                "PLAYBOOK_SAVED", bot.id(), bot.name(), "导入做法", bot.name() + " 导入了技能「" + saved.name() + "」");
        return saved;
    }

    public FileContent readLibraryFile(String botId, String relativePath) {
        store.getBot(botId);
        String cleaned =
                relativePath == null ? "" : relativePath.replace('\\', '/').strip();
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) throw new HarnessException("INVALID", "请提供文件路径");
        Path target = libraryRoot.resolve(cleaned).normalize();
        if (!target.startsWith(libraryRoot)) throw new HarnessException("INVALID", "文件路径非法");
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new HarnessException("NOT_FOUND", "资料库文件不存在：" + cleaned);
        }
        try {
            long size = Files.size(target);
            byte[] bytes = Files.readAllBytes(target);
            boolean truncated = bytes.length > MAX_PREVIEW_BYTES;
            int len = truncated ? MAX_PREVIEW_BYTES : bytes.length;
            String text = new String(bytes, 0, len, StandardCharsets.UTF_8);
            String normalized = libraryRoot.relativize(target).toString().replace('\\', '/');
            return new FileContent(normalized, size, text, truncated);
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "无法读取资料库文件");
        }
    }

    public List<ExternalAgentCatalog.Profile> availableExternalAgents() {
        return externalAgents == null ? List.of() : externalAgents.list();
    }

    private BotCard buildCard(Bot bot) {
        List<Task> activeTasks = store.activeTasksForBot(bot.id());
        Task failed = store.latestFailedTaskAfter(bot.id(), bot.lastSeenAt());
        boolean replying = orchestrator.isReplying(bot.id());
        int queued = orchestrator.queuedMessages(bot.id()).size();
        String status = failed != null
                ? "FAILED"
                : replying ? "REPLYING" : !activeTasks.isEmpty() ? "WORKING" : queued > 0 ? "QUEUED" : "IDLE";
        int turns = store.countActiveTurns(bot.id());
        int playbooks = store.playbooks(bot.id()).size();
        int routines = store.routines(bot.id()).size();
        var exp = BotTemplates.experience(
                turns, playbooks, store.countCompletedPlaybookTasks(bot.id()), store.countCompletedTasks(bot.id()));
        String preview = store.lastPreview(bot.id());
        return new BotCard(
                bot,
                status,
                preview,
                failed == null ? "" : failed.errorDetail(),
                activeTasks.size(),
                activeTasks,
                routines,
                playbooks,
                queued,
                exp);
    }

    private String validateUniqueName(String raw, String selfId) {
        String name = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").strip();
        if (name.isEmpty() || name.length() > 30) throw new HarnessException("INVALID", "Bot 名称需为 1–30 个字符");
        if (name.contains("@") || name.contains(" ")) throw new HarnessException("INVALID", "Bot 名称不能包含空格或 @");
        Bot dup = store.findBotByName(name);
        if (dup != null && !dup.id().equals(selfId)) {
            throw new HarnessException("CONFLICT", "已存在同名 Bot：" + name);
        }
        return name;
    }

    private String validateWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) return "";
        String cleaned = workspaceId.strip();
        boolean exists = sessions.workspaces().stream().anyMatch(ws -> ws.id().equals(cleaned));
        if (!exists) throw new HarnessException("NOT_FOUND", "项目工作区不存在");
        return cleaned;
    }

    private String validateProfile(String profileId) {
        if (profileId == null || profileId.isBlank() || "default".equals(profileId.strip())) return "";
        settings.profile(profileId.strip());
        return profileId.strip();
    }

    private static String parseApproval(String raw) {
        if (raw == null || raw.isBlank()) return ApprovalMode.SMART.name();
        try {
            return ApprovalMode.valueOf(raw.strip().toUpperCase()).name();
        } catch (IllegalArgumentException error) {
            throw new HarnessException("INVALID", "不支持的审批模式：" + raw);
        }
    }

    private <T> T write(Supplier<T> work) {
        synchronized (sessions) {
            return tx.execute(status -> work.get());
        }
    }
}
