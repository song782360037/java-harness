package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.BotSkill;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bot 原生技能包服务（兼容 Claude Code SKILL.md 目录与压缩包格式）：
 * - 支持从 GitHub 仓库/目录链接、本机文件夹或面板上传的 .zip/.skill 包扫描技能；
 * - 严格两阶段确认：先生成待确认卡（列出脚本与可预览文件），用户显式勾选确认后才原子落盘；
 * - 安全防护：严格限制 GitHub HTTPS 域名防 SSRF、拒绝符号链接、拒绝 zip-slip/盘符/大小写冲突路径、限制文件数与体积；
 * - 后台任务通过 use_skill 按需加载 SKILL.md 正文与脚本目录；主 Bot 可统一查看、安装、卸载和复用全队技能。
 */
public final class BotSkillService {
    public static final String SKILL_FILE = "SKILL.md";
    private static final Duration OFFER_TTL = Duration.ofMinutes(30);
    private static final int MAX_SKILLS_PER_BOT = 150;
    private static final int MAX_FILES_PER_SKILL = 500;
    private static final long MAX_BYTES_PER_SKILL = 20L * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 60L * 1024 * 1024;
    private static final long MAX_UNPACKED_BYTES = 100L * 1024 * 1024;
    private static final int MAX_FOLDER_ENTRIES = 3000;
    private static final int MAX_INSTRUCTIONS_CHARS = 40_000;
    private static final int MAX_PREVIEW_BYTES = 400_000;
    private static final Pattern SKILL_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}$");
    private static final Pattern GITHUB_URL = Pattern.compile(
            "^https://(?:www\\.)?github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:/(?:tree|blob)/([^?#]+?))?/?(?:[?#].*)?$");
    private static final Set<String> ALLOWED_DOWNLOAD_HOSTS =
            Set.of("github.com", "www.github.com", "codeload.github.com", "objects.githubusercontent.com");
    private static final Set<String> JUNK_NAMES =
            Set.of(".git", ".DS_Store", "__MACOSX", "node_modules", "__pycache__");
    private static final Set<String> SCRIPT_EXTENSIONS = Set.of(
            "sh", "bash", "zsh", "py", "js", "mjs", "cjs", "ts", "ps1", "bat", "cmd", "rb", "pl", "php");

    public record OfferedSkill(
            String name,
            String description,
            String version,
            String sourceKind,
            String sourceRepo,
            String sourcePath,
            String sourceRef,
            int fileCount,
            List<String> scripts,
            long totalBytes,
            boolean userInvoked,
            String replaces) {}

    public record SkippedSkill(String folder, String reason) {}

    public record SkillOfferView(
            String offerId,
            String targetBotId,
            String targetBotName,
            List<OfferedSkill> skills,
            List<SkippedSkill> skipped,
            List<String> installed) {}

    public record SkillView(
            String id,
            String botId,
            String botName,
            String name,
            String description,
            String version,
            String sourceKind,
            String sourceRepo,
            String sourcePath,
            String sourceRef,
            int fileCount,
            List<String> scripts,
            long totalBytes,
            boolean userInvoked,
            int useCount,
            Instant lastUsedAt,
            Instant installedAt) {}

    public record SkillFileView(String path, long bytes, boolean script) {}

    public record SkillFileContent(String path, String content, boolean binary, boolean tooLarge, long bytes) {}

    private record ParsedPackage(
            String name,
            String description,
            String version,
            int fileCount,
            List<String> scripts,
            long totalBytes,
            boolean userInvoked,
            boolean internal) {}

    private record StagedSkill(OfferedSkill meta, Map<String, byte[]> files) {}

    private record PendingOffer(
            String offerId,
            String chatBotId,
            Long entrySeq,
            String targetBotId,
            String targetBotName,
            List<StagedSkill> packages,
            List<SkippedSkill> skipped,
            Instant createdAt) {}

    private record CandidateBundle(
            Map<String, byte[]> files,
            String sourceKind,
            String sourceRepo,
            String sourceRef,
            String sourcePrefix,
            String rootName) {}

    private final BotStore store;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final Path skillsRoot;
    private final Path libraryRoot;
    private final ObjectMapper json;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, PendingOffer> offers = new ConcurrentHashMap<>();
    private volatile BotOrchestrator orchestrator;

    public BotSkillService(
            BotStore store,
            SessionStore sessions,
            TransactionTemplate tx,
            Path libraryRoot,
            ObjectMapper json) {
        this.store = store;
        this.sessions = sessions;
        this.tx = tx;
        this.libraryRoot = libraryRoot.toAbsolutePath().normalize();
        this.skillsRoot = this.libraryRoot.resolve("_skills").normalize();
        this.json = json;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        cleanupStagingDirs();
    }

    public void useOrchestrator(BotOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    private void cleanupStagingDirs() {
        try {
            if (!Files.isDirectory(skillsRoot)) return;
            try (var stream = Files.list(skillsRoot)) {
                for (Path p : stream.toList()) {
                    if (p.getFileName().toString().startsWith(".staging-")) {
                        deleteTreeQuietly(p);
                    }
                }
            }
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    /** 聊天内调用 install_skill：扫描来源并生成待用户勾选的确认卡（不落盘安装）。 */
    public SkillOfferView offerFromChat(String chatBotId, String targetBotIdOrName, String source) {
        Bot caller = store.getBot(chatBotId);
        Bot target = resolveTargetOwner(caller, targetBotIdOrName);
        CandidateBundle bundle = loadSource(source);
        return createOffer(caller.id(), target, bundle, true);
    }

    /** 面板中通过粘贴 GitHub 链接或本地文件夹路径扫描技能包（不污染聊天记录）。 */
    public SkillOfferView offerFromPanelSource(String botId, String source) {
        Bot target = store.getBot(botId);
        CandidateBundle bundle = loadSource(source);
        return createOffer(null, target, bundle, false);
    }

    /** 面板中上传 .zip 或 .skill 压缩包扫描技能包（不污染聊天记录）。 */
    public SkillOfferView offerFromUpload(String botId, String filename, byte[] bytes) {
        Bot target = store.getBot(botId);
        if (bytes == null || bytes.length == 0) throw new HarnessException("INVALID", "压缩包不能为空");
        if (bytes.length > MAX_ARCHIVE_BYTES) throw new HarnessException("INVALID", "技能压缩包超过 60 MB 上限");
        String cleanName = filename == null ? "skill.zip" : filename.replace('\\', '/');
        cleanName = cleanName.substring(cleanName.lastIndexOf('/') + 1).strip();
        String lower = cleanName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".zip") && !lower.endsWith(".skill")) {
            throw new HarnessException("INVALID", "仅支持上传 .zip 或 .skill 格式的技能压缩包");
        }
        String baseName = cleanName.replaceFirst("(?i)\\.(zip|skill)$", "");
        Map<String, byte[]> files = unpackZipBytes(bytes, "");
        CandidateBundle bundle = new CandidateBundle(files, "UPLOAD", "", "", cleanName, baseName);
        return createOffer(null, target, bundle, false);
    }

    /** 用户确认或拒绝待安装技能卡；勾选的技能原子写入磁盘并入库。 */
    public SkillOfferView answerOffer(String callerBotId, String offerId, List<String> chosenNames) {
        pruneExpiredOffers();
        PendingOffer offer = offers.get(offerId);
        if (offer == null) {
            throw new HarnessException("NOT_FOUND", "该技能安装确认卡已过期或已处理，请重新扫描");
        }
        if (offer.chatBotId() != null && !offer.chatBotId().equals(callerBotId)) {
            throw new HarnessException("CONFLICT", "确认卡不属于当前 Bot 对话");
        }
        if (offer.chatBotId() == null && !offer.targetBotId().equals(callerBotId)) {
            throw new HarnessException("CONFLICT", "上传确认卡不属于当前 Bot");
        }
        List<String> picked = chosenNames == null
                ? List.of()
                : chosenNames.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();

        Set<String> available = new HashSet<>();
        for (StagedSkill st : offer.packages()) available.add(st.meta().name());
        for (String name : picked) {
            if (!available.contains(name)) {
                throw new HarnessException("INVALID", "确认卡中不存在技能：" + name);
            }
        }

        List<StagedSkill> toInstall = offer.packages().stream()
                .filter(st -> picked.contains(st.meta().name()))
                .toList();

        if (!toInstall.isEmpty()) {
            List<BotSkill> existing = store.skillsForBot(offer.targetBotId());
            Set<String> existingNames = new HashSet<>();
            for (BotSkill s : existing) existingNames.add(s.name());
            long added = toInstall.stream().filter(st -> !existingNames.contains(st.meta().name())).count();
            if (existing.size() + added > MAX_SKILLS_PER_BOT) {
                throw new HarnessException("CONFLICT", "单个 Bot 最多安装 " + MAX_SKILLS_PER_BOT + " 个技能");
            }
            for (StagedSkill st : toInstall) {
                placeFilesAtomically(offer.offerId(), offer.targetBotId(), st.meta().name(), st.files());
                String scriptsJson = writeJsonQuietly(st.meta().scripts());
                write(() -> {
                    store.upsertSkill(
                            offer.targetBotId(),
                            st.meta().name(),
                            st.meta().description(),
                            st.meta().version(),
                            st.meta().sourceKind(),
                            st.meta().sourceRepo(),
                            st.meta().sourcePath(),
                            st.meta().sourceRef(),
                            st.meta().fileCount(),
                            scriptsJson,
                            st.meta().totalBytes(),
                            st.meta().userInvoked());
                    long seq = store.latestEntrySeq(offer.targetBotId());
                    store.recordChange(
                            offer.targetBotId(),
                            seq,
                            "install_skill",
                            "SKILL_INSTALLED",
                            "安装技能「" + st.meta().name() + "」",
                            "",
                            st.meta().description());
                    return null;
                });
            }
        }

        offers.remove(offerId);
        SkillOfferView settled = new SkillOfferView(
                offer.offerId(),
                offer.targetBotId(),
                offer.targetBotName(),
                offer.packages().stream().map(StagedSkill::meta).toList(),
                offer.skipped(),
                picked);

        if (offer.chatBotId() != null && offer.entrySeq() != null) {
            String settledJson = writeJsonQuietly(settled);
            write(() -> {
                store.updateEntryAttachmentsJson(offer.chatBotId(), offer.entrySeq(), settledJson);
                return null;
            });
        }
        if (orchestrator != null && !picked.isEmpty()) {
            orchestrator.pushNotice(
                    "SKILL_INSTALLED",
                    offer.targetBotId(),
                    offer.targetBotName(),
                    "已安装技能",
                    offer.targetBotName() + " 安装了 " + String.join("、", picked));
        }
        return settled;
    }

    /** 卸载指定 Bot 名下的一个或多个技能（主 Bot 可代卸载团队任意 Bot 的技能）。 */
    public List<String> removeSkills(String callerBotId, String targetBotIdOrName, List<String> names) {
        if (names == null || names.isEmpty()) throw new HarnessException("INVALID", "请指定要卸载的技能名称");
        Bot caller = store.getBot(callerBotId);
        Bot target = resolveTargetOwner(caller, targetBotIdOrName);
        List<String> cleaned = names.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (cleaned.isEmpty()) throw new HarnessException("INVALID", "请指定要卸载的技能名称");
        List<BotSkill> existing = store.skillsForBot(target.id());
        Set<String> known = new HashSet<>();
        for (BotSkill s : existing) known.add(s.name());
        for (String name : cleaned) {
            if (!known.contains(name)) {
                throw new HarnessException("NOT_FOUND", "技能不存在：" + name);
            }
        }
        write(() -> {
            store.deleteSkills(target.id(), cleaned);
            long seq = store.latestEntrySeq(target.id());
            store.recordChange(
                    target.id(),
                    seq,
                    "remove_skill",
                    "SKILL_REMOVED",
                    "卸载技能：" + String.join("、", cleaned),
                    String.join(", ", cleaned),
                    "");
            return null;
        });
        for (String name : cleaned) {
            deleteTreeQuietly(skillFolder(target.id(), name));
        }
        return cleaned;
    }

    /** 列出面板可见技能：普通 Bot 看自己，主 Bot 看全团队（自己排最前）。 */
    public List<SkillView> listViews(String botId) {
        Bot current = store.getBot(botId);
        Map<String, Bot> botById = new LinkedHashMap<>();
        for (Bot b : store.listBots(false)) botById.put(b.id(), b);
        List<BotSkill> raw = current.main() ? store.allSkills() : store.skillsForBot(current.id());
        var views = new ArrayList<SkillView>(raw.size());
        for (BotSkill s : raw) {
            Bot owner = botById.get(s.botId());
            if (owner == null) continue;
            views.add(new SkillView(
                    s.id(),
                    s.botId(),
                    owner.name(),
                    s.name(),
                    s.description(),
                    s.version(),
                    s.sourceKind(),
                    s.sourceRepo(),
                    s.sourcePath(),
                    s.sourceRef(),
                    s.fileCount(),
                    parseStringList(s.scriptsJson()),
                    s.totalBytes(),
                    s.userInvoked(),
                    s.useCount(),
                    s.lastUsedAt(),
                    s.installedAt()));
        }
        if (current.main()) {
            views.sort(Comparator.comparingInt((SkillView v) -> v.botId().equals(current.id()) ? 0 : 1)
                    .thenComparing(SkillView::botName)
                    .thenComparing(SkillView::name));
        }
        return views;
    }

    /** 预览尚未安装的待确认卡里的技能文件列表（SKILL.md 排第一）。 */
    public List<SkillFileView> listOfferFiles(String offerId, String skillName) {
        StagedSkill staged = requireOfferSkill(offerId, skillName);
        return staged.files().entrySet().stream()
                .map(e -> new SkillFileView(e.getKey(), e.getValue().length, isScriptPath(e.getKey())))
                .sorted(Comparator.comparingInt((SkillFileView f) -> SKILL_FILE.equals(f.path()) ? 0 : 1)
                        .thenComparing(SkillFileView::path))
                .toList();
    }

    public SkillFileContent readOfferFile(String offerId, String skillName, String filePath) {
        StagedSkill staged = requireOfferSkill(offerId, skillName);
        String rel = normalizeRelPath(filePath);
        byte[] data = staged.files().get(rel);
        if (data == null) throw new HarnessException("NOT_FOUND", "文件不存在：" + rel);
        return toFileContent(rel, data);
    }

    /** 列出已安装技能的磁盘文件列表（SKILL.md 排第一）。 */
    public List<SkillFileView> listInstalledFiles(String botId, String skillName) {
        store.requireSkill(botId, skillName);
        Path dir = skillFolder(botId, skillName);
        if (!Files.isDirectory(dir)) return List.of();
        var result = new ArrayList<SkillFileView>();
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p)).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (hasJunkSegment(rel)) continue;
                result.add(new SkillFileView(rel, Files.size(p), isScriptPath(rel)));
            }
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "读取技能目录失败");
        }
        result.sort(Comparator.comparingInt((SkillFileView f) -> SKILL_FILE.equals(f.path()) ? 0 : 1)
                .thenComparing(SkillFileView::path));
        return result;
    }

    public SkillFileContent readInstalledFile(String botId, String skillName, String filePath) {
        store.requireSkill(botId, skillName);
        String rel = normalizeRelPath(filePath);
        checkRelativePath(rel);
        Path dir = skillFolder(botId, skillName);
        Path target = dir.resolve(rel).normalize();
        if (!target.startsWith(dir) || !Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
            throw new HarnessException("NOT_FOUND", "技能文件不存在：" + rel);
        }
        try {
            byte[] data = Files.readAllBytes(target);
            return toFileContent(rel, data);
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "无法读取技能文件");
        }
    }

    /** 后台任务按需打开技能（use_skill）：支持主 Bot 用 `botId/skillName` 复用任意 Bot 技能。 */
    public String useSkill(String taskBotId, String wanted) {
        Bot caller = store.getBot(taskBotId);
        String raw = wanted == null ? "" : wanted.strip();
        if (raw.isEmpty()) throw new HarnessException("INVALID", "请指定技能名称");

        String explicitOwner = null;
        String skillName = raw;
        int slash = raw.indexOf('/');
        if (slash >= 0) {
            explicitOwner = raw.substring(0, slash).strip();
            skillName = raw.substring(slash + 1).strip();
        }

        List<BotSkill> pool = caller.main() ? store.allSkills() : store.skillsForBot(caller.id());
        String finalOwner = explicitOwner;
        String finalName = skillName;
        List<BotSkill> matches = pool.stream()
                .filter(s -> s.name().equals(finalName) && (finalOwner == null || s.botId().equals(finalOwner)))
                .toList();
        BotSkill chosen = matches.stream()
                .filter(s -> s.botId().equals(caller.id()))
                .findFirst()
                .orElse(matches.size() == 1 ? matches.getFirst() : null);
        if (chosen == null) {
            List<String> usable = pool.stream()
                    .map(s -> s.botId().equals(caller.id()) ? s.name() : s.botId() + "/" + s.name())
                    .toList();
            if (matches.size() > 1) {
                throw new HarnessException(
                        "CONFLICT", "多个 Bot 都安装了同名技能「" + skillName + "」，请使用「BotID/" + skillName + "」指定。可用：" + String.join("、", usable));
            }
            throw new HarnessException("NOT_FOUND", "没有可用的技能「" + wanted + "」。可用：" + (usable.isEmpty() ? "无" : String.join("、", usable)));
        }

        Path dir = skillFolder(chosen.botId(), chosen.name());
        Path skillMd = dir.resolve(SKILL_FILE);
        if (!Files.isRegularFile(skillMd)) {
            throw new HarnessException("NOT_FOUND", "技能「" + chosen.name() + "」的磁盘文件缺失，请重新安装");
        }
        String rawMd;
        try {
            rawMd = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "无法读取技能说明文件");
        }
        String body = extractSkillBody(rawMd);
        boolean truncated = body.length() > MAX_INSTRUCTIONS_CHARS;
        if (truncated) {
            body = body.substring(0, MAX_INSTRUCTIONS_CHARS) + "\n……（正文较长，后续部分请用 library_read 分段读取）";
        }

        List<SkillFileView> files = listInstalledFiles(chosen.botId(), chosen.name());
        String libRelDir = "_skills/" + chosen.botId() + "/" + chosen.name();
        write(() -> {
            store.incrementSkillUse(chosen.botId(), chosen.name());
            return null;
        });

        var sb = new StringBuilder();
        sb.append("技能：").append(chosen.name());
        if (!chosen.version().isBlank()) sb.append(" (v").append(chosen.version()).append(')');
        sb.append('\n');
        sb.append("资料库相对目录（用于 library_read）：").append(libRelDir).append('\n');
        sb.append("本地绝对目录（用于脚本执行）：").append(dir.toAbsolutePath()).append('\n');
        sb.append("包含文件（共 ").append(files.size()).append(" 个）：\n");
        int shown = 0;
        for (SkillFileView f : files) {
            if (shown++ >= 80) {
                sb.append("... 其余 ").append(files.size() - 80).append(" 个文件\n");
                break;
            }
            sb.append("- ").append(libRelDir).append('/').append(f.path());
            if (f.script()) sb.append(" [脚本]");
            sb.append('\n');
        }
        sb.append("\n--- SKILL.md 指引 ---\n").append(body);
        return sb.toString();
    }

    // ── Internal Parsing, Validation & Download ──────────────────────

    private Bot resolveTargetOwner(Bot caller, String targetBotIdOrName) {
        if (targetBotIdOrName == null || targetBotIdOrName.isBlank()) return caller;
        String cleaned = targetBotIdOrName.strip();
        if (cleaned.startsWith("@")) cleaned = cleaned.substring(1).strip();
        Bot target = store.findBotByName(cleaned);
        if (target == null) target = store.getBot(cleaned);
        if (!target.id().equals(caller.id()) && !caller.main()) {
            throw new HarnessException("CONFLICT", "只有主 Bot 可以帮其他 Bot 安装或卸载技能");
        }
        return target;
    }

    private SkillOfferView createOffer(String chatBotId, Bot target, CandidateBundle bundle, boolean appendChatCard) {
        List<String> folders = findSkillFolders(bundle.files().keySet());
        if (folders.isEmpty()) {
            throw new HarnessException("INVALID", "未找到任何包含 SKILL.md 的有效技能目录");
        }
        if (folders.size() > MAX_SKILLS_PER_BOT) {
            throw new HarnessException("INVALID", "单次扫描出的技能超过上限（" + MAX_SKILLS_PER_BOT + " 个）");
        }

        Map<String, BotSkill> existingByName = new HashMap<>();
        for (BotSkill s : store.skillsForBot(target.id())) existingByName.put(s.name(), s);

        var staged = new ArrayList<StagedSkill>();
        var skipped = new ArrayList<SkippedSkill>();
        var seenNames = new HashSet<String>();
        for (String folder : folders) {
            String prefix = folder.isEmpty() ? "" : folder + "/";
            String folderName = folder.isEmpty()
                    ? bundle.rootName()
                    : folder.substring(folder.lastIndexOf('/') + 1);
            var skillFiles = new LinkedHashMap<String, byte[]>();
            for (var entry : bundle.files().entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    String rel = entry.getKey().substring(prefix.length());
                    if (!rel.isEmpty()) skillFiles.put(rel, entry.getValue());
                }
            }
            try {
                ParsedPackage pkg = validatePackage(skillFiles, folderName);
                if (pkg.internal() && folders.size() > 1) continue;
                if (!seenNames.add(pkg.name())) continue;
                BotSkill existing = existingByName.get(pkg.name());
                String srcPath = joinRepoPath(bundle.sourcePrefix(), folder);
                OfferedSkill meta = new OfferedSkill(
                        pkg.name(),
                        pkg.description(),
                        pkg.version(),
                        bundle.sourceKind(),
                        bundle.sourceRepo(),
                        srcPath,
                        bundle.sourceRef(),
                        pkg.fileCount(),
                        pkg.scripts(),
                        pkg.totalBytes(),
                        pkg.userInvoked(),
                        existing == null ? null : (existing.version().isBlank() ? "已安装版" : existing.version()));
                staged.add(new StagedSkill(meta, skillFiles));
            } catch (RuntimeException error) {
                if (folders.size() == 1) throw error;
                skipped.add(new SkippedSkill(folderName, error.getMessage() == null ? "格式无效" : error.getMessage()));
            }
        }
        if (staged.isEmpty()) {
            throw new HarnessException("INVALID", skipped.isEmpty() ? "未找到可安装的技能" : "扫描到的技能均校验未通过：" + skipped.getFirst().reason());
        }

        pruneExpiredOffers();
        String offerId = "sk-" + UUID.randomUUID().toString().substring(0, 12);
        SkillOfferView view = new SkillOfferView(
                offerId,
                target.id(),
                target.name(),
                staged.stream().map(StagedSkill::meta).toList(),
                skipped,
                null);

        Long entrySeq = null;
        if (appendChatCard && chatBotId != null) {
            String cardJson = writeJsonQuietly(view);
            String summaryText = staged.size() == 1
                    ? "技能安装确认：" + staged.getFirst().meta().name()
                    : "技能安装确认：发现 " + staged.size() + " 个技能";
            Entry appended = write(() -> store.appendEntry(
                    chatBotId,
                    "",
                    "SKILL_OFFER",
                    summaryText,
                    null,
                    "",
                    cardJson,
                    "[]",
                    "",
                    "",
                    "",
                    ""));
            entrySeq = appended.seq();
        }
        offers.put(offerId, new PendingOffer(offerId, chatBotId, entrySeq, target.id(), target.name(), staged, skipped, Instant.now()));
        return view;
    }

    private CandidateBundle loadSource(String rawSource) {
        String source = rawSource == null ? "" : rawSource.strip();
        if (source.isEmpty()) throw new HarnessException("INVALID", "请提供 GitHub 链接或本机技能文件夹路径");
        Matcher gh = GITHUB_URL.matcher(source);
        if (gh.matches()) {
            return loadFromGitHub(gh.group(1), gh.group(2), gh.group(3));
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            throw new HarnessException("INVALID", "在线安装目前仅支持公开的 https://github.com/ 仓库链接");
        }
        return loadFromLocalFolder(source);
    }

    private CandidateBundle loadFromGitHub(String owner, String repo, String afterTree) {
        List<String> parts = new ArrayList<>();
        if (afterTree != null && !afterTree.isBlank()) {
            String decoded = URLDecoder.decode(afterTree, StandardCharsets.UTF_8);
            for (String p : decoded.split("/")) {
                if (!p.isBlank()) parts.add(p);
            }
        }
        List<Integer> refLengths = new ArrayList<>();
        if (parts.isEmpty()) {
            refLengths.add(0);
        } else {
            int max = Math.min(3, parts.size());
            for (int len = 1; len <= max; len++) refLengths.add(len);
        }
        for (int len : refLengths) {
            String ref = len == 0 ? "HEAD" : String.join("/", parts.subList(0, len));
            String pointed = len < parts.size() ? String.join("/", parts.subList(len, parts.size())) : "";
            pointed = pointed.replaceFirst("(?i)/?SKILL\\.md$", "").replaceFirst("/$", "");
            String url = "https://github.com/" + owner + "/" + repo + "/archive/" + ref + ".zip";
            byte[] archive = downloadGitHubArchive(url);
            if (archive != null) {
                Map<String, byte[]> files = unpackZipBytes(archive, pointed);
                if (files.isEmpty()) {
                    throw new HarnessException("NOT_FOUND", "仓库 " + owner + "/" + repo + " 中未找到路径：" + pointed);
                }
                String rootName = pointed.isEmpty() ? repo : pointed.substring(pointed.lastIndexOf('/') + 1);
                return new CandidateBundle(files, "GITHUB", owner + "/" + repo, ref, pointed, rootName);
            }
        }
        throw new HarnessException("NOT_FOUND", "在 GitHub 上找不到该公开仓库或分支（私有仓库暂不支持直接拉取）");
    }

    private byte[] downloadGitHubArchive(String initialUrl) {
        String currentUrl = initialUrl;
        for (int hops = 0; hops < 6; hops++) {
            URI uri = URI.create(currentUrl);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_DOWNLOAD_HOSTS.contains(host)) {
                throw new HarnessException("INVALID", "拒绝访问非 GitHub 域名：" + host);
            }
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "JavaHarness-BotSkills/1.0")
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int status = resp.statusCode();
                if (status == 404) {
                    resp.body().close();
                    return null;
                }
                if (status >= 300 && status < 400) {
                    String location = resp.headers().firstValue("Location").orElse("");
                    resp.body().close();
                    if (location.isBlank()) throw new HarnessException("UNAVAILABLE", "GitHub 重定向缺少目标地址");
                    currentUrl = uri.resolve(location).toString();
                    continue;
                }
                if (status < 200 || status >= 300) {
                    resp.body().close();
                    throw new HarnessException("UNAVAILABLE", "GitHub 下载失败（HTTP " + status + "）");
                }
                try (InputStream in = resp.body()) {
                    return readLimited(in, MAX_ARCHIVE_BYTES, "仓库压缩包超过 60 MB 上限，请指向具体技能子目录链接");
                }
            } catch (HarnessException error) {
                throw error;
            } catch (Exception error) {
                throw new HarnessException("UNAVAILABLE", "连接 GitHub 失败：" + error.getMessage());
            }
        }
        throw new HarnessException("UNAVAILABLE", "GitHub 下载重定向次数过多");
    }

    private CandidateBundle loadFromLocalFolder(String input) {
        String expanded = input;
        if (expanded.equals("~") || expanded.startsWith("~/") || expanded.startsWith("~\\")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        Path root = Path.of(expanded).toAbsolutePath().normalize();
        if (!Files.exists(root)) throw new HarnessException("NOT_FOUND", "本地路径不存在：" + root);
        if (Files.isRegularFile(root)) {
            String lower = root.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip") || lower.endsWith(".skill")) {
                try {
                    long size = Files.size(root);
                    if (size > MAX_ARCHIVE_BYTES) throw new HarnessException("INVALID", "压缩包超过 60 MB 上限");
                    byte[] raw = Files.readAllBytes(root);
                    String baseName = root.getFileName().toString().replaceFirst("(?i)\\.(zip|skill)$", "");
                    return new CandidateBundle(unpackZipBytes(raw, ""), "LOCAL", "", "", root.toString(), baseName);
                } catch (IOException error) {
                    throw new HarnessException("UNAVAILABLE", "无法读取本地压缩包");
                }
            }
            throw new HarnessException("INVALID", "请提供技能文件夹路径或 .zip / .skill 压缩包路径");
        }
        if (!Files.isDirectory(root)) throw new HarnessException("INVALID", "路径不是有效文件夹：" + root);

        var files = new LinkedHashMap<String, byte[]>();
        long totalBytes = 0;
        int visited = 0;
        try {
            var stack = new ArrayList<Path>();
            stack.add(root);
            while (!stack.isEmpty()) {
                Path dir = stack.removeLast();
                try (var stream = Files.list(dir)) {
                    for (Path entry : stream.toList()) {
                        if (++visited > MAX_FOLDER_ENTRIES) {
                            throw new HarnessException("INVALID", "文件夹内容过多（超过 " + MAX_FOLDER_ENTRIES + " 项），请直接指向技能子目录");
                        }
                        String name = entry.getFileName().toString();
                        if (isJunkSegment(name) || Files.isSymbolicLink(entry)) continue;
                        if (Files.isDirectory(entry)) {
                            stack.add(entry);
                        } else if (Files.isRegularFile(entry)) {
                            String rel = root.relativize(entry).toString().replace('\\', '/');
                            checkRelativePath(rel);
                            long sz = Files.size(entry);
                            totalBytes += sz;
                            if (totalBytes > MAX_UNPACKED_BYTES) {
                                throw new HarnessException("INVALID", "文件夹总体积超过 100 MB 上限");
                            }
                            files.put(rel, Files.readAllBytes(entry));
                        }
                    }
                }
            }
        } catch (HarnessException error) {
            throw error;
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "扫描本地技能文件夹失败：" + error.getMessage());
        }
        String rootName = root.getFileName() == null ? "skill" : root.getFileName().toString();
        return new CandidateBundle(files, "LOCAL", "", "", root.toString(), rootName);
    }

    static Map<String, byte[]> unpackZipBytes(byte[] zipBytes, String stripSubdirAfterTop) {
        var rawEntries = new LinkedHashMap<String, byte[]>();
        long totalUnpacked = 0;
        try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || hasJunkSegment(name)) continue;
                checkRelativePath(name);
                byte[] data = readLimited(zis, MAX_BYTES_PER_SKILL, "压缩包中单个文件过大");
                totalUnpacked += data.length;
                if (totalUnpacked > MAX_UNPACKED_BYTES) {
                    throw new HarnessException("INVALID", "解压总大小超过 100 MB 安全上限");
                }
                rawEntries.put(name, data);
            }
        } catch (HarnessException error) {
            throw error;
        } catch (IOException error) {
            throw new HarnessException("INVALID", "无法解析 ZIP 压缩包：" + error.getMessage());
        }
        if (rawEntries.isEmpty()) return Map.of();

        // Strip common single top-level directory (e.g. GitHub's repo-branch/ or zipped folder/)
        String commonTop = findCommonTopDirectory(rawEntries.keySet());
        var normalized = new LinkedHashMap<String, byte[]>();
        String subPrefix = stripSubdirAfterTop == null || stripSubdirAfterTop.isBlank()
                ? ""
                : stripSubdirAfterTop.replaceFirst("^/+", "").replaceFirst("/+$", "") + "/";

        for (var e : rawEntries.entrySet()) {
            String path = e.getKey();
            if (commonTop != null && path.startsWith(commonTop + "/")) {
                path = path.substring(commonTop.length() + 1);
            }
            if (!subPrefix.isEmpty()) {
                if (!path.startsWith(subPrefix)) continue;
                path = path.substring(subPrefix.length());
            }
            if (path.isEmpty() || hasJunkSegment(path)) continue;
            checkRelativePath(path);
            normalized.put(path, e.getValue());
        }
        return normalized;
    }

    private static String findCommonTopDirectory(Set<String> paths) {
        String top = null;
        for (String p : paths) {
            int slash = p.indexOf('/');
            if (slash <= 0) return null;
            String candidate = p.substring(0, slash);
            if (top == null) top = candidate;
            else if (!top.equals(candidate)) return null;
        }
        return top;
    }

    private static List<String> findSkillFolders(Set<String> paths) {
        if (paths.contains(SKILL_FILE)) return List.of("");
        var folders = new ArrayList<String>();
        for (String p : paths) {
            int slash = p.lastIndexOf('/');
            String base = slash >= 0 ? p.substring(slash + 1) : p;
            if (SKILL_FILE.equals(base)) {
                folders.add(slash >= 0 ? p.substring(0, slash) : "");
            }
        }
        folders.sort(String::compareTo);
        var outermost = new ArrayList<String>();
        for (String f : folders) {
            boolean nested = false;
            for (String outer : outermost) {
                if (outer.isEmpty() || f.startsWith(outer + "/")) {
                    nested = true;
                    break;
                }
            }
            if (!nested) outermost.add(f);
        }
        return outermost;
    }

    static ParsedPackage validatePackage(Map<String, byte[]> files, String folderName) {
        byte[] skillBytes = files.get(SKILL_FILE);
        if (skillBytes == null) throw new HarnessException("INVALID", "缺少 " + SKILL_FILE);
        if (files.size() > MAX_FILES_PER_SKILL) {
            throw new HarnessException("INVALID", "技能文件数超过上限（" + MAX_FILES_PER_SKILL + " 个）");
        }
        long totalBytes = 0;
        var seenCaseInsensitive = new HashMap<String, String>();
        var scripts = new ArrayList<String>();
        for (var e : files.entrySet()) {
            String rel = e.getKey();
            checkRelativePath(rel);
            String folded = Normalizer.normalize(rel, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            String clash = seenCaseInsensitive.putIfAbsent(folded, rel);
            if (clash != null) {
                throw new HarnessException("INVALID", "存在大小写冲突的文件路径：" + clash + " 与 " + rel);
            }
            totalBytes += e.getValue().length;
            if (isScriptPath(rel)) scripts.add(rel);
        }
        if (totalBytes > MAX_BYTES_PER_SKILL) {
            throw new HarnessException("INVALID", "单个技能总大小超过 20 MB 上限");
        }
        scripts.sort(String::compareTo);

        String md = new String(skillBytes, StandardCharsets.UTF_8);
        Map<String, String> fm = parseFrontmatter(md);
        String name = fm.getOrDefault("name", folderName == null ? "" : folderName).strip();
        if (!SKILL_NAME.matcher(name).matches()) {
            throw new HarnessException("INVALID", "技能名不合法（仅允许字母、数字、点、横杠与下划线）：" + name);
        }
        String desc = fm.getOrDefault("description", "").strip();
        if (desc.isEmpty()) {
            throw new HarnessException("INVALID", "SKILL.md 缺少 description 字段");
        }
        if (desc.length() > 900) desc = desc.substring(0, 900);
        String version = fm.getOrDefault("version", fm.getOrDefault("metadata.version", "")).strip();
        if (version.length() > 60) version = version.substring(0, 60);
        boolean userInvoked = "true".equalsIgnoreCase(fm.getOrDefault("disable-model-invocation", ""));
        String internalVal = fm.getOrDefault("metadata.internal", "");
        boolean internal = "true".equalsIgnoreCase(internalVal) || "1".equals(internalVal);
        return new ParsedPackage(name, desc, version, files.size(), List.copyOf(scripts), totalBytes, userInvoked, internal);
    }

    static Map<String, String> parseFrontmatter(String markdown) {
        var result = new LinkedHashMap<String, String>();
        if (markdown == null) return result;
        String text = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) return result;
        int start = text.indexOf('\n') + 1;
        int end = findFrontmatterEnd(text, start);
        if (end < 0) return result;
        String[] lines = text.substring(start, end).split("\\r?\\n");
        Pattern pairRe = Pattern.compile("^([\\w-]+):\\s*(.*)$");
        Pattern nestedRe = Pattern.compile("^\\s+([\\w-]+):\\s*(.*)$");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = pairRe.matcher(lines[i]);
            if (!m.matches()) continue;
            String key = m.group(1);
            String val = m.group(2).strip();
            var indented = new ArrayList<String>();
            while (i + 1 < lines.length && (lines[i + 1].isBlank() || Character.isWhitespace(lines[i + 1].charAt(0)))) {
                indented.add(lines[++i]);
            }
            if (val.matches("^[|>][-+]?$")) {
                var trimmed = new ArrayList<String>();
                for (String line : indented) trimmed.add(line.strip());
                String joined = val.startsWith("|")
                        ? String.join("\n", trimmed).strip()
                        : trimmed.stream().filter(s -> !s.isEmpty()).reduce((a, b) -> a + " " + b).orElse("");
                result.put(key, joined);
            } else if (val.isEmpty()) {
                for (String line : indented) {
                    Matcher nm = nestedRe.matcher(line);
                    if (nm.matches()) result.put(key + "." + nm.group(1), unquoteYamlScalar(nm.group(2)));
                }
            } else {
                var parts = new ArrayList<String>();
                parts.add(val);
                for (String line : indented) {
                    if (!line.isBlank()) parts.add(line.strip());
                }
                result.put(key, unquoteYamlScalar(String.join(" ", parts).strip()));
            }
        }
        return result;
    }

    static String extractSkillBody(String markdown) {
        if (markdown == null) return "";
        String text = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) return text.strip();
        int start = text.indexOf('\n') + 1;
        int end = findFrontmatterEnd(text, start);
        if (end < 0) return text.strip();
        int after = text.indexOf('\n', end);
        return after < 0 ? "" : text.substring(after + 1).strip();
    }

    private static int findFrontmatterEnd(String text, int start) {
        int idx = start;
        while (idx < text.length()) {
            int nl = text.indexOf('\n', idx);
            String line = (nl < 0 ? text.substring(idx) : text.substring(idx, nl)).strip();
            if ("---".equals(line)) return idx;
            if (nl < 0) break;
            idx = nl + 1;
        }
        return -1;
    }

    private static String unquoteYamlScalar(String value) {
        String s = value.strip();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s.replaceAll("\\s+#.*$", "").strip();
    }

    private void placeFilesAtomically(String offerId, String botId, String skillName, Map<String, byte[]> files) {
        Path target = skillFolder(botId, skillName);
        Path staging = skillsRoot.resolve(".staging-" + offerId + "-" + skillName).normalize();
        Path replaced = skillsRoot.resolve(".staging-" + offerId + "-" + skillName + "-old").normalize();
        try {
            Files.createDirectories(staging);
            for (var e : files.entrySet()) {
                Path out = staging.resolve(e.getKey()).normalize();
                if (!out.startsWith(staging)) throw new HarnessException("INVALID", "非法技能文件路径：" + e.getKey());
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                Files.write(out, e.getValue());
            }
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                deleteTreeQuietly(replaced);
                Files.move(target, replaced);
            }
            Files.move(staging, target);
            deleteTreeQuietly(replaced);
        } catch (IOException error) {
            deleteTreeQuietly(staging);
            if (Files.exists(replaced) && !Files.exists(target)) {
                try {
                    Files.move(replaced, target);
                } catch (IOException ignored) {
                    // Best effort rollback.
                }
            }
            throw new HarnessException("UNAVAILABLE", "写入技能文件失败：" + error.getMessage());
        }
    }

    private Path skillFolder(String botId, String skillName) {
        Path target = skillsRoot.resolve(botId).resolve(skillName).normalize();
        if (!target.startsWith(skillsRoot)) throw new HarnessException("INVALID", "非法技能路径");
        return target;
    }

    private StagedSkill requireOfferSkill(String offerId, String skillName) {
        pruneExpiredOffers();
        PendingOffer offer = offers.get(offerId);
        if (offer == null) throw new HarnessException("NOT_FOUND", "确认卡已过期");
        return offer.packages().stream()
                .filter(p -> p.meta().name().equals(skillName))
                .findFirst()
                .orElseThrow(() -> new HarnessException("NOT_FOUND", "确认卡中不存在技能：" + skillName));
    }

    private void pruneExpiredOffers() {
        Instant cutoff = Instant.now().minus(OFFER_TTL);
        offers.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    private static SkillFileContent toFileContent(String rel, byte[] data) {
        if (data.length > MAX_PREVIEW_BYTES) {
            return new SkillFileContent(rel, null, false, true, data.length);
        }
        int checkLen = Math.min(data.length, 8000);
        for (int i = 0; i < checkLen; i++) {
            if (data[i] == 0) {
                return new SkillFileContent(rel, null, true, false, data.length);
            }
        }
        return new SkillFileContent(rel, new String(data, StandardCharsets.UTF_8), false, false, data.length);
    }

    private static byte[] readLimited(InputStream in, long maxBytes, String errMsg) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) throw new HarnessException("INVALID", errMsg);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static void checkRelativePath(String path) {
        if (path == null || path.isEmpty() || path.contains("\\") || path.contains(":")) {
            throw new HarnessException("INVALID", "存在不安全的技能文件路径：" + path);
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new HarnessException("INVALID", "存在不安全的技能文件路径：" + path);
            }
        }
    }

    private static String normalizeRelPath(String raw) {
        String p = raw == null ? "" : raw.replace('\\', '/').strip();
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private static boolean hasJunkSegment(String path) {
        for (String part : path.split("/")) {
            if (isJunkSegment(part)) return true;
        }
        return false;
    }

    private static boolean isJunkSegment(String part) {
        return JUNK_NAMES.contains(part) || part.startsWith("._");
    }

    static boolean isScriptPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return false;
        return SCRIPT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String joinRepoPath(String a, String b) {
        String left = a == null ? "" : a.strip();
        String right = b == null ? "" : b.strip();
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + "/" + right;
    }

    private List<String> parseStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            var node = json.readTree(raw);
            if (!node.isArray()) return List.of();
            var list = new ArrayList<String>();
            for (var item : node) {
                if (item.isTextual()) list.add(item.asText());
            }
            return List.copyOf(list);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeJsonQuietly(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private <T> T write(Supplier<T> work) {
        synchronized (sessions) {
            return tx.execute(status -> work.get());
        }
    }
}
