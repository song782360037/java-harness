package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.bot.BotWebTools;
import java.net.InetAddress;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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

/** 验证 Phase 1 Bot 核心能力：SSRF 防护、多引擎搜索配置加密、Bot/User 长期记忆、library_edit/set_status、资料库内审批命令与多模态附件。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:bot-phase1;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class BotEnhancementsPhase1Test {
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

    record UpstreamRequest(String system, List<String> toolNames, String lastRole, JsonNode lastMessage) {}

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
    private volatile Function<UpstreamRequest, UpstreamReply> handler = req -> UpstreamReply.text("收到。");
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
                var toolNames = new ArrayList<String>();
                for (JsonNode t : body.path("tools")) {
                    toolNames.add(t.path("function").path("name").asText());
                }
                var req = new UpstreamRequest(system, List.copyOf(toolNames), last.path("role").asText(""), last);
                requests.add(req);
                UpstreamReply reply = handler.apply(req);
                var sb = new StringBuilder();
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
                    sb.append("data: ").append(chunk).append("\n\n");
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
                    sb.append("data: ").append(chunk).append("\n\n");
                }
                sb.append("data: [DONE]\n\n");
                byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
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
                Map.entry("modelName", "mock-gpt"),
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
    void webFetchBlocksPrivateAndMetadataAddresses() {
        assertThat(BotWebTools.validatePublicUri(URI.create("http://127.0.0.1:8080/api"), InetAddress::getAllByName))
                .contains("禁止访问");
        assertThat(BotWebTools.validatePublicUri(
                        URI.create("http://169.254.169.254/latest/meta-data"), InetAddress::getAllByName))
                .contains("禁止访问");
        assertThat(BotWebTools.validatePublicUri(URI.create("http://localhost:4317/"), InetAddress::getAllByName))
                .contains("禁止访问");
        assertThat(BotWebTools.validatePublicUri(
                        URI.create("http://192.168.1.10/admin"), InetAddress::getAllByName))
                .contains("禁止访问");
        assertThat(BotWebTools.validatePublicUri(URI.create("file:///etc/passwd"), InetAddress::getAllByName))
                .contains("仅允许读取 http/https");
        assertThat(BotWebTools.validatePublicUri(
                        URI.create("https://example.com/page"),
                        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")}))
                .isNull();
    }

    @Test
    void rememberAndForgetFactsAreInjectedIntoPromptAndManageableViaApi() throws Exception {
        JsonNode ws = get("/api/bots/workspace");
        String mainId = ws.path("bots").get(0).path("bot").path("id").asText();

        AtomicReference<String> savedFactId = new AtomicReference<>("");
        handler = req -> {
            if ("tool".equals(req.lastRole())) {
                return UpstreamReply.text("已经记住你的偏好了！");
            }
            if (req.lastMessage().path("content").asText("").contains("喜欢用表格")) {
                return UpstreamReply.tool(
                        "call_mem_1",
                        "remember",
                        Map.of("scope", "user", "text", "用户偏好所有周报用 Markdown 表格输出"),
                        json);
            }
            return UpstreamReply.text("已按表格偏好处理。");
        };

        post("/api/bots/" + mainId + "/messages", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "content", "记住我以后喜欢用表格看周报"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode facts = get("/api/bots/" + mainId + "/facts");
            assertThat(facts.path("userFacts").size()).isGreaterThanOrEqualTo(1);
            boolean matched = false;
            for (JsonNode f : facts.path("userFacts")) {
                if (f.path("content").asText().contains("Markdown 表格")) {
                    savedFactId.set(f.path("id").asText());
                    matched = true;
                }
            }
            assertThat(matched).isTrue();
        });

        // Next chat turn sees the remembered fact in its system prompt
        requests.clear();
        post("/api/bots/" + mainId + "/messages", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "content", "汇报一下本周重点"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(requests).isNotEmpty();
            assertThat(requests.getFirst().system()).contains("Markdown 表格");
        });

        // Delete via REST API
        post("/api/bot-facts/" + savedFactId.get() + "/delete", Map.of());
        JsonNode afterDelete = get("/api/bots/" + mainId + "/facts");
        for (JsonNode f : afterDelete.path("userFacts")) {
            assertThat(f.path("id").asText()).isNotEqualTo(savedFactId.get());
        }
    }

    @Test
    void backgroundTaskSupportsSetStatusLibraryEditWebToolsAndInlineCommandApproval() throws Exception {
        JsonNode created = post("/api/bots", Map.of(
                "name", "审批执行官",
                "roleSummary", "资料库文档与脚本助手",
                "systemPrompt", "使用资料库和命令完成任务",
                "approvalMode", "MANUAL"));
        String botId = created.path("bot").path("id").asText();

        AtomicInteger step = new AtomicInteger(0);
        handler = req -> {
            int n = step.incrementAndGet();
            // Confirm web_fetch, web_search, library_edit, set_status and propose_command are mounted
            assertThat(req.toolNames())
                    .contains("library_write", "library_edit", "set_status", "web_fetch", "web_search", "propose_command");
            if (n == 1) {
                return UpstreamReply.tool(
                        "call_status", "set_status", Map.of("text", "写入并局部修改资料库文档"), json);
            }
            if (n == 2) {
                return UpstreamReply.tool(
                        "call_write",
                        "library_write",
                        Map.of("path", "notes/plan.md", "content", "# Plan\nStatus: DRAFT\n"),
                        json);
            }
            if (n == 3) {
                return UpstreamReply.tool(
                        "call_edit",
                        "library_edit",
                        Map.of("path", "notes/plan.md", "old_text", "Status: DRAFT", "new_text", "Status: DONE"),
                        json);
            }
            if (n == 4) {
                return UpstreamReply.tool(
                        "call_cmd",
                        "propose_command",
                        Map.of(
                                "command", "echo hello-bot-library",
                                "directory", ".",
                                "purpose", "验证资料库目录命令审批",
                                "timeout_seconds", 15),
                        json);
            }
            return UpstreamReply.text("文档已更新且命令执行成功。");
        };

        JsonNode task = post("/api/bots/" + botId + "/tasks", Map.of(
                "title", "资料库编辑与命令测试",
                "instruction", "先更新状态，写文档再局部修改，最后执行一条 echo 命令"));
        String taskId = task.path("id").asText();

        // Wait for pending command approval to surface in Bot inline approvals API
        AtomicReference<String> commandId = new AtomicReference<>("");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode thread = get("/api/bots/" + botId + "/thread");
            JsonNode approvals = thread.path("pendingApprovals");
            assertThat(approvals.size()).isGreaterThanOrEqualTo(1);
            commandId.set(approvals.get(0).path("commandId").asText());
            assertThat(approvals.get(0).path("commandText").asText()).contains("hello-bot-library");
        });

        // Approve inline right from Bot Workbench API
        JsonNode approved = post("/api/bot-approvals/" + commandId.get() + "/approve", Map.of());
        assertThat(approved.path("status").asText()).isIn("APPROVED", "RUNNING", "COMPLETED");

        // Task completes, library file has edited content, and status_text was persisted
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode detail = get("/api/bot-tasks/" + taskId);
            assertThat(detail.path("task").path("status").asText()).isEqualTo("COMPLETED");
            assertThat(detail.path("task").path("statusText").asText()).isEqualTo("写入并局部修改资料库文档");
        });

        Path written = directory.resolve("bot-library").resolve("notes").resolve("plan.md");
        assertThat(Files.readString(written, StandardCharsets.UTF_8)).contains("Status: DONE");
    }

    @Test
    void searchSettingsEncryptApiKeyAndLimitsPersistCleanly() throws Exception {
        JsonNode initialSearch = get("/api/bots/settings/search");
        JsonNode savedSearch = post("/api/bots/settings/search", Map.of(
                "provider", "TAVILY",
                "endpointUrl", "",
                "apiKey", "tvly-secret-1234",
                "clearApiKey", false,
                "fallbackFreeEnabled", true,
                "maxResults", 8,
                "timeoutSeconds", 20,
                "revision", initialSearch.path("revision").asLong()));
        assertThat(savedSearch.path("provider").asText()).isEqualTo("TAVILY");
        assertThat(savedSearch.path("apiKeyConfigured").asBoolean()).isTrue();
        assertThat(savedSearch.path("apiKeyHint").asText()).isEqualTo("••••1234");
        assertThat(savedSearch.toString()).doesNotContain("tvly-secret-1234");

        JsonNode initialLimits = get("/api/bots/settings/limits");
        JsonNode savedLimits = post("/api/bots/settings/limits", Map.of(
                "maxConcurrentTasks", 5,
                "taskTimeoutMinutes", 20,
                "commandTimeoutSeconds", 420,
                "maxFactsPerScope", 60,
                "revision", initialLimits.path("revision").asLong()));
        assertThat(savedLimits.path("maxConcurrentTasks").asInt()).isEqualTo(5);
        assertThat(savedLimits.path("commandTimeoutSeconds").asInt()).isEqualTo(420);
    }

    @Test
    void multimodalAttachmentIsForwardedToModelAndMirroredToLibrary() throws Exception {
        JsonNode ws = get("/api/bots/workspace");
        String mainId = ws.path("bots").get(0).path("bot").path("id").asText();

        // 1x1 transparent PNG
        byte[] png = java.util.Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        JsonNode uploaded = uploadAttachment("chart.png", "image/png", png);
        String attachmentId = uploaded.path("id").asText();
        assertThat(attachmentId).isNotBlank();

        handler = req -> UpstreamReply.text("已看到你上传的图片 chart.png。");
        post("/api/bots/" + mainId + "/messages", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "content", "请分析这张图",
                "attachmentIds", List.of(attachmentId)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(requests).isNotEmpty();
            JsonNode contentNode = requests.getFirst().lastMessage().path("content");
            assertThat(contentNode.isArray()).isTrue();
            boolean hasImagePart = false;
            for (JsonNode part : contentNode) {
                if ("image_url".equals(part.path("type").asText())) {
                    hasImagePart = true;
                }
            }
            assertThat(hasImagePart).isTrue();
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode thread = get("/api/bots/" + mainId + "/thread");
            boolean matched = false;
            for (JsonNode entry : thread.path("entries")) {
                String attJson = entry.path("attachmentsJson").asText("");
                if ("USER".equals(entry.path("kind").asText()) && attJson.contains("chart.png")) {
                    matched = true;
                    JsonNode arr = json.readTree(attJson);
                    String libPath = arr.get(0).path("libraryPath").asText("");
                    assertThat(libPath).startsWith("uploads/");
                    assertThat(Files.exists(directory.resolve("bot-library").resolve(libPath))).isTrue();
                }
            }
            assertThat(matched).isTrue();
        });
    }

    private JsonNode uploadAttachment(String filename, String contentType, byte[] data) throws Exception {
        String boundary = "----HarnessBoundary" + UUID.randomUUID();
        var out = new java.io.ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/attachments"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("POST /api/attachments -> %s", res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private JsonNode get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private JsonNode post(String path, Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("POST %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }
}
