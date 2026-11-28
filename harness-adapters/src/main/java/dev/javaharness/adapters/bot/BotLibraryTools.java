package dev.javaharness.adapters.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bot 资料库工具装饰器：为后台任务提供 bot-library/ 下的免 Diff 自动落盘读写、局部编辑、检索、
 * 小程序保存、技能包按需打开（use_skill）与阶段进度汇报能力，同时保留底层工作区与 MCP 工具。
 */
public final class BotLibraryTools implements WorkspaceTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_WRITE_BYTES = 512_000;
    private static final int MAX_READ_LINES = 200;

    private static final List<ToolDefinition> DEFINITIONS = List.of(
            new ToolDefinition(
                    "library_list",
                    "列出 Bot 本地资料库（bot-library）中的目录和文件",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("path", Map.of("type", "string", "description", "相对路径，留空表示资料库根目录")),
                            "additionalProperties",
                            false)),
            new ToolDefinition(
                    "library_read",
                    "读取 Bot 本地资料库（bot-library）中的文本文件，每次最多返回 200 行",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "path", Map.of("type", "string", "description", "相对文件路径"),
                                    "start_line", Map.of("type", "integer", "description", "起始行号（从 1 开始）"),
                                    "end_line", Map.of("type", "integer", "description", "结束行号")),
                            "required",
                            List.of("path"),
                            "additionalProperties",
                            false)),
            new ToolDefinition(
                    "library_write",
                    "写入或覆盖 Bot 本地资料库（bot-library）中的文档、报告或笔记（自动落盘，无需 Diff 审批）",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "path", Map.of("type", "string", "description", "相对文件路径，如 reports/weekly.md"),
                                    "content", Map.of("type", "string", "description", "UTF-8 文件内容")),
                            "required",
                            List.of("path", "content"),
                            "additionalProperties",
                            false)),
            new ToolDefinition(
                    "library_edit",
                    "精确局部修改 Bot 本地资料库中的已有文本文件（old_text 必须在文件中唯一匹配，避免整篇重写）",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "path", Map.of("type", "string", "description", "相对文件路径"),
                                    "old_text", Map.of("type", "string", "description", "要被替换的原文片段（必须精确且唯一匹配）"),
                                    "new_text", Map.of("type", "string", "description", "替换后的新文本")),
                            "required",
                            List.of("path", "old_text", "new_text"),
                            "additionalProperties",
                            false)),
            new ToolDefinition(
                    "library_search",
                    "按关键词检索 Bot 本地资料库（bot-library）中的文件名与文本内容",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "query", Map.of("type", "string", "description", "检索关键词"),
                                    "path", Map.of("type", "string", "description", "可选子目录")),
                            "required",
                            List.of("query"),
                            "additionalProperties",
                            false)),
            new ToolDefinition(
                    "set_status",
                    "更新当前后台任务正在执行的阶段进度（显示给用户看，如「拉取最新提交」「汇总周报」，不超过 30 字）",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("text", Map.of("type", "string", "description", "简短动宾进度短语（1–30 字，不加「正在」）")),
                            "required",
                            List.of("text"),
                            "additionalProperties",
                            false)));

    private static final ToolDefinition USE_SKILL_DEF = new ToolDefinition(
            "use_skill",
            "打开一个已安装的技能包：返回其 SKILL.md 完整说明、技能目录路径和文件列表；之后可用 library_read 读参考资料、propose_command 跑脚本",
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("skill", Map.of("type", "string", "description", "技能名称；主 Bot 使用其他 Bot 的技能时写成「BotID/技能名」")),
                    "required",
                    List.of("skill"),
                    "additionalProperties",
                    false));

    /** 可选小程序工具处理器：由服务层注入，实现 save_app / update_app_data。 */
    public interface AppTools {
        String saveApp(
                String appId,
                String name,
                String description,
                String icon,
                String color,
                String changeSummary,
                String html,
                String initialDataJson);

        String updateAppData(String appId, String dataJson);
    }

    private static final List<ToolDefinition> APP_DEFINITIONS = List.of(
            new ToolDefinition(
                    "save_app",
                    "创建或更新一个交互式单文件 HTML 小程序（改页面会递增版本号并留存历史版本；页面内可用 window.harnessBot.getData/setData/onData/sendMessage 或 window.harnessBot.data/save/ask 与数据及 Bot 交互）",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.ofEntries(
                                    Map.entry("app_id", Map.of("type", "string", "description", "可选：更新已有小程序时传入其 ID，新建留空")),
                                    Map.entry("name", Map.of("type", "string", "description", "小程序名称（1-60 字）")),
                                    Map.entry("description", Map.of("type", "string", "description", "一句话功能简介")),
                                    Map.entry("icon", Map.of("type", "string", "description", "可选图标：chart/table/calendar/doc/check/clock/team/chat/folder/cube")),
                                    Map.entry("color", Map.of("type", "string", "description", "可选主题色（十六进制，如 #2d5d9c）")),
                                    Map.entry("change", Map.of("type", "string", "description", "可选：本次改动说明（一句话，用于版本记录与聊天卡片）")),
                                    Map.entry("html", Map.of("type", "string", "description", "完整单文件 HTML（可引用公共 CDN 图表/样式库）")),
                                    Map.entry("data", Map.of("type", "object", "description", "可选：初始 JSON 数据对象"))),
                            "required",
                            List.of("name", "html"),
                            "additionalProperties",
                            false)),
            new ToolDefinition(
                    "update_app_data",
                    "仅更新已有小程序的 JSON 数据（不升页面版本，实时推送给已打开或收起的小程序浮窗）",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "app_id", Map.of("type", "string", "description", "目标小程序 ID"),
                                    "data", Map.of("type", "object", "description", "新的 JSON 数据对象")),
                            "required",
                            List.of("app_id", "data"),
                            "additionalProperties",
                            false)));

    private final WorkspaceTools delegate;
    private final Path root;
    private final BiConsumer<String, Long> onFileWritten;
    private final AppTools appTools;
    private final Consumer<String> onStatus;
    private final Function<String, String> skillOpener;

    public BotLibraryTools(WorkspaceTools delegate, Path root, BiConsumer<String, Long> onFileWritten) {
        this(delegate, root, onFileWritten, null, null, null);
    }

    public BotLibraryTools(
            WorkspaceTools delegate, Path root, BiConsumer<String, Long> onFileWritten, AppTools appTools) {
        this(delegate, root, onFileWritten, appTools, null, null);
    }

    public BotLibraryTools(
            WorkspaceTools delegate,
            Path root,
            BiConsumer<String, Long> onFileWritten,
            AppTools appTools,
            Consumer<String> onStatus) {
        this(delegate, root, onFileWritten, appTools, onStatus, null);
    }

    public BotLibraryTools(
            WorkspaceTools delegate,
            Path root,
            BiConsumer<String, Long> onFileWritten,
            AppTools appTools,
            Consumer<String> onStatus,
            Function<String, String> skillOpener) {
        this.delegate = delegate == null ? WorkspaceTools.disabled() : delegate;
        this.root = root.toAbsolutePath().normalize();
        this.onFileWritten = onFileWritten;
        this.appTools = appTools;
        this.onStatus = onStatus;
        this.skillOpener = skillOpener;
    }

    @Override
    public String workspace() {
        return delegate.workspace();
    }

    @Override
    public List<ToolDefinition> definitions() {
        var all = new ArrayList<ToolDefinition>(delegate.definitions());
        for (ToolDefinition tool : DEFINITIONS) {
            if (all.stream().noneMatch(existing -> existing.name().equals(tool.name()))) {
                all.add(tool);
            }
        }
        if (appTools != null) {
            for (ToolDefinition tool : APP_DEFINITIONS) {
                if (all.stream().noneMatch(existing -> existing.name().equals(tool.name()))) {
                    all.add(tool);
                }
            }
        }
        if (skillOpener != null && all.stream().noneMatch(existing -> existing.name().equals(USE_SKILL_DEF.name()))) {
            all.add(USE_SKILL_DEF);
        }
        return List.copyOf(all);
    }

    @Override
    public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return execute("", call, cancellation);
    }

    @Override
    public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return switch (call.name()) {
            case "library_list" -> list(call.arguments(), cancellation);
            case "library_read" -> read(call.arguments(), cancellation);
            case "library_write" -> write(call.arguments(), cancellation);
            case "library_edit" -> edit(call.arguments(), cancellation);
            case "library_search" -> search(call.arguments(), cancellation);
            case "set_status" -> setStatus(call.arguments(), cancellation);
            case "save_app" -> saveApp(call.arguments(), cancellation);
            case "update_app_data" -> updateAppData(call.arguments(), cancellation);
            case "use_skill" -> useSkill(call.arguments(), cancellation);
            default -> delegate.execute(runId, call, cancellation);
        };
    }

    private Result useSkill(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        if (skillOpener == null) return new Result(false, "技能服务未启用");
        JsonNode node = parse(args);
        String name = node.path("skill").asText("").strip();
        if (name.isEmpty()) return new Result(false, "缺少 skill 名称参数");
        try {
            return new Result(true, skillOpener.apply(name));
        } catch (RuntimeException error) {
            return new Result(false, error.getMessage() == null ? "打开技能失败" : error.getMessage());
        }
    }

    private Result saveApp(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        if (appTools == null) return new Result(false, "小程序服务未启用");
        JsonNode node = parse(args);
        String appId = node.path("app_id").asText("").strip();
        String name = node.path("name").asText("").strip();
        String desc = node.path("description").asText("").strip();
        String icon = node.path("icon").asText("").strip();
        String color = node.path("color").asText("").strip();
        String change = node.path("change").asText("").strip();
        String html = node.path("html").asText("");
        if (name.isEmpty() || html.isBlank()) return new Result(false, "name 与 html 不能为空");
        String dataJson =
                node.has("data") && !node.get("data").isNull() ? JSON.writeValueAsString(node.get("data")) : null;
        return new Result(true, appTools.saveApp(appId, name, desc, icon, color, change, html, dataJson));
    }

    private Result updateAppData(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        if (appTools == null) return new Result(false, "小程序服务未启用");
        JsonNode node = parse(args);
        String appId = node.path("app_id").asText("").strip();
        if (appId.isEmpty() || !node.has("data") || node.get("data").isNull()) {
            return new Result(false, "app_id 与 data 不能为空");
        }
        String dataJson = JSON.writeValueAsString(node.get("data"));
        return new Result(true, appTools.updateAppData(appId, dataJson));
    }

    private Path resolveSafe(String raw) throws IOException {
        Files.createDirectories(root);
        String cleaned = raw == null ? "" : raw.replace('\\', '/').strip();
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        Path target = cleaned.isEmpty() ? root : root.resolve(cleaned).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("路径不能越出资料库根目录");
        }
        return target;
    }

    private boolean isInternalReserved(String rel) {
        return rel.equals("_skills") || rel.startsWith("_skills/") || rel.equals("avatars") || rel.startsWith("avatars/");
    }

    private Result list(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode node = parse(args);
        String requested = node.path("path").asText("");
        Path dir = resolveSafe(requested);
        if (!Files.exists(dir)) return new Result(true, "（目录为空）");
        if (!Files.isDirectory(dir)) return new Result(false, "指定路径不是目录");
        boolean explicitlyInSkills = requested.replace('\\', '/').strip().startsWith("_skills");
        var lines = new ArrayList<String>();
        try (var stream = Files.walk(dir, 3)) {
            stream.filter(path -> !path.equals(dir)).sorted().forEach(path -> {
                if (lines.size() >= 200) return;
                String rel = root.relativize(path).toString().replace('\\', '/');
                if (!explicitlyInSkills && isInternalReserved(rel)) return;
                lines.add(Files.isDirectory(path) ? rel + "/" : rel);
            });
        }
        return new Result(true, lines.isEmpty() ? "（资料库为空）" : String.join("\n", lines));
    }

    private Result read(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode node = parse(args);
        String rawPath = node.path("path").asText("");
        if (rawPath.isBlank()) return new Result(false, "缺少 path 参数");
        Path file = resolveSafe(rawPath);
        if (!Files.exists(file) || !Files.isRegularFile(file)) return new Result(false, "文件不存在：" + rawPath);
        List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
        int start = Math.max(1, node.path("start_line").asInt(1));
        int requestedEnd = node.path("end_line").asInt(start + MAX_READ_LINES - 1);
        int end = Math.min(all.size(), Math.min(requestedEnd, start + MAX_READ_LINES - 1));
        var sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            sb.append(i).append(": ").append(all.get(i - 1)).append('\n');
        }
        if (end < all.size()) {
            sb.append("...（共 ")
                    .append(all.size())
                    .append(" 行，可继续从第 ")
                    .append(end + 1)
                    .append(" 行读取）");
        }
        return new Result(true, sb.toString());
    }

    private Result write(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode node = parse(args);
        String rawPath = node.path("path").asText("");
        if (rawPath.isBlank()) return new Result(false, "缺少 path 参数");
        String content = node.path("content").asText("");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) {
            return new Result(false, "文件内容过大（超过 " + MAX_WRITE_BYTES + " 字节）");
        }
        Path file = resolveSafe(rawPath);
        if (file.equals(root)) return new Result(false, "请指定具体文件路径");
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (isInternalReserved(relative)) {
            return new Result(false, "不能直接写入系统保留目录（_skills / avatars）");
        }
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        if (onFileWritten != null) onFileWritten.accept(relative, (long) bytes.length);
        return new Result(true, "已写入资料库文件：" + relative + "（" + bytes.length + " 字节）");
    }

    private Result edit(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode node = parse(args);
        String rawPath = node.path("path").asText("");
        String oldText = node.path("old_text").asText("");
        String newText = node.path("new_text").asText("");
        if (rawPath.isBlank()) return new Result(false, "缺少 path 参数");
        if (oldText.isEmpty()) return new Result(false, "old_text 不能为空");
        Path file = resolveSafe(rawPath);
        if (!Files.exists(file) || !Files.isRegularFile(file)) return new Result(false, "文件不存在：" + rawPath);
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (isInternalReserved(relative)) {
            return new Result(false, "不能直接修改系统保留目录（_skills / avatars）");
        }
        String current = Files.readString(file, StandardCharsets.UTF_8);
        int first = current.indexOf(oldText);
        if (first < 0) return new Result(false, "文件中未找到指定的 old_text，请先使用 library_read 核对原文");
        if (current.indexOf(oldText, first + oldText.length()) >= 0) {
            return new Result(false, "old_text 在文件中匹配到多处，请提供更长的上下文使其唯一");
        }
        String updated = current.substring(0, first) + newText + current.substring(first + oldText.length());
        byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) {
            return new Result(false, "修改后文件内容过大（超过 " + MAX_WRITE_BYTES + " 字节）");
        }
        Files.write(file, bytes);
        if (onFileWritten != null) onFileWritten.accept(relative, (long) bytes.length);
        return new Result(true, "已局部更新资料库文件：" + relative + "（" + bytes.length + " 字节）");
    }

    private Result setStatus(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode node = parse(args);
        String text = node.path("text").asText("").replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isEmpty()) return new Result(false, "进度 text 不能为空");
        if (text.length() > 60) text = text.substring(0, 60);
        if (onStatus != null) onStatus.accept(text);
        return new Result(true, "已更新阶段进度：" + text);
    }

    private Result search(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode node = parse(args);
        String query = node.path("query").asText("").strip();
        if (query.isEmpty()) return new Result(false, "缺少 query 参数");
        String lower = query.toLowerCase();
        String requested = node.path("path").asText("");
        Path dir = resolveSafe(requested);
        if (!Files.exists(dir)) return new Result(true, "未找到匹配结果");
        boolean explicitlyInSkills = requested.replace('\\', '/').strip().startsWith("_skills");
        var hits = new ArrayList<String>();
        try (var stream = Files.walk(dir, 5)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                cancellation.check();
                String rel = root.relativize(path).toString().replace('\\', '/');
                if (!explicitlyInSkills && isInternalReserved(rel)) continue;
                if (rel.toLowerCase().contains(lower)) {
                    hits.add("[文件] " + rel);
                }
                if (Files.size(path) > MAX_WRITE_BYTES) continue;
                try {
                    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size() && hits.size() < 40; i++) {
                        if (lines.get(i).toLowerCase().contains(lower)) {
                            hits.add(rel + ":" + (i + 1) + ": " + lines.get(i).strip());
                        }
                    }
                } catch (IOException ignored) {
                    // Skip binary files.
                }
                if (hits.size() >= 40) break;
            }
        }
        return new Result(true, hits.isEmpty() ? "未找到匹配结果" : String.join("\n", hits));
    }

    private static JsonNode parse(String args) throws IOException {
        if (args == null || args.isBlank()) return JSON.createObjectNode();
        return JSON.readTree(args);
    }
}
