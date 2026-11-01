package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.automation.AutomationStore;
import dev.javaharness.server.service.AutomationService;
import dev.javaharness.server.service.SettingsService;
import dev.javaharness.server.service.UnattendedSubmissions;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:automation-api;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class AutomationApiIntegrationTest {
    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "harness.settings.key-file",
                () -> directory.resolve("settings.key").toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    SessionStore sessions;

    @Autowired
    AgentEngine engine;

    @Autowired
    SettingsService settings;

    @Autowired
    UnattendedSubmissions unattended;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private HttpServer upstream;
    private String workspaceId;

    @BeforeEach
    void model() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(json.readTree(exchange.getRequestBody()));
                hold.await(20, TimeUnit.SECONDS);
                String data = json.writeValueAsString(Map.of(
                        "choices",
                        List.of(Map.of("index", 0, "delta", Map.of("content", "已完成巡检。"), "finish_reason", "stop"))));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody()
                        .write(("data: " + data + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        upstream.start();
        var config = (ObjectNode) get("/settings");
        config.put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1")
                .put("modelName", "automation-model")
                .put("apiKeyRequired", false)
                .put("apiKey", "")
                .put("runTimeoutSeconds", 45);
        assertThat(post("/settings", config).statusCode()).isEqualTo(200);
        Path root = Files.createDirectories(directory.resolve("workspace-" + UUID.randomUUID()));
        workspaceId = sessions.saveWorkspace("", "巡检", List.of(root.toString()), "folder", "#336699", 0)
                .id();
    }

    @AfterEach
    void stop() {
        hold.countDown();
        upstream.stop(0);
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api" + path);
    }

    HttpResponse<String> post(String path, Object body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode get(String path) throws Exception {
        return json.readTree(
                client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .body());
    }

    Map<String, Object> draft(String kind, String time, String cron, boolean allowCommands) {
        var value = new HashMap<String, Object>();
        value.put("revision", 0);
        value.put("name", "每日巡检");
        value.put("prompt", "检查项目状态");
        value.put("workspaceId", workspaceId);
        value.put("modelProfileId", "default");
        value.put("reasoningEffort", null);
        value.put("scheduleKind", kind);
        value.put("scheduleTime", time);
        value.put("scheduleWeekday", 1);
        value.put("cron", cron);
        value.put("allowCommands", allowCommands);
        value.put("enabled", true);
        return value;
    }

    @Test
    void validatesSchedulesAndComputesNextRun() throws Exception {
        assertThat(post("/automations", draft("CUSTOM", "", "*/5 * * * *", false))
                        .statusCode())
                .isEqualTo(400);
        assertThat(post("/automations", draft("CUSTOM", "", "0 9 * *", false)).statusCode())
                .isEqualTo(400);
        // Spring 要求日和周同时满足，与经典 cron 的“任一满足”不同，因此拒绝同时指定。
        assertThat(post("/automations", draft("CUSTOM", "", "0 9 1 * 1", false)).statusCode())
                .isEqualTo(400);
        assertThat(post("/automations", draft("CUSTOM", "", "0 " + "1,".repeat(60) + "2 * * *", false))
                        .statusCode())
                .isEqualTo(400);
        assertThat(post("/automations", draft("DAILY", "25:00", "", false)).statusCode())
                .isEqualTo(400);
        assertThat(post("/automations", draft("YEARLY", "09:00", "", false)).statusCode())
                .isEqualTo(400);
        var missingWorkspace = draft("DAILY", "09:00", "", false);
        missingWorkspace.put("workspaceId", "missing");
        assertThat(post("/automations", missingWorkspace).statusCode()).isEqualTo(404);

        var weekly = json.readTree(
                post("/automations", draft("WEEKLY", "08:30", "", false)).body());
        assertThat(weekly.path("cron").asText()).isEqualTo("30 8 * * 1");
        assertThat(weekly.path("nextRunAt").isNull()).isFalse();
        var custom = json.readTree(post("/automations", draft("CUSTOM", "", " 15  9 * * 1-5 ", false))
                .body());
        assertThat(custom.path("cron").asText()).isEqualTo("15 9 * * 1-5");

        var manual = draft("MANUAL", "09:00", "", false);
        var created = json.readTree(post("/automations", manual).body());
        assertThat(created.path("cron").asText()).isEmpty();
        assertThat(created.path("nextRunAt").isNull()).isTrue();

        manual.put("revision", created.path("revision").asLong());
        manual.put("scheduleKind", "HOURLY");
        manual.put("scheduleTime", "00:15");
        var hourly = json.readTree(
                post("/automations/" + created.path("id").asText(), manual).body());
        assertThat(hourly.path("cron").asText()).isEqualTo("15 * * * *");
        assertThat(hourly.path("revision").asLong())
                .isEqualTo(created.path("revision").asLong() + 1);
        assertThat(post("/automations/" + created.path("id").asText(), manual).statusCode())
                .isEqualTo(409);

        manual.put("enabled", false);
        manual.put("revision", hourly.path("revision").asLong());
        var paused = json.readTree(
                post("/automations/" + created.path("id").asText(), manual).body());
        assertThat(paused.path("enabled").asBoolean()).isFalse();
        assertThat(paused.path("nextRunAt").isNull()).isTrue();
        assertThat(post(
                                "/automations/" + created.path("id").asText() + "/delete",
                                Map.of("revision", paused.path("revision").asLong()))
                        .statusCode())
                .isEqualTo(200);
        assertThat(post("/automations/" + created.path("id").asText() + "/run", Map.of())
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void runNowStartsSessionWithoutCommandToolUnlessAllowed() throws Exception {
        String id = json.readTree(post("/automations", draft("MANUAL", "09:00", "", false))
                        .body())
                .path("id")
                .asText();
        var execution =
                json.readTree(post("/automations/" + id + "/run", Map.of()).body());
        assertThat(execution.path("status").asText()).isEqualTo("STARTED");
        assertThat(execution.path("trigger").asText()).isEqualTo("MANUAL");
        String session = execution.path("sessionId").asText();
        String run = execution.path("runId").asText();
        assertThat(sessions.snapshot(session).session().workspaceId()).isEqualTo(workspaceId);
        assertThat(sessions.snapshot(session).session().title()).startsWith("每日巡检 · ");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(run).status().active());
        assertThat(sessions.run(run).status()).as(sessions.run(run).detail()).isEqualTo(RunStatus.COMPLETED);
        assertThat(requests.getLast().path("tools").toString()).doesNotContain("propose_command");
        assertThat(requests.getLast().path("messages").toString()).contains("无人值守");
        assertThat(get("/automations/" + id + "/runs").get(0).path("runStatus").asText())
                .isEqualTo("COMPLETED");

        // 同一会话里的手动追问恢复命令工具（仍需审批）。
        String followUp = json.readTree(post(
                                "/sessions/" + session + "/runs",
                                Map.of("requestId", UUID.randomUUID().toString(), "prompt", "继续"))
                        .body())
                .path("id")
                .asText();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(followUp).status().active());
        assertThat(requests.getLast().path("tools").toString()).contains("propose_command");

        String allowed = json.readTree(
                        post("/automations", draft("MANUAL", "09:00", "", true)).body())
                .path("id")
                .asText();
        String allowedRun = json.readTree(
                        post("/automations/" + allowed + "/run", Map.of()).body())
                .path("runId")
                .asText();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(allowedRun).status().active());
        assertThat(requests.getLast().path("tools").toString()).contains("propose_command");
    }

    @Test
    void triggeredRunUsesTheAutomationApprovalMode() throws Exception {
        var invalid = draft("MANUAL", "09:00", "", false);
        invalid.put("approvalMode", "ALWAYS");
        assertThat(post("/automations", invalid).statusCode()).isEqualTo(400);

        // 未提交审批模式的旧客户端保持手动审批。
        var legacy = json.readTree(post("/automations", draft("MANUAL", "09:00", "", false)).body());
        assertThat(legacy.path("approvalMode").asText()).isEqualTo("MANUAL");

        var smart = draft("MANUAL", "09:00", "", true);
        smart.put("approvalMode", "SMART");
        var created = json.readTree(post("/automations", smart).body());
        assertThat(created.path("approvalMode").asText()).isEqualTo("SMART");
        var execution = json.readTree(
                post("/automations/" + created.path("id").asText() + "/run", Map.of()).body());
        String run = execution.path("runId").asText();
        assertThat(jdbc.queryForObject("SELECT approval_mode FROM harness_run WHERE id=?", String.class, run))
                .isEqualTo("SMART");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(run).status().active());

        // 编辑后下一次触发使用新的模式。
        smart.put("revision", created.path("revision").asLong());
        smart.put("approvalMode", "FULL_ACCESS");
        var edited = json.readTree(
                post("/automations/" + created.path("id").asText(), smart).body());
        assertThat(edited.path("approvalMode").asText()).isEqualTo("FULL_ACCESS");
        String next = json.readTree(
                        post("/automations/" + created.path("id").asText() + "/run", Map.of()).body())
                .path("runId")
                .asText();
        assertThat(jdbc.queryForObject("SELECT approval_mode FROM harness_run WHERE id=?", String.class, next))
                .isEqualTo("FULL_ACCESS");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(next).status().active());
    }

    @Test
    void ticksTriggerOnScheduleCatchUpOnceAndSkipWhileRunning() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-10-30T10:30:00Z"));
        var service = new AutomationService(
                new AutomationStore(jdbc), sessions, engine, settings, unattended, clock, ZoneId.of("UTC"));
        var value = service.save(
                "",
                new AutomationService.Draft(
                        0, "整点巡检", "检查", workspaceId, "default", null, "HOURLY", "00:00", 0, "", false, null, true));
        assertThat(value.nextRunAt()).isEqualTo(Instant.parse("2026-10-30T11:00:00Z"));

        service.tick();
        assertThat(service.executions(value.id())).isEmpty();

        clock.now = Instant.parse("2026-10-30T11:00:30Z");
        service.tick();
        var first = service.executions(value.id());
        assertThat(first).singleElement().satisfies(execution -> {
            assertThat(execution.trigger()).isEqualTo("SCHEDULED");
            assertThat(execution.status()).isEqualTo("STARTED");
            assertThat(execution.scheduledFor()).isEqualTo(Instant.parse("2026-10-30T11:00:00Z"));
        });
        String run = first.getFirst().runId();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(run).status().active());

        // 服务停机错过 12–15 点：记录一次跳过，只补跑 15 点。
        clock.now = Instant.parse("2026-10-30T15:10:00Z");
        service.tick();
        var history = service.executions(value.id());
        assertThat(history).hasSize(3);
        assertThat(history).anySatisfy(execution -> {
            assertThat(execution.status()).isEqualTo("SKIPPED");
            assertThat(execution.detail()).contains("仅补跑最近一次");
        });
        assertThat(history).anySatisfy(execution -> {
            assertThat(execution.trigger()).isEqualTo("CATCH_UP");
            assertThat(execution.scheduledFor()).isEqualTo(Instant.parse("2026-10-30T15:00:00Z"));
        });
        assertThat(service.list().stream()
                        .filter(item -> item.id().equals(value.id()))
                        .findFirst()
                        .orElseThrow()
                        .nextRunAt())
                .isEqualTo(Instant.parse("2026-10-30T16:00:00Z"));
        String catchUp = history.stream()
                .filter(execution -> execution.trigger().equals("CATCH_UP"))
                .findFirst()
                .orElseThrow()
                .runId();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(catchUp).status().active());

        // 上一次运行未结束：定时触发跳过，立即运行返回冲突。
        hold = new CountDownLatch(1);
        clock.now = Instant.parse("2026-10-30T16:00:10Z");
        service.tick();
        String slow = service.executions(value.id()).getFirst().runId();
        clock.now = Instant.parse("2026-10-30T17:00:10Z");
        service.tick();
        assertThat(service.executions(value.id()).getFirst().detail()).contains("上一次运行仍在进行");
        assertThatThrownBy(() -> service.runNow(value.id())).hasMessageContaining("上一次运行仍在进行");
        hold.countDown();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(slow).status().active());

        // 超过 7 天的错过计划不再补跑。
        clock.now = Instant.parse("2026-11-08T17:30:00Z");
        int before = service.executions(value.id()).size();
        service.tick();
        var latest = service.executions(value.id());
        assertThat(latest).hasSize(before + 2);
        var lastCatchUp = latest.stream()
                .filter(execution -> execution.trigger().equals("CATCH_UP"))
                .findFirst()
                .orElseThrow();
        assertThat(lastCatchUp.scheduledFor()).isEqualTo(Instant.parse("2026-11-08T17:00:00Z"));
        assertThat(latest)
                .anySatisfy(execution -> assertThat(execution.status()).isEqualTo("SKIPPED"));
        String last = lastCatchUp.runId();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !sessions.run(last).status().active());
    }

    @Test
    void failedSubmissionIsRecordedWithoutLeavingAStartedTrigger() throws Exception {
        var value = draft("MANUAL", "09:00", "", false);
        value.put("workspaceId", "");
        String id = json.readTree(post("/automations", value).body()).path("id").asText();
        var config = (ObjectNode) get("/settings");
        config.put("modelName", "");
        assertThat(post("/settings", config).statusCode()).isEqualTo(200);
        var execution =
                json.readTree(post("/automations/" + id + "/run", Map.of()).body());
        assertThat(execution.path("status").asText()).isEqualTo("FAILED");
        assertThat(execution.path("detail").asText()).contains("模型未配置");
        // 模型未配置时不新建空会话。
        assertThat(execution.path("sessionId").isNull()).isTrue();
        int sessionCount = sessions.sessions().size();
        post("/automations/" + id + "/run", Map.of());
        assertThat(sessions.sessions()).hasSize(sessionCount);
    }

    static final class MutableClock extends Clock {
        volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
