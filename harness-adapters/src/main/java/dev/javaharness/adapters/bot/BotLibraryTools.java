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

/**
 * Bot 资料库工具装饰器：为后台任务提供 bot-library/ 下的免 Diff 自动落盘读写与检索能力，
 * 同时保留底层可选绑定的项目工作区工具与 MCP 工具。
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
                    "写入或更新 Bot 本地资料库（bot-library）中的文档、报告或笔记（自动落盘，无需 Diff 审批）",
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
                            false)));

    /** 可选小程序工具处理器：由服务层注入，实现 save_app / update_app_data。 */
    public interface AppTools {
        String saveApp(String appId, String name, String description, String html, String initialDataJson);

        String updateAppData(String appId, String dataJson);
    }

    private static final List<ToolDefinition> APP_DEFINITIONS = List.of(
            new ToolDefinition(
                    "save_app",
                    "创建或更新一个交互式单文件 HTML 小程序（改页面会递增版本号；页面内可用 window.harnessBot.getData/setData/onData/sendMessage 与数据及 Bot 交互）",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "app_id", Map.of("type", "string", "description", "可选：更新已有小程序时传入其 ID，新建留空"),
                                    "name", Map.of("type", "string", "description", "小程序名称（1-60 字）"),
                                    "description", Map.of("type", "string", "description", "一句话功能简介"),
                                    "html", Map.of("type", "string", "description", "完整单文件 HTML（可引用公共 CDN 图表/样式库）"),
                                    "data", Map.of("type", "object", "description", "可选：初始 JSON 数据对象")),
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

    public BotLibraryTools(WorkspaceTools delegate, Path root, BiConsumer<String, Long> onFileWritten) {
        this(delegate, root, onFileWritten, null);
    }

    public BotLibraryTools(
            WorkspaceTools delegate, Path root, BiConsumer<String, Long> onFileWritten, AppTools appTools) {
        this.delegate = delegate == null ? WorkspaceTools.disabled() : delegate;
        this.root = root.toAbsolutePath().normalize();
        this.onFileWritten = onFileWritten;
        this.appTools = appTools;
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
            case "library_search" -> search(call.arguments(), cancellation);
            case "save_app" -> saveApp(call.arguments(), cancellation);
            case "update_app_data" -> updateAppData(call.arguments(), cancellation);
            default -> delegate.execute(runId, call, cancellation);
        };
    }

    private Result saveApp(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        if (appTools == null) return new Result(false, "小程序服务未启用");
        JsonNode node = parse(args);
        String appId = node.path("app_id").asText("").strip();
        String name = node.path("name").asText("").strip();
        String desc = node.path("description").asText("").strip();
        String html = node.path("html").asText("");
        if (name.isEmpty() || html.isBlank()) return new Result(false, "name 与 html 不能为空");
        String dataJson =
                node.has("data") && !node.get("data").isNull() ? JSON.writeValueAsString(node.get("data")) : null;
        return new Result(true, appTools.saveApp(appId, name, desc, html, dataJson));
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

    private Result list(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode node = parse(args);
        Path dir = resolveSafe(node.path("path").asText(""));
        if (!Files.exists(dir)) return new Result(true, "（目录为空）");
        if (!Files.isDirectory(dir)) return new Result(false, "指定路径不是目录");
        var lines = new ArrayList<String>();
        try (var stream = Files.walk(dir, 3)) {
            stream.filter(path -> !path.equals(dir)).sorted().limit(200).forEach(path -> {
                String rel = root.relativize(path).toString().replace('\\', '/');
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
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (onFileWritten != null) onFileWritten.accept(relative, (long) bytes.length);
        return new Result(true, "已写入资料库文件：" + relative + "（" + bytes.length + " 字节）");
    }

    private Result search(String args, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode node = parse(args);
        String query = node.path("query").asText("").strip();
        if (query.isEmpty()) return new Result(false, "缺少 query 参数");
        String lower = query.toLowerCase();
        Path dir = resolveSafe(node.path("path").asText(""));
        if (!Files.exists(dir)) return new Result(true, "未找到匹配结果");
        var hits = new ArrayList<String>();
        try (var stream = Files.walk(dir, 5)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                cancellation.check();
                String rel = root.relativize(path).toString().replace('\\', '/');
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
