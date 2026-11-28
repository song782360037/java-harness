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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
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

/** 验证 Phase 2 后端能力：灵动岛 Debug 观测（Context/Trace/Changes）、多版本小程序预览/恢复/导出、正在查看上下文注入与安全原始文件预览。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:bot-phase2;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class BotEnhancementsPhase2Test {
    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("harness.settings.key-file", () -> directory.resolve("settings.key").toString());
        registry.add("harness.bots.library-directory", () -> directory.resolve("bot-library").toString());
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
    private final List<UpstreamReq> requests = new CopyOnWriteArrayList<>();
    private volatile Function<UpstreamReq, UpstreamReply> handler = req -> UpstreamReply.text("收到。");
    private HttpServer upstream;

    @BeforeEach
    void setupUpstream() throws Exception {
        requests.clear();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                JsonNode body = json.readTree(exchange.getRequestBody());
                JsonNode messages = body.path("messages");
                String system = messages.path(0).path("content").asText("");
                JsonNode last = messages.path(messages.size() - 1);
                var req = new UpstreamReq(system, last.path("role").asText(""), last.path("content").asText(""));
                requests.add(req);
                UpstreamReply reply = handler.apply(req);
                String chunk = !reply.toolCalls().isEmpty()
                        ? json.writeValueAsString(Map.of(
                                "choices",
                                List.of(Map.of(
                                        "index",
                                        0,
                                        "delta",
                                        Map.of("role", "assistant", "tool_calls", reply.toolCalls()),
                                        "finish_reason",
                                        "tool_calls"))))
                        : json.writeValueAsString(Map.of(
                                "choices",
                                List.of(Map.of(
                                        "index",
                                        0,
                                        "delta",
                                        Map.of("role", "assistant", "content", reply.text()),
                                        "finish_reason",
                                        "stop"))));
                byte[] out = ("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            } catch (Throwable error) {
                error.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        });
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.start();

        JsonNode settings = get("/api/settings");
        post("/api/settings", Map.ofEntries(
                Map.entry("revision", settings.path("revision").asLong()),
                Map.entry("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1"),
                Map.entry("apiKey", "test-key"),
                Map.entry("clearApiKey", false),
                Map.entry("modelName", "mock-p2"),
                Map.entry("apiKeyRequired", true),
                Map.entry("connectTimeoutSeconds", 10),
                Map.entry("runTimeoutSeconds", 30),
                Map.entry("contextWindowTokens", 64000),
                Map.entry("maxOutputTokens", 2048),
                Map.entry("toolTimeoutSeconds", 10),
                Map.entry("maxToolsPerRequest", 32)));
    }

    @AfterEach
    void tearDown() {
        if (upstream != null) upstream.stop(0);
    }

    @Test
    void miniAppVersionsRestoreExportAndViewingContextWorkEndToEnd() throws Exception {
        JsonNode card = post("/api/bots", Map.of(
                "name", "面板师" + UUID.randomUUID().toString().substring(0, 4),
                "roleSummary", "构建多版本交互看板",
                "systemPrompt", "负责生成和维护交互小程序"));
        String botId = card.path("bot").path("id").asText();

        handler = req -> {
            if (req.system().contains("独立后台任务执行引擎")) {
                if ("user".equals(req.lastRole()) && req.lastContent().contains("初版看板")) {
                    return UpstreamReply.tool(
                            "call_v1",
                            "save_app",
                            Map.of(
                                    "app_id", "kpi-board",
                                    "name", "KPI 看板",
                                    "description", "季度目标进度",
                                    "icon", "chart",
                                    "color", "#2d5d9c",
                                    "change", "初版柱状图",
                                    "html", "<!doctype html><html><head><title>V1</title></head><body><h1>KPI V1</h1></body></html>",
                                    "data", Map.of("q1", 88)),
                            json);
                }
                if ("user".equals(req.lastRole()) && req.lastContent().contains("加导出按钮")) {
                    return UpstreamReply.tool(
                            "call_v2",
                            "save_app",
                            Map.of(
                                    "app_id", "kpi-board",
                                    "name", "KPI 看板",
                                    "description", "季度目标进度",
                                    "icon", "chart",
                                    "color", "#2d5d9c",
                                    "change", "新增趋势折线",
                                    "html", "<!doctype html><html><head><title>V2</title></head><body><h1>KPI V2</h1></body></html>"),
                            json);
                }
                return UpstreamReply.text("已完成 [KPI 看板](app:kpi-board)");
            }
            return UpstreamReply.text("收到，正在查看你的 KPI 看板。");
        };

        // v1
        post("/api/bots/" + botId + "/tasks", Map.of("title", "做初版看板", "instruction", "生成初版看板"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode bundle = get("/api/bot-apps/kpi-board");
            assertThat(bundle.path("app").path("version").asInt()).isEqualTo(1);
            assertThat(bundle.path("app").path("icon").asText()).isEqualTo("chart");
            assertThat(bundle.path("html").asText()).contains("window.harnessBot").contains("reportError");
        });

        // v2
        post("/api/bots/" + botId + "/tasks", Map.of("title", "升级看板", "instruction", "加导出按钮并改版", "miniAppId", "kpi-board"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode bundle = get("/api/bot-apps/kpi-board");
            assertThat(bundle.path("app").path("version").asInt()).isEqualTo(2);
            assertThat(bundle.path("versions").size()).isEqualTo(2);
            assertThat(bundle.path("versions").get(1).path("changeSummary").asText()).isEqualTo("新增趋势折线");
        });

        // Preview v1 returns v1 HTML with current data
        JsonNode previewV1 = get("/api/bot-apps/kpi-board/versions/1");
        assertThat(previewV1.path("shownVersion").asInt()).isEqualTo(1);
        assertThat(previewV1.path("html").asText()).contains("KPI V1");
        assertThat(previewV1.path("data").path("q1").asInt()).isEqualTo(88);

        // Restore v1 creates v3 without losing v2
        JsonNode restored = post("/api/bot-apps/kpi-board/restore", Map.of("version", 1));
        assertThat(restored.path("version").asInt()).isEqualTo(3);
        JsonNode versions = get("/api/bot-apps/kpi-board/versions");
        assertThat(versions.size()).isEqualTo(3);
        assertThat(versions.get(2).path("changeSummary").asText()).contains("v1");
        assertThat(get("/api/bot-apps/kpi-board").path("html").asText()).contains("KPI V1");

        // Export standalone HTML contains data snapshot and attachment header
        HttpResponse<String> exported = getRaw("/api/bot-apps/kpi-board/export?version=2");
        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(exported.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        assertThat(exported.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(exported.body()).contains("KPI V2").contains("\"q1\":88");

        // Chat message with viewingMiniAppId injects context note to upstream prompt
        requests.clear();
        post("/api/bots/" + botId + "/messages", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "content", "把标题字体调大一点",
                "viewingMiniAppId", "kpi-board",
                "fromMiniApp", true));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(requests).isNotEmpty();
            assertThat(requests.getFirst().lastContent()).contains("KPI 看板").contains("kpi-board");
        });
    }

    @Test
    void debugEndpointsAndSafeRawLibraryFilesWork() throws Exception {
        JsonNode ws = get("/api/bots/workspace");
        String mainId = ws.path("bots").get(0).path("bot").path("id").asText();

        handler = req -> {
            if ("tool".equals(req.lastRole())) return UpstreamReply.text("记住了，已更新。");
            return UpstreamReply.tool(
                    "call_rem", "remember", Map.of("scope", "bot", "text", "每周五提醒团队复盘"), json);
        };

        post("/api/bots/" + mainId + "/messages", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "content", "记住每周五提醒团队复盘"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode changes = get("/api/bots/" + mainId + "/debug/changes");
            assertThat(changes.size()).isGreaterThanOrEqualTo(1);
            assertThat(changes.get(0).path("toolName").asText()).isEqualTo("remember");
        });

        JsonNode ctx = get("/api/bots/" + mainId + "/debug/context");
        assertThat(ctx.path("windowTokens").asInt()).isEqualTo(64000);
        assertThat(ctx.path("sections").size()).isGreaterThanOrEqualTo(4);
        assertThat(ctx.path("tools").size()).isGreaterThanOrEqualTo(10);
        assertThat(ctx.path("totalEstimatedTokens").asInt()).isGreaterThan(0);

        JsonNode traces = get("/api/bots/" + mainId + "/debug/traces");
        assertThat(traces.size()).isGreaterThanOrEqualTo(1);
        assertThat(traces.get(0).path("steps").size()).isGreaterThanOrEqualTo(1);

        // Raw file endpoint: safe PNG inline, HTML downgraded to octet-stream, traversal rejected
        Path lib = directory.resolve("bot-library");
        Files.createDirectories(lib.resolve("media"));
        Files.write(lib.resolve("media/sample.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        Files.writeString(lib.resolve("media/evil.html"), "<script>alert(1)</script>");

        HttpResponse<String> pngRes = getRaw("/api/bots/" + mainId + "/files/raw?path=media/sample.png");
        assertThat(pngRes.statusCode()).isEqualTo(200);
        assertThat(pngRes.headers().firstValue("Content-Type").orElse("")).startsWith("image/png");
        assertThat(pngRes.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");

        HttpResponse<String> htmlRes = getRaw("/api/bots/" + mainId + "/files/raw?path=media/evil.html");
        assertThat(htmlRes.statusCode()).isEqualTo(200);
        assertThat(htmlRes.headers().firstValue("Content-Type").orElse("")).contains("application/octet-stream");

        HttpResponse<String> traversalRes = getRaw("/api/bots/" + mainId + "/files/raw?path=../../settings.key");
        assertThat(traversalRes.statusCode()).isEqualTo(404);
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
