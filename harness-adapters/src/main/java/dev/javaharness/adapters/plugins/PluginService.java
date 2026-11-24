package dev.javaharness.adapters.plugins;

import static dev.javaharness.adapters.plugins.PluginJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.run.RunExtension;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionStore;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** Local plugin catalog, scoped bindings and run-owned executable snapshots. */
public final class PluginService {
    public record Installation(
            String id, String name, String version, String source, String digest, JsonNode manifest, boolean removed) {}

    record Binding(
            String scope,
            String name,
            String installationId,
            boolean enabled,
            JsonNode config,
            String secrets,
            List<String> allowedTools,
            String trustedDigest,
            long revision) {}

    public record BindingView(
            String scope,
            String name,
            String installationId,
            boolean enabled,
            JsonNode config,
            List<String> secretKeys,
            List<String> allowedTools,
            boolean trusted,
            String reviewDigest,
            List<JsonNode> launches,
            String launchError,
            long revision) {}

    public record Detail(
            Installation installation,
            JsonNode configSchema,
            Collection<PluginPackages.Component> skills,
            Collection<PluginPackages.Component> commands,
            Set<String> servers,
            int activeRuns) {}

    public record SaveBinding(
            String installationId,
            boolean enabled,
            JsonNode config,
            Map<String, String> secrets,
            boolean clearSecrets,
            List<String> allowedTools,
            long revision) {}

    public record Command(String id, String description) {}

    public record Authoring(JsonNode definition, List<String> secretKeys, long revision) {}

    public record Configuration(
            JsonNode schema,
            BindingView binding,
            Map<String, String> servers,
            boolean credentialsUnavailable,
            boolean selected) {}

    public record Configure(
            JsonNode config, Map<String, String> secrets, List<String> retainedKeys, boolean enabled, long revision) {}

    public record LoadIssue(String installationId, String name, String server, String message) {}

    record Captured(
            Installation installation,
            PluginPackages.Package pkg,
            Binding binding,
            List<PluginMcpClient.Launch> launches,
            List<String> serverNames,
            String trustDigest,
            List<LoadIssue> issues) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore sessions;
    private final LocalSecretCipher cipher;
    private final PluginPackages packages;
    private final PluginLaunchResolver launcher;
    private final ExternalToolApprovals approvals;
    private final Set<PreparedPluginExtension> active = ConcurrentHashMap.newKeySet();
    private static final RowMapper<Installation> INSTALLATION = (rs, row) -> new Installation(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("source"),
            rs.getString("digest"),
            parse(rs.getString("manifest")),
            rs.getBoolean("removed"));
    private static final RowMapper<Binding> BINDING = (rs, row) -> new Binding(
            rs.getString("scope"),
            rs.getString("name"),
            rs.getString("installation_id"),
            rs.getBoolean("enabled"),
            parse(rs.getString("config")),
            rs.getString("secrets"),
            strings(parse(rs.getString("allowed_tools"))),
            rs.getString("trusted_digest"),
            rs.getLong("revision"));

    public PluginService(
            JdbcTemplate jdbc, TransactionTemplate tx, SessionStore sessions, LocalSecretCipher cipher, Path root) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sessions = sessions;
        this.cipher = cipher;
        packages = new PluginPackages(root.resolve("packages"));
        launcher = new PluginLaunchResolver(
                jdbc, packages, root.toAbsolutePath().normalize().resolve("state"));
        approvals = new ExternalToolApprovals(jdbc, tx, sessions);
    }

    /** List installations without starting their components. */
    public List<Installation> installations() {
        synchronized (sessions) {
            return jdbc.query(
                    "SELECT * FROM harness_plugin_installation WHERE removed=FALSE ORDER BY name,version",
                    INSTALLATION);
        }
    }

    private Installation installation(String id) {
        var rows = jdbc.query("SELECT * FROM harness_plugin_installation WHERE id=?", INSTALLATION, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "插件不存在");
        return rows.getFirst();
    }

    /** Import a directory after fully validating and copying its files. */
    public Installation importDirectory(String path, String source) {
        source(source);
        return register(packages.directory(path), source);
    }
    /** Import a bounded ZIP package without executing installation scripts. */
    public Installation importZip(InputStream input, String source) {
        source(source);
        return register(packages.zip(input), source);
    }
    /** Install generated first-party or standalone extensions through the normal immutable package registry. */
    public Installation importBundle(Map<String, byte[]> files, String source) {
        source(source);
        return register(packages.bundle(files), source);
    }
    /** Import and select atomically; a valid package with missing settings remains installed but disabled. */
    public Installation installDirectory(String path, String source) {
        return install(() -> importDirectory(path, source));
    }

    public Installation installZip(InputStream input, String source) {
        return install(() -> importZip(input, source));
    }

    public Installation installBundle(Map<String, byte[]> files, String source) {
        return install(() -> importBundle(files, source));
    }

    private Installation install(Supplier<Installation> importer) {
        synchronized (sessions) {
            Set<String> existing =
                    new HashSet<>(jdbc.queryForList("SELECT id FROM harness_plugin_installation", String.class));
            Installation[] imported = new Installation[1];
            try {
                return tx.execute(status -> {
                    imported[0] = importer.get();
                    return activate(imported[0]);
                });
            } catch (RuntimeException error) {
                if (imported[0] != null && !existing.contains(imported[0].id())) packages.discard(imported[0].id());
                throw error;
            }
        }
    }

    private static void source(String source) {
        if (source == null || !source.matches("[a-zA-Z0-9._-]{1,100}") || source.equals(".") || source.equals(".."))
            throw invalid("来源 ID 需为 1–100 个字母、数字或 ._- 字符");
    }

    private Installation register(PluginPackages.Package pkg, String source) {
        boolean registered = false;
        try {
            synchronized (sessions) {
                var rows = jdbc.query(
                        "SELECT * FROM harness_plugin_installation WHERE source=? AND name=? AND version=?",
                        INSTALLATION,
                        source,
                        pkg.name(),
                        pkg.version());
                if (!rows.isEmpty()) {
                    Installation prior = rows.getFirst();
                    if (!prior.digest().equals(pkg.digest()))
                        throw new HarnessException("CONFLICT", "相同来源和版本的内容不同，请升级版本");
                    try {
                        packages.read(prior.id(), prior.digest());
                    } catch (HarnessException damaged) {
                        if (!prior.removed()) throw new HarnessException("CONFLICT", "安装内容不可用，请先卸载此版本再重新安装");
                        if (active.stream()
                                .anyMatch(run -> run.captured.stream()
                                        .anyMatch(value ->
                                                value.installation().id().equals(prior.id()))))
                            throw new HarnessException("CONFLICT", "此版本仍有活动任务，请结束任务后重新安装");
                        packages.restore(prior.id(), pkg);
                        registered = true;
                    }
                    jdbc.update("UPDATE harness_plugin_installation SET removed=FALSE WHERE id=?", prior.id());
                    return installation(prior.id());
                }
                jdbc.update(
                        "INSERT INTO harness_plugin_installation(id,name,version,source,digest,manifest,created_at) VALUES (?,?,?,?,?,?,?)",
                        pkg.id(),
                        pkg.name(),
                        pkg.version(),
                        source,
                        pkg.digest(),
                        write(pkg.manifest()),
                        OffsetDateTime.now(ZoneOffset.UTC));
                registered = true;
                return installation(pkg.id());
            }
        } finally {
            if (!registered) packages.discard(pkg.id());
        }
    }

    /** Inspect package capabilities and their configuration schema. */
    public Detail detail(String id) {
        synchronized (sessions) {
            Installation item = installation(id);
            var pkg = packages.read(id, item.digest());
            return new Detail(
                    item,
                    pkg.configSchema(),
                    pkg.skills().values(),
                    pkg.commands().values(),
                    pkg.servers().keySet(),
                    (int) active.stream()
                            .filter(run -> run.captured.stream()
                                    .anyMatch(value -> value.installation().id().equals(id)))
                            .count());
        }
    }

    /** Read only a declared Skill, never arbitrary installation files or connector configuration. */
    public String skillContent(String id, String skill) {
        synchronized (sessions) {
            Installation item = installation(id);
            var pkg = packages.read(id, item.digest());
            var component = pkg.skills().get(skill);
            if (component == null) throw new HarnessException("NOT_FOUND", "技能不存在");
            return PluginPackages.text(pkg.files(), component.path(), 32768);
        }
    }

    /** Resolve the global selection for every session. */
    private List<Binding> effective(Session session) {
        var values = new TreeMap<String, Binding>();
        for (Binding binding : jdbc.query("SELECT * FROM harness_plugin_binding WHERE scope='user'", BINDING))
            values.put(binding.name(), binding);
        return values.values().stream().filter(Binding::enabled).toList();
    }

    /** Preserve compatible settings; incompatible versions remain installed for explicit configuration. */
    public Installation activate(Installation item) {
        synchronized (sessions) {
            Binding old = binding("user", item.name());
            if (old != null && old.installationId().equals(item.id())) return item;
            if (old != null && !installation(old.installationId()).source().equals(item.source()))
                throw new HarnessException("CONFLICT", "同名插件来自不同来源，请先卸载原插件");
            var pkg = packages.read(item.id(), item.digest());
            JsonNode config = defaults(pkg.configSchema(), old == null ? null : old.config());
            boolean ready = true;
            try {
                validate(pkg.configSchema(), config);
            } catch (HarnessException missingConfiguration) {
                if (old != null) return item;
                ready = false;
            }
            if (old != null) {
                try {
                    capture(new Binding(
                            "user",
                            item.name(),
                            item.id(),
                            old.enabled(),
                            config,
                            old.secrets(),
                            List.of("*"),
                            "",
                            old.revision()));
                } catch (HarnessException | IllegalStateException incompatibleSettings) {
                    return item;
                }
            }
            if (ready) {
                save(
                        "user",
                        item.name(),
                        new SaveBinding(
                                item.id(),
                                false,
                                config,
                                Map.of(),
                                false,
                                List.of("*"),
                                old == null ? 0 : old.revision()));
                jdbc.update(
                        "UPDATE harness_plugin_binding SET enabled=? WHERE scope='user' AND name=?",
                        old == null || old.enabled(),
                        item.name());
            } else {
                jdbc.update(
                        "MERGE INTO harness_plugin_binding KEY(scope,name) VALUES ('user',?,?,FALSE,?,?,'[\"*\"]','',?)",
                        item.name(),
                        item.id(),
                        write(config),
                        old == null ? "" : old.secrets(),
                        old == null ? 1 : old.revision() + 1);
            }
            return item;
        }
    }

    /** Toggle without resolving executables so broken extensions remain recoverable. */
    public void setEnabled(String id, boolean enabled, long revision) {
        synchronized (sessions) {
            Installation item = installation(id);
            Binding value = binding("user", item.name());
            if (!item.removed()
                    && enabled
                    && revision == 0
                    && (value == null || !value.installationId().equals(id))) {
                activate(item);
                value = binding("user", item.name());
                if (!value.installationId().equals(id))
                    throw new HarnessException("CONFLICT", "请先在详情中配置此版本，当前版本和配置保持不变");
                revision = value.revision();
            }
            if (value == null) throw new HarnessException("CONFLICT", "扩展已更新，请刷新");
            if (item.removed() || !value.installationId().equals(id) || value.revision() != revision)
                throw new HarnessException("CONFLICT", "扩展已更新，请刷新");
            if (enabled) validate(packages.read(item.id(), item.digest()).configSchema(), value.config());
            jdbc.update(
                    "UPDATE harness_plugin_binding SET enabled=?,allowed_tools='[\"*\"]',revision=revision+1 WHERE scope='user' AND name=?",
                    enabled,
                    item.name());
        }
    }

    /** Save only non-secret authoring fields; credentials remain encrypted in the binding. */
    public void rememberAuthoring(String id, JsonNode definition) {
        synchronized (sessions) {
            jdbc.update(
                    "MERGE INTO harness_extension_authoring KEY(installation_id) VALUES (?,?)", id, write(definition));
        }
    }
    /** Return a redacted form only for entries authored by this application. */
    public Authoring authoring(String id) {
        synchronized (sessions) {
            var rows = jdbc.queryForList(
                    "SELECT definition FROM harness_extension_authoring WHERE installation_id=?", String.class, id);
            Installation item = installation(id);
            Binding value = binding("user", item.name());
            if (rows.isEmpty() || value == null || !value.installationId().equals(id) || item.removed())
                throw new HarnessException("CONFLICT", "此扩展的信息只读");
            JsonNode form = parse(rows.getFirst());
            var keys = new ArrayList<String>();
            for (String location : List.of("env", "headers"))
                form.path("definition")
                        .path(location)
                        .fieldNames()
                        .forEachRemaining(
                                key -> keys.add("main:" + (location.equals("env") ? "env:" : "header:") + key));
            return new Authoring(form, List.copyOf(keys), value.revision());
        }
    }
    /** Replace a custom version atomically while keeping prior packages and run snapshots intact. */
    public Installation replaceAuthored(
            String previous,
            long revision,
            Map<String, byte[]> files,
            JsonNode definition,
            Map<String, String> replacements,
            Set<String> retainedKeys) {
        synchronized (sessions) {
            authoring(previous);
            Installation prior = installation(previous);
            Binding value = requiredBinding("user", prior.name());
            if (value.revision() != revision) throw new HarnessException("CONFLICT", "扩展已更新，请重新打开编辑");
            Map<String, String> secrets = retainedSecrets(value, retainedKeys);
            secrets.putAll(replacements);
            return tx.execute(status -> {
                Installation next = importBundle(files, "custom");
                if (!next.name().equals(prior.name())) throw invalid("编辑时不能修改唯一标识");
                save(
                        "user",
                        next.name(),
                        new SaveBinding(next.id(), false, null, secrets, true, List.of("*"), revision));
                jdbc.update(
                        "UPDATE harness_plugin_binding SET enabled=? WHERE scope='user' AND name=?",
                        value.enabled(),
                        next.name());
                rememberAuthoring(next.id(), definition);
                if (!next.id().equals(previous))
                    jdbc.update("UPDATE harness_plugin_installation SET removed=TRUE WHERE id=?", previous);
                return next;
            });
        }
    }

    /** Read package settings without requiring a working credential key or a live MCP connection. */
    public Configuration configuration(String id) {
        synchronized (sessions) {
            Detail detail = detail(id);
            Binding value = binding("user", detail.installation().name());
            if (detail.installation().removed()) throw new HarnessException("CONFLICT", "此版本已卸载");
            if (value != null
                    && !installation(value.installationId())
                            .source()
                            .equals(detail.installation().source()))
                throw new HarnessException("CONFLICT", "同名插件来自不同来源，请先卸载原插件");
            if (value == null) throw new HarnessException("CONFLICT", "请先启用此版本");
            var pkg = packages.read(id, detail.installation().digest());
            var servers = new TreeMap<String, String>();
            pkg.servers()
                    .forEach((name, server) ->
                            servers.put(name, server.path("type").asText()));
            boolean selected = value.installationId().equals(id);
            if (!selected)
                value = new Binding(
                        value.scope(),
                        value.name(),
                        id,
                        value.enabled(),
                        defaults(pkg.configSchema(), value.config()),
                        value.secrets(),
                        value.allowedTools(),
                        "",
                        value.revision());
            boolean unavailable = false;
            try {
                secretValues(value.secrets());
            } catch (IllegalStateException error) {
                unavailable = true;
            }
            return new Configuration(detail.configSchema(), view(value), servers, unavailable, selected);
        }
    }

    /** Replace user settings and explicitly retained credentials without modifying immutable package bytes. */
    public BindingView configure(String id, Configure request) {
        synchronized (sessions) {
            Installation item = installation(id);
            Binding old = requiredBinding("user", item.name());
            if (old.revision() != request.revision()) throw new HarnessException("CONFLICT", "配置已更新，请重新读取");
            var secrets = retainedSecrets(
                    old, request.retainedKeys() == null ? Set.of() : new HashSet<>(request.retainedKeys()));
            if (request.secrets() != null) secrets.putAll(request.secrets());
            return tx.execute(status -> {
                save(
                        "user",
                        item.name(),
                        new SaveBinding(id, false, request.config(), secrets, true, List.of("*"), request.revision()));
                // Validate the complete launch settings before committing this configuration revision.
                Binding saved = requiredBinding("user", item.name());
                capture(saved);
                jdbc.update(
                        "UPDATE harness_plugin_binding SET enabled=? WHERE scope='user' AND name=?",
                        request.enabled(),
                        item.name());
                return view(requiredBinding("user", item.name()));
            });
        }
    }

    private Map<String, String> retainedSecrets(Binding value, Set<String> retained) {
        var result = new TreeMap<String, String>();
        if (!retained.isEmpty()) {
            try {
                result.putAll(secretValues(value.secrets()));
            } catch (IllegalStateException error) {
                throw invalid("原凭据无法读取，请重新填写所有凭据或明确删除，不能保留旧值");
            }
            if (!result.keySet().containsAll(retained)) throw invalid("要保留的凭据已不存在，请重新读取配置");
            result.keySet().retainAll(retained);
        }
        return result;
    }

    private static JsonNode defaults(JsonNode schema, JsonNode supplied) {
        JsonNode config = supplied == null ? JSON.createObjectNode() : supplied.deepCopy();
        if (config.isObject())
            schema.path("properties").fields().forEachRemaining(field -> {
                if (!config.has(field.getKey()) && field.getValue().has("default"))
                    ((ObjectNode) config).set(field.getKey(), field.getValue().get("default"));
            });
        return config;
    }

    /** Read bindings with secret values omitted and execution previews redacted. */
    public List<BindingView> bindings(String scope) {
        synchronized (sessions) {
            scope(scope);
            return jdbc
                    .query("SELECT * FROM harness_plugin_binding WHERE scope=? ORDER BY name", BINDING, scope)
                    .stream()
                    .map(this::view)
                    .toList();
        }
    }

    private Binding binding(String scope, String name) {
        var rows = jdbc.query("SELECT * FROM harness_plugin_binding WHERE scope=? AND name=?", BINDING, scope, name);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Save complete scoped settings; execution trust is retained only for identical launch identities. */
    public BindingView save(String scope, String name, SaveBinding request) {
        synchronized (sessions) {
            scope(scope);
            Installation item = installation(request.installationId());
            if (item.removed() || !item.name().equals(name)) throw invalid("插件身份不匹配或已卸载");
            var pkg = packages.read(item.id(), item.digest());
            Binding old = binding(scope, name);
            if (request.revision() != (old == null ? 0 : old.revision()))
                throw new HarnessException("CONFLICT", "插件配置已更新，请刷新");
            if (old != null && !installation(old.installationId()).source().equals(item.source()))
                throw new HarnessException("CONFLICT", "同名插件来自不同来源，请先解除原绑定");
            JsonNode config = defaults(pkg.configSchema(), request.config());
            if (write(config).length() > 32768) throw invalid("插件配置过大");
            validate(pkg.configSchema(), config);
            String secrets = old == null || request.clearSecrets() ? "" : old.secrets();
            if (request.secrets() != null && !request.secrets().isEmpty()) {
                Map<String, String> merged = new TreeMap<>(secretValues(secrets));
                merged.putAll(request.secrets());
                if (write(merged).length() > 16384) throw invalid("凭据配置过大");
                secrets = cipher.encrypt(write(merged));
            }
            List<String> allowed = request.allowedTools() == null ? List.of() : List.copyOf(request.allowedTools());
            if (allowed.size() > 128
                    || allowed.stream()
                            .anyMatch(value -> !value.equals("*") && !value.matches("[a-zA-Z0-9_-]{1,128}/.{1,256}")))
                throw invalid("预授权需填写精确 server/tool 名称");
            Binding next = new Binding(
                    scope, name, item.id(), request.enabled(), config, secrets, allowed, "", request.revision() + 1);
            String trusted = "";
            if (next.enabled()) {
                Captured capture = capture(next);
                if (old != null && old.trustedDigest().equals(capture.trustDigest())) trusted = old.trustedDigest();
            }
            jdbc.update(
                    """
                    MERGE INTO harness_plugin_binding(scope,name,installation_id,enabled,config,secrets,allowed_tools,trusted_digest,revision)
                    KEY(scope,name) VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                    scope,
                    name,
                    item.id(),
                    next.enabled(),
                    write(config),
                    secrets,
                    write(allowed),
                    trusted,
                    next.revision());
            return view(binding(scope, name));
        }
    }

    /** Grant startup trust only to the exact preview the user reviewed. */
    public BindingView trust(String scope, String name, long revision, String reviewDigest) {
        synchronized (sessions) {
            scope(scope);
            Binding value = requiredBinding(scope, name);
            Captured captured = capture(value);
            if (value.revision() != revision || !captured.trustDigest().equals(reviewDigest))
                throw new HarnessException("CONFLICT", "启动配置已改变，请重新审阅");
            jdbc.update(
                    "UPDATE harness_plugin_binding SET trusted_digest=?,revision=revision+1 WHERE scope=? AND name=?",
                    reviewDigest,
                    scope,
                    name);
            return view(requiredBinding(scope, name));
        }
    }

    /** Remove an override so the user-level choice can apply again. */
    public void unbind(String scope, String name, long revision) {
        synchronized (sessions) {
            scope(scope);
            if (requiredBinding(scope, name).revision() != revision) throw new HarnessException("CONFLICT", "配置已更新");
            jdbc.update("DELETE FROM harness_plugin_binding WHERE scope=? AND name=?", scope, name);
        }
    }

    /** Stop new use while retaining historical packages; optionally revoke all active leases. */
    public List<String> remove(String id, boolean revoke, boolean uninstall) {
        synchronized (sessions) {
            installation(id);
            jdbc.update(
                    "UPDATE harness_plugin_binding SET enabled=FALSE,trusted_digest='',revision=revision+1 WHERE installation_id=?",
                    id);
            if (uninstall) {
                jdbc.update("DELETE FROM harness_plugin_binding WHERE installation_id=?", id);
                jdbc.update("UPDATE harness_plugin_installation SET removed=TRUE WHERE id=?", id);
            }
            var runs = new ArrayList<String>();
            if (revoke) {
                for (PreparedPluginExtension run : active)
                    if (run.captured.stream()
                            .anyMatch(value -> value.installation().id().equals(id))) {
                        run.revoked.set(true);
                        if (run.runId != null) runs.add(run.runId);
                    }
                approvals.revoke(id);
            }
            return runs;
        }
    }

    /** Return the command menu for the session's effective plugin scope. */
    public List<Command> commands(String sessionId) {
        synchronized (sessions) {
            var result = new ArrayList<Command>();
            for (Binding binding : effective(sessions.snapshot(sessionId).session())) {
                Installation item = installation(binding.installationId());
                if (item.removed()) continue;
                for (var component :
                        packages.read(item.id(), item.digest()).commands().values())
                    result.add(new Command(item.name() + ":" + component.id(), component.description()));
            }
            return result;
        }
    }

    /** Capture package bytes and settings under the same session monitor used by submission. */
    public RunExtension prepare(Session session, String prompt) {
        synchronized (sessions) {
            var captured = effective(session).stream()
                    .map(binding -> capture(binding, true))
                    .toList();
            if (captured.isEmpty() && !commandPrompt(prompt)) return RunExtension.none();
            var prepared =
                    new PreparedPluginExtension(captured, prompt, launcher, approvals, jdbc, tx, sessions, active);
            active.add(prepared);
            return prepared;
        }
    }

    /** Read the durable plugin selection and discovered tools for a run. */
    public JsonNode snapshot(String runId) {
        synchronized (sessions) {
            sessions.run(runId);
            return parse(
                    jdbc.queryForObject("SELECT plugin_snapshot FROM harness_run WHERE id=?", String.class, runId));
        }
    }

    public ExternalToolApprovals approvals() {
        return approvals;
    }

    /** Diagnose one configured connection only after its startup identity has been trusted. */
    public Map<String, Object> diagnose(String scope, String name) throws Exception {
        return diagnose(scope, name, false, "");
    }
    /** Explicitly test saved settings, including disabled entries, without changing their enabled state. */
    public Map<String, Object> diagnoseConfiguration(String id) throws Exception {
        String name;
        synchronized (sessions) {
            configuration(id);
            name = installation(id).name();
        }
        return diagnose("user", name, true, id);
    }

    private Map<String, Object> diagnose(String scope, String name, boolean allowDisabled, String expectedId)
            throws Exception {
        Captured value;
        PreparedPluginExtension probe;
        synchronized (sessions) {
            scope(scope);
            Binding binding = requiredBinding(scope, name);
            if (!expectedId.isEmpty() && !binding.installationId().equals(expectedId))
                throw new HarnessException("CONFLICT", "插件版本已更新，请刷新后重试");
            if (!binding.enabled() && !allowDisabled) throw new HarnessException("CONFLICT", "请先启用插件再执行连接诊断");
            value = capture(binding, true);
            requireTrusted(value);
            probe = new PreparedPluginExtension(
                    List.of(value), "连接诊断", launcher, approvals, jdbc, tx, sessions, active);
            active.add(probe);
        }
        var result = new LinkedHashMap<String, Object>();
        try (probe) {
            for (var issue : value.issues())
                result.put(
                        issue.server().isEmpty() ? "配置" : issue.server(),
                        Map.of("success", false, "message", issue.message(), "tools", List.of()));
            for (int i = 0; i < value.launches().size(); i++) {
                String server = value.serverNames().get(i);
                ModelProvider.Cancellation cancellation = () -> {
                    probe.guard();
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                };
                PluginMcpClient client = null;
                try {
                    cancellation.check();
                    client = launcher.openClient(value.launches().get(i));
                    probe.clients.add(client);
                    result.put(
                            server,
                            Map.of(
                                    "success",
                                    true,
                                    "message",
                                    "连接成功",
                                    "tools",
                                    client.initialize(cancellation).keySet()));
                } catch (Exception error) {
                    cancellation.check();
                    if (!(error instanceof HarnessException) && !PluginLaunchResolver.connectionFailure(error))
                        throw error;
                    result.put(
                            server,
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    PluginLaunchResolver.connectionMessage(error),
                                    "tools",
                                    List.of()));
                } finally {
                    if (client != null) {
                        client.close();
                        probe.clients.remove(client);
                    }
                }
            }
        }
        return result;
    }

    private Binding requiredBinding(String scope, String name) {
        Binding value = binding(scope, name);
        if (value == null) throw new HarnessException("NOT_FOUND", "插件绑定不存在");
        return value;
    }

    private void scope(String value) {
        if (value == null || !value.equals("user") && !value.matches("workspace:[0-9a-f-]{36}"))
            throw invalid("插件范围不正确");
        if (value.startsWith("workspace:")) sessions.workspace(value.substring(10));
    }

    private Map<String, String> secretValues(String encrypted) {
        if (encrypted.isEmpty()) return Map.of();
        return JSON.convertValue(
                parse(cipher.decrypt(encrypted)),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
    }

    private BindingView view(Binding binding) {
        Captured value;
        List<String> secretKeys = List.of();
        try {
            secretKeys =
                    secretValues(binding.secrets()).keySet().stream().sorted().toList();
            value = capture(binding);
        } catch (HarnessException | IllegalStateException error) {
            return new BindingView(
                    binding.scope(),
                    binding.name(),
                    binding.installationId(),
                    binding.enabled(),
                    binding.config(),
                    secretKeys,
                    binding.allowedTools(),
                    false,
                    "",
                    List.of(),
                    error.getMessage(),
                    binding.revision());
        }
        var previews = new ArrayList<JsonNode>();
        for (int i = 0; i < value.launches().size(); i++) {
            var launch = value.launches().get(i);
            var node = JSON.createObjectNode();
            node.put("server", value.serverNames().get(i));
            node.set("command", JSON.valueToTree(launch.command()));
            node.put("cwd", launch.cwd());
            node.put("url", launch.url());
            node.set("environmentKeys", JSON.valueToTree(launch.environment().keySet()));
            node.set("headerKeys", JSON.valueToTree(launch.headers().keySet()));
            previews.add(node);
        }
        return new BindingView(
                binding.scope(),
                binding.name(),
                binding.installationId(),
                binding.enabled(),
                binding.config(),
                secretKeys,
                binding.allowedTools(),
                binding.allowedTools().contains("*")
                        || value.launches().isEmpty()
                        || binding.trustedDigest().equals(value.trustDigest()),
                value.trustDigest(),
                previews,
                "",
                binding.revision());
    }

    private Captured capture(Binding binding) {
        return capture(binding, false);
    }

    private Captured capture(Binding binding, boolean tolerateConnectionFailure) {
        Installation item = installation(binding.installationId());
        if (item.removed()) throw new HarnessException("CONFLICT", "插件已卸载");
        var pkg = packages.read(item.id(), item.digest());
        var launches = new ArrayList<PluginMcpClient.Launch>();
        var names = new ArrayList<String>();
        var reviewed = new ArrayList<Object>();
        var issues = new ArrayList<LoadIssue>();
        Map<String, String> secrets;
        try {
            validate(pkg.configSchema(), binding.config());
            secrets = secretValues(binding.secrets());
            for (String key : secrets.keySet()) {
                String[] parts = key.split(":", 3);
                if (parts.length != 3
                        || !pkg.servers().containsKey(parts[0])
                        || !Set.of("env", "header").contains(parts[1])
                        || parts[2].isEmpty()
                        || parts[2].length() > 128
                        || parts[1].equals("env")
                                && (!parts[2].matches("[a-zA-Z_][a-zA-Z0-9_-]{0,127}")
                                        || Set.of("PLUGIN_ROOT", "PLUGIN_DATA")
                                                .contains(parts[2].toUpperCase(Locale.ROOT))))
                    throw invalid("凭据键需为 server:env:NAME 或 server:header:Name");
            }
        } catch (HarnessException | IllegalStateException error) {
            if (!tolerateConnectionFailure) throw error;
            return new Captured(
                    item,
                    pkg,
                    binding,
                    List.of(),
                    List.of(),
                    "",
                    List.of(new LoadIssue(item.id(), item.name(), "", error.getMessage())));
        }
        pkg.servers().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            PluginMcpClient.Launch launch;
            try {
                launch = launcher.launch(item, binding, entry.getKey(), entry.getValue(), secrets);
            } catch (HarnessException error) {
                if (!tolerateConnectionFailure) throw error;
                issues.add(new LoadIssue(item.id(), item.name(), entry.getKey(), error.getMessage()));
                return;
            }
            launches.add(launch);
            names.add(entry.getKey());
            var explicitEnvironment = new TreeMap<String, String>();
            explicitEnvironment.put("PLUGIN_ROOT", launch.environment().get("PLUGIN_ROOT"));
            explicitEnvironment.put("PLUGIN_DATA", launch.environment().get("PLUGIN_DATA"));
            entry.getValue()
                    .path("env")
                    .fieldNames()
                    .forEachRemaining(key ->
                            explicitEnvironment.put(key, launch.environment().get(key)));
            secrets.forEach((key, value) -> {
                String[] parts = key.split(":", 3);
                if (parts[0].equals(entry.getKey()) && parts[1].equals("env")) explicitEnvironment.put(parts[2], value);
            });
            reviewed.add(Map.of(
                    "server",
                    entry.getKey(),
                    "command",
                    launch.command(),
                    "cwd",
                    launch.cwd(),
                    "url",
                    launch.url(),
                    "environment",
                    explicitEnvironment,
                    "headers",
                    launch.headers(),
                    "timeoutSeconds",
                    launch.timeoutSeconds()));
        });
        // Inherited host variables may change between launches; explicit overrides and resolved executables remain
        // trusted identities.
        String trust = fingerprint(
                List.of(item.digest(), binding.config(), reviewed, binding.allowedTools(), binding.scope()));
        return new Captured(item, pkg, binding, List.copyOf(launches), List.copyOf(names), trust, List.copyOf(issues));
    }

    /** Validate with the runtime HTTP implementation before storing any executable binding. */
    public static void validateHeaders(URI uri, Map<String, String> headers) {
        PluginLaunchResolver.validateHeaders(uri, headers);
    }

    private static List<String> strings(JsonNode array) {
        var values = new ArrayList<String>();
        for (JsonNode value : array) values.add(value.asText());
        return List.copyOf(values);
    }

    static boolean commandPrompt(String prompt) {
        return prompt.split("\\s", 2)[0].matches("/[a-z0-9][a-z0-9.-]*:[a-z0-9][a-z0-9_-]*");
    }

    static void requireTrusted(Captured value) {
        if (!value.binding().allowedTools().contains("*")
                && !value.launches().isEmpty()
                && !value.binding().trustedDigest().equals(value.trustDigest()))
            throw new HarnessException(
                    "CONFLICT", "请在插件设置中审阅并信任当前启动配置：" + value.installation().name());
    }
}
