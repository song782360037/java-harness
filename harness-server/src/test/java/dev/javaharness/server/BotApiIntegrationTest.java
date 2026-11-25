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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
            "spring.datasource.url=jdbc:h2:mem:bot-api;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class BotApiIntegrationTest {
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

    record UpstreamRequest(String system, List<String> toolNames, String lastRole, String lastContent) {}

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
    private final List<UpstreamRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<UpstreamRequest, UpstreamReply> handler = req -> UpstreamReply.text("收到，马上处理。");
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private HttpServer upstream;

    @BeforeEach
    void setupUpstream() throws Exception {
        requests.clear();
        hold = new CountDownLatch(0);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                JsonNode body = json.readTree(exchange.getRequestBody());
                JsonNode messages = body.path("messages");
                String system = messages.path(0).path("content").asText("");
                JsonNode last = messages.path(messages.size() - 1);
                var toolNames = new ArrayList<String>();
                for (JsonNode t : body.path("tools")) {
                    toolNames.add(t.path("function").path("name").asText());
                }
                var req = new UpstreamRequest(
                        system,
                        List.copyOf(toolNames),
                        last.path("role").asText(""),
                        last.path("content").asText(""));
                requests.add(req);
                hold.await(15, TimeUnit.SECONDS);
                UpstreamReply reply = handler.apply(req);
                StringBuilder sse = new StringBuilder();
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
                    sse.append("data: ").append(chunk).append("\n\ndata: [DONE]\n\n");
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
                    sse.append("data: ").append(chunk).append("\n\ndata: [DONE]\n\n");
                }
                byte[] out = sse.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            } catch (Exception error) {
                exchange.sendResponseHeaders(500, -1);
            }
        });
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.start();
        configureDefaultModel("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");
    }

    @AfterEach
    void teardownUpstream() {
        hold.countDown();
        if (upstream != null) upstream.stop(0);
    }

    private void configureDefaultModel(String baseUrl) throws Exception {
        JsonNode settings = get("/api/settings");
        long revision = settings.path("revision").asLong();
        post(
                "/api/settings",
                Map.ofEntries(
                        Map.entry("revision", revision),
                        Map.entry("baseUrl", baseUrl),
                        Map.entry("modelName", "test-bot-model"),
                        Map.entry("apiKey", "sk-test-key"),
                        Map.entry("apiKeyRequired", true),
                        Map.entry("connectTimeoutSeconds", 5),
                        Map.entry("runTimeoutSeconds", 30),
                        Map.entry("contextWindowTokens", 64000),
                        Map.entry("maxOutputTokens", 2048),
                        Map.entry("toolTimeoutSeconds", 10),
                        Map.entry("maxToolsPerRequest", 16)));
    }

    @Test
    void mainBotExistsHiddenSessionsProtectedAndChatUsesOnlyCoordinationTools() throws Exception {
        JsonNode ws = get("/api/bots/workspace");
        assertThat(ws.path("bots").size()).isGreaterThanOrEqualTo(1);
        JsonNode mainCard = null;
        for (JsonNode card : ws.path("bots")) {
            if (card.path("bot").path("main").asBoolean()) mainCard = card;
        }
        assertThat(mainCard).isNotNull();
        String mainId = mainCard.path("bot").path("id").asText();
        String chatSessionId = mainCard.path("bot").path("chatSessionId").asText();

        // Main bot cannot be archived
        HttpResponse<String> archiveMain = postRaw("/api/bots/" + mainId + "/archive", Map.of());
        assertThat(archiveMain.statusCode()).isEqualTo(409);

        // Hidden bot session is excluded from ordinary sessions and rejects mutations with 409
        JsonNode sessions = get("/api/sessions");
        for (JsonNode s : sessions) {
            assertThat(s.path("id").asText()).isNotEqualTo(chatSessionId);
        }
        HttpResponse<String> renameHidden =
                postRaw("/api/sessions/" + chatSessionId + "/title", Map.of("title", "hack"));
        assertThat(renameHidden.statusCode()).isEqualTo(409);

        // Chat turn uses strictly BotChatTools (no delegate_task, no read_file, no propose_command)
        handler = req -> UpstreamReply.text("你好，我是主 Bot！");
        HttpResponse<String> msgRes = postRaw(
                "/api/bots/" + mainId + "/messages",
                Map.of("requestId", UUID.randomUUID().toString(), "content", "你好呀"));
        assertThat(msgRes.statusCode()).isEqualTo(202);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode thread = get("/api/bots/" + mainId + "/thread");
            assertThat(thread.path("entries").size()).isGreaterThanOrEqualTo(2);
            boolean hasAssistant = false;
            for (JsonNode entry : thread.path("entries")) {
                if ("ASSISTANT".equals(entry.path("kind").asText())
                        && entry.path("content").asText().contains("你好，我是主 Bot")) {
                    hasAssistant = true;
                }
            }
            assertThat(hasAssistant)
                    .as("entries=%s, requests=%d", thread.path("entries"), requests.size())
                    .isTrue();
        });

        assertThat(requests).isNotEmpty();
        List<String> chatToolNames = requests.getFirst().toolNames();
        assertThat(chatToolNames).contains("start_task", "delegate_to_bot", "create_bot", "save_playbook");
        assertThat(chatToolNames).doesNotContain("delegate_task", "propose_command", "library_write");
    }

    @Test
    void backgroundTaskWritesLibraryFileAndSilentDelegationSynthesizesBackToMainBot() throws Exception {
        // Create a specialist bot
        JsonNode workerCard = post(
                "/api/bots",
                Map.of(
                        "name",
                        "文档官" + UUID.randomUUID().toString().substring(0, 4),
                        "roleSummary",
                        "整理报告并写入资料库",
                        "systemPrompt",
                        "你是文档整理官",
                        "shape",
                        "leaf",
                        "color",
                        "#2f7d7a"));
        String workerId = workerCard.path("bot").path("id").asText();
        String workerName = workerCard.path("bot").path("name").asText();

        JsonNode ws = get("/api/bots/workspace");
        String mainId = "";
        for (JsonNode card : ws.path("bots")) {
            if (card.path("bot").path("main").asBoolean())
                mainId = card.path("bot").path("id").asText();
        }
        assertThat(mainId).isNotBlank();

        handler = req -> {
            if (req.system().contains("独立后台任务执行引擎")) {
                if ("user".equals(req.lastRole())) {
                    return UpstreamReply.tool(
                            "call_lib_1",
                            "library_write",
                            Map.of("path", "reports/weekly.md", "content", "# 本周汇报\n一切顺利"),
                            json);
                }
                return UpstreamReply.text("已将周报写入资料库 reports/weekly.md。");
            }
            if (req.lastContent().contains("请帮我委派写一份周报")) {
                return UpstreamReply.tool(
                        "call_del_1",
                        "delegate_to_bot",
                        Map.of("bot", workerName, "title", "整理本周汇报", "instruction", "把本周汇报写入 reports/weekly.md"),
                        json);
            }
            if ("tool".equals(req.lastRole()) && req.lastContent().contains("已将任务")) {
                return UpstreamReply.text("好的，已经悄悄派给 [" + workerName + "](bot:" + workerId + ") 在后台整理了。");
            }
            if (req.lastContent().contains("你委派给")) {
                return UpstreamReply.text("汇总完毕：[" + workerName + "](bot:" + workerId + ") 已完成周报并落盘到资料库。");
            }
            return UpstreamReply.text("收到。");
        };

        postRaw(
                "/api/bots/" + mainId + "/messages",
                Map.of("requestId", UUID.randomUUID().toString(), "content", "请帮我委派写一份周报"));

        String finalMainId = mainId;
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode mainThread = get("/api/bots/" + finalMainId + "/thread");
            boolean hasTaskResult = false;
            boolean hasSynthesis = false;
            for (JsonNode entry : mainThread.path("entries")) {
                if ("TASK_RESULT".equals(entry.path("kind").asText())
                        && entry.path("content").asText().contains("reports/weekly.md")) {
                    hasTaskResult = true;
                }
                if ("ASSISTANT".equals(entry.path("kind").asText())
                        && entry.path("content").asText().contains("汇总完毕")) {
                    hasSynthesis = true;
                }
            }
            assertThat(hasTaskResult).isTrue();
            assertThat(hasSynthesis).isTrue();
        });

        // Verify file physically written in bot-library and recorded under worker bot
        Path written = directory.resolve("bot-library").resolve("reports").resolve("weekly.md");
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readString(written, StandardCharsets.UTF_8)).contains("本周汇报");

        JsonNode workerThread = get("/api/bots/" + workerId + "/thread");
        // Silent delegation keeps worker's own chat entries clean
        assertThat(workerThread.path("entries").size()).isEqualTo(0);
        assertThat(workerThread.path("files").size()).isEqualTo(1);
        assertThat(workerThread.path("files").get(0).path("relativePath").asText())
                .isEqualTo("reports/weekly.md");

        // TaskActivityService includes BOT_TASK in recent snapshot
        JsonNode activity = get("/api/runs/activity");
        boolean foundBotTask = false;
        for (JsonNode item : activity.path("recent")) {
            if ("BOT_TASK".equals(item.path("source").asText())
                    && workerId.equals(item.path("botId").asText())) {
                foundBotTask = true;
            }
        }
        assertThat(foundBotTask).isTrue();
    }

    @Test
    void queuedMessagesMergeIntoNextTurnWhenBotIsReplying() throws Exception {
        JsonNode botCard = post(
                "/api/bots",
                Map.of(
                        "name",
                        "排队测试" + UUID.randomUUID().toString().substring(0, 4),
                        "roleSummary",
                        "测试消息排队",
                        "systemPrompt",
                        "测试用"));
        String botId = botCard.path("bot").path("id").asText();

        CountDownLatch firstGate = new CountDownLatch(1);
        hold = firstGate;
        handler = req -> {
            if (req.lastContent().contains("第二句") && req.lastContent().contains("第三句")) {
                return UpstreamReply.text("收到合并后的第二句和第三句！");
            }
            return UpstreamReply.text("收到第一句！");
        };

        HttpResponse<String> first = postRaw(
                "/api/bots/" + botId + "/messages",
                Map.of("requestId", UUID.randomUUID().toString(), "content", "第一句"));
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(json.readTree(first.body()).path("queued").asBoolean()).isFalse();

        // Send two more messages while first turn is held in flight
        HttpResponse<String> second = postRaw(
                "/api/bots/" + botId + "/messages",
                Map.of("requestId", UUID.randomUUID().toString(), "content", "第二句"));
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(json.readTree(second.body()).path("queued").asBoolean()).isTrue();

        HttpResponse<String> third = postRaw(
                "/api/bots/" + botId + "/messages",
                Map.of("requestId", UUID.randomUUID().toString(), "content", "第三句"));
        assertThat(third.statusCode()).isEqualTo(202);
        assertThat(json.readTree(third.body()).path("queued").asBoolean()).isTrue();

        firstGate.countDown();

        await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
            JsonNode thread = get("/api/bots/" + botId + "/thread");
            assertThat(thread.path("queued").size()).isEqualTo(0);
            boolean merged = false;
            for (JsonNode entry : thread.path("entries")) {
                if ("ASSISTANT".equals(entry.path("kind").asText())
                        && entry.path("content").asText().contains("收到合并后的第二句和第三句")) {
                    merged = true;
                }
            }
            assertThat(merged).isTrue();
        });
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpResponse<String> res = postRaw(path, body);
        assertThat(res.statusCode()).as("POST %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private HttpResponse<String> postRaw(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
