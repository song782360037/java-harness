package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.server.service.BotRoutineScheduler;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Function;
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
            "spring.datasource.url=jdbc:h2:mem:bot-m2-api;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class BotRoutineAndSkillIntegrationTest {
    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "harness.settings.key-file",
                () -> directory.resolve("settings.key").toString());
        registry.add(
                "harness.bots.library-directory",
                () -> directory.resolve("bot-library").toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BotRoutineScheduler scheduler;

    record UpstreamReq(String system, String lastRole, String lastContent) {}

    record UpstreamReply(String text, List<Map<String, Object>> toolCalls) {
        static UpstreamReply text(String value) {
            return new UpstreamReply(value, List.of());
        }

        static UpstreamReply tool(String id, String name, Map<String, Object> args, ObjectMapper json) {
            try {
                return new UpstreamReply(
                        "",
                        List.of(Map.of(
                                "index",
                                0,
                                "id",
                                id,
                                "type",
                                "function",
                                "function",
                                Map.of("name", name, "arguments", json.writeValueAsString(args)))));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private volatile Function<UpstreamReq, UpstreamReply> handler = req -> UpstreamReply.text("完成。");
    private HttpServer upstream;

    @BeforeEach
    void setupUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                JsonNode body = json.readTree(exchange.getRequestBody());
                JsonNode messages = body.path("messages");
                String system = messages.path(0).path("content").asText("");
                JsonNode last = messages.path(messages.size() - 1);
                UpstreamReply reply = handler.apply(new UpstreamReq(
                        system,
                        last.path("role").asText(""),
                        last.path("content").asText("")));
                String sse;
                if (!reply.toolCalls().isEmpty()) {
                    String chunk = json.writeValueAsString(Map.of(
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "delta",
                                    Map.of("role", "assistant", "tool_calls", reply.toolCalls()),
                                    "finish_reason",
                                    "tool_calls"))));
                    sse = "data: " + chunk + "\n\ndata: [DONE]\n\n";
                } else {
                    String chunk = json.writeValueAsString(Map.of(
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "delta",
                                    Map.of("role", "assistant", "content", reply.text()),
                                    "finish_reason",
                                    "stop"))));
                    sse = "data: " + chunk + "\n\ndata: [DONE]\n\n";
                }
                byte[] out = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            }
        });
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.start();
        JsonNode settings = get("/api/settings");
        post(
                "/api/settings",
                Map.ofEntries(
                        Map.entry("revision", settings.path("revision").asLong()),
                        Map.entry(
                                "baseUrl",
                                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1"),
                        Map.entry("modelName", "test-m2-model"),
                        Map.entry("apiKey", "sk-test"),
                        Map.entry("apiKeyRequired", true),
                        Map.entry("connectTimeoutSeconds", 5),
                        Map.entry("runTimeoutSeconds", 30),
                        Map.entry("contextWindowTokens", 64000),
                        Map.entry("maxOutputTokens", 2048),
                        Map.entry("toolTimeoutSeconds", 10),
                        Map.entry("maxToolsPerRequest", 16)));
    }

    @AfterEach
    void teardown() {
        if (upstream != null) upstream.stop(0);
    }

    @Test
    void routinesCreateViaChatTickAndReportConclusionAndSuggestionsParseCleanly() throws Exception {
        JsonNode card = post(
                "/api/bots",
                Map.of(
                        "name",
                        "简报员" + UUID.randomUUID().toString().substring(0, 4),
                        "roleSummary",
                        "定时发简报",
                        "systemPrompt",
                        "你是简报员"));
        String botId = card.path("bot").path("id").asText();

        handler = req -> {
            if (req.system().contains("独立后台任务执行引擎")) {
                return UpstreamReply.text("今日晨报：系统一切正常，无阻塞项。");
            }
            if ("user".equals(req.lastRole()) && req.lastContent().contains("每天早上 9 点")) {
                return UpstreamReply.tool(
                        "call_r_1",
                        "create_routine",
                        Map.of(
                                "name",
                                "每日晨报",
                                "instruction",
                                "汇总今日工作状态",
                                "schedule_kind",
                                "DAILY",
                                "schedule_time",
                                "09:00"),
                        json);
            }
            return UpstreamReply.text("已经帮你设好了每日晨报！\n[建议: 立即试跑一次 | 查看所有例行任务]");
        };

        postRaw(
                "/api/bots/" + botId + "/messages",
                Map.of("requestId", UUID.randomUUID().toString(), "content", "每天早上 9 点帮我发一份晨报"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode thread = get("/api/bots/" + botId + "/thread");
            assertThat(thread.path("routines").size()).isEqualTo(1);
            JsonNode last = thread.path("entries").get(thread.path("entries").size() - 1);
            assertThat(last.path("kind").asText()).isEqualTo("ASSISTANT");
            assertThat(last.path("content").asText()).doesNotContain("[建议:");
            JsonNode suggestions = json.readTree(last.path("suggestionsJson").asText());
            assertThat(suggestions.size()).isEqualTo(2);
            assertThat(suggestions.get(0).asText()).isEqualTo("立即试跑一次");
        });

        String routineId =
                get("/api/bots/" + botId + "/routines").get(0).path("id").asText();

        // Force routine due 1 minute ago and invoke deterministic scheduler tick
        jdbc.update(
                "UPDATE harness_bot_routine SET next_run_at=? WHERE id=?",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                routineId);
        scheduler.tick();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode thread = get("/api/bots/" + botId + "/thread");
            boolean hasReport = false;
            for (JsonNode entry : thread.path("entries")) {
                if ("TASK_RESULT".equals(entry.path("kind").asText())
                        && entry.path("content").asText().contains("今日晨报")) {
                    hasReport = true;
                }
            }
            assertThat(hasReport).isTrue();
            JsonNode updatedRoutine = get("/api/bots/" + botId + "/routines").get(0);
            assertThat(updatedRoutine.path("lastStatus").asText()).isEqualTo("COMPLETED");
        });
    }

    @Test
    void skillImportToPlaybookAndLibraryFilePreviewWithTraversalGuard() throws Exception {
        JsonNode card = post(
                "/api/bots",
                Map.of(
                        "name",
                        "技能测试" + UUID.randomUUID().toString().substring(0, 4),
                        "roleSummary",
                        "测试技能导入",
                        "systemPrompt",
                        "测试"));
        String botId = card.path("bot").path("id").asText();

        JsonNode skills = get("/api/bots/skills");
        assertThat(skills.isArray()).isTrue();
        if (!skills.isEmpty()) {
            String firstSkillId = skills.get(0).path("id").asText();
            JsonNode imported = post("/api/bots/" + botId + "/playbooks/import-skill", Map.of("skillId", firstSkillId));
            assertThat(imported.path("sourceSkillId").asText()).isEqualTo(firstSkillId);
            assertThat(imported.path("steps").asText()).isNotBlank();
        }

        // Write a file under bot-library and verify preview endpoint + path traversal rejection
        Path note = directory.resolve("bot-library").resolve("notes").resolve("demo.md");
        Files.createDirectories(note.getParent());
        Files.writeString(note, "# Hello Library\nContent ok", StandardCharsets.UTF_8);

        JsonNode preview = get("/api/bots/" + botId + "/files/content?path=notes/demo.md");
        assertThat(preview.path("relativePath").asText()).isEqualTo("notes/demo.md");
        assertThat(preview.path("content").asText()).contains("Hello Library");

        HttpResponse<String> traversal = getRaw("/api/bots/" + botId + "/files/content?path=../settings.key");
        assertThat(traversal.statusCode()).isEqualTo(400);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = getRaw(path);
        assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private HttpResponse<String> getRaw(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpResponse<String> res = postRaw(path, body);
        assertThat(res.statusCode()).as("POST %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private HttpResponse<String> postRaw(String path, Object body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
