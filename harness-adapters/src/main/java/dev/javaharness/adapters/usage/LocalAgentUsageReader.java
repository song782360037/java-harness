package dev.javaharness.adapters.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 读取本机其他 agent 的会话日志并汇总 token 用量：Claude Code（~/.claude/projects）、Codex（~/.codex/sessions，含 zstd 压缩的旧日志）、
 * WorkBuddy（~/.workbuddy/projects）与 Qoder（~/.qoder/projects，格式同 Claude Code）。
 * 按文件大小与修改时间缓存解析结果，只重新解析有变化的文件；无法解析的行直接跳过。
 */
public final class LocalAgentUsageReader {
    public static final String CLAUDE_CODE = "claude-code";
    public static final String CODEX = "codex";
    public static final String WORKBUDDY = "workbuddy";
    public static final String QODER = "qoder";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单条用量；key 非空时用于跨文件去重（恢复会话会复制历史消息）。inputTokens 含缓存命中部分。 */
    public record AgentUsage(
            String source,
            String key,
            Instant at,
            String model,
            long inputTokens,
            long outputTokens,
            long cachedTokens) {}

    private record Parsed(long size, long modified, List<AgentUsage> entries) {}

    private final Map<String, List<Path>> roots;
    private final Map<Path, Parsed> cache = new HashMap<>();

    public LocalAgentUsageReader(Path home) {
        roots = Map.of(
                CLAUDE_CODE, List.of(home.resolve(".claude").resolve("projects")),
                CODEX,
                        List.of(
                                home.resolve(".codex").resolve("sessions"),
                                home.resolve(".codex").resolve("archived_sessions")),
                WORKBUDDY, List.of(home.resolve(".workbuddy").resolve("projects")),
                QODER, List.of(home.resolve(".qoder").resolve("projects")));
    }

    /** 该来源的日志目录是否存在。 */
    public boolean available(String source) {
        return roots.getOrDefault(source, List.of()).stream().anyMatch(Files::isDirectory);
    }

    /** 返回指定时刻及之后的用量，同一 key 只保留输出最多的一条（流式写入时早期行的输出尚不完整）。 */
    public synchronized List<AgentUsage> usageSince(Instant from) {
        Set<Path> seen = new HashSet<>();
        List<AgentUsage> all = new ArrayList<>();
        roots.forEach((source, paths) -> {
            for (Path root : paths) all.addAll(scan(root, source, seen));
        });
        cache.keySet().retainAll(seen);
        Map<String, AgentUsage> keyed = new LinkedHashMap<>();
        List<AgentUsage> result = new ArrayList<>();
        for (AgentUsage usage : all) {
            if (usage.at().isBefore(from)) continue;
            if (usage.key() == null) result.add(usage);
            else keyed.merge(usage.key(), usage, (a, b) -> b.outputTokens() > a.outputTokens() ? b : a);
        }
        result.addAll(keyed.values());
        return result;
    }

    private List<AgentUsage> scan(Path root, String source, Set<Path> seen) {
        if (!Files.isDirectory(root)) return List.of();
        List<Path> files = new ArrayList<>();
        try {
            // Unreadable subdirectories are skipped instead of hiding the whole source.
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.toString();
                    // Codex compresses older rollouts; skip a .zst while its plain copy still exists.
                    if (attributes.isRegularFile()
                            && (name.endsWith(".jsonl")
                                    || name.endsWith(".jsonl.zst")
                                            && !Files.exists(file.resolveSibling(file.getFileName()
                                                    .toString()
                                                    .replaceFirst("\\.zst$", ""))))) files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        List<AgentUsage> result = new ArrayList<>();
        for (Path file : files) {
            long size;
            long modified;
            try {
                size = Files.size(file);
                modified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                continue;
            }
            Parsed parsed = cache.get(file);
            if (parsed == null || parsed.size() != size || parsed.modified() != modified) {
                List<AgentUsage> entries;
                try {
                    entries = switch (source) {
                        case CODEX -> codex(file);
                        case WORKBUDDY -> workbuddy(file);
                        default -> claude(file, source);
                    };
                } catch (IOException e) {
                    // A file locked or deleted before opening is retried on the next request.
                    continue;
                } catch (RuntimeException e) {
                    // Corrupt or truncated content counts as empty until the file changes, instead of failing the
                    // request.
                    entries = List.of();
                }
                parsed = new Parsed(size, modified, entries);
                cache.put(file, parsed);
            }
            seen.add(file);
            result.addAll(parsed.entries());
        }
        return result;
    }

    /**
     * Claude Code 与 Qoder 的 input_tokens 不含缓存，这里合计为总输入；同一消息按内容块拆成多行，用消息 id 去重。
     * Qoder 内置模型只记录 credits、token 为 0，这类记录会被跳过。
     */
    private static List<AgentUsage> claude(Path file, String source) throws IOException {
        List<AgentUsage> entries = new ArrayList<>();
        try (Stream<JsonNode> nodes = lines(file, "\"usage\"")) {
            for (JsonNode node : (Iterable<JsonNode>) nodes::iterator) {
                JsonNode message = node.path("message");
                JsonNode usage = message.path("usage");
                String model = message.path("model").asText("");
                if (!"assistant".equals(node.path("type").asText()) || !usage.isObject() || model.equals("<synthetic>"))
                    continue;
                Instant at = timestamp(node);
                if (at == null) continue;
                long cached = usage.path("cache_read_input_tokens").asLong();
                long input = usage.path("input_tokens").asLong()
                        + usage.path("cache_creation_input_tokens").asLong()
                        + cached;
                long output = usage.path("output_tokens").asLong();
                if (input + output == 0) continue;
                String id = message.path("id").asText("");
                String key = id.isEmpty()
                        ? null
                        : source + ":" + id + ":" + node.path("requestId").asText("");
                entries.add(
                        new AgentUsage(source, key, at, model.isEmpty() ? "unknown" : model, input, output, cached));
            }
        }
        return entries;
    }

    /**
     * WorkBuddy 的用量在 providerData.usage，inputTokens 已含缓存命中；同一回复的多个工具调用各占一行且用量相同，
     * 而 messageId 偶尔会被不同请求复用，因此以 messageId 加输入 token 作为去重键，同键保留输出最多的一条。
     */
    private static List<AgentUsage> workbuddy(Path file) throws IOException {
        List<AgentUsage> entries = new ArrayList<>();
        try (Stream<JsonNode> nodes = lines(file, "\"usage\"")) {
            for (JsonNode node : (Iterable<JsonNode>) nodes::iterator) {
                JsonNode data = node.path("providerData");
                JsonNode usage = data.path("usage");
                if (!usage.isObject()) continue;
                long input = usage.path("inputTokens").asLong();
                long output = usage.path("outputTokens").asLong();
                JsonNode time = node.path("timestamp");
                Instant at = !time.canConvertToLong()
                        ? timestamp(node)
                        : time.asLong() > 0 ? Instant.ofEpochMilli(time.asLong()) : null;
                if (at == null || input + output == 0) continue;
                String id = data.path("messageId").asText("");
                String key = id.isEmpty() ? null : WORKBUDDY + ":" + id + ":" + input;
                String model = data.path("model").asText("");
                entries.add(new AgentUsage(
                        WORKBUDDY,
                        key,
                        at,
                        model.isEmpty() ? "unknown" : model,
                        input,
                        output,
                        usage.path("inputTokensDetails")
                                .path(0)
                                .path("cached_tokens")
                                .asLong()));
            }
        }
        return entries;
    }

    /** Codex 的 token_count 事件会重复上报同一累计值，只在累计 total 变化时计入本次 last_token_usage。 */
    private static List<AgentUsage> codex(Path file) throws IOException {
        List<AgentUsage> entries = new ArrayList<>();
        String model = "unknown";
        long lastTotal = -1;
        try (Stream<JsonNode> nodes = lines(file, "\"token_count\"", "\"turn_context\"")) {
            for (JsonNode node : (Iterable<JsonNode>) nodes::iterator) {
                JsonNode payload = node.path("payload");
                if ("turn_context".equals(node.path("type").asText())) {
                    model = payload.path("model").asText(model);
                    continue;
                }
                JsonNode info = payload.path("info");
                if (!"token_count".equals(payload.path("type").asText()) || !info.isObject()) continue;
                long total = info.path("total_token_usage").path("total_tokens").asLong();
                if (total == lastTotal) continue;
                lastTotal = total;
                JsonNode last = info.path("last_token_usage");
                Instant at = timestamp(node);
                long input = last.path("input_tokens").asLong();
                long output = last.path("output_tokens").asLong();
                if (at == null || input + output == 0) continue;
                entries.add(new AgentUsage(
                        CODEX,
                        null,
                        at,
                        model,
                        input,
                        output,
                        last.path("cached_input_tokens").asLong()));
            }
        }
        return entries;
    }

    /** 逐行惰性解析包含任一标记的行，跳过大段正文并避免整文件驻留内存；调用方负责关闭。 */
    private static Stream<JsonNode> lines(Path file, String... markers) throws IOException {
        InputStream input = new BufferedInputStream(Files.newInputStream(file));
        if (file.toString().endsWith(".zst")) input = new ZstdInputStream(input);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        return reader.lines()
                .filter(line -> Stream.of(markers).anyMatch(line::contains))
                .map(LocalAgentUsageReader::parse)
                .filter(Objects::nonNull)
                .onClose(() -> {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (IOException e) {
            // Partially written trailing lines are picked up once the file changes again.
            return null;
        }
    }

    private static Instant timestamp(JsonNode node) {
        try {
            return Instant.parse(node.path("timestamp").asText());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
