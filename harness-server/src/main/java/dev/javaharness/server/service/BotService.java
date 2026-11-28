package dev.javaharness.server.service;

import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.BotFile;
import dev.javaharness.adapters.bot.BotStore.BotLimits;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.adapters.bot.BotStore.Fact;
import dev.javaharness.adapters.bot.BotStore.LayoutItem;
import dev.javaharness.adapters.bot.BotStore.Meta;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.adapters.bot.BotStore.PendingBotApproval;
import dev.javaharness.adapters.bot.BotStore.Playbook;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Section;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.adapters.bot.BotStore.WebSearchSettings;
import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.adapters.plugins.PluginService;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.adapters.settings.ProviderSettings;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.session.SessionSnapshot;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.CommandExecution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
            List<String> outfit,
            Boolean hidden,
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
            int skillCount,
            int queuedCount,
            int pendingApprovalCount,
            int unreadCount,
            String avatarUrl,
            BotTemplates.Experience experience) {}

    public record WorkspaceSnapshot(
            Meta meta,
            List<Section> sections,
            List<BotCard> bots,
            List<BotTemplates.RoleTemplate> templates,
            List<BotOrchestrator.IslandNotice> notices,
            List<PendingBotApproval> pendingApprovals,
            String version) {}

    public record StreamingReply(String runId, String status, String output) {}

    public record ThreadSnapshot(
            BotCard card,
            List<Entry> entries,
            StreamingReply streaming,
            List<BotOrchestrator.QueuedMessage> queued,
            List<Task> tasks,
            List<Playbook> playbooks,
            List<BotSkillService.SkillView> skills,
            List<Routine> routines,
            List<MiniApp> apps,
            List<BotFile> files,
            List<Fact> userFacts,
            List<Fact> botFacts,
            List<PendingBotApproval> pendingApprovals,
            String version) {}

    public record FactsBundle(List<Fact> userFacts, List<Fact> botFacts) {}

    public record WebSearchSettingsView(
            String provider,
            String endpointUrl,
            boolean apiKeyConfigured,
            String apiKeyHint,
            boolean deepSeekKeyAvailable,
            boolean fallbackFreeEnabled,
            int maxResults,
            int timeoutSeconds,
            long revision) {}

    public record WebSearchSettingsDraft(
            String provider,
            String endpointUrl,
            String apiKey,
            boolean clearApiKey,
            boolean fallbackFreeEnabled,
            int maxResults,
            int timeoutSeconds,
            long revision) {}

    public record BotLimitsDraft(
            int maxConcurrentTasks,
            int taskTimeoutMinutes,
            int commandTimeoutSeconds,
            int modelSilenceSeconds,
            int maxFactsPerScope,
            long revision) {}

    public record TaskDetail(Task task, SessionSnapshot sessionSnapshot) {}

    public record SkillSummary(String id, String title, String description, String category, boolean installed) {}

    public record FileContent(String relativePath, long sizeBytes, String content, boolean truncated) {}

    public record RawFile(Path path, String mediaType, String filename, long sizeBytes) {}

    private static final int MAX_PREVIEW_BYTES = 200_000;

    private final BotStore store;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final SettingsService settings;
    private final BotOrchestrator orchestrator;
    private final Path libraryRoot;
    private ExtensionCatalogService extensions;
    private ExternalAgentCatalog externalAgents;
    private CommandService commands;
    private LocalSecretCipher secretCipher;
    private BotSkillService skillService;
    private static final Set<String> SEARCH_PROVIDERS =
            Set.of("NONE", "DUCKDUCKGO", "DEEPSEEK", "TAVILY", "BRAVE", "CUSTOM");

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

    public void useCommands(CommandService commands) {
        this.commands = commands;
    }

    public void useSecretCipher(LocalSecretCipher secretCipher) {
        this.secretCipher = secretCipher;
    }

    public void useSkills(BotSkillService skillService) {
        this.skillService = skillService;
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
                store.allPendingBotApprovals(),
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
                skillService == null ? List.of() : skillService.listViews(botId),
                store.routines(botId),
                store.miniApps(botId),
                store.files(botId),
                store.facts("USER", ""),
                store.facts("BOT", botId),
                store.pendingApprovalsForBot(botId),
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
                        main.avatarPath(),
                        main.outfitJson(),
                        false,
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
        boolean nextHidden = draft.hidden() == null ? existing.hidden() : draft.hidden();
        if (existing.main() && nextHidden) {
            throw new HarnessException("CONFLICT", "主 Bot 不能隐藏");
        }
        String nextOutfitJson = existing.outfitJson();
        if (draft.outfit() != null) {
            int level = BotTemplates.experience(
                            store.countActiveTurns(existing.id()),
                            store.playbooks(existing.id()).size(),
                            store.countCompletedPlaybookTasks(existing.id()),
                            store.countCompletedTasks(existing.id()))
                    .level();
            List<String> valid = BotTemplates.validateOutfit(draft.outfit(), level, existing.main());
            nextOutfitJson = BotTemplates.toOutfitJson(valid);
        }
        String finalOutfitJson = nextOutfitJson;
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
                existing.avatarPath(),
                finalOutfitJson,
                nextHidden,
                nextHidden ? false : (draft.pinned() == null ? existing.pinned() : draft.pinned()),
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

    public void markUnread(String botId) {
        store.getBot(botId);
        write(() -> {
            store.markUnread(botId);
            return null;
        });
    }

    public void markAllSeen() {
        write(() -> {
            store.markAllSeen();
            return null;
        });
    }

    public List<Entry> searchEntries(String botId, String query, int limit) {
        store.getBot(botId);
        return store.searchEntries(botId, query, limit);
    }

    /** 上传自定义头像（校验 PNG/JPEG/WebP/GIF 魔数且 <= 512KB，拒绝 SVG/HTML）。 */
    public BotCard uploadAvatar(String botId, byte[] bytes) {
        Bot existing = store.getBot(botId);
        if (bytes == null || bytes.length == 0) throw new HarnessException("INVALID", "头像文件不能为空");
        if (bytes.length > 512 * 1024) throw new HarnessException("INVALID", "头像图片不能超过 512 KB");
        String ext = detectImageExtension(bytes);
        if (ext == null) {
            throw new HarnessException("INVALID", "仅支持 PNG / JPEG / WebP / GIF 格式图片");
        }
        Path avatarsDir = libraryRoot.resolve("avatars").normalize();
        Path target = avatarsDir.resolve(existing.id() + "." + ext).normalize();
        if (!target.startsWith(avatarsDir)) throw new HarnessException("INVALID", "非法头像路径");
        try {
            Files.createDirectories(avatarsDir);
            if (!existing.avatarPath().isBlank()) {
                Path old = libraryRoot.resolve(existing.avatarPath()).normalize();
                if (old.startsWith(avatarsDir) && !old.equals(target)) {
                    Files.deleteIfExists(old);
                }
            }
            Files.write(target, bytes);
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "保存头像失败");
        }
        String rel = "avatars/" + existing.id() + "." + ext;
        Bot updated = write(() -> store.updateBotAvatar(existing.id(), rel));
        return buildCard(updated);
    }

    public BotCard deleteAvatar(String botId) {
        Bot existing = store.getBot(botId);
        if (!existing.avatarPath().isBlank()) {
            Path avatarsDir = libraryRoot.resolve("avatars").normalize();
            Path old = libraryRoot.resolve(existing.avatarPath()).normalize();
            if (old.startsWith(avatarsDir)) {
                try {
                    Files.deleteIfExists(old);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            }
        }
        Bot updated = write(() -> store.updateBotAvatar(existing.id(), ""));
        return buildCard(updated);
    }

    public RawFile readAvatar(String botId) {
        Bot bot = store.getBot(botId);
        if (bot.avatarPath().isBlank()) throw new HarnessException("NOT_FOUND", "该 Bot 未上传自定义头像");
        return readRawFile(botId, bot.avatarPath());
    }

    private static String detectImageExtension(byte[] b) {
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89
                && b[1] == 'P'
                && b[2] == 'N'
                && b[3] == 'G'
                && b[4] == 0x0D
                && b[5] == 0x0A
                && b[6] == 0x1A
                && b[7] == 0x0A) {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 12
                && b[0] == 'R'
                && b[1] == 'I'
                && b[2] == 'F'
                && b[3] == 'F'
                && b[8] == 'W'
                && b[9] == 'E'
                && b[10] == 'B'
                && b[11] == 'P') {
            return "webp";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "gif";
        }
        return null;
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

    /** 读取资料库原始文件（供图片/媒体安全预览与下载；HTML/SVG 强制降级为 octet-stream 防同源脚本执行）。 */
    public RawFile readRawFile(String botId, String relativePath) {
        store.getBot(botId);
        if (relativePath == null || relativePath.isBlank()) throw new HarnessException("INVALID", "缺少文件路径");
        String cleaned = relativePath.replace('\\', '/').strip();
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        Path target = libraryRoot.resolve(cleaned).normalize();
        if (!target.startsWith(libraryRoot) || target.equals(libraryRoot) || !Files.isRegularFile(target)) {
            throw new HarnessException("NOT_FOUND", "资料库文件不存在");
        }
        try {
            long size = Files.size(target);
            String filename = target.getFileName().toString();
            String lower = filename.toLowerCase();
            String mediaType;
            if (lower.endsWith(".png")) mediaType = "image/png";
            else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mediaType = "image/jpeg";
            else if (lower.endsWith(".webp")) mediaType = "image/webp";
            else if (lower.endsWith(".gif")) mediaType = "image/gif";
            else if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".csv"))
                mediaType = "text/plain; charset=utf-8";
            else if (lower.endsWith(".json")) mediaType = "application/json; charset=utf-8";
            else if (lower.endsWith(".pdf")) mediaType = "application/pdf";
            else mediaType = "application/octet-stream";
            return new RawFile(target, mediaType, filename, size);
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "无法读取资料库文件");
        }
    }

    public List<ExternalAgentCatalog.Profile> availableExternalAgents() {
        return externalAgents == null ? List.of() : externalAgents.list();
    }

    public FactsBundle facts(String botId) {
        store.getBot(botId);
        return new FactsBundle(store.facts("USER", ""), store.facts("BOT", botId));
    }

    public Fact addFact(String botId, String scope, String content) {
        return orchestrator.rememberFromTool(botId, scope, content);
    }

    public void deleteFact(String factId) {
        Fact existing = store.fact(factId);
        write(() -> {
            store.deleteFact(factId);
            if (!existing.botId().isBlank()) {
                store.recordChange(
                        existing.botId(),
                        store.latestEntrySeq(existing.botId()),
                        "forget",
                        "FACT_REMOVED",
                        existing.content(),
                        existing.content(),
                        "");
            }
            return null;
        });
    }

    public List<PendingBotApproval> pendingApprovals(String botId) {
        store.getBot(botId);
        return store.pendingApprovalsForBot(botId);
    }

    public List<PendingBotApproval> allPendingApprovals() {
        return store.allPendingBotApprovals();
    }

    public CommandExecution approveBotCommand(String commandId) {
        if (commands == null) throw new HarnessException("UNAVAILABLE", "命令服务未启用");
        if (!store.isBotTaskCommand(commandId)) {
            throw new HarnessException("NOT_FOUND", "仅支持在 Bot 工作台审批 Bot 后台任务的命令");
        }
        return commands.approve(commandId);
    }

    public CommandExecution rejectBotCommand(String commandId) {
        if (commands == null) throw new HarnessException("UNAVAILABLE", "命令服务未启用");
        if (!store.isBotTaskCommand(commandId)) {
            throw new HarnessException("NOT_FOUND", "仅支持在 Bot 工作台拒绝 Bot 后台任务的命令");
        }
        return commands.reject(commandId);
    }

    public WebSearchSettingsView webSearchSettings() {
        WebSearchSettings raw = store.ensureWebSearchSettings();
        String hint = "";
        boolean configured = !raw.apiKeyEncrypted().isBlank();
        if (configured && secretCipher != null) {
            try {
                String plain = secretCipher.decrypt(raw.apiKeyEncrypted());
                if (plain.length() >= 4) hint = "••••" + plain.substring(plain.length() - 4);
                else if (!plain.isEmpty()) hint = "••••";
            } catch (RuntimeException ignored) {
                hint = "••••";
            }
        }
        boolean hasDeepSeek = settings.providers().stream()
                .anyMatch(p -> p.baseUrl() != null
                        && p.baseUrl().toLowerCase().contains("deepseek")
                        && p.apiKey() != null
                        && !p.apiKey().isBlank());
        return new WebSearchSettingsView(
                raw.provider(),
                raw.endpointUrl(),
                configured,
                hint,
                hasDeepSeek,
                raw.fallbackFreeEnabled(),
                raw.maxResults(),
                raw.timeoutSeconds(),
                raw.revision());
    }

    public WebSearchSettingsView saveWebSearchSettings(WebSearchSettingsDraft draft) {
        String provider = draft.provider() == null ? "DUCKDUCKGO" : draft.provider().strip().toUpperCase();
        if (!SEARCH_PROVIDERS.contains(provider)) {
            throw new HarnessException("INVALID", "不支持的搜索引擎：" + provider);
        }
        String endpoint = draft.endpointUrl() == null ? "" : draft.endpointUrl().strip();
        if ("CUSTOM".equals(provider) && endpoint.isEmpty()) {
            throw new HarnessException("INVALID", "选择自定义搜索引擎时请填写接口 URL");
        }
        int maxResults = Math.max(1, Math.min(draft.maxResults() <= 0 ? 6 : draft.maxResults(), 10));
        int timeoutSeconds = Math.max(5, Math.min(draft.timeoutSeconds() <= 0 ? 15 : draft.timeoutSeconds(), 45));
        WebSearchSettings existing = store.ensureWebSearchSettings();
        String encrypted = existing.apiKeyEncrypted();
        String incomingKey = draft.apiKey() == null ? "" : draft.apiKey().strip();
        if (draft.clearApiKey()) {
            encrypted = "";
        } else if (!incomingKey.isEmpty()) {
            if (secretCipher == null) throw new HarnessException("UNAVAILABLE", "密钥加密服务未启用");
            encrypted = secretCipher.encrypt(incomingKey);
        }
        String finalEncrypted = encrypted;
        write(() -> store.saveWebSearchSettings(
                provider, endpoint, finalEncrypted, draft.fallbackFreeEnabled(), maxResults, timeoutSeconds, draft.revision()));
        return webSearchSettings();
    }

    public BotLimits limits() {
        return store.ensureLimits();
    }

    public BotLimits saveLimits(BotLimitsDraft draft) {
        int concurrent = Math.max(1, Math.min(draft.maxConcurrentTasks() <= 0 ? 3 : draft.maxConcurrentTasks(), 10));
        int taskMinutes = Math.max(1, Math.min(draft.taskTimeoutMinutes() <= 0 ? 15 : draft.taskTimeoutMinutes(), 120));
        int cmdSeconds =
                Math.max(10, Math.min(draft.commandTimeoutSeconds() <= 0 ? 300 : draft.commandTimeoutSeconds(), 1800));
        int silenceSeconds =
                Math.max(10, Math.min(draft.modelSilenceSeconds() <= 0 ? 300 : draft.modelSilenceSeconds(), 3600));
        int maxFacts = Math.max(5, Math.min(draft.maxFactsPerScope() <= 0 ? 50 : draft.maxFactsPerScope(), 200));
        BotLimits saved = write(
                () -> store.saveLimits(concurrent, taskMinutes, cmdSeconds, silenceSeconds, maxFacts, draft.revision()));
        orchestrator.onLimitsUpdated();
        return saved;
    }

    private BotCard buildCard(Bot bot) {
        List<Task> activeTasks = store.activeTasksForBot(bot.id());
        Task failed = store.latestFailedTaskAfter(bot.id(), bot.lastSeenAt());
        boolean replying = orchestrator.isReplying(bot.id());
        int queued = orchestrator.queuedMessages(bot.id()).size();
        int pendingApprovals = store.pendingApprovalsForBot(bot.id()).size();
        String status = failed != null
                ? "FAILED"
                : replying ? "REPLYING" : !activeTasks.isEmpty() ? "WORKING" : queued > 0 ? "QUEUED" : "IDLE";
        int turns = store.countActiveTurns(bot.id());
        int playbooks = store.playbooks(bot.id()).size();
        int skills = bot.main() ? store.allSkills().size() : store.countSkills(bot.id());
        int routines = store.routines(bot.id()).size();
        var exp = BotTemplates.experience(
                turns, playbooks, store.countCompletedPlaybookTasks(bot.id()), store.countCompletedTasks(bot.id()));
        String preview = store.lastPreview(bot.id());
        int unread = store.countUnreadEntries(bot.id(), bot.lastSeenAt());
        String avatarUrl = bot.avatarPath().isBlank() ? "" : "/api/bots/" + bot.id() + "/avatar?v=" + bot.revision();
        return new BotCard(
                bot,
                status,
                preview,
                failed == null ? "" : failed.errorDetail(),
                activeTasks.size(),
                activeTasks,
                routines,
                playbooks,
                skills,
                queued,
                pendingApprovals,
                unread,
                avatarUrl,
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
