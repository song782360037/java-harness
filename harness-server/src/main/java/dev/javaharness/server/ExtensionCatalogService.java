package dev.javaharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javaharness.adapters.plugins.*;
import dev.javaharness.spi.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.javaharness.adapters.plugins.PluginJson.*;

/** Catalog presentation and standalone authoring reuse the existing package, binding and execution contracts. */
public final class ExtensionCatalogService {
    public record Item(String id, String kind, String title, String description, String category, String icon,
                       boolean official, boolean featured, String version, String installationId, String parentName, String componentId, String issue, boolean enabled, boolean editable, long revision) {}
    public record Preview(Item item, String content) {}
    public record Skill(String name, String title, String description, String version, String content, String scope) {}
    public record Connector(String name, String title, String description, String version, JsonNode definition, String scope) {
        @Override public String toString() { return "Connector[configuration=redacted]"; }
    }
    private record Bundle(JsonNode metadata, Map<String, byte[]> files, String digest, String content) {}
    private static final String OFFICIAL = "javaharness-official";
    private final PluginService plugins;
    private final SessionStore sessions;
    private final Map<String, Bundle> catalog = new LinkedHashMap<>();

    public ExtensionCatalogService(PluginService plugins, SessionStore sessions) {
        this.plugins = plugins; this.sessions = sessions;
        try (var input = getClass().getResourceAsStream("/extensions/catalog.json")) {
            JsonNode root = parse(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
            for (JsonNode item : root.path("items")) {
                String kind = item.path("kind").asText(), id = item.path("id").asText();
                String version = root.path("version").asText();
                if (kind.equals("connector")) version += "-runtime-" + digest(write(BuiltinMcpServer.launchArguments(item.path("connector").asText())) + System.getProperty("java.home")).substring(0, 12);
                var files = manifest(id, kind, item.path("title").asText(), item.path("description").asText(), version, item.path("category").asText(), item.path("icon").asText());
                String content;
                if (kind.equals("connector")) {
                    put(files, "mcp.json", write(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json", "mcpServers", Map.of("main",
                            Map.of("type", "stdio", "command", java.nio.file.Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), "args", BuiltinMcpServer.launchArguments(item.path("connector").asText()))))));
                    content = item.path("description").asText() + "\n\n由 Java Harness 提供，在独立 Java 进程中运行。仅处理工具明确收到的输入，不读取工作区文件，不连接外部服务。安装后全局启用并允许工具调用。";
                } else {
                    JsonNode workflow = root.path("workflows").path(item.path("workflow").asText());
                    content = workflow.path("body").asText();
                    put(files, "skills/workflow/SKILL.md", markdown("workflow", workflow.path("description").asText(), content));
                    if (kind.equals("plugin")) put(files, "dev.javaharness/commands/run.md", "---\ndescription: " + item.path("title").asText() + "\n---\n" + content + "\n\n本次范围：$ARGUMENTS\n");
                }
                catalog.put(id, new Bundle(item, Map.copyOf(files), PluginPackages.bundleDigest(files), content));
            }
        } catch (java.io.IOException error) { throw new IllegalStateException("无法读取官方扩展目录", error); }
    }

    /** List curated entries and installed components without launching MCP or resolving executables. */
    public List<Item> items() {
        synchronized (sessions) {
            var installed = plugins.installations(); var result = new ArrayList<Item>(); var represented = new HashSet<String>();
            for (var entry : catalog.entrySet()) {
                Bundle bundle = entry.getValue();
                var existing = installed.stream().filter(item -> item.source().equals(OFFICIAL) && item.name().equals(entry.getKey()) && item.digest().equals(bundle.digest())).findFirst();
                String id = existing.map(PluginService.Installation::id).orElse(""); if (!id.isEmpty()) represented.add(id);
                JsonNode meta = bundle.metadata();
                String issue = "";
                if (!id.isEmpty()) try { plugins.detail(id); } catch (HarnessException error) { issue = error.getMessage(); }
                result.add(new Item(entry.getKey(), meta.path("kind").asText(), meta.path("title").asText(), meta.path("description").asText(), meta.path("category").asText(), meta.path("icon").asText(), true, meta.path("featured").asBoolean(), parse(new String(bundle.files().get("plugin.json"), StandardCharsets.UTF_8)).path("version").asText(), id, "", "workflow", issue, false, false, 0));
            }
            for (var installation : installed) {
                JsonNode meta = installation.manifest().path("extensions").path("dev.javaharness");
                String kind = meta.path("kind").asText("plugin"), title = meta.path("title").asText(installation.name()), category = meta.path("category").asText("工作流"), icon = meta.path("icon").asText(kind.equals("connector") ? "globe" : "cube");
                boolean official = represented.contains(installation.id());
                PluginService.Detail detail;
                try { detail = plugins.detail(installation.id()); }
                catch (HarnessException error) {
                    if (!official) result.add(new Item("installed:" + installation.id(), kind, title, installation.manifest().path("description").asText(), category, icon, false, false, installation.version(), installation.id(), "", "", error.getMessage(), false, false, 0));
                    continue;
                }
                if (!official) result.add(new Item("installed:" + installation.id(), kind, title, installation.manifest().path("description").asText(), category, icon, false, false, installation.version(), installation.id(), "", kind.equals("skill") ? detail.skills().iterator().next().id() : "", "", false, false, 0));
                if (kind.equals("plugin")) {
                    for (var skill : detail.skills()) result.add(new Item("installed:" + installation.id() + ":skill:" + skill.id(), "skill", skill.id(), skill.description(), category, "code", official, false, installation.version(), installation.id(), title, skill.id(), "", false, false, 0));
                    for (String server : detail.servers()) result.add(new Item("installed:" + installation.id() + ":connector:" + server, "connector", server, "来自 " + title + " 的 MCP 连接器", category, "globe", official, false, installation.version(), installation.id(), title, server, "", false, false, 0));
                }
            }
            var bindings = plugins.bindings("user");
            return result.stream().map(item -> {
                var binding = bindings.stream().filter(value -> value.installationId().equals(item.installationId())).findFirst();
                boolean editable = false;
                if (!item.installationId().isEmpty() && item.parentName().isEmpty()) try { plugins.authoring(item.installationId()); editable = true; } catch (HarnessException ignored) { }
                return new Item(item.id(), item.kind(), item.title(), item.description(), item.category(), item.icon(), item.official(), item.featured(), item.version(), item.installationId(), item.parentName(), item.componentId(), item.issue(), binding.map(PluginService.BindingView::enabled).orElse(false), editable, binding.map(PluginService.BindingView::revision).orElse(0L));
            }).toList();
        }
    }

    /** Preview actual bundled instructions or declared components; never return connector credentials. */
    public Preview preview(String id) {
        Item item = items().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(() -> new HarnessException("NOT_FOUND", "扩展不存在"));
        Bundle bundle = catalog.get(id);
        String content = bundle != null ? bundle.content() : item.kind().equals("skill") && item.issue().isEmpty()
                ? plugins.skillContent(item.installationId(), item.componentId()) : item.description();
        return new Preview(item, content);
    }

    /** Install verified first-party bytes and enable them globally without starting a process. */
    public PluginService.Installation install(String id) {
        Bundle bundle = catalog.get(id); if (bundle == null) throw new HarnessException("NOT_FOUND", "官方扩展不存在");
        return plugins.activate(plugins.importBundle(bundle.files(), OFFICIAL));
    }

    /** Author a standalone Skill without requiring the user to construct a plugin package. */
    public PluginService.Installation createSkill(Skill request) { return writeSkill(request, "", 0); }
    /** Reuse the add form for custom revisions, with optimistic concurrency. */
    public PluginService.Installation update(String id, long revision, JsonNode form) {
        var previous = plugins.authoring(id).definition();
        if (!previous.path("name").asText().equals(form.path("name").asText())) throw invalid("编辑时不能修改唯一标识");
        return previous.path("definition").isObject() ? writeConnector(JSON.convertValue(form, Connector.class), id, revision) : writeSkill(JSON.convertValue(form, Skill.class), id, revision);
    }
    private PluginService.Installation writeSkill(Skill request, String previous, long revision) {
        validateAuthoring(request.name(), request.title(), request.description(), request.content());
        var files = manifest("custom-skill-" + request.name(), "skill", request.title(), request.description(), request.version(), "工作流", "code");
        put(files, "skills/" + request.name() + "/SKILL.md", markdown(request.name(), request.description(), request.content()));
        return finish(files, "custom-skill-" + request.name(), Map.of(), JSON.valueToTree(request), previous, revision, Set.of());
    }

    /** Keep env/header values in encrypted scoped credentials rather than in the immutable installation. */
    public PluginService.Installation createConnector(Connector request) { return writeConnector(request, "", 0); }
    private PluginService.Installation writeConnector(Connector request, String previous, long revision) {
        validateAuthoring(request.name(), request.title(), request.description(), "connector");
        JsonNode definition = request.definition();
        if (definition == null || !definition.isObject()) throw invalid("请填写单个 MCP 服务的 JSON 配置");
        String type = definition.path("type").asText(); boolean stdio = type.equals("stdio");
        if (!stdio && !type.equals("streamable-http")) throw invalid("连接器支持 stdio 或 streamable-http");
        Set<String> keys = stdio ? Set.of("type", "command", "args", "env") : Set.of("type", "url", "headers");
        definition.fieldNames().forEachRemaining(key -> { if (!keys.contains(key)) throw invalid("连接器配置字段不支持：" + key); });
        var server = JSON.createObjectNode(); server.put("type", type); var properties = JSON.createObjectNode();
        String target = stdio ? "command" : "url";
        if (!definition.path(target).isTextual() || definition.path(target).asText().isBlank() || definition.path(target).asText().length() > 2048) throw invalid("请填写有效的 " + target);
        if (!stdio) {
            URI uri;
            try { uri = URI.create(definition.path("url").asText()); } catch (IllegalArgumentException error) { throw invalid("MCP URL 不正确"); }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) throw invalid("远端连接器需要 HTTPS；密钥请放在 headers，不要放在 URL");
        }
        properties.set(target, JSON.valueToTree(Map.of("type", "string", "default", definition.path(target).asText()))); server.put(target, "${config." + target + "}");
        if (stdio) {
            if (definition.has("args") && (!definition.path("args").isArray() || definition.path("args").size() > 64)) throw invalid("args 必须是最多 64 项的字符串数组");
            var arguments = JSON.createArrayNode(); int index = 0;
            for (JsonNode arg : definition.path("args")) {
                if (!arg.isTextual() || arg.asText().length() > 2048) throw invalid("args 仅支持不超过 2048 字符的字符串");
                String key = "arg" + index++; properties.set(key, JSON.valueToTree(Map.of("type", "string", "default", arg.asText()))); arguments.add("${config." + key + "}");
            }
            server.set("args", arguments);
        }
        var retained = new HashSet<String>(); var secrets = new TreeMap<String, String>(); var headers = new TreeMap<String, String>();
        JsonNode variables = definition.path(stdio ? "env" : "headers");
        if (!variables.isMissingNode() && !variables.isObject()) throw invalid("env/headers 必须为字符串对象");
        variables.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!(entry.getValue().isTextual() || !previous.isEmpty() && entry.getValue().isNull()) || key.isEmpty() || key.length() > 128 || stdio && (!key.matches("[a-zA-Z_][a-zA-Z0-9_-]*") || Set.of("PLUGIN_ROOT", "PLUGIN_DATA").contains(key.toUpperCase(Locale.ROOT)))) throw invalid("env/header 名称或值不正确");
            String secretKey = "main:" + (stdio ? "env:" : "header:") + key;
            if (entry.getValue().isNull()) retained.add(secretKey); else secrets.put(secretKey, entry.getValue().asText());
            if (!stdio) headers.put(key, entry.getValue().isNull() ? "retained" : entry.getValue().asText());
        });
        PluginService.validateHeaders(URI.create("https://localhost/mcp"), headers);
        if (write(secrets).length() > 16384) throw invalid("凭据配置过大");
        var files = manifest("custom-mcp-" + request.name(), "connector", request.title(), request.description(), request.version(), "开发者工具", "globe");
        put(files, "mcp.json", write(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json", "mcpServers", Map.of("main", server))));
        put(files, "dev.javaharness/config.schema.json", write(Map.of("type", "object", "properties", properties, "additionalProperties", false)));
        JsonNode form = JSON.valueToTree(request);
        JsonNode redacted = form.path("definition").path(stdio ? "env" : "headers");
        if (redacted.isObject()) { var names = new ArrayList<String>(); redacted.fieldNames().forEachRemaining(names::add); names.forEach(key -> ((ObjectNode) redacted).putNull(key)); }
        return finish(files, "custom-mcp-" + request.name(), secrets, form, previous, revision, retained);
    }

    /** Serialize standalone creation with scoped selection so an existing binding is never silently replaced. */
    private PluginService.Installation finish(Map<String, byte[]> files, String name, Map<String, String> secrets, JsonNode form, String previous, long revision, Set<String> retained) {
        String scope = "user";
        synchronized (sessions) {
            if (!previous.isEmpty()) return plugins.replaceAuthored(previous, revision, files, form, secrets, retained);
            if (plugins.bindings(scope).stream().anyMatch(binding -> binding.name().equals(name))) throw new HarnessException("CONFLICT", "已有同名扩展，请编辑已安装的扩展或使用新的标识");
            var known = plugins.installations().stream().map(PluginService.Installation::id).toList();
            var item = plugins.importBundle(files, "custom");
            try {
                plugins.save(scope, name, new PluginService.SaveBinding(item.id(), false, JSON.createObjectNode(), secrets, false, List.of("*"), 0));
                plugins.setEnabled(item.id(), true, 1); plugins.rememberAuthoring(item.id(), form); return item;
            }
            catch (RuntimeException error) {
                if (!known.contains(item.id())) try { plugins.remove(item.id(), false, true); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }
    }

    private static void validateAuthoring(String name, String title, String description, String content) {
        if (name == null || name.length() > 40 || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || title == null || title.isBlank() || title.length() > 80
                || description == null || description.isBlank() || description.length() > 500 || description.contains("\n") || description.contains("\r")
                || content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > 30000) throw invalid("请填写有效标识、名称、单行说明和不超过 30 KB 的正文");
    }
    private static Map<String, byte[]> manifest(String name, String kind, String title, String description, String version, String category, String icon) {
        var files = new LinkedHashMap<String, byte[]>();
        if (version == null) throw invalid("请填写版本号");
        put(files, "plugin.json", write(Map.of("$schema", PluginPackages.SCHEMA, "name", name, "version", version, "description", description,
                "extensions", Map.of("dev.javaharness", Map.of("schemaVersion", 1, "kind", kind, "title", title, "category", category, "icon", icon)))));
        return files;
    }
    private static String markdown(String name, String description, String content) { return "---\nname: " + name + "\ndescription: " + write(description) + "\n---\n\n" + content + "\n"; }
    private static void put(Map<String, byte[]> files, String path, String content) {
        files.put(path, (path.endsWith(".json") ? canonical(parse(content)) : content).getBytes(StandardCharsets.UTF_8));
    }
}
