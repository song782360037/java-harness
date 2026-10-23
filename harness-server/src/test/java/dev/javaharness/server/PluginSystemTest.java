package dev.javaharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.*;
import dev.javaharness.adapters.plugins.*;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import static dev.javaharness.adapters.plugins.PluginJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class PluginSystemTest {
    @TempDir Path directory;
    JdbcTemplate jdbc; TransactionTemplate tx; JdbcSessionStore sessions; PluginService plugins;
    ExecutorService workers; HttpServer http;
    AtomicInteger calls = new AtomicInteger(), connections = new AtomicInteger(); AtomicBoolean changed = new AtomicBoolean(), slow = new AtomicBoolean(), slowInitialize = new AtomicBoolean();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<JsonNode> schemaOverride = new AtomicReference<>();
    AtomicBoolean failDiscovery = new AtomicBoolean(), slowDiscovery = new AtomicBoolean();
    AtomicInteger discoveries = new AtomicInteger();
    AtomicReference<String> customHeader = new AtomicReference<>();

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate(); jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds)); sessions = new JdbcSessionStore(jdbc, tx);
        plugins = new PluginService(jdbc, tx, sessions, new LocalSecretCipher(directory.resolve("settings.key")), directory.resolve("plugins"));
        workers = Executors.newVirtualThreadPerTaskExecutor();
    }
    @AfterEach void close() throws Exception { if (http != null) http.stop(0); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }

    Path pkg(String version, boolean mcp) throws Exception {
        Path path = Files.createDirectory(directory.resolve("source-" + UUID.randomUUID()));
        Files.createDirectories(path.resolve("skills/review/references")); Files.createDirectories(path.resolve("dev.javaharness/commands"));
        Files.writeString(path.resolve("plugin.json"), write(Map.of("$schema", PluginPackages.SCHEMA, "name", "java-review", "version", version,
                "extensions", Map.of("dev.javaharness", Map.of("schemaVersion", 1)))));
        Files.writeString(path.resolve("skills/review/SKILL.md"), "---\nname: review\ndescription: Java 审查\n---\n检查实际调用链，不绕过审核。\n");
        Files.writeString(path.resolve("skills/review/references/checklist.md"), "确认回归范围。");
        Files.writeString(path.resolve("dev.javaharness/commands/review.md"), "请按审查步骤处理：$ARGUMENTS");
        if (mcp) {
            startHttp(); Files.writeString(path.resolve("mcp.json"), write(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                    "mcpServers", Map.of("report", Map.of("type", "streamable-http", "url", "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp")))));
        }
        return path;
    }
    PluginService.BindingView enable(PluginService.Installation item, List<String> allowed, Map<String, String> secrets) {
        return plugins.save("user", item.name(), new PluginService.SaveBinding(item.id(), true, parse("{}"), secrets, false, allowed, 0));
    }
    PluginService.BindingView trust(PluginService.BindingView binding) { return plugins.trust(binding.scope(), binding.name(), binding.revision(), binding.reviewDigest()); }
    Session session() { return sessions.createSession("插件测试"); }
    Run submit(Session session, RunExtension extension) {
        var result = sessions.submit(session.id(), UUID.randomUUID().toString(), "test", "NORMAL", new ModelInfo("test", "test", true), ModelSelection.DEFAULT, new ModelSelection("default", ""), extension.snapshot());
        extension.submitted(result.run().id()); return sessions.transition(result.run().id(), RunStatus.RUNNING, "");
    }
    ToolCall call(WorkspaceTools tools) { return new ToolCall(UUID.randomUUID().toString(), tools.definitions().stream().filter(value -> value.name().startsWith("p_")).findFirst().orElseThrow().name(), "{\"input\":\"hello\"}"); }
    ModelProvider.Cancellation cancellation() { return () -> { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }; }

    @Test void pureSkillAndCommandUseImmutableBytesAndPersistedSelection() throws Exception {
        Path path = pkg("1.0.0", false); var item = plugins.importDirectory(path.toString(), "local"); enable(item, List.of(), Map.of());
        Session session = session();
        try (var extension = plugins.prepare(session, "/java-review:review 检查本轮")) {
            Run run = submit(session, extension);
            Files.writeString(path.resolve("skills/review/SKILL.md"), "changed source");
            WorkspaceTools tools = extension.open(run.id(), WorkspaceTools.disabled(), cancellation());
            assertThat(extension.instructions()).contains("检查本轮", "java-review:review");
            assertThat(tools.execute(new ToolCall("skill", "load_skill", "{\"id\":\"java-review:review\"}"), cancellation()).output()).contains("检查实际调用链");
            assertThat(tools.execute(new ToolCall("resource", "read_plugin_resource", "{\"id\":\"java-review:skills/review/references/checklist.md\"}"), cancellation()).output()).contains("确认回归范围");
            assertThat(tools.execute(new ToolCall("escape", "read_plugin_resource", "{\"id\":\"java-review:../settings.key\"}"), cancellation()).success()).isFalse();
            assertThat(plugins.snapshot(run.id()).path("plugins").get(0).path("digest").asText()).isEqualTo(item.digest());
            assertThat(plugins.commands(session.id())).extracting(PluginService.Command::id).containsExactly("java-review:review");
        }
    }

    @Test void legacyWorkspaceDisableDoesNotOverrideGlobalSelection() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", false).toString(), "local"); enable(item, List.of(), Map.of());
        var workspace = sessions.saveWorkspace("", "test", List.of(directory.toString()), "folder", "#123456", 0);
        Session session = sessions.createSessionInWorkspace("scope", workspace.id()); String scope = "workspace:" + workspace.id();
        var disabled = plugins.save(scope, item.name(), new PluginService.SaveBinding(item.id(), false, parse("{}"), Map.of(), false, List.of(), 0));
        try (var extension = plugins.prepare(session, "test")) { assertThat(extension.snapshot()).contains("java-review"); }
        plugins.unbind(scope, item.name(), disabled.revision());
        try (var extension = plugins.prepare(session, "test")) { assertThat(extension.snapshot()).contains("java-review"); }
    }

    @Test void versionConflictAndInstallTamperingAreRejected() throws Exception {
        Path path = pkg("1.0.0", false); var item = plugins.importDirectory(path.toString(), "local"); enable(item, List.of(), Map.of());
        assertThat(plugins.importDirectory(path.toString(), "local").id()).isEqualTo(item.id());
        Files.writeString(path.resolve("skills/review/references/checklist.md"), "different");
        assertThatThrownBy(() -> plugins.importDirectory(path.toString(), "local")).isInstanceOf(HarnessException.class).hasMessageContaining("版本");
        try (var files = Files.list(directory.resolve("plugins/packages"))) { assertThat(files.count()).isEqualTo(1); }
        Files.writeString(directory.resolve("plugins/packages").resolve(item.id()).resolve("skills/review/SKILL.md"), "tampered");
        assertThatThrownBy(() -> plugins.prepare(session(), "test")).isInstanceOf(HarnessException.class).hasMessageContaining("改变");
    }

    /** Promoting a workspace binding preserves the selected plugin's persistent data directory. */
    @Test void globalMigrationKeepsLegacyPluginData() throws Exception {
        Path path = pkg("1.0.0", false);
        Files.writeString(path.resolve("mcp.json"), write(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json", "mcpServers", Map.of("main", Map.of("type", "stdio", "command", "java", "cwd", "${PLUGIN_DATA}")))));
        var item = plugins.importDirectory(path.toString(), "local");
        var workspace = sessions.saveWorkspace("", "legacy", List.of(directory.toString()), "folder", "#123456", 0);
        String scope = "workspace:" + workspace.id();
        var old = plugins.save(scope, item.name(), new PluginService.SaveBinding(item.id(), false, parse("{}"), Map.of(), false, List.of(), 0));
        Path state = Path.of(old.launches().getFirst().path("cwd").asText()); Files.createDirectories(state); Files.writeString(state.resolve("marker"), "persisted");
        jdbc.update("INSERT INTO harness_plugin_binding_archive SELECT * FROM harness_plugin_binding WHERE scope=?", scope);
        plugins.activate(item);
        Path migrated = Path.of(plugins.bindings("user").getFirst().launches().getFirst().path("cwd").asText());
        assertThat(migrated).isEqualTo(state); assertThat(Files.readString(migrated.resolve("marker"))).isEqualTo("persisted");
    }

    @Test void zipTraversalCaseAliasesAndUnsupportedSchemaAreRejected() throws Exception {
        var manager = new PluginPackages(directory.resolve("zip"));
        for (String entry : List.of("../escape", "C:/escape", "CON.txt", "foo. ", "a\\b")) {
            var output = new ByteArrayOutputStream(); try (var zip = new ZipOutputStream(output)) { zip.putNextEntry(new ZipEntry(entry)); zip.write(1); zip.closeEntry(); }
            assertThatThrownBy(() -> manager.zip(new ByteArrayInputStream(output.toByteArray()))).isInstanceOf(HarnessException.class);
        }
        assertThatThrownBy(() -> PluginJson.schema(parse("{\"type\":\"string\",\"pattern\":\"x\"}"))).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> PluginJson.validate(parse("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"), parse("{\"extra\":1}"))).isInstanceOf(HarnessException.class);
        var bomb = new ByteArrayOutputStream(); try (var zip = new ZipOutputStream(bomb)) { zip.putNextEntry(new ZipEntry("directory/")); zip.write(new byte[100000]); zip.closeEntry(); }
        assertThatThrownBy(() -> manager.zip(new ByteArrayInputStream(bomb.toByteArray()))).isInstanceOf(HarnessException.class).hasMessageContaining("目录条目");
    }

    @Test void untrustedStartupMakesNoNetworkRequestAndSecretsNeverEnterViewsOrSnapshots() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local");
        var binding = enable(item, List.of(), Map.of("report:header:Authorization", "Bearer fixture-secret"));
        assertThat(write(binding)).doesNotContain("fixture-secret");
        assertThat(jdbc.queryForObject("SELECT secrets FROM harness_plugin_binding", String.class)).doesNotContain("fixture-secret");
        Session session = session();
        try (var extension = plugins.prepare(session, "test")) {
            Run run = submit(session, extension);
            assertThatThrownBy(() -> extension.open(run.id(), WorkspaceTools.disabled(), cancellation())).isInstanceOf(HarnessException.class).hasMessageContaining("信任");
            assertThat(connections.get()).isZero(); assertThat(extension.snapshot()).doesNotContain("fixture-secret");
        }
        trust(binding); assertThat(plugins.diagnose("user", item.name())).containsKey("report");
        assertThat(authorization.get()).isEqualTo("Bearer fixture-secret");
        assertThat(plugins.bindings("user").getFirst().trusted()).isTrue();
    }

    @Test void enginePreservesActionableStartupFailureInRunDetail() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local"); enable(item, List.of(), Map.of());
        var modelRequests = new AtomicInteger();
        ModelProvider provider = new ModelProvider() {
            public ModelInfo info() { return new ModelInfo("fixture", "fixture", true); }
            public void stream(ModelRequest request, Cancellation cancellation, java.util.function.Consumer<String> text) { modelRequests.incrementAndGet(); }
        };
        var config = new RunConfiguration(provider.info(), Duration.ofSeconds(10), 16000, 32,
                workspace -> new RunConfiguration.Resources(provider, WorkspaceTools.disabled(), () -> {}));
        RunConfigurationSource source = new RunConfigurationSource() {
            public RunConfiguration snapshot() { return config; }
            public RunExtension extensions(Session session, String prompt) { return plugins.prepare(session, prompt); }
        };
        try (var engine = new DefaultAgentEngine(sessions, source)) {
            var run = engine.submit(session().id(), UUID.randomUUID().toString(), "test", "NORMAL").run();
            await().atMost(Duration.ofSeconds(5)).until(() -> !sessions.run(run.id()).status().active());
            assertThat(sessions.run(run.id()).status()).isEqualTo(RunStatus.FAILED);
            assertThat(sessions.run(run.id()).detail()).contains("插件设置", "信任当前启动配置", item.name());
            assertThat(connections.get()).isZero(); assertThat(modelRequests.get()).isZero();
        }
    }

    @Test void rejectionPreventsDispatchAndApprovalExecutesExactlyOnce() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local"); trust(enable(item, List.of(), Map.of()));
        Session session = session();
        try (var extension = plugins.prepare(session, "test")) {
            Run run = submit(session, extension); WorkspaceTools tools = extension.open(run.id(), WorkspaceTools.disabled(), cancellation());
            ToolCall rejected = call(tools);
            var first = workers.submit(() -> tools.execute(rejected, cancellation()));
            await().atMost(Duration.ofSeconds(5)).until(() -> plugins.approvals().list(run.id()).size() == 1);
            var decision = plugins.approvals().list(run.id()).getFirst();
            assertThat(calls.get()).isZero(); plugins.approvals().decide(decision.id(), false);
            assertThat(first.get(5, TimeUnit.SECONDS).success()).isFalse(); assertThat(calls.get()).isZero();
            var second = workers.submit(() -> tools.execute(call(tools), cancellation()));
            await().atMost(Duration.ofSeconds(5)).until(() -> plugins.approvals().list(run.id()).size() == 2);
            var approved = plugins.approvals().list(run.id()).get(1); plugins.approvals().decide(approved.id(), true);
            assertThat(second.get(5, TimeUnit.SECONDS).output()).contains("fixture-result"); assertThat(calls.get()).isEqualTo(1);
            assertThat(plugins.snapshot(run.id()).path("tools").get(0).path("tool").asText()).isEqualTo("report");
        }
    }

    @Test void preauthorizationDoesNotTrustServerHintsAndDirectoryDriftPreventsCall() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local"); trust(enable(item, List.of("report/report"), Map.of()));
        Session session = session();
        try (var extension = plugins.prepare(session, "test")) {
            Run run = submit(session, extension); var tools = extension.open(run.id(), WorkspaceTools.disabled(), cancellation());
            changed.set(true);
            var result = tools.execute(call(tools), cancellation());
            assertThat(result.success()).isFalse(); assertThat(result.output()).contains("目录已变化", "新任务"); assertThat(calls.get()).isZero();
            assertThat(plugins.approvals().list(run.id()).getFirst().status()).isEqualTo("FAILED");
            assertThat(plugins.approvals().list(run.id()).getFirst().detail()).isEqualTo(result.output());
        }
    }

    @Test void directoryIsRecheckedAfterUserApprovalAndDiscoveryFailureIsNotUnknown() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local"); trust(enable(item, List.of(), Map.of()));
        Session session = session();
        try (var extension = plugins.prepare(session, "test")) {
            Run run = submit(session, extension); var tools = extension.open(run.id(), WorkspaceTools.disabled(), cancellation());
            var pending = workers.submit(() -> tools.execute(call(tools), cancellation()));
            await().atMost(Duration.ofSeconds(5)).until(() -> plugins.approvals().list(run.id()).size() == 1);
            changed.set(true); plugins.approvals().decide(plugins.approvals().list(run.id()).getFirst().id(), true);
            assertThat(pending.get(5, TimeUnit.SECONDS).output()).contains("目录已变化");
            assertThat(plugins.approvals().list(run.id()).getFirst().status()).isEqualTo("FAILED");
            changed.set(false); failDiscovery.set(true);
            var disconnected = workers.submit(() -> tools.execute(call(tools), cancellation()));
            await().atMost(Duration.ofSeconds(5)).until(() -> plugins.approvals().list(run.id()).size() == 2);
            plugins.approvals().decide(plugins.approvals().list(run.id()).get(1).id(), true);
            assertThat(disconnected.get(5, TimeUnit.SECONDS).success()).isFalse();
            assertThat(plugins.approvals().list(run.id())).extracting(ExternalToolApprovals.Approval::status).containsOnly("FAILED");
            assertThat(calls.get()).isZero();
        }
    }

    @Test void cancellationDuringDiscoveryRecordsUnsentCallAsCancelled() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local"); trust(enable(item, List.of("report/report"), Map.of()));
        Session session = session();
        try (var extension = plugins.prepare(session, "test")) {
            Run run = submit(session, extension); var tools = extension.open(run.id(), WorkspaceTools.disabled(), cancellation());
            slowDiscovery.set(true);
            var future = workers.submit(() -> tools.execute(call(tools), cancellation()));
            await().atMost(Duration.ofSeconds(5)).until(() -> discoveries.get() >= 2);
            assertThat(plugins.approvals().list(run.id()).getFirst().status()).isEqualTo("APPROVED");
            future.cancel(true); extension.close();
            await().atMost(Duration.ofSeconds(5)).until(() -> plugins.approvals().list(run.id()).getFirst().status().equals("CANCELLED"));
            assertThat(calls.get()).isZero();
        }
    }

    @Test void brokenExecutableRemainsManageableAndCanBeDisabledWithoutResolvingLaunch() throws Exception {
        Path path = pkg("1.0.0", false), executable = directory.resolve("fixture.exe"); Files.writeString(executable, "not executed");
        Files.writeString(path.resolve("mcp.json"), write(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json", "mcpServers", Map.of("report", Map.of("type", "stdio", "command", executable.toString())))));
        var item = plugins.importDirectory(path.toString(), "local"); var binding = trust(enable(item, List.of(), Map.of()));
        Files.delete(executable);
        var broken = plugins.bindings("user").getFirst();
        assertThat(broken.trusted()).isFalse(); assertThat(broken.launches()).isEmpty(); assertThat(broken.launchError()).contains("可执行文件不存在");
        assertThat(plugins.installations()).hasSize(1); assertThat(plugins.detail(item.id()).installation().id()).isEqualTo(item.id());
        assertThatThrownBy(() -> plugins.prepare(session(), "test")).isInstanceOf(HarnessException.class);
        var disabled = plugins.save("user", item.name(), new PluginService.SaveBinding(item.id(), false, parse("{}"), Map.of(), false, List.of(), binding.revision()));
        assertThat(disabled.enabled()).isFalse(); assertThat(disabled.launchError()).contains("可执行文件不存在");
        try (var extension = plugins.prepare(session(), "test")) { assertThat(extension.snapshot()).isEqualTo("{}"); }
        plugins.unbind("user", item.name(), disabled.revision()); assertThat(plugins.bindings("user")).isEmpty();
        plugins.remove(item.id(), false, true); assertThat(plugins.installations()).isEmpty();
    }

    @Test void commandsDirectoryOverrideAcceptsBothTrailingSlashForms() throws Exception {
        var manager = new PluginPackages(directory.resolve("command-packages"));
        for (String suffix : List.of("", "/")) {
            Path path = pkg("1.0.0", false);
            Files.writeString(path.resolve("plugin.json"), write(Map.of("$schema", PluginPackages.SCHEMA, "name", "java-review", "version", "1.0.0",
                    "extensions", Map.of("dev.javaharness", Map.of("schemaVersion", 1, "commands", "./dev.javaharness/commands" + suffix, "configSchema", "./dev.javaharness/settings.json")))));
            Files.writeString(path.resolve("dev.javaharness/settings.json"), "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\"}}}");
            var imported = manager.directory(path.toString());
            assertThat(imported.commands()).containsKey("review"); assertThat(imported.configSchema().path("properties").has("mode")).isTrue();
        }
    }

    @Test void headerTokensAreAcceptedAndRestrictedOrInvalidHeadersFailBeforeConnection() throws Exception {
        Path path = pkg("1.0.0", true);
        var mcp = (com.fasterxml.jackson.databind.node.ObjectNode) parse(Files.readString(path.resolve("mcp.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) mcp.path("mcpServers").path("report")).set("headers", JSON.valueToTree(Map.of("X_API_KEY", "literal")));
        Files.writeString(path.resolve("mcp.json"), write(mcp));
        var item = plugins.importDirectory(path.toString(), "local"); var binding = trust(enable(item, List.of(), Map.of("report:header:X.API+KEY", "fixture-secret")));
        plugins.diagnose("user", item.name()); assertThat(customHeader.get()).isEqualTo("literal");
        assertThat(write(plugins.bindings("user"))).doesNotContain("fixture-secret");
        int established = connections.get();
        for (String name : List.of("Connection", "Content-Length", "Expect", "Upgrade", "Host", "Accept", "Mcp-Session-Id")) {
            assertThatThrownBy(() -> plugins.save("user", item.name(), new PluginService.SaveBinding(item.id(), true, parse("{}"), Map.of("report:header:" + name, "fixture"), false, List.of(), binding.revision())))
                    .isInstanceOf(HarnessException.class).hasMessageContaining("受限 Header");
        }
        for (var entry : Map.of("Bad Header", "fixture", "X-Valid", "fixture\r\nInjected: secret").entrySet()) {
            assertThatThrownBy(() -> plugins.save("user", item.name(), new PluginService.SaveBinding(item.id(), true, parse("{}"), Map.of("report:header:" + entry.getKey(), entry.getValue()), false, List.of(), binding.revision())))
                    .isInstanceOf(HarnessException.class).hasMessage("HTTP Header 名称或值不合法");
        }
        assertThat(connections.get()).isEqualTo(established); assertThat(plugins.bindings("user").getFirst().revision()).isEqualTo(binding.revision());
        var changedSecret = plugins.save("user", item.name(), new PluginService.SaveBinding(item.id(), true, parse("{}"), Map.of("report:header:X.API+KEY", "changed"), false, List.of(), binding.revision()));
        assertThat(changedSecret.trusted()).isFalse(); assertThat(changedSecret.reviewDigest()).isNotEqualTo(binding.reviewDigest());
    }

    @Test void ordinaryDisableKeepsOldLeaseAndImmediateRevocationStopsWaitingCall() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local"); trust(enable(item, List.of(), Map.of()));
        Session session = session();
        try (var extension = plugins.prepare(session, "test")) {
            Run run = submit(session, extension); var tools = extension.open(run.id(), WorkspaceTools.disabled(), cancellation());
            plugins.remove(item.id(), false, false);
            try (var next = plugins.prepare(session, "next")) { assertThat(next.snapshot()).isEqualTo("{}"); }
            var future = workers.submit(() -> tools.execute(call(tools), cancellation()));
            await().atMost(Duration.ofSeconds(5)).until(() -> !plugins.approvals().list(run.id()).isEmpty());
            assertThat(plugins.remove(item.id(), true, false)).contains(run.id());
            assertThat(future.get(5, TimeUnit.SECONDS).success()).isFalse(); assertThat(calls.get()).isZero();
            assertThatThrownBy(() -> plugins.approvals().decide(plugins.approvals().list(run.id()).getFirst().id(), true)).isInstanceOf(HarnessException.class);
        }
    }

    @Test void crashRecoveryMarksUnconfirmedDispatchUnknownAndNeverReplays() throws Exception {
        Session session = session(); Run run = sessions.submit(session.id(), UUID.randomUUID().toString(), "test", "NORMAL", new ModelInfo("test", "test", true)).run();
        var item = plugins.importDirectory(pkg("1.0.0", false).toString(), "local");
        jdbc.update("INSERT INTO harness_external_approval(id,run_id,call_id,installation_id,plugin_name,plugin_version,server_name,tool_name,arguments,identity_digest,status,detail,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), run.id(), "crash", item.id(), item.name(), item.version(), "report", "report", "{}", "a", "RUNNING", "");
        var restored = new ExternalToolApprovals(jdbc, tx, sessions);
        assertThat(restored.list(run.id()).getFirst().status()).isEqualTo("UNKNOWN"); assertThat(calls.get()).isZero();
    }

    @Test void engineCountsPluginInstructionsAndPreservesIdempotencyAfterDisable() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", false).toString(), "local"); enable(item, List.of(), Map.of()); Session session = session();
        var captured = new AtomicReference<ModelRequest>();
        ModelProvider provider = new ModelProvider() {
            public ModelInfo info() { return new ModelInfo("fixture", "fixture", true); }
            public void stream(ModelRequest request, Cancellation cancellation, java.util.function.Consumer<String> text) { captured.set(request); text.accept("ok"); }
        };
        RunConfiguration configuration = new RunConfiguration(provider.info(), Duration.ofSeconds(30), 16000, 1024, 32, TokenCounter.approximate(), workspace -> new RunConfiguration.Resources(provider, WorkspaceTools.disabled(), () -> {}));
        RunConfigurationSource source = new RunConfigurationSource() {
            public RunConfiguration snapshot() { return configuration; }
            public RunExtension extensions(Session session, String prompt) { return plugins.prepare(session, prompt); }
        };
        try (var engine = new DefaultAgentEngine(sessions, source)) {
            String requestId = UUID.randomUUID().toString(); Run run = engine.submit(session.id(), requestId, "/java-review:review 代码", "NORMAL").run();
            await().atMost(Duration.ofSeconds(10)).until(() -> !sessions.run(run.id()).status().active());
            assertThat(sessions.run(run.id()).status()).as(sessions.run(run.id()).detail()).isEqualTo(RunStatus.COMPLETED);
            assertThat(captured.get().instructions()).contains("Java 审查", "审查步骤处理：代码");
            assertThat(captured.get().context().tokens()).isPositive();
            plugins.remove(item.id(), false, true);
            assertThat(plugins.bindings("user")).isEmpty();
            assertThat(engine.submit(session.id(), requestId, "/java-review:review 代码", "NORMAL").created()).isFalse();
        }
    }

    @Test void plainSlashInputIsNotParsedAsPluginCommand() throws Exception {
        try (var empty = plugins.prepare(session(), "/api/runs 这个接口如何实现")) { assertThat(empty.snapshot()).isEqualTo("{}"); }
        var item = plugins.importDirectory(pkg("1.0.0", false).toString(), "local"); enable(item, List.of(), Map.of());
        try (var extension = plugins.prepare(session(), "/api/runs 这个接口如何实现")) { assertThat(extension.instructions()).contains("Java 审查"); }
        assertThatThrownBy(() -> plugins.prepare(session(), "/java-review:missing code")).isInstanceOf(HarnessException.class);
    }

    @Test void commandInstructionsSurviveForcedFinalAnswerWithoutTools() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", false).toString(), "local"); enable(item, List.of(), Map.of());
        var requests = new CopyOnWriteArrayList<ModelRequest>();
        ModelProvider provider = new ModelProvider() {
            public ModelInfo info() { return new ModelInfo("fixture", "fixture", true); }
            public void stream(ModelRequest request, Cancellation cancellation, java.util.function.Consumer<String> text) { throw new UnsupportedOperationException(); }
            public ModelTurn complete(ModelRequest request, Cancellation cancellation, java.util.function.Consumer<String> text) throws Exception {
                requests.add(request);
                if (request.finalAnswerOnly()) { text.accept("收尾完成"); return new ModelTurn("收尾完成", List.of()); }
                Thread.sleep(5000);
                return new ModelTurn("", List.of(new ToolCall("late-skill", "load_skill", "{\"id\":\"java-review:review\"}")));
            }
        };
        RunConfiguration configuration = new RunConfiguration(provider.info(), Duration.ofSeconds(6), 16000, 1024, 32,
                TokenCounter.approximate(), workspace -> new RunConfiguration.Resources(provider, WorkspaceTools.disabled(), () -> {}));
        RunConfigurationSource source = new RunConfigurationSource() {
            public RunConfiguration snapshot() { return configuration; }
            public RunExtension extensions(Session session, String prompt) { return plugins.prepare(session, prompt); }
        };
        try (var engine = new DefaultAgentEngine(sessions, source)) {
            Run run = engine.submit(session().id(), UUID.randomUUID().toString(), "/java-review:review 独有收尾检查项", "NORMAL").run();
            await().atMost(Duration.ofSeconds(10)).until(() -> !sessions.run(run.id()).status().active());
            assertThat(sessions.run(run.id()).status()).as(sessions.run(run.id()).detail()).isEqualTo(RunStatus.COMPLETED);
            assertThat(requests).hasSize(2);
            assertThat(requests.getLast().finalAnswerOnly()).isTrue(); assertThat(requests.getLast().tools()).isEmpty();
            assertThat(requests.getLast().instructions()).contains("审查步骤处理：独有收尾检查项");
            assertThat(sessions.tools(run.id())).extracting(ToolExecution::status).containsExactly("SKIPPED");
        }
    }

    @Test void rawTopLevelEnumSurvivesSdkConversionAndIsEnforced() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local"); trust(enable(item, List.of("report/report"), Map.of()));
        schemaOverride.set(parse("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"],\"additionalProperties\":false,\"enum\":[{\"input\":\"safe\"}]}"));
        Session session = session();
        try (var extension = plugins.prepare(session, "test")) {
            Run run = submit(session, extension); var tools = extension.open(run.id(), WorkspaceTools.disabled(), cancellation());
            ToolCall unsafe = call(tools);
            assertThat(tools.definitions().stream().filter(value -> value.name().equals(unsafe.name())).findFirst().orElseThrow().parameters()).containsKey("enum");
            assertThat(tools.execute(unsafe, cancellation()).success()).isFalse(); assertThat(calls.get()).isZero();
            assertThat(tools.execute(new ToolCall("safe", unsafe.name(), "{\"input\":\"safe\"}"), cancellation()).success()).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            ((com.fasterxml.jackson.databind.node.ObjectNode) schemaOverride.get()).set("enum", parse("[{\"input\":\"hello\"}]"));
            assertThat(tools.execute(new ToolCall("drift", unsafe.name(), "{\"input\":\"safe\"}"), cancellation()).success()).isFalse();
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test void startupFingerprintIsStableAcrossIndependentJvms() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var outputs = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var process = new ProcessBuilder(java, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), getClass().getName()).redirectErrorStream(true).start();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(process.exitValue()).as(output).isZero(); outputs.add(output);
        }
        assertThat(new HashSet<>(outputs)).hasSize(1);
    }

    @Test void inheritedEnvironmentChangesKeepTrustButExplicitChangesAndExecutableChangesDoNot() throws Exception {
        Path root = Files.createDirectory(directory.resolve("trust-jvms"));
        String baseline = trustInChild(root, "java", "first");
        assertThat(baseline).startsWith("TRUST:true:");
        assertThat(trustInChild(root, "java", "second")).isEqualTo(baseline);
        assertThat(trustInChild(root, "change", "second")).startsWith("TRUST:false:").isNotEqualTo(baseline);
        Path runnerRoot = Files.createDirectory(directory.resolve("runner-jvms"));
        assertThat(trustInChild(runnerRoot, "runner", "first")).startsWith("TRUST:true:");
        assertThat(trustInChild(runnerRoot, "runner", "second")).startsWith("TRUST:false:");
    }

    /** Exercise persisted trust through real JVM environment changes without launching the declared MCP. */
    private String trustInChild(Path root, String mode, String environment) throws Exception {
        Path inherited = Files.createDirectories(root.resolve(environment));
        Files.writeString(inherited.resolve(System.getProperty("os.name").startsWith("Windows") ? "review-runner.exe" : "review-runner"), "never executed");
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(java, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                getClass().getName(), root.toString(), mode).redirectErrorStream(true);
        builder.environment().put("PATH", inherited + File.pathSeparator + System.getenv().getOrDefault("PATH", ""));
        builder.environment().put("TEMP", inherited.toString()); builder.environment().put("TMP", inherited.toString());
        var process = builder.start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        return output.lines().filter(line -> line.startsWith("TRUST:")).findFirst().orElseThrow(() -> new AssertionError(output));
    }

    /** Child entry point for persistent startup identity checks or the canonical JSON fingerprint probe. */
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            Path root = Path.of(args[0]);
            var ds = new DriverManagerDataSource("jdbc:h2:file:" + root.resolve("trust-db").toString().replace('\\', '/') + ";DB_CLOSE_DELAY=-1;WRITE_DELAY=0", "sa", "");
            Flyway.configure().dataSource(ds).load().migrate(); var jdbc = new JdbcTemplate(ds);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds)); var sessions = new JdbcSessionStore(jdbc, tx);
            var plugins = new PluginService(jdbc, tx, sessions, new LocalSecretCipher(root.resolve("key")), root.resolve("plugins"));
            if (plugins.installations().isEmpty()) {
                Path source = Files.createDirectory(root.resolve("source")); Files.createDirectories(source.resolve("dev.javaharness"));
                Files.writeString(source.resolve("plugin.json"), write(Map.of("$schema", PluginPackages.SCHEMA, "name", "trust-test", "version", "1.0.0")));
                Files.writeString(source.resolve("dev.javaharness/config.schema.json"), "{\"type\":\"object\",\"properties\":{\"flag\":{\"type\":\"string\",\"default\":\"one\"}},\"additionalProperties\":false}");
                Files.writeString(source.resolve("mcp.json"), write(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json", "mcpServers", Map.of("report",
                        Map.of("type", "stdio", "command", args[1].equals("runner") ? "review-runner" : "java", "env", Map.of("DECLARED", "${config.flag}"))))));
                var item = plugins.importDirectory(source.toString(), "local");
                var binding = plugins.save("user", item.name(), new PluginService.SaveBinding(item.id(), true, parse("{}"), Map.of("report:env:API_KEY", "fixture"), false, List.of(), 0));
                plugins.trust("user", item.name(), binding.revision(), binding.reviewDigest());
            }
            var binding = plugins.bindings("user").getFirst();
            if (args[1].equals("change")) binding = plugins.save("user", binding.name(), new PluginService.SaveBinding(binding.installationId(), true, parse("{\"flag\":\"two\"}"), Map.of(), false, List.of(), binding.revision()));
            System.out.println("TRUST:" + binding.trusted() + ":" + binding.reviewDigest()); jdbc.execute("SHUTDOWN"); return;
        }
        System.out.println(fingerprint(List.of(Map.copyOf(Map.of("z", "1", "a", "2", "j", "3")), parse("{\"b\":{\"d\":1,\"c\":2},\"a\":0}"))));
    }

    @Test void realStdioProcessIsCancelledAndReaped() throws Exception {
        Path path = pkg("1.0.0", false);
        try (var fixture = getClass().getResourceAsStream("/plugins/stdio-server.java")) { Files.copy(Objects.requireNonNull(fixture), path.resolve("server.java")); }
        Files.writeString(path.resolve("mcp.json"), write(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json", "mcpServers", Map.of("report", Map.of("type", "stdio", "command", "java", "args", List.of("--source", "21", "${PLUGIN_ROOT}/server.java"))))));
        var item = plugins.importDirectory(path.toString(), "local"); trust(enable(item, List.of("report/report"), Map.of())); Session session = session();
        try (var extension = plugins.prepare(session, "test")) {
            Run run = submit(session, extension); WorkspaceTools tools = extension.open(run.id(), WorkspaceTools.disabled(), cancellation());
            Path pidFile;
            try (var files = Files.walk(directory.resolve("plugins/state"))) { pidFile = files.filter(file -> file.getFileName().toString().equals("pid.txt")).findFirst().orElseThrow(); }
            long pid = Long.parseLong(Files.readString(pidFile));
            var started = new CountDownLatch(1);
            var future = workers.submit(() -> { started.countDown(); return tools.execute(call(tools), cancellation()); }); started.await();
            await().atMost(Duration.ofSeconds(5)).until(() -> plugins.approvals().list(run.id()).stream().anyMatch(value -> value.status().equals("RUNNING")));
            future.cancel(true); extension.close();
            await().atMost(Duration.ofSeconds(5)).until(() -> ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive());
            await().atMost(Duration.ofSeconds(5)).until(() -> plugins.approvals().list(run.id()).getFirst().status().equals("UNKNOWN"));
        }
    }

    @Test void revocationCancelsDiagnosisWhileInitializationIsPending() throws Exception {
        var item = plugins.importDirectory(pkg("1.0.0", true).toString(), "local"); trust(enable(item, List.of(), Map.of()));
        slowInitialize.set(true);
        var probe = workers.submit(() -> plugins.diagnose("user", item.name()));
        await().atMost(Duration.ofSeconds(5)).until(() -> connections.get() > 0);
        plugins.remove(item.id(), true, false);
        assertThatThrownBy(() -> probe.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(plugins.detail(item.id()).activeRuns()).isZero();
        assertThatThrownBy(() -> plugins.diagnose("user", item.name())).isInstanceOf(HarnessException.class);
    }

    @Test void upgradeChangesOnlyNewRunsAndInvalidatesStartupTrust() throws Exception {
        Path oldPath = pkg("1.0.0", true);
        var old = plugins.importDirectory(oldPath.toString(), "local"); var binding = trust(enable(old, List.of(), Map.of()));
        Session session = session();
        try (var lease = plugins.prepare(session, "test")) {
            Path nextPath = pkg("2.0.0", false);
            Files.copy(oldPath.resolve("mcp.json"), nextPath.resolve("mcp.json"));
            Files.writeString(nextPath.resolve("skills/review/SKILL.md"), "---\nname: review\ndescription: 新版审查\n---\n新版内容\n");
            var next = plugins.importDirectory(nextPath.toString(), "local");
            var saved = plugins.save("user", next.name(), new PluginService.SaveBinding(next.id(), true, parse("{}"), Map.of(), false, List.of(), binding.revision()));
            assertThat(saved.trusted()).isFalse();
            Run run = submit(session, lease); var tools = lease.open(run.id(), WorkspaceTools.disabled(), cancellation());
            assertThat(tools.execute(new ToolCall("old-skill", "load_skill", "{\"id\":\"java-review:review\"}"), cancellation()).output()).contains("检查实际调用链").doesNotContain("新版内容");
            try (var upcoming = plugins.prepare(session, "test")) { assertThat(upcoming.snapshot()).contains("2.0.0"); assertThat(upcoming.instructions()).contains("新版审查"); }
        }
    }

    void startHttp() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); http.setExecutor(workers);
        http.createContext("/mcp", exchange -> {
            try (exchange) {
                connections.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                customHeader.set(exchange.getRequestHeaders().getFirst("X_API_KEY"));
                JsonNode request = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (request.path("method").asText().equals("tools/list")) {
                    discoveries.incrementAndGet();
                    if (slowDiscovery.get()) Thread.sleep(30000);
                    if (failDiscovery.get()) { exchange.sendResponseHeaders(503, -1); return; }
                }
                if (slowInitialize.get() && request.path("method").asText().equals("initialize")) Thread.sleep(30000);
                if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); return; }
                Object result = switch (request.path("method").asText()) {
                    case "initialize" -> Map.of("protocolVersion", request.path("params").path("protocolVersion").asText(), "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "fixture", "version", "1"));
                    case "tools/list" -> Map.of("tools", List.of(Map.of("name", "report", "description", changed.get() ? "changed" : "fixture", "inputSchema", schemaOverride.get() != null ? schemaOverride.get() : Map.of("type", "object", "properties", Map.of("input", Map.of("type", "string")), "required", List.of("input"), "additionalProperties", false))));
                    case "tools/call" -> { calls.incrementAndGet(); if (slow.get()) Thread.sleep(30000); yield Map.of("content", List.of(Map.of("type", "text", "text", "fixture-result")), "isError", false); }
                    default -> Map.of();
                };
                byte[] response = write(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Exception ignored) { /* A cancellation closes the fixture exchange. */ }
        }); http.start();
    }
}
