package dev.javaharness.adapters.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javaharness.spi.HarnessException;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static dev.javaharness.adapters.plugins.PluginJson.*;

/** Copies packages into immutable, bounded installations without executing their contents. */
public final class PluginPackages {
    public static final String SCHEMA = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json";
    public static final long MAX_TOTAL = 32L * 1024 * 1024;
    private static final int MAX_FILE = 8 * 1024 * 1024;
    private final Path root;
    public record Component(String id, String description, String path) {}
    public record Package(String id, String name, String version, String digest, JsonNode manifest,
                          Map<String, Component> skills, Map<String, Component> commands, JsonNode configSchema,
                          Map<String, JsonNode> servers, Map<String, byte[]> files) {}

    public PluginPackages(Path root) {
        Path absolute = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute);
            Path real = absolute.toRealPath();
            // Both forms expand Windows 8.3 aliases; only the default form follows links.
            if (Files.isSymbolicLink(absolute) || !real.equals(absolute.toRealPath(LinkOption.NOFOLLOW_LINKS))) throw new IOException();
            this.root = real;
        }
        catch (IOException error) { throw new IllegalStateException("插件目录不可用"); }
    }

    /** Import a directory by copying regular files; links are never followed. */
    public Package directory(String path) {
        try {
            Path source = Path.of(path).toRealPath();
            if (!Files.isDirectory(source) || root.startsWith(source) || source.startsWith(root)) throw invalid("请选择插件源码目录，不要导入安装目录");
            var files = new TreeMap<String, byte[]>();
            try (var paths = Files.walk(source)) {
                for (Path entry : paths.toList()) {
                    if (Files.isSymbolicLink(entry) || !entry.toRealPath().startsWith(source)) throw invalid("插件包不允许链接或越界文件");
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.size(entry) > MAX_FILE) throw invalid("插件文件类型或大小不支持");
                    add(files, source.relativize(entry).toString().replace('\\', '/'), Files.readAllBytes(entry));
                }
            }
            return publish(files);
        } catch (IOException | InvalidPathException error) { throw invalid("无法读取插件目录"); }
    }

    /** Extract ZIP entries as regular files with no archive-supplied filesystem attributes. */
    public Package zip(InputStream input) {
        var files = new TreeMap<String, byte[]>();
        try (var zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry; int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 512) throw invalid("ZIP 条目过多");
                String name = safeName(entry.getName().replaceAll("/$", ""));
                if (entry.isDirectory()) { if (zip.read() != -1) throw invalid("ZIP 目录条目不能包含文件内容"); continue; }
                byte[] bytes = zip.readNBytes(MAX_FILE + 1);
                if (bytes.length > MAX_FILE) throw invalid("插件文件过大");
                add(files, name, bytes);
            }
            return publish(files);
        } catch (IOException error) { throw invalid("无法读取 ZIP，请将 plugin.json 放在包根目录"); }
    }

    /** Validate a host-authored in-memory package with the same limits as directory and ZIP imports. */
    public Package bundle(Map<String, byte[]> input) {
        var files = new TreeMap<String, byte[]>();
        input.forEach((name, bytes) -> add(files, name, bytes.clone()));
        return publish(files);
    }

    /** Compare first-party catalog content without trusting source labels supplied by an importer. */
    public static String bundleDigest(Map<String, byte[]> input) {
        var files = new TreeMap<String, byte[]>(); input.forEach((name, bytes) -> add(files, name, bytes));
        return packageDigest(files);
    }

    /** Re-read a stored package and verify all bytes against its captured digest. */
    public Package read(String id, String expectedDigest) {
        if (!id.matches("[0-9a-f-]{36}")) throw invalid("安装 ID 不正确");
        var files = new TreeMap<String, byte[]>();
        Path directory = root.resolve(id);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root.toRealPath())) throw invalid("安装目录包含链接");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE) throw invalid("安装文件已改变");
                add(files, directory.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
            if (!packageDigest(files).equals(expectedDigest)) throw new HarnessException("CONFLICT", "插件安装内容已改变，请重新导入新版本");
            return inspect(id, files);
        } catch (IOException error) { throw new HarnessException("CONFLICT", "插件安装文件不可用，请重新安装"); }
    }

    /** Return the installation root without accepting arbitrary filesystem paths. */
    public Path path(String id) { if (!id.matches("[0-9a-f-]{36}")) throw invalid("安装 ID 不正确"); return root.resolve(id); }

    /** Delete only a newly imported, unregistered UUID directory after an import is rejected or deduplicated. */
    public void discard(String id) { deleteTree(path(id)); }

    /** Restore a removed, damaged installation from newly validated bytes, preserving its historical identity. */
    public void restore(String id, Package replacement) {
        Path target = path(id), incoming = path(replacement.id()), backup = root.resolve("recovery-" + UUID.randomUUID());
        read(replacement.id(), replacement.digest());
        boolean moved = false;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE); moved = true; }
            Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            if (moved) try { Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE); } catch (IOException rollback) { error.addSuppressed(rollback); }
            throw new HarnessException("CONFLICT", "插件恢复失败，请停止相关程序后重试");
        }
        if (moved) deleteTree(backup);
    }

    private Package publish(Map<String, byte[]> files) {
        String id = UUID.randomUUID().toString();
        Package pkg = inspect(id, files);
        Path stage = root.resolve("stage-" + id), target = path(id);
        try {
            Files.createDirectory(stage);
            for (var file : files.entrySet()) {
                Path path = stage.resolve(file.getKey()); Files.createDirectories(path.getParent());
                Files.write(path, file.getValue(), StandardOpenOption.CREATE_NEW);
            }
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            return pkg;
        } catch (IOException error) { cleanup(stage); throw invalid("插件安装失败"); }
    }

    /** Remove only this manager's unpublished staging directory. */
    private void cleanup(Path stage) {
        if (!stage.getParent().equals(root) || !stage.getFileName().toString().startsWith("stage-")) throw new IllegalArgumentException();
        deleteTree(stage);
    }

    private void deleteTree(Path target) {
        if (!target.normalize().getParent().equals(root)) throw new IllegalArgumentException("Invalid package cleanup target");
        try (var paths = Files.walk(target)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch (IOException error) { System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "插件临时目录清理失败"); }
    }

    private static void add(Map<String, byte[]> files, String name, byte[] bytes) {
        name = safeName(name);
        String candidate = name;
        if (files.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(candidate) || key.toLowerCase(Locale.ROOT).startsWith(candidate.toLowerCase(Locale.ROOT) + "/") || candidate.toLowerCase(Locale.ROOT).startsWith(key.toLowerCase(Locale.ROOT) + "/"))) throw invalid("插件路径重复或冲突");
        if (files.size() >= 256 || bytes.length > MAX_FILE || files.values().stream().mapToLong(value -> value.length).sum() + bytes.length > MAX_TOTAL) throw invalid("插件文件数或总大小超过限制");
        files.put(name, bytes);
    }

    /** Reject traversal and Windows path aliases consistently on every host OS. */
    public static String safeName(String value) {
        if (value.isEmpty() || value.length() > 240 || value.contains("\\") || value.startsWith("/") || value.matches(".*[\\x00-\\x1f:<>\"|?*].*")) throw invalid("插件路径不合法");
        for (String part : value.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) throw invalid("插件路径不合法");
        return value;
    }

    private static Package inspect(String id, Map<String, byte[]> files) {
        JsonNode manifest = parse(text(files, "plugin.json", 65536));
        if (!manifest.isObject() || !manifest.path("$schema").asText().equals(SCHEMA)) throw invalid("需要 Agent Plugins 1.0.0 plugin.json");
        Set<String> known = Set.of("$schema", "name", "version", "description", "author", "homepage", "repository", "license", "keywords", "extensions");
        manifest.fieldNames().forEachRemaining(key -> { if (!known.contains(key)) throw invalid("不支持的清单字段：" + key); });
        String name = manifest.path("name").asText(), version = manifest.path("version").asText();
        if (!name.matches("[a-z0-9](?:[a-z0-9.-]{0,62}[a-z0-9])?") || name.contains("--") || name.contains("..") || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?") || version.length() > 64) throw invalid("插件名称或版本不正确，版本需为 x.y.z");
        JsonNode extension = manifest.path("extensions").path("dev.javaharness");
        if (!extension.isMissingNode()) {
            if (!extension.isObject() || extension.path("schemaVersion").asInt() != 1) throw invalid("平台扩展版本不支持");
            extension.fieldNames().forEachRemaining(key -> { if (!Set.of("schemaVersion", "requires", "commands", "configSchema", "kind", "title", "category", "icon").contains(key)) throw invalid("未支持的平台扩展：" + key); });
            for (String key : List.of("title", "category", "icon")) if (extension.has(key) && (!extension.get(key).isTextual() || extension.get(key).asText().length() > 100)) throw invalid("扩展展示字段不正确");
            for (JsonNode requirement : extension.path("requires")) if (!Set.of("skills", "commands", "mcp.stdio", "mcp.streamable-http").contains(requirement.asText())) throw invalid("插件要求未支持的能力：" + requirement.asText());
        }
        var skills = components(files, "skills/", true);
        String commandDirectory = extensionPath(extension, "commands", "dev.javaharness/commands/");
        var commands = components(files, commandDirectory.endsWith("/") ? commandDirectory : commandDirectory + "/", false);
        String configPath = extensionPath(extension, "configSchema", "dev.javaharness/config.schema.json");
        JsonNode configSchema = files.containsKey(configPath) ? parse(text(files, configPath, 32768)) : parse("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
        schema(configSchema);
        var servers = new LinkedHashMap<String, JsonNode>();
        if (files.containsKey("mcp.json")) {
            JsonNode mcp = parse(text(files, "mcp.json", 65536));
            if (!mcp.path("$schema").asText().equals("https://agent-plugins.org/schemas/1.0.0/mcp.schema.json") || !mcp.path("mcpServers").isObject() || mcp.path("mcpServers").size() > 8) throw invalid("mcp.json 格式不支持");
            mcp.path("mcpServers").fields().forEachRemaining(entry -> {
                if (!entry.getKey().matches("[a-zA-Z0-9_-]{1,128}")) throw invalid("MCP 服务名称不正确");
                String type = entry.getValue().path("type").asText();
                if (!Set.of("stdio", "streamable-http").contains(type)) throw invalid("仅支持 stdio 和 Streamable HTTP");
                servers.put(entry.getKey(), entry.getValue().deepCopy());
            });
        }
        String kind = extension.path("kind").asText("plugin");
        if (!Set.of("plugin", "skill", "connector").contains(kind)
                || kind.equals("skill") && (skills.size() != 1 || !commands.isEmpty() || !servers.isEmpty())
                || kind.equals("connector") && (servers.size() != 1 || !skills.isEmpty() || !commands.isEmpty())) throw invalid("扩展类型与包内组件不一致");
        return new Package(id, name, version, packageDigest(files), manifest, Map.copyOf(skills), Map.copyOf(commands), configSchema, Map.copyOf(servers), Map.copyOf(files));
    }

    private static String packageDigest(Map<String, byte[]> files) {
        var hashes = new TreeMap<String, String>(); files.forEach((key, value) -> hashes.put(key, digest(value))); return digest(write(hashes));
    }

    private static String extensionPath(JsonNode extension, String key, String fallback) {
        String value = extension.path(key).asText("./" + fallback);
        if (!value.startsWith("./dev.javaharness/")) throw invalid("平台文件必须位于 dev.javaharness 目录");
        String relative = value.substring(2); return safeName(relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative) + (value.endsWith("/") ? "/" : "");
    }

    private static Map<String, Component> components(Map<String, byte[]> files, String prefix, boolean skills) {
        var result = new LinkedHashMap<String, Component>();
        files.keySet().stream().sorted().forEach(path -> {
            if (!path.startsWith(prefix)) return;
            String remainder = path.substring(prefix.length());
            String name = skills && remainder.matches("[^/]+/SKILL\\.md") ? remainder.substring(0, remainder.indexOf('/'))
                    : !skills && remainder.matches("[^/]+\\.md") ? remainder.substring(0, remainder.length() - 3) : null;
            if (name == null) return;
            if (!name.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw invalid("技能或命令名称不正确");
            String body = text(files, path, 32768), description = "";
            if (body.startsWith("---\n") || body.startsWith("---\r\n")) {
                String[] parts = body.split("\\r?\\n---\\r?\\n", 2);
                if (parts.length != 2) throw invalid("Markdown frontmatter 未结束");
                for (String line : parts[0].split("\\r?\\n")) if (line.startsWith("description:")) description = line.substring(12).strip().replaceAll("^[\"']|[\"']$", "");
            }
            if (skills && (description.isBlank() || description.equals("|") || description.equals(">"))) throw invalid("Skill 需要单行 description frontmatter");
            if (description.isBlank()) description = name;
            if (description.length() > 1000 || result.size() >= 64) throw invalid("组件描述或数量超过限制");
            result.put(name, new Component(name, description, path));
        });
        return result;
    }

    /** Decode a packaged resource using strict UTF-8 and an explicit byte limit. */
    public static String text(Map<String, byte[]> files, String path, int limit) {
        byte[] bytes = files.get(path);
        if (bytes == null || bytes.length > limit) throw invalid("插件资源不存在或过大：" + path);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw invalid("插件资源必须是 UTF-8 文本"); }
    }
}
