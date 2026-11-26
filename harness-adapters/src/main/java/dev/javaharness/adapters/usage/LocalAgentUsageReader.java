package dev.javaharness.adapters.usage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 读取本机其他 agent 的会话日志并汇总 token 用量：Claude Code（~/.claude/projects）、Codex（~/.codex/sessions，含 zstd 压缩的旧日志）、
 * WorkBuddy（~/.workbuddy/projects）、Qoder（~/.qoder/projects，格式同 Claude Code）与 Antigravity
 * （~/.gemini/antigravity/brain/&lt;会话&gt;/.system_generated/logs/transcript.jsonl）。
 *
 * <p>按文件大小与修改时间缓存解析结果，只重新解析有变化的文件；无法解析的行直接跳过。首次读取时多线程并行解析，
 * 并只在字节层面命中标记的行上做 JSON 解析、跳过正文字段；指定缓存文件时解析结果会落盘，重启后无需全量重读。
 */
public final class LocalAgentUsageReader {
    public static final String CLAUDE_CODE = "claude-code";
    public static final String CODEX = "codex";
    public static final String WORKBUDDY = "workbuddy";
    public static final String QODER = "qoder";
    public static final String ANTIGRAVITY = "antigravity";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 解析逻辑或缓存格式变化时递增，旧缓存随之作废。 */
    private static final int CACHE_VERSION = 1;

    private static final int CACHE_MAGIC = 0x4A485553;
    private static final Path ANTIGRAVITY_TRANSCRIPT = Path.of(".system_generated", "logs", "transcript.jsonl");

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

    private record Candidate(Path file, String source, long size, long modified) {}

    /** 各格式只需读取的字段（点号表示嵌套），其余字段在解析时直接跳过，避免为长正文分配字符串。 */
    private static final Set<String> CLAUDE_FIELDS =
            Set.of("type", "timestamp", "requestId", "message.id", "message.model", "message.usage");

    private static final Set<String> WORKBUDDY_FIELDS =
            Set.of("timestamp", "providerData.usage", "providerData.messageId", "providerData.model");
    private static final Set<String> CODEX_FIELDS =
            Set.of("type", "timestamp", "payload.type", "payload.model", "payload.info");
    private static final Set<String> ANTIGRAVITY_FIELDS =
            Set.of("step_index", "type", "created_at", "model", "input_tokens", "cache_read_tokens", "output_tokens");

    private final Map<String, List<Path>> roots;
    private final Path cacheFile;
    private final int parallelism;
    private final Map<Path, Parsed> cache = new HashMap<>();
    private boolean cacheLoaded;
    private final AtomicBoolean saving = new AtomicBoolean();
    private final AtomicReference<Map<Path, Parsed>> pending = new AtomicReference<>();

    public LocalAgentUsageReader(Path home) {
        this(home, null);
    }

    /** cacheFile 为 null 时只在内存中缓存。 */
    public LocalAgentUsageReader(Path home, Path cacheFile) {
        roots = Map.of(
                CLAUDE_CODE, List.of(home.resolve(".claude").resolve("projects")),
                CODEX,
                        List.of(
                                home.resolve(".codex").resolve("sessions"),
                                home.resolve(".codex").resolve("archived_sessions")),
                WORKBUDDY, List.of(home.resolve(".workbuddy").resolve("projects")),
                QODER, List.of(home.resolve(".qoder").resolve("projects")),
                ANTIGRAVITY,
                        List.of(home.resolve(".gemini").resolve("antigravity").resolve("brain")));
        this.cacheFile = cacheFile;
        this.parallelism = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    /** 该来源的日志目录是否存在。 */
    public boolean available(String source) {
        return roots.getOrDefault(source, List.of()).stream().anyMatch(Files::isDirectory);
    }

    /** 预先读取全部日志填充缓存，供启动后在后台调用，使首次打开统计页时无需等待。 */
    public void warmUp() {
        usageSince(Instant.EPOCH);
    }

    /** 返回指定时刻及之后的用量，同一 key 只保留输出最多的一条（流式写入时早期行的输出尚不完整）。 */
    public synchronized List<AgentUsage> usageSince(Instant from) {
        loadCache();
        List<Candidate> files = new ArrayList<>();
        roots.forEach((source, paths) -> {
            for (Path root : paths) files.addAll(list(root, source));
        });
        boolean changed = refresh(files);
        Set<Path> seen = new HashSet<>();
        Map<String, AgentUsage> keyed = new LinkedHashMap<>();
        List<AgentUsage> result = new ArrayList<>();
        for (Candidate candidate : files) {
            Parsed parsed = cache.get(candidate.file());
            if (parsed == null) continue;
            seen.add(candidate.file());
            for (AgentUsage usage : parsed.entries()) {
                if (usage.at().isBefore(from)) continue;
                if (usage.key() == null) result.add(usage);
                else keyed.merge(usage.key(), usage, (a, b) -> b.outputTokens() > a.outputTokens() ? b : a);
            }
        }
        changed |= cache.keySet().retainAll(seen);
        if (changed) saveCache();
        result.addAll(keyed.values());
        return result;
    }

    /** 并行解析新增或有变化的文件；无法打开的文件从缓存移除，下次请求时重试。返回缓存是否有变化。 */
    private boolean refresh(List<Candidate> files) {
        List<Candidate> stale = new ArrayList<>();
        for (Candidate candidate : files) {
            Parsed parsed = cache.get(candidate.file());
            if (parsed == null || parsed.size() != candidate.size() || parsed.modified() != candidate.modified())
                stale.add(candidate);
        }
        if (stale.isEmpty()) return false;
        // Largest first so one huge rollout does not end up alone at the tail of the pool.
        stale.sort(Comparator.comparingLong(Candidate::size).reversed());
        List<Parsed> results = new ArrayList<>();
        if (stale.size() == 1 || parallelism == 1) {
            for (Candidate candidate : stale) results.add(parse(candidate));
        } else {
            List<Callable<Parsed>> tasks = new ArrayList<>();
            for (Candidate candidate : stale) tasks.add(() -> parse(candidate));
            try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, stale.size()), task -> {
                Thread thread = new Thread(task, "agent-usage-reader");
                thread.setDaemon(true);
                return thread;
            })) {
                for (Future<Parsed> future : pool.invokeAll(tasks)) results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
        for (int i = 0; i < stale.size(); i++) {
            Path file = stale.get(i).file();
            if (results.get(i) == null) cache.remove(file);
            else cache.put(file, results.get(i));
        }
        return true;
    }

    private static Parsed parse(Candidate candidate) {
        List<AgentUsage> entries;
        try {
            entries = switch (candidate.source()) {
                case CODEX -> codex(candidate.file());
                case WORKBUDDY -> workbuddy(candidate.file());
                case ANTIGRAVITY -> antigravity(candidate.file());
                default -> claude(candidate.file(), candidate.source());
            };
        } catch (IOException e) {
            // A file locked or deleted before opening is retried on the next request.
            return null;
        } catch (RuntimeException e) {
            // Corrupt or truncated content counts as empty until the file changes, instead of failing the request.
            entries = List.of();
        }
        return new Parsed(candidate.size(), candidate.modified(), entries);
    }

    /** 列出来源目录下的日志文件，大小与修改时间直接取自遍历时的文件属性，避免逐个文件额外查询。 */
    private static List<Candidate> list(Path root, String source) {
        if (!Files.isDirectory(root)) return List.of();
        List<Candidate> files = new ArrayList<>();
        if (source.equals(ANTIGRAVITY)) {
            // brain/ also holds screenshots and artifacts; only each conversation's transcript is needed.
            try (DirectoryStream<Path> conversations = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (Path conversation : conversations) {
                    Path file = conversation.resolve(ANTIGRAVITY_TRANSCRIPT);
                    try {
                        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                        if (attributes.isRegularFile()) files.add(candidate(file, source, attributes));
                    } catch (IOException e) {
                        // Older conversations have no transcript.
                    }
                }
            } catch (IOException e) {
                return List.of();
            }
            return files;
        }
        Set<Path> plain = new HashSet<>();
        List<Candidate> compressed = new ArrayList<>();
        try {
            // Unreadable subdirectories are skipped instead of hiding the whole source.
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                    String name = file.getFileName().toString();
                    if (name.endsWith(".jsonl")) {
                        files.add(candidate(file, source, attributes));
                        plain.add(file);
                    } else if (name.endsWith(".jsonl.zst")) compressed.add(candidate(file, source, attributes));
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
        // Codex compresses older rollouts; skip a .zst while its plain copy still exists.
        for (Candidate candidate : compressed) {
            String name = candidate.file().getFileName().toString();
            if (!plain.contains(candidate.file().resolveSibling(name.substring(0, name.length() - 4))))
                files.add(candidate);
        }
        return files;
    }

    private static Candidate candidate(Path file, String source, BasicFileAttributes attributes) {
        return new Candidate(
                file, source, attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    /**
     * Claude Code 与 Qoder 的 input_tokens 不含缓存，这里合计为总输入；同一消息按内容块拆成多行，用消息 id 去重。
     * Qoder 内置模型只记录 credits、token 为 0，这类记录会被跳过。
     */
    private static List<AgentUsage> claude(Path file, String source) throws IOException {
        List<AgentUsage> entries = new ArrayList<>();
        for (JsonNode node : nodes(file, CLAUDE_FIELDS, "\"usage\"")) {
            JsonNode message = node.path("message");
            JsonNode usage = message.path("usage");
            String model = message.path("model").asText("");
            if (!"assistant".equals(node.path("type").asText()) || !usage.isObject() || model.equals("<synthetic>"))
                continue;
            Instant at = timestamp(node, "timestamp");
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
            entries.add(new AgentUsage(source, key, at, model.isEmpty() ? "unknown" : model, input, output, cached));
        }
        return entries;
    }

    /**
     * WorkBuddy 的用量在 providerData.usage，inputTokens 已含缓存命中；同一回复的多个工具调用各占一行且用量相同，
     * 而 messageId 偶尔会被不同请求复用，因此以 messageId 加输入 token 作为去重键，同键保留输出最多的一条。
     */
    private static List<AgentUsage> workbuddy(Path file) throws IOException {
        List<AgentUsage> entries = new ArrayList<>();
        for (JsonNode node : nodes(file, WORKBUDDY_FIELDS, "\"usage\"")) {
            JsonNode data = node.path("providerData");
            JsonNode usage = data.path("usage");
            if (!usage.isObject()) continue;
            long input = usage.path("inputTokens").asLong();
            long output = usage.path("outputTokens").asLong();
            JsonNode time = node.path("timestamp");
            Instant at = !time.canConvertToLong()
                    ? timestamp(node, "timestamp")
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
        return entries;
    }

    /** Codex 的 token_count 事件会重复上报同一累计值，只在累计 total 变化时计入本次 last_token_usage。 */
    private static List<AgentUsage> codex(Path file) throws IOException {
        List<AgentUsage> entries = new ArrayList<>();
        String model = "unknown";
        long lastTotal = -1;
        for (JsonNode node : nodes(file, CODEX_FIELDS, "\"token_count\"", "\"turn_context\"")) {
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
            Instant at = timestamp(node, "timestamp");
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
        return entries;
    }

    /**
     * Antigravity 每个会话一份 transcript，模型回复步骤（PLANNER_RESPONSE）记录本次调用的 token；input_tokens 不含缓存命中，
     * 这里加上 cache_read_tokens 作为总输入。同一步骤偶尔会重复写入，以会话目录加步骤序号去重。
     */
    private static List<AgentUsage> antigravity(Path file) throws IOException {
        List<AgentUsage> entries = new ArrayList<>();
        String conversation =
                file.getParent().getParent().getParent().getFileName().toString();
        for (JsonNode node : nodes(file, ANTIGRAVITY_FIELDS, "\"input_tokens\"")) {
            if (!"PLANNER_RESPONSE".equals(node.path("type").asText())) continue;
            Instant at = timestamp(node, "created_at");
            long cached = node.path("cache_read_tokens").asLong();
            long input = node.path("input_tokens").asLong() + cached;
            long output = node.path("output_tokens").asLong();
            if (at == null || input + output == 0) continue;
            JsonNode step = node.path("step_index");
            String key = step.canConvertToLong() ? ANTIGRAVITY + ":" + conversation + ":" + step.asLong() : null;
            String model = node.path("model").asText("");
            entries.add(
                    new AgentUsage(ANTIGRAVITY, key, at, model.isEmpty() ? "unknown" : model, input, output, cached));
        }
        return entries;
    }

    /**
     * 逐行读取原始字节，只对包含任一标记的行做 JSON 解析且只保留 fields 中的字段，跳过大段正文的解码与驻留。
     * 打开失败抛出 IOException（下次重试）；读取中途出错按内容损坏处理。
     */
    private static List<JsonNode> nodes(Path file, Set<String> fields, String... markers) throws IOException {
        byte[][] patterns = Arrays.stream(markers)
                .map(marker -> marker.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
        Set<String> branches = new HashSet<>();
        for (String field : fields)
            for (int dot = field.indexOf('.'); dot > 0; dot = field.indexOf('.', dot + 1))
                branches.add(field.substring(0, dot));
        List<JsonNode> nodes = new ArrayList<>();
        InputStream raw = Files.newInputStream(file);
        try (InputStream input = file.getFileName().toString().endsWith(".zst")
                ? new ZstdInputStream(new BufferedInputStream(raw, 1 << 16))
                : raw) {
            byte[] buffer = new byte[1 << 16];
            int length = 0;
            while (true) {
                if (length == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
                int read = input.read(buffer, length, buffer.length - length);
                if (read < 0) break;
                int scanFrom = length;
                length += read;
                int start = 0;
                for (int i = scanFrom; i < length; i++) {
                    if (buffer[i] != '\n') continue;
                    line(buffer, start, i, patterns, fields, branches, nodes);
                    start = i + 1;
                }
                if (start > 0) {
                    System.arraycopy(buffer, start, buffer, 0, length - start);
                    length -= start;
                }
            }
            line(buffer, 0, length, patterns, fields, branches, nodes);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable usage log", e);
        }
        return nodes;
    }

    private static void line(
            byte[] buffer,
            int start,
            int end,
            byte[][] patterns,
            Set<String> fields,
            Set<String> branches,
            List<JsonNode> nodes) {
        if (end <= start) return;
        boolean match = false;
        for (byte[] pattern : patterns) {
            if (contains(buffer, start, end, pattern)) {
                match = true;
                break;
            }
        }
        if (!match) return;
        try (JsonParser parser = JSON.createParser(buffer, start, end - start)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return;
            nodes.add(select(parser, "", fields, branches));
        } catch (IOException e) {
            // Partially written trailing lines are picked up once the file changes again.
        }
    }

    private static boolean contains(byte[] buffer, int start, int end, byte[] pattern) {
        byte first = pattern[0];
        outer:
        for (int i = start, last = end - pattern.length; i <= last; i++) {
            if (buffer[i] != first) continue;
            for (int j = 1; j < pattern.length; j++) if (buffer[i + j] != pattern[j]) continue outer;
            return true;
        }
        return false;
    }

    /** 解析当前对象，只保留列出的字段及其上级对象，其余子树直接跳过。 */
    private static ObjectNode select(JsonParser parser, String prefix, Set<String> fields, Set<String> branches)
            throws IOException {
        ObjectNode node = JSON.createObjectNode();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            String path = prefix + name;
            JsonToken token = parser.nextToken();
            if (fields.contains(path)) node.set(name, JSON.readTree(parser));
            else if (token == JsonToken.START_OBJECT && branches.contains(path))
                node.set(name, select(parser, path + ".", fields, branches));
            else parser.skipChildren();
        }
        return node;
    }

    private static Instant timestamp(JsonNode node, String field) {
        try {
            return Instant.parse(node.path(field).asText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 首次读取时加载落盘的解析结果；文件缺失、版本不符或损坏时视为空缓存。 */
    private void loadCache() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) return;
        Map<Path, Parsed> loaded = new HashMap<>();
        try (DataInputStream in =
                new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile), 1 << 16))) {
            if (in.readInt() != CACHE_MAGIC || in.readInt() != CACHE_VERSION) return;
            int files = in.readInt();
            for (int i = 0; i < files; i++) {
                Path file = Path.of(in.readUTF());
                long size = in.readLong();
                long modified = in.readLong();
                String source = in.readUTF();
                int count = in.readInt();
                List<AgentUsage> entries = new ArrayList<>(count);
                for (int j = 0; j < count; j++) {
                    String key = in.readBoolean() ? in.readUTF() : null;
                    Instant at = Instant.ofEpochSecond(in.readLong(), in.readInt());
                    entries.add(
                            new AgentUsage(source, key, at, in.readUTF(), in.readLong(), in.readLong(), in.readLong()));
                }
                loaded.put(file, new Parsed(size, modified, List.copyOf(entries)));
            }
        } catch (IOException | RuntimeException e) {
            // A damaged cache is rebuilt from the logs.
            return;
        }
        cache.putAll(loaded);
    }

    /**
     * 在后台线程写入最新的缓存快照，写入期间的新变化会在本轮结束后接着写入；先写临时文件再替换，避免中途退出留下半个文件。
     * 写入失败只影响下次启动的速度。
     */
    private void saveCache() {
        if (cacheFile == null) return;
        pending.set(Map.copyOf(cache));
        if (!saving.compareAndSet(false, true)) return;
        Thread writer = new Thread(
                () -> {
                    do {
                        for (Map<Path, Parsed> snapshot; (snapshot = pending.getAndSet(null)) != null; ) {
                            try {
                                write(snapshot);
                            } catch (IOException | RuntimeException e) {
                                // Next start falls back to reparsing changed logs.
                            }
                        }
                        saving.set(false);
                    } while (pending.get() != null && saving.compareAndSet(false, true));
                },
                "agent-usage-cache-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void write(Map<Path, Parsed> snapshot) throws IOException {
        Path parent = cacheFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, cacheFile.getFileName().toString(), ".tmp");
        try {
            try (DataOutputStream out =
                    new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), 1 << 16))) {
                out.writeInt(CACHE_MAGIC);
                out.writeInt(CACHE_VERSION);
                out.writeInt(snapshot.size());
                for (var item : snapshot.entrySet()) {
                    Parsed parsed = item.getValue();
                    out.writeUTF(item.getKey().toString());
                    out.writeLong(parsed.size());
                    out.writeLong(parsed.modified());
                    out.writeUTF(
                            parsed.entries().isEmpty()
                                    ? ""
                                    : parsed.entries().getFirst().source());
                    out.writeInt(parsed.entries().size());
                    for (AgentUsage usage : parsed.entries()) {
                        out.writeBoolean(usage.key() != null);
                        if (usage.key() != null) out.writeUTF(usage.key());
                        out.writeLong(usage.at().getEpochSecond());
                        out.writeInt(usage.at().getNano());
                        out.writeUTF(usage.model());
                        out.writeLong(usage.inputTokens());
                        out.writeLong(usage.outputTokens());
                        out.writeLong(usage.cachedTokens());
                    }
                }
            }
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
