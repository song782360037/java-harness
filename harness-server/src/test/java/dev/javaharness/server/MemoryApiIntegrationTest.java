package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.memory.JdbcMemoryStore;
import dev.javaharness.adapters.settings.HarnessSettings;
import dev.javaharness.server.service.MemoryService;
import dev.javaharness.server.service.SettingsService;
import dev.javaharness.spi.session.SessionStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:memory-api;DB_CLOSE_DELAY=-1",
            "harness.automations.enabled=false",
            "harness.session-naming.enabled=false",
            "harness.settings.key-file=${java.io.tmpdir}/harness-memory-api.key"
        })
class MemoryApiIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    SettingsService models;

    @Autowired
    MemoryService automatic;

    @Autowired
    JdbcMemoryStore memories;

    @Autowired
    SessionStore sessions;

    private final HttpClient client = HttpClient.newHttpClient();

    HttpResponse<String> request(String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path));
        if (body != null)
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void createConfirmSearchAndDeleteThroughHttp() throws Exception {
        var created = request(
                "/memories",
                Map.of(
                        "workspaceId",
                        "",
                        "title",
                        "接口测试记忆",
                        "content",
                        "接口状态保存正确",
                        "kind",
                        "FACT",
                        "tags",
                        List.of("接口"),
                        "status",
                        "CANDIDATE"));
        assertThat(created.statusCode()).isEqualTo(201);
        var item = json.readTree(created.body());
        String id = item.path("id").asText();
        assertThat(request("/memories?query=接口测试记忆&status=ACTIVE", null).body()).contains("\"total\":0");
        ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("status", "ACTIVE");
        assertThat(request("/memories/" + id, item).statusCode()).isEqualTo(200);
        var found = request("/memories?query=接口测试记忆&status=ACTIVE", null);
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(json.readTree(found.body()).path("total").asInt()).isEqualTo(1);
        assertThat(request("/memories/" + id + "/delete", Map.of("revision", 1)).statusCode())
                .isEqualTo(409);
        assertThat(request("/memories/" + id + "/delete", Map.of("revision", 2)).statusCode())
                .isEqualTo(204);
        assertThat(request("/memories/" + id, null).statusCode()).isEqualTo(404);
    }

    @Test
    void malformedFieldsAndQueriesReturnClientErrors() throws Exception {
        assertThat(request(
                                "/memories",
                                Map.of(
                                        "workspaceId",
                                        "",
                                        "title",
                                        "无类型",
                                        "content",
                                        "正文",
                                        "tags",
                                        List.of(),
                                        "status",
                                        "ACTIVE"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(request("/memories?limit=201", null).statusCode()).isEqualTo(400);
        assertThat(request("/memories?status=UNKNOWN", null).statusCode()).isEqualTo(400);
        assertThat(request("/memories?workspaceId=missing", null).statusCode()).isEqualTo(404);
        assertThat(request("/memory-relations", Map.of("toId", "missing", "label", "相关"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(request("/memory-relations/delete", Map.of("fromId", "missing"))
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void settingsExposeSwitchProgressAndRejectUnconfiguredModelsAndStaleRevision() throws Exception {
        var initial = json.readTree(request("/memory-settings", null).body());
        assertThat(initial.path("settings").path("enabled").asBoolean()).isFalse();
        long revision = initial.path("settings").path("revision").asLong();
        var value = Map.of("enabled", false, "modelProfileId", "default", "revision", revision);
        assertThat(request("/memory-settings", value).statusCode()).isEqualTo(200);
        assertThat(request("/memory-settings", value).statusCode()).isEqualTo(409);
        assertThat(request(
                                "/memory-settings",
                                Map.of("enabled", true, "modelProfileId", "default", "revision", revision + 1))
                        .statusCode())
                .isEqualTo(400);
        assertThat(request("/memory-settings/retry", Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    void realHttpAdapterBackfillsAndRecallsWithoutManualConfirmation() throws Exception {
        var received = new CopyOnWriteArrayList<JsonNode>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                var input = json.readTree(exchange.getRequestBody());
                received.add(input);
                boolean extraction = input.path("messages").toString().contains("提取最多4条");
                String content = extraction
                        ? "[{\"title\":\"HTTP 自动记忆\",\"content\":\"PostgreSQL HTTP 适配器验证事实\",\"kind\":\"FACT\",\"tags\":[\"数据库\"]}]"
                        : "PostgreSQL 事实已验证。";
                String chunk = json.writeValueAsString(Map.of(
                        "choices",
                        List.of(Map.of("index", 0, "delta", Map.of("content", content), "finish_reason", "stop"))));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody()
                        .write(("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        upstream.start();
        try {
            automatic.configure(false, "default", memories.settings().revision());
            var profile = models.saveProfile(
                    null,
                    "自动记忆 HTTP 验收",
                    "",
                    new HarnessSettings(
                            0,
                            "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
                            "",
                            "memory-http",
                            false,
                            5,
                            15,
                            0,
                            0,
                            5,
                            8,
                            8000,
                            1000),
                    false);
            var source = sessions.createSession("HTTP 自动记忆来源");
            var submitted = request(
                    "/sessions/" + source.id() + "/runs",
                    Map.of(
                            "requestId",
                            UUID.randomUUID().toString(),
                            "prompt",
                            "PostgreSQL 的 HTTP 验证事实",
                            "scenario",
                            "NORMAL",
                            "modelProfileId",
                            profile.id(),
                            "reasoningEffort",
                            ""));
            assertThat(submitted.statusCode()).isEqualTo(202);
            String runId = json.readTree(submitted.body()).path("id").asText();
            await().atMost(Duration.ofSeconds(8))
                    .until(() -> !sessions.run(runId).status().active());
            assertThat(memories.count("", "ACTIVE", "HTTP 自动记忆")).isZero();
            assertThat(request(
                                    "/memory-settings",
                                    Map.of(
                                            "enabled",
                                            true,
                                            "modelProfileId",
                                            profile.id(),
                                            "revision",
                                            memories.settings().revision()))
                            .statusCode())
                    .isEqualTo(200);
            await().atMost(Duration.ofSeconds(10)).until(() -> memories.count("", "ACTIVE", "HTTP 自动记忆") == 1);
            var generated = memories.list("", "ACTIVE", "HTTP 自动记忆", 0, 10).getFirst();
            assertThat(generated.sourceRunId()).isEqualTo(runId);
            var extraction = received.stream()
                    .filter(value -> value.path("messages").toString().contains("提取最多4条"))
                    .findFirst()
                    .orElseThrow();
            assertThat(extraction.has("tools")).isFalse();
            assertThat(extraction.has("tool_choice")).isFalse();
            var followup = sessions.createSession("HTTP 召回验证");
            var next = request(
                    "/sessions/" + followup.id() + "/runs",
                    Map.of(
                            "requestId",
                            UUID.randomUUID().toString(),
                            "prompt",
                            "PostgreSQL HTTP 事实",
                            "scenario",
                            "NORMAL",
                            "modelProfileId",
                            profile.id(),
                            "reasoningEffort",
                            ""));
            String nextId = json.readTree(next.body()).path("id").asText();
            await().atMost(Duration.ofSeconds(8))
                    .until(() -> !sessions.run(nextId).status().active());
            assertThat(request("/runs/" + nextId + "/memories", null).body()).contains("HTTP 自动记忆");
        } finally {
            automatic.configure(false, "default", memories.settings().revision());
            upstream.stop(0);
        }
    }
}
