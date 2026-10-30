package dev.javaharness.server;

import static dev.javaharness.adapters.plugins.PluginJson.*;
import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.plugins.PluginPackages;
import dev.javaharness.adapters.plugins.PluginService;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.server.service.ExtensionCatalogService;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ExtensionCatalogTest {
    @TempDir
    Path directory;

    JdbcTemplate jdbc;
    JdbcSessionStore sessions;
    PluginService plugins;
    ExtensionCatalogService catalog;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
        // Keep this isolated database alive across the fixture's short-lived JDBC connections.
        ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx);
        plugins = new PluginService(
                jdbc,
                tx,
                sessions,
                new LocalSecretCipher(directory.resolve("settings.key")),
                directory.resolve("plugins"));
        catalog = new ExtensionCatalogService(plugins, sessions);
    }

    /** First-party entries install immutable commands and Skills with global permission grants. */
    @Test
    void catalogInstallsRealContentAndGrantsGlobalExecution() {
        assertThat(catalog.items()).hasSize(18);
        assertThat(catalog.items()).allMatch(ExtensionCatalogService.Item::official);
        var item = catalog.install("harness-java-review");
        assertThat(catalog.install("harness-java-review").id()).isEqualTo(item.id());
        assertThat(plugins.bindings("user")).singleElement().satisfies(binding -> {
            assertThat(binding.enabled()).isTrue();
            assertThat(binding.allowedTools()).containsExactly("*");
        });
        assertThat(plugins.detail(item.id()).commands())
                .extracting(PluginPackages.Component::id)
                .containsExactly("run");
        assertThat(catalog.preview("harness-java-review").content()).contains("调用方", "回归验证");
        assertThat(catalog.items())
                .filteredOn(value -> value.installationId().equals(item.id()))
                .hasSize(2);
        var binding = plugins.save(
                "user",
                item.name(),
                new PluginService.SaveBinding(item.id(), true, parse("{}"), Map.of(), false, List.of("*"), 1));
        assertThat(binding.enabled()).isTrue();
        assertThat(plugins.commands(sessions.createSession("catalog").id()))
                .extracting(PluginService.Command::id)
                .containsExactly("harness-java-review:run");
    }

    /** Importer labels alone cannot confer the official badge; damaged installs stay recoverable. */
    @Test
    void forgedSourceIsNotOfficialAndCorruptCatalogInstallShowsIssue() throws Exception {
        var files = Map.of(
                "plugin.json",
                write(Map.of("$schema", PluginPackages.SCHEMA, "name", "harness-java-review", "version", "2.0.0"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var forged = plugins.importBundle(files, "javaharness-official");
        assertThat(catalog.items())
                .filteredOn(value -> value.installationId().equals(forged.id()))
                .allMatch(value -> !value.official());
        var real = catalog.install("skill-java-review");
        Files.writeString(directory.resolve("plugins/packages/" + real.id() + "/skills/workflow/SKILL.md"), "tampered");
        assertThat(catalog.preview("skill-java-review").item().issue()).contains("改变");
        plugins.remove(real.id(), false, true);
        assertThat(catalog.preview("skill-java-review").item().installationId()).isEmpty();
        assertThat(catalog.install("skill-java-review").id()).isEqualTo(real.id());
        assertThat(plugins.skillContent(real.id(), "workflow")).contains("回归验证");
        assertThat(catalog.preview("skill-java-review").item().issue()).isEmpty();
        try (var paths = Files.list(directory.resolve("plugins/packages"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith("recovery-"))
                            .count())
                    .isZero();
        }
    }

    /** Independently authored Skills become available to the same real run-owned load_skill tool. */
    @Test
    void standaloneSkillLoadsAndDuplicateCreationPreservesBinding() throws Exception {
        var request =
                new ExtensionCatalogService.Skill("team-review", "团队审查", "团队规范", "1.0.0", "检查实际调用链与回归范围。", "user");
        var item = catalog.createSkill(request);
        var original = plugins.bindings("user").getFirst();
        assertThat(original.enabled()).isTrue();
        assertThatThrownBy(() -> catalog.createSkill(request))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("同名");
        assertThat(plugins.bindings("user").getFirst()).isEqualTo(original);
        plugins.save(
                "user",
                item.name(),
                new PluginService.SaveBinding(
                        item.id(), true, parse("{}"), Map.of(), false, List.of(), original.revision()));
        var session = sessions.createSession("skill");
        try (var extension = plugins.prepare(session, "review")) {
            var run = sessions.submit(
                            session.id(),
                            UUID.randomUUID().toString(),
                            "review",
                            "NORMAL",
                            new ModelInfo("test", "test", true),
                            ModelSelection.DEFAULT,
                            new ModelSelection("default", ""),
                            extension.snapshot())
                    .run();
            extension.submitted(run.id());
            sessions.transition(run.id(), RunStatus.RUNNING, "");
            var tools = extension.open(run.id(), WorkspaceTools.disabled(), () -> {});
            assertThat(tools.execute(
                                    new ToolCall(
                                            "skill", "load_skill", "{\"id\":\"custom-skill-team-review:team-review\"}"),
                                    () -> {})
                            .output())
                    .contains("实际调用链");
        }
    }

    /** Connector authoring separates encrypted secrets from portable package configuration and validates headers. */
    @Test
    void connectorSecretsAreEncryptedAndNeverPublished() throws Exception {
        var item = catalog.createConnector(new ExtensionCatalogService.Connector(
                "team-api",
                "团队服务",
                "HTTP 工具",
                "1.0.0",
                parse(
                        "{\"type\":\"streamable-http\",\"url\":\"https://example.com/mcp\",\"headers\":{\"X_API_KEY\":\"fixture-credential\"}}"),
                "user"));
        assertThat(write(item)).doesNotContain("fixture-credential");
        var binding = plugins.bindings("user").getFirst();
        assertThat(binding.enabled()).isTrue();
        assertThat(binding.secretKeys()).containsExactly("main:header:X_API_KEY");
        assertThat(write(binding)).doesNotContain("fixture-credential");
        assertThat(jdbc.queryForObject("SELECT secrets FROM harness_plugin_binding", String.class))
                .doesNotContain("fixture-credential");
        try (var paths = Files.walk(directory.resolve("plugins/packages/" + item.id()))) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                assertThat(Files.readString(path)).doesNotContain("fixture-credential");
        }
        assertThat(plugins.detail(item.id())
                        .configSchema()
                        .path("properties")
                        .path("url")
                        .path("default")
                        .asText())
                .isEqualTo("https://example.com/mcp");
        assertThatThrownBy(() -> catalog.createConnector(new ExtensionCatalogService.Connector(
                        "bad",
                        "bad",
                        "bad",
                        "1.0.0",
                        parse(
                                "{\"type\":\"streamable-http\",\"url\":\"https://example.com/mcp\",\"headers\":{\"Connection\":\"fixture-credential\"}}"),
                        "user")))
                .isInstanceOf(HarnessException.class)
                .hasMessageNotContaining("fixture-credential");
        assertThat(plugins.installations()).hasSize(1);
    }

    /** The built-in connector executes with installation-time authorization and retains an audit record. */
    @Test
    void firstPartyConnectorRunsThroughExistingMcpLifecycle() throws Exception {
        var item = catalog.install("connector-text");
        assertThat(plugins.bindings("user").getFirst().trusted()).isTrue();
        assertThat(write(plugins.diagnose("user", item.name()))).contains("analyze_text");
        var session = sessions.createSession("builtin");
        try (var extension = plugins.prepare(session, "text")) {
            var run = sessions.submit(
                            session.id(),
                            UUID.randomUUID().toString(),
                            "text",
                            "NORMAL",
                            new ModelInfo("test", "test", true),
                            ModelSelection.DEFAULT,
                            new ModelSelection("default", ""),
                            extension.snapshot())
                    .run();
            extension.submitted(run.id());
            sessions.transition(run.id(), RunStatus.RUNNING, "");
            var tools = extension.open(run.id(), WorkspaceTools.disabled(), () -> {});
            String tool = tools.definitions().stream()
                    .filter(value -> value.name().startsWith("p_"))
                    .findFirst()
                    .orElseThrow()
                    .name();
            var result = tools.execute(new ToolCall("text", tool, "{\"text\":\"hello\\n世界\"}"), () -> {});
            assertThat(result.success()).as(result.output()).isTrue();
            assertThat(result.output()).contains("sha256", "characters", "utf8Bytes");
            assertThat(plugins.approvals().list(run.id()).getFirst().status()).isEqualTo("COMPLETED");
        }
    }

    /** Package presentation types cannot hide executable components under the standalone Skill tab. */
    @Test
    void skillKindRejectsMcpAndInvalidNamesDoNotLeaveInstallations() {
        var files = new HashMap<String, byte[]>();
        files.put(
                "plugin.json",
                write(Map.of(
                                "$schema",
                                PluginPackages.SCHEMA,
                                "name",
                                "fake-skill",
                                "version",
                                "1.0.0",
                                "extensions",
                                Map.of("dev.javaharness", Map.of("schemaVersion", 1, "kind", "skill"))))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put(
                "mcp.json",
                "{\"$schema\":\"https://agent-plugins.org/schemas/1.0.0/mcp.schema.json\",\"mcpServers\":{\"main\":{\"type\":\"stdio\",\"command\":\"java\"}}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> plugins.importBundle(files, "custom"))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("类型");
        assertThatThrownBy(() -> catalog.createSkill(
                        new ExtensionCatalogService.Skill("a--b", "bad", "bad", "1.0.0", "body", "user")))
                .isInstanceOf(HarnessException.class);
        assertThat(plugins.installations()).isEmpty();
    }

    /** Immutable catalog bytes and installed badges must survive the JVM's randomized Map.of iteration order. */
    @Test
    void catalogPackageDigestsAreStableAcrossJvmRestarts() throws Exception {
        String expected = catalog.install("harness-java-review").digest() + ":"
                + catalog.install("connector-text").digest();
        for (int index = 0; index < 2; index++) {
            Path root = Files.createDirectory(directory.resolve("child-" + index)),
                    log = directory.resolve("child-" + index + ".log");
            var process = new ProcessBuilder(
                            Path.of(
                                            System.getProperty("java.home"),
                                            "bin",
                                            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                                    .toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            IdentityProbe.class.getName(),
                            root.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            try {
                assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as(Files.readString(log)).isZero();
                assertThat(Files.readAllLines(log).stream()
                                .filter(line -> line.startsWith("CATALOG:"))
                                .toList())
                        .containsExactly("CATALOG:" + expected);
            } finally {
                process.destroyForcibly();
            }
        }
    }

    /** Edited custom content keeps captured versions intact and rejects stale or read-only changes. */
    @Test
    void customEditPreservesOldSnapshotsAndGlobalDisable() throws Exception {
        var item = catalog.createSkill(
                new ExtensionCatalogService.Skill("editable", "原名称", "团队方法", "1.0.0", "原正文", "user"));
        var session = sessions.createSession("edit");
        try (var captured = plugins.prepare(session, "review")) {
            long revision = plugins.authoring(item.id()).revision();
            plugins.setEnabled(item.id(), false, revision);
            assertThatThrownBy(() -> catalog.update(item.id(), revision, parse("{}")))
                    .isInstanceOf(HarnessException.class);
            var form = plugins.authoring(item.id()).definition().deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) form)
                    .put("title", "新名称")
                    .put("version", "1.0.1")
                    .put("content", "新正文");
            var next = catalog.update(item.id(), revision + 1, form);
            assertThat(next.id()).isNotEqualTo(item.id());
            assertThat(plugins.installations())
                    .extracting(PluginService.Installation::id)
                    .containsExactly(next.id());
            assertThat(plugins.bindings("user").getFirst().enabled()).isFalse();
            var run = sessions.submit(
                            session.id(),
                            UUID.randomUUID().toString(),
                            "review",
                            "NORMAL",
                            new ModelInfo("test", "test", true),
                            ModelSelection.DEFAULT,
                            new ModelSelection("default", ""),
                            captured.snapshot())
                    .run();
            captured.submitted(run.id());
            sessions.transition(run.id(), RunStatus.RUNNING, "");
            var tools = captured.open(run.id(), WorkspaceTools.disabled(), () -> {});
            assertThat(tools.execute(
                                    new ToolCall("old", "load_skill", "{\"id\":\"custom-skill-editable:editable\"}"),
                                    () -> {})
                            .output())
                    .contains("原正文")
                    .doesNotContain("新正文");
            assertThatThrownBy(() -> catalog.update(item.id(), revision + 1, form))
                    .isInstanceOf(HarnessException.class);
        }
        var official = catalog.install("harness-java-review");
        assertThatThrownBy(() -> plugins.authoring(official.id()))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("只读");
    }

    /** Redacted credential placeholders retain original secrets without publishing them in authoring storage. */
    @Test
    void connectorEditingRetainsAndRemovesEncryptedCredentials() {
        var item = catalog.createConnector(new ExtensionCatalogService.Connector(
                "edit-api",
                "API",
                "服务",
                "1.0.0",
                parse(
                        "{\"type\":\"streamable-http\",\"url\":\"https://example.com/mcp\",\"headers\":{\"X_API_KEY\":\"private-value\"}}"),
                "user"));
        var original = plugins.authoring(item.id());
        assertThat(write(original)).doesNotContain("private-value");
        assertThat(original.definition()
                        .path("definition")
                        .path("headers")
                        .path("X_API_KEY")
                        .isNull())
                .isTrue();
        var form = original.definition().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) form).put("version", "1.0.1");
        String encrypted = jdbc.queryForObject(
                "SELECT secrets FROM harness_plugin_binding WHERE name=?", String.class, item.name());
        var next = catalog.update(item.id(), original.revision(), form);
        assertThat(plugins.bindings("user").getFirst().secretKeys()).containsExactly("main:header:X_API_KEY");
        assertThat(jdbc.queryForObject(
                        "SELECT secrets FROM harness_plugin_binding WHERE name=?", String.class, item.name()))
                .isNotEmpty();
        assertThat(encrypted).doesNotContain("private-value");
        var latest = plugins.authoring(next.id());
        var cleared = latest.definition().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) cleared).put("version", "1.0.2");
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        cleared.path("definition").path("headers"))
                .removeAll();
        catalog.update(next.id(), latest.revision(), cleared);
        assertThat(plugins.bindings("user").getFirst().secretKeys()).isEmpty();
        assertThat(jdbc.queryForList("SELECT definition FROM harness_extension_authoring", String.class))
                .noneMatch(value -> value.contains("private-value"));
    }

    /** The real V11-to-V12 migration archives overrides and deterministically retains one global configuration. */
    @Test
    void migrationRemovesWorkspaceDifferencesAndPreservesGlobalDisable() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).target("11").load().migrate();
        var db = new JdbcTemplate(ds);
        String id = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO harness_plugin_installation(id,name,version,source,digest,manifest,created_at) VALUES (?,'legacy','1.0.0','local','digest','{}',CURRENT_TIMESTAMP)",
                id);
        db.update(
                "INSERT INTO harness_plugin_binding VALUES ('user','legacy',?,FALSE,'{\"owner\":\"global\"}','encrypted-user','[]','',2)",
                id);
        db.update(
                "INSERT INTO harness_plugin_binding VALUES ('workspace:a','legacy',?,TRUE,'{}','encrypted-workspace','[]','',9)",
                id);
        db.update(
                "INSERT INTO harness_plugin_binding VALUES ('workspace:b','other',?,TRUE,'{\"owner\":\"recent\"}','encrypted-other','[]','',7)",
                id);
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM harness_plugin_binding_archive", Integer.class))
                .isEqualTo(3);
        assertThat(db.queryForList("SELECT DISTINCT scope FROM harness_plugin_binding", String.class))
                .containsExactly("user");
        var global = db.queryForMap("SELECT * FROM harness_plugin_binding WHERE name='legacy'");
        assertThat(global.get("ENABLED")).isEqualTo(false);
        assertThat(global.get("SECRETS")).hasToString("encrypted-user");
        assertThat(db.queryForObject(
                        "SELECT allowed_tools FROM harness_plugin_binding WHERE name='legacy'", String.class))
                .isEqualTo("[\"*\"]");
    }

    /** Global version switches work, and untrusted source labels never confer editing privileges. */
    @Test
    void installedVersionCanBeReenabledAndImportedContentStaysReadOnly() {
        var first =
                catalog.createSkill(new ExtensionCatalogService.Skill("versions", "版本", "说明", "1.0.0", "正文", "user"));
        var files = Map.of(
                "plugin.json",
                write(Map.of("$schema", PluginPackages.SCHEMA, "name", first.name(), "version", "2.0.0"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var second = plugins.activate(plugins.importBundle(files, "custom"));
        assertThat(plugins.bindings("user").getFirst().installationId()).isEqualTo(second.id());
        plugins.setEnabled(first.id(), true, 0);
        assertThat(plugins.bindings("user").getFirst().installationId()).isEqualTo(first.id());
        plugins.setEnabled(second.id(), true, 0);
        assertThatThrownBy(() -> plugins.authoring(second.id()))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("只读");
        var forged = plugins.activate(plugins.importBundle(
                Map.of(
                        "plugin.json",
                                write(Map.of(
                                                "$schema",
                                                PluginPackages.SCHEMA,
                                                "name",
                                                "custom-skill-forged",
                                                "version",
                                                "1.0.0",
                                                "extensions",
                                                Map.of("dev.javaharness", Map.of("schemaVersion", 1, "kind", "skill"))))
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "skills/forged/SKILL.md",
                                "---\nname: forged\ndescription: imported\n---\nImported instructions"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "custom"));
        assertThatThrownBy(() -> plugins.authoring(forged.id()))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("只读");
    }

    public static final class IdentityProbe {
        /** Build the same first-party catalog in an independent JVM using the parent's effective classpath. */
        public static void main(String[] args) {
            var fixture = new ExtensionCatalogTest();
            fixture.directory = Path.of(args[0]);
            fixture.setup();
            System.out.println(
                    "CATALOG:" + fixture.catalog.install("harness-java-review").digest() + ":"
                            + fixture.catalog.install("connector-text").digest());
        }
    }
}
