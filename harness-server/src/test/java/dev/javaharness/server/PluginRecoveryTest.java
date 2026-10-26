package dev.javaharness.server;

import dev.javaharness.adapters.*;
import dev.javaharness.adapters.plugins.*;
import dev.javaharness.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.javaharness.adapters.plugins.PluginJson.*;
import static org.assertj.core.api.Assertions.*;

class PluginRecoveryTest {
    @TempDir Path directory;
    JdbcTemplate jdbc; JdbcSessionStore sessions; PluginService plugins; ExtensionCatalogService catalog;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate(); jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds)); sessions = new JdbcSessionStore(jdbc, tx);
        plugins = new PluginService(jdbc, tx, sessions, new LocalSecretCipher(directory.resolve("settings.key")), directory.resolve("plugins"));
        catalog = new ExtensionCatalogService(plugins, sessions);
    }

    /** A damaged credential never removes the edit, disable or uninstall recovery paths. */
    @Test void corruptCredentialDoesNotBreakCatalogAndCanBeReplaced() {
        var item = catalog.createConnector(new ExtensionCatalogService.Connector("api", "API", "fixture", "1.0.0",
                parse("{\"type\":\"streamable-http\",\"url\":\"https://example.com/mcp\",\"headers\":{\"X_KEY\":\"old-secret\"}}"), "user"));
        jdbc.update("UPDATE harness_plugin_binding SET secrets='corrupt' WHERE name=?", item.name());
        assertThat(catalog.items()).filteredOn(value -> value.installationId().equals(item.id())).singleElement().satisfies(value -> {
            assertThat(value.editable()).isTrue(); assertThat(value.issue()).contains("无法读取");
        });
        assertThat(plugins.configuration(item.id()).credentialsUnavailable()).isTrue();
        var form = plugins.authoring(item.id());
        assertThatThrownBy(() -> catalog.update(item.id(), form.revision(), form.definition())).hasMessageContaining("重新填写");
        var replacement = form.definition().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) replacement.path("definition").path("headers")).put("X_KEY", "new-secret");
        var next = catalog.update(item.id(), form.revision(), replacement);
        assertThat(plugins.configuration(next.id()).credentialsUnavailable()).isFalse();
        assertThat(write(catalog.items())).doesNotContain("old-secret", "new-secret");
        jdbc.update("UPDATE harness_plugin_binding SET secrets='corrupt' WHERE name=?", next.name());
        plugins.setEnabled(next.id(), false, plugins.authoring(next.id()).revision());
        plugins.remove(next.id(), false, true); assertThat(plugins.installations()).isEmpty();
    }

    /** A valid package is installed once, shown as pending settings, then enabled after configuration. */
    @Test void requiredSettingsRemainRecoverableAndSourceConflictsRollback() {
        var files = bundle("configurable", Map.of());
        files.put("dev.javaharness/config.schema.json", bytes("{\"type\":\"object\",\"properties\":{\"project\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"project\"],\"additionalProperties\":false}"));
        var item = plugins.installBundle(files, "local");
        var pending = plugins.configuration(item.id());
        assertThat(pending.binding().enabled()).isFalse(); assertThat(pending.binding().launchError()).contains("project");
        assertThat(catalog.items()).filteredOn(value -> value.installationId().equals(item.id())).allMatch(value -> value.issue().contains("project"));
        assertThat(plugins.installBundle(files, "local").id()).isEqualTo(item.id());
        var saved = plugins.configure(item.id(), new PluginService.Configure(parse("{\"project\":\"demo\"}"), Map.of(), List.of(), true, pending.binding().revision()));
        assertThat(saved.enabled()).isTrue(); assertThat(saved.launchError()).isEmpty();
        assertThatThrownBy(() -> plugins.configure(item.id(), new PluginService.Configure(parse("{}"), Map.of(), List.of(), true, saved.revision()))).hasMessageContaining("project");
        assertThat(plugins.configuration(item.id()).binding()).isEqualTo(saved);
        assertThatThrownBy(() -> plugins.installBundle(files, "another-source")).hasMessageContaining("不同来源");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM harness_plugin_installation", Integer.class)).isEqualTo(1);
        assertThat(plugins.configuration(item.id()).binding()).isEqualTo(saved);
        try (var paths = Files.list(directory.resolve("plugins/packages"))) { assertThat(paths.count()).isEqualTo(1); }
        catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    /** The active version and earlier user settings survive a failed replacement import. */
    @Test void failedConflictDoesNotResurrectPreviouslyRemovedInstall() {
        var files = bundle("duplicate", Map.of()); var removed = plugins.installBundle(files, "other"); plugins.remove(removed.id(), false, true);
        var current = plugins.installBundle(files, "local");
        assertThatThrownBy(() -> plugins.installBundle(files, "other")).hasMessageContaining("不同来源");
        assertThat(plugins.installations()).extracting(PluginService.Installation::id).containsExactly(current.id());
    }

    /** Compatible upgrades retain user values, credentials and an explicitly disabled selection. */
    @Test void compatibleUpgradeRetainsConfigurationAndDisablement() {
        var files = bundle("upgrade", Map.of("api", Map.of("type", "streamable-http", "url", "https://example.com/mcp")));
        files.put("dev.javaharness/config.schema.json", bytes("{\"type\":\"object\",\"properties\":{\"project\":{\"type\":\"string\",\"default\":\"default-project\"}},\"required\":[\"project\"],\"additionalProperties\":false}"));
        var first = plugins.installBundle(files, "local");
        var before = plugins.configure(first.id(), new PluginService.Configure(parse("{\"project\":\"user-project\"}"), Map.of("api:header:X_KEY", "secret"), List.of(), false, 1));
        files.put("plugin.json", bytes(write(Map.of("$schema", PluginPackages.SCHEMA, "name", "upgrade", "version", "1.0.1"))));
        var next = plugins.installBundle(files, "local");
        var after = plugins.configuration(next.id()).binding();
        assertThat(after.installationId()).isEqualTo(next.id());
        assertThat(after.config()).isEqualTo(before.config()); assertThat(after.enabled()).isFalse();
        assertThat(after.secretKeys()).containsExactly("api:header:X_KEY");
        assertThat(after.revision()).isGreaterThan(before.revision());
        plugins.setEnabled(first.id(), true, 0);
        assertThat(plugins.configuration(first.id()).binding().enabled()).isTrue();
        assertThat(plugins.configuration(first.id()).binding().config()).isEqualTo(before.config());
    }

    /** Required settings without defaults survive upgrades and new defaults fill only missing keys. */
    @Test void compatibleUpgradeKeepsRequiredValuesAndAddsDefaults() {
        var files = bundle("required-upgrade", Map.of());
        files.put("dev.javaharness/config.schema.json", bytes("{\"type\":\"object\",\"properties\":{\"project\":{\"type\":\"string\"}},\"required\":[\"project\"],\"additionalProperties\":false}"));
        var first = plugins.installBundle(files, "local");
        plugins.configure(first.id(), new PluginService.Configure(parse("{\"project\":\"user-project\"}"), Map.of(), List.of(), true, 1));
        files.put("plugin.json", bytes(write(Map.of("$schema", PluginPackages.SCHEMA, "name", "required-upgrade", "version", "1.0.1"))));
        files.put("dev.javaharness/config.schema.json", bytes("{\"type\":\"object\",\"properties\":{\"project\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"default\":10}},\"required\":[\"project\",\"limit\"],\"additionalProperties\":false}"));
        var next = plugins.installBundle(files, "local");
        var after = plugins.configuration(next.id()).binding();
        assertThat(after.config()).isEqualTo(parse("{\"project\":\"user-project\",\"limit\":10}"));
        assertThat(after.enabled()).isTrue();
    }

    /** Incompatible candidates leave the current version intact until valid settings are saved atomically. */
    @Test void incompatibleUpgradeCanBeConfiguredWithoutBreakingCurrentVersion() {
        var files = bundle("pending-upgrade", Map.of());
        files.put("dev.javaharness/config.schema.json", bytes("{\"type\":\"object\",\"properties\":{\"project\":{\"type\":\"string\"}},\"required\":[\"project\"],\"additionalProperties\":false}"));
        var first = plugins.installBundle(files, "local");
        var before = plugins.configure(first.id(), new PluginService.Configure(parse("{\"project\":\"user-project\"}"), Map.of(), List.of(), true, 1));
        files.put("plugin.json", bytes(write(Map.of("$schema", PluginPackages.SCHEMA, "name", "pending-upgrade", "version", "2.0.0"))));
        files.put("dev.javaharness/config.schema.json", bytes("{\"type\":\"object\",\"properties\":{\"projectId\":{\"type\":\"integer\"}},\"required\":[\"projectId\"],\"additionalProperties\":false}"));
        var next = plugins.installBundle(files, "local");
        assertThat(plugins.configuration(first.id()).binding()).isEqualTo(before);
        assertThatThrownBy(() -> plugins.setEnabled(next.id(), true, 0)).hasMessageContaining("配置");
        var draft = plugins.configuration(next.id());
        assertThat(draft.selected()).isFalse();
        assertThat(draft.binding().config()).isEqualTo(before.config());
        assertThat(draft.schema().path("properties").has("projectId")).isTrue();
        assertThatThrownBy(() -> plugins.configure(next.id(), new PluginService.Configure(parse("{}"), Map.of(), List.of(), true, before.revision()))).hasMessageContaining("projectId");
        assertThat(plugins.configuration(first.id()).binding()).isEqualTo(before);
        plugins.setEnabled(first.id(), false, before.revision());
        assertThatThrownBy(() -> plugins.configure(next.id(), new PluginService.Configure(parse("{\"projectId\":42}"), Map.of(), List.of(), true, before.revision()))).hasMessageContaining("已更新");
        var selected = plugins.configure(next.id(), new PluginService.Configure(parse("{\"projectId\":42}"), Map.of(), List.of(), true, before.revision() + 1));
        assertThat(plugins.configuration(next.id()).selected()).isTrue();
        assertThat(selected.installationId()).isEqualTo(next.id()); assertThat(selected.enabled()).isTrue();
        assertThat(selected.config()).isEqualTo(parse("{\"projectId\":42}"));
    }

    /** Removed MCP credentials remain visible and must be deleted before committing a serverless version. */
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void candidateCanDropCredentialsForRemovedServers(boolean changedSchema) {
        var files = bundle("removed-server", Map.of("api", Map.of("type", "streamable-http", "url", "https://example.com/mcp")));
        var first = plugins.installBundle(files, "local");
        var before = plugins.configure(first.id(), new PluginService.Configure(parse("{}"), Map.of("api:header:X_KEY", "secret"), List.of(), true, 1));
        files.put("plugin.json", bytes(write(Map.of("$schema", PluginPackages.SCHEMA, "name", "removed-server", "version", "2.0.0"))));
        files.remove("mcp.json");
        if (changedSchema) files.put("dev.javaharness/config.schema.json", bytes("{\"type\":\"object\",\"properties\":{\"projectId\":{\"type\":\"integer\"}},\"required\":[\"projectId\"],\"additionalProperties\":false}"));
        var next = plugins.installBundle(files, "local");
        var draft = plugins.configuration(next.id());
        assertThat(draft.selected()).isFalse();
        assertThat(plugins.configuration(first.id()).binding()).isEqualTo(before);
        assertThat(draft.servers()).isEmpty(); assertThat(draft.binding().secretKeys()).containsExactly("api:header:X_KEY");
        var config = parse(changedSchema ? "{\"projectId\":42}" : "{}");
        assertThatThrownBy(() -> plugins.configure(next.id(), new PluginService.Configure(config, Map.of(), List.of("api:header:X_KEY"), true, before.revision()))).hasMessageContaining("凭据键");
        assertThat(plugins.configuration(first.id()).binding()).isEqualTo(before);
        var saved = plugins.configure(next.id(), new PluginService.Configure(config, Map.of(), List.of(), true, before.revision()));
        assertThat(saved.installationId()).isEqualTo(next.id()); assertThat(saved.secretKeys()).isEmpty();
    }

    /** Restart restores saved choices and never selects an older version after uninstall or unbinding. */
    @Test void restartDoesNotReactivateUnselectedVersions() {
        var files = bundle("restart", Map.of()); var first = plugins.installBundle(files, "local");
        files.put("plugin.json", bytes(write(Map.of("$schema", PluginPackages.SCHEMA, "name", "restart", "version", "1.0.1"))));
        var next = plugins.installBundle(files, "local");
        plugins.remove(next.id(), false, true);
        plugins = new RuntimeConfiguration().pluginService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), sessions, directory.resolve("plugins").toString(), directory.resolve("settings.key").toString());
        assertThat(plugins.bindings("user")).isEmpty();
        assertThat(plugins.installations()).extracting(PluginService.Installation::id).containsExactly(first.id());
        plugins.setEnabled(first.id(), true, 0);
        var selected = plugins.configuration(first.id()).binding();
        plugins.unbind("user", first.name(), selected.revision());
        plugins = new RuntimeConfiguration().pluginService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), sessions, directory.resolve("plugins").toString(), directory.resolve("settings.key").toString());
        assertThat(plugins.bindings("user")).isEmpty();
        plugins.setEnabled(first.id(), true, 0);
        selected = plugins.configuration(first.id()).binding();
        plugins.setEnabled(first.id(), false, selected.revision());
        var disabled = plugins.configuration(first.id()).binding();
        plugins = new RuntimeConfiguration().pluginService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), sessions, directory.resolve("plugins").toString(), directory.resolve("settings.key").toString());
        assertThat(plugins.configuration(first.id()).binding()).isEqualTo(disabled);
    }

    /** A deleted executable is visible on its parent and connector while healthy MCP and Skills still execute. */
    @Test void brokenConnectorIsIsolatedWithinTheSamePlugin() throws Exception {
        Path missing = directory.resolve("gone.exe");
        var servers = new LinkedHashMap<String, Object>();
        servers.put("broken", Map.of("type", "stdio", "command", missing.toString()));
        servers.put("healthy", Map.of("type", "stdio", "command", "java", "args", BuiltinMcpServer.launchArguments("text")));
        var files = bundle("mixed", servers);
        files.put("skills/review/SKILL.md", bytes("---\nname: review\ndescription: review\n---\nUse evidence."));
        files.put("dev.javaharness/commands/review.md", bytes("Review with the configured connections."));
        var item = plugins.installBundle(files, "local");
        assertThat(catalog.items()).filteredOn(value -> value.installationId().equals(item.id())).allMatch(value -> !value.issue().isEmpty());
        var session = sessions.createSession("mixed");
        assertThatThrownBy(() -> plugins.prepare(session, "/mixed:review check this")).hasMessageContaining("所选插件命令暂不可用", "可执行文件不存在");
        try (var extension = plugins.prepare(session, "ordinary question")) {
            var run = sessions.submit(session.id(), UUID.randomUUID().toString(), "question", "NORMAL", new ModelInfo("test", "test", true), ModelSelection.DEFAULT, new ModelSelection("default", ""), extension.snapshot()).run();
            extension.submitted(run.id()); sessions.transition(run.id(), RunStatus.RUNNING, "");
            var tools = extension.open(run.id(), WorkspaceTools.disabled(), () -> {});
            assertThat(tools.definitions()).anyMatch(tool -> tool.description().contains("healthy/analyze_text"));
            assertThat(tools.execute(new ToolCall("skill", "load_skill", "{\"id\":\"mixed:review\"}"), () -> {}).output()).contains("Use evidence");
            assertThat(extension.instructions()).contains("不可用", "broken");
            assertThat(plugins.snapshot(run.id()).path("loadIssues").get(0).path("server").asText()).isEqualTo("broken");
        }
        assertThat(plugins.detail(item.id()).activeRuns()).isZero();
        var diagnosis = write(plugins.diagnoseConfiguration(item.id()));
        assertThat(diagnosis).contains("analyze_text", "可执行文件不存在");
        assertThat(plugins.detail(item.id()).activeRuns()).isZero();
    }

    /** Protocol startup failures are isolated too; an unavailable server contributes no tools. */
    @Test void initializationFailureDoesNotPreventHealthyConnectionOrChangeSavedEnablement() throws Exception {
        var files = bundle("startup", Map.of(
                "broken", Map.of("type", "stdio", "command", "java", "args", List.of("-version")),
                "healthy", Map.of("type", "stdio", "command", "java", "args", BuiltinMcpServer.launchArguments("text"))));
        files.put("dev.javaharness/commands/review.md", bytes("Review with the configured connections."));
        var item = plugins.installBundle(files, "local");
        var binding = plugins.configuration(item.id()).binding(); plugins.setEnabled(item.id(), false, binding.revision());
        String result = write(plugins.diagnoseConfiguration(item.id()));
        assertThat(result).contains("analyze_text", "\"success\":false");
        assertThat(plugins.configuration(item.id()).binding().enabled()).isFalse();
        assertThat(plugins.detail(item.id()).activeRuns()).isZero();
        plugins.setEnabled(item.id(), true, binding.revision() + 1);
        var session = sessions.createSession("startup");
        try (var extension = plugins.prepare(session, "ordinary question")) {
            var run = sessions.submit(session.id(), UUID.randomUUID().toString(), "question", "NORMAL", new ModelInfo("test", "test", true), ModelSelection.DEFAULT, new ModelSelection("default", ""), extension.snapshot()).run();
            extension.submitted(run.id()); sessions.transition(run.id(), RunStatus.RUNNING, "");
            var tools = extension.open(run.id(), WorkspaceTools.disabled(), () -> {});
            assertThat(tools.definitions()).anyMatch(tool -> tool.description().contains("healthy/analyze_text"));
            assertThat(tools.definitions()).noneMatch(tool -> tool.description().contains("/broken/"));
            assertThat(plugins.snapshot(run.id()).path("loadIssues")).hasSize(1);
        }
        var commandSession = sessions.createSession("command");
        try (var extension = plugins.prepare(commandSession, "/startup:review")) {
            var run = sessions.submit(commandSession.id(), UUID.randomUUID().toString(), "question", "NORMAL", new ModelInfo("test", "test", true), ModelSelection.DEFAULT, new ModelSelection("default", ""), extension.snapshot()).run();
            extension.submitted(run.id()); sessions.transition(run.id(), RunStatus.RUNNING, "");
            assertThatThrownBy(() -> extension.open(run.id(), WorkspaceTools.disabled(), () -> {})).hasMessageContaining("所选插件命令暂不可用");
            assertThat(plugins.snapshot(run.id()).path("loadIssues")).hasSize(1);
        }
    }

    /** Configuration changes never publish credentials or mutate package identity; stale saves fail atomically. */
    @Test void packageCredentialsCanBeSetRotatedRemovedAndRecovered() {
        var item = plugins.installBundle(bundle("credentials", Map.of("api", Map.of("type", "streamable-http", "url", "https://example.com/mcp"))), "local");
        var first = plugins.configure(item.id(), new PluginService.Configure(parse("{}"), Map.of("api:header:X_KEY", "first-secret"), List.of(), true, 1));
        var second = plugins.configure(item.id(), new PluginService.Configure(parse("{}"), Map.of("api:header:X_KEY", "second-secret"), List.of(), true, first.revision()));
        assertThatThrownBy(() -> plugins.configure(item.id(), new PluginService.Configure(parse("{}"), Map.of(), List.of(), true, first.revision()))).hasMessageContaining("已更新");
        assertThat(write(plugins.configuration(item.id()))).doesNotContain("first-secret", "second-secret");
        assertThat(plugins.detail(item.id()).installation().digest()).isEqualTo(item.digest());
        jdbc.update("UPDATE harness_plugin_binding SET secrets='corrupt' WHERE name=?", item.name());
        assertThatThrownBy(() -> plugins.configure(item.id(), new PluginService.Configure(parse("{}"), Map.of(), List.of("api:header:X_KEY"), true, second.revision()))).hasMessageContaining("重新填写");
        var recovered = plugins.configure(item.id(), new PluginService.Configure(parse("{}"), Map.of("api:header:X_KEY", "replacement"), List.of(), true, second.revision()));
        var empty = plugins.configure(item.id(), new PluginService.Configure(parse("{}"), Map.of(), List.of(), true, recovered.revision()));
        assertThat(empty.secretKeys()).isEmpty();
    }

    /** Resolve a Windows PATH shim and complete a real MCP handshake through the batch launcher. */
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void windowsPathCmdStartsARealMcpServer() throws Exception {
        Path node = Arrays.stream(System.getenv("PATH").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(value -> !value.isBlank()).map(Path::of).map(path -> path.resolve("node.exe")).filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(node != null, "Node is not installed on this Windows host");
        Path bin = Files.createDirectory(directory.resolve("shim bin"));
        Files.writeString(bin.resolve("server.cjs"), """
                require('node:readline').createInterface({input:process.stdin}).on('line', line => {
                  const request=JSON.parse(line); if(request.id===undefined)return;
                  const result=request.method==='initialize'
                    ? {protocolVersion:request.params.protocolVersion,capabilities:{tools:{}},serverInfo:{name:'fixture',version:'1'}}
                    : {tools:[{name:'ping',inputSchema:{type:'object',properties:{},additionalProperties:false}}]};
                  process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:request.id,result})+'\\n');
                });
                """);
        Files.writeString(bin.resolve("fixture-mcp.cmd"), "@\"" + node + "\" \"%~dp0server.cjs\" %*\r\n");
        var definition = Map.<String, Object>of("type", "stdio", "command", "fixture-mcp", "env", Map.of("PATH", bin.toString()));
        var item = plugins.installBundle(bundle("shim", Map.of("main", definition)), "local");
        var launch = plugins.configuration(item.id()).binding().launches().getFirst();
        assertThat(launch.path("command").get(0).asText()).endsWith("cmd.exe");
        assertThat(write(launch.path("command"))).contains("fixture-mcp.cmd");
        assertThat(write(plugins.diagnoseConfiguration(item.id()))).contains("\"success\":true", "ping");
        assertThat(plugins.detail(item.id()).activeRuns()).isZero();
    }

    /** Build only immutable fixture files; no external service or real account is used. */
    private Map<String, byte[]> bundle(String name, Map<String, Object> servers) {
        var files = new HashMap<String, byte[]>();
        files.put("plugin.json", bytes(write(Map.of("$schema", PluginPackages.SCHEMA, "name", name, "version", "1.0.0"))));
        if (!servers.isEmpty()) files.put("mcp.json", bytes(write(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json", "mcpServers", servers))));
        return files;
    }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
