package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:bot-m3-api;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class BotMiniAppIntegrationTest {
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
                        Map.entry("modelName", "test-m3-model"),
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
    void miniAppLifecycleCreatesBridgedHtmlUpdatesDataWithoutVersionBumpAndDeletesCleanly() throws Exception {
        JsonNode card = post(
                "/api/bots",
                Map.of(
                        "name",
                        "报表官" + UUID.randomUUID().toString().substring(0, 4),
                        "roleSummary",
                        "生成交互小程序",
                        "systemPrompt",
                        "你是报表专家"));
        String botId = card.path("bot").path("id").asText();

        AtomicReference<String> createdAppId = new AtomicReference<>("");
        handler = req -> {
            if (req.system().contains("独立后台任务执行引擎")) {
                if ("user".equals(req.lastRole()) && req.lastContent().contains("收入看板")) {
                    return UpstreamReply.tool(
                            "call_app_1",
                            "save_app",
                            Map.of(
                                    "app_id",
                                    "income-board",
                                    "name",
                                    "收入汇总",
                                    "description",
                                    "本月收入与周趋势看板",
                                    "html",
                                    "<!doctype html><html><head><title>收入</title></head><body><h1 id=\"t\">收入</h1></body></html>",
                                    "data",
                                    Map.of("total", 12800, "currency", "CNY")),
                            json);
                }
                if ("user".equals(req.lastRole()) && req.lastContent().contains("改版升级")) {
                    return UpstreamReply.tool(
                            "call_app_2",
                            "save_app",
                            Map.of(
                                    "app_id",
                                    "income-board",
                                    "name",
                                    "收入汇总 Pro",
                                    "description",
                                    "升级版看板",
                                    "html",
                                    "<!doctype html><html><head></head><body><h1>Pro</h1></body></html>"),
                            json);
                }
                return UpstreamReply.text("已为你生成交互小程序：[收入汇总](app:income-board)");
            }
            return UpstreamReply.text("收到。");
        };

        // Trigger background task to create Mini App v1
        post("/api/bots/" + botId + "/tasks", Map.of("title", "做收入看板", "instruction", "生成收入看板小程序"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode thread = get("/api/bots/" + botId + "/thread");
            assertThat(thread.path("apps").size()).isEqualTo(1);
            assertThat(thread.path("apps").get(0).path("id").asText()).isEqualTo("income-board");
            assertThat(thread.path("apps").get(0).path("version").asInt()).isEqualTo(1);
            createdAppId.set(thread.path("apps").get(0).path("id").asText());
        });

        String appId = createdAppId.get();
        JsonNode bundle = get("/api/bot-apps/" + appId);
        assertThat(bundle.path("app").path("version").asInt()).isEqualTo(1);
        assertThat(bundle.path("data").path("total").asInt()).isEqualTo(12800);
        assertThat(bundle.path("html").asText()).contains("window.harnessBot");
        assertThat(bundle.path("html").asText()).contains("harness-mini-app");

        // Data-only update keeps version at v1
        JsonNode dataUpdated = post("/api/bot-apps/" + appId + "/data", Map.of("total", 25600, "currency", "CNY"));
        assertThat(dataUpdated.path("version").asInt()).isEqualTo(1);
        assertThat(get("/api/bot-apps/" + appId).path("data").path("total").asInt())
                .isEqualTo(25600);

        // HTML update bumps version to v2 while preserving data
        post("/api/bots/" + botId + "/tasks", Map.of("title", "升级看板", "instruction", "对收入汇总做改版升级"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode v2 = get("/api/bot-apps/" + appId);
            assertThat(v2.path("app").path("version").asInt()).isEqualTo(2);
            assertThat(v2.path("app").path("name").asText()).isEqualTo("收入汇总 Pro");
            assertThat(v2.path("data").path("total").asInt()).isEqualTo(25600);
        });

        // Delete removes DB record and disk directory
        post("/api/bot-apps/" + appId + "/delete", Map.of());
        assertThat(getRaw("/api/bot-apps/" + appId).statusCode()).isEqualTo(404);
        assertThat(Files.exists(directory.resolve("bot-library").resolve("apps").resolve(appId)))
                .isFalse();
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
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("POST %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }
}
