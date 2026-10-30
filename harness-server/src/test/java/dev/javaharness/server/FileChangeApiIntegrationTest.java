package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolExecution;
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
            "spring.datasource.url=jdbc:h2:mem:file-change-api;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false"
        })
class FileChangeApiIntegrationTest {
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

    private final HttpClient client = HttpClient.newHttpClient();

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

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> tool(int index, String id, String name, Map<String, String> args) throws Exception {
        return Map.of(
                "index",
                index,
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of("name", name, "arguments", json.writeValueAsString(args)));
    }

    @Test
    void realChatCompletionProtocolProposesDiffAndOnlyReviewApiWrites() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path file = workspace.resolve("App.java");
        Files.writeString(file, "class App { int value = 1; }\n");
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<JsonNode>();
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                JsonNode request = json.readTree(exchange.getRequestBody());
                requests.add(request);
                JsonNode last =
                        request.path("messages").get(request.path("messages").size() - 1);
                Map<String, Object> delta;
                if (last.path("role").asText().equals("user")) {
                    delta = Map.of("tool_calls", List.of(tool(0, "read", "read_file", Map.of("path", "App.java"))));
                } else if (last.path("tool_call_id").asText().equals("read")) {
                    delta = Map.of(
                            "tool_calls",
                            List.of(
                                    tool(
                                            0,
                                            "replace",
                                            "propose_file_change",
                                            Map.of(
                                                    "path",
                                                    "App.java",
                                                    "operation",
                                                    "replace",
                                                    "old_text",
                                                    "value = 1",
                                                    "new_text",
                                                    "value = 2")),
                                    tool(
                                            1,
                                            "create",
                                            "propose_file_change",
                                            Map.of(
                                                    "path",
                                                    "New.txt",
                                                    "operation",
                                                    "create",
                                                    "old_text",
                                                    "",
                                                    "new_text",
                                                    "new\n"))));
                } else delta = Map.of("content", "已生成两份待审核修改，请在页面确认。");
                String data = json.writeValueAsString(Map.of(
                        "choices",
                        List.of(Map.of(
                                "index",
                                0,
                                "delta",
                                delta,
                                "finish_reason",
                                delta.containsKey("content") ? "stop" : "tool_calls"))));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody()
                        .write(("data: " + data + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        upstream.start();
        try {
            var config = json.readTree(get("/settings").body()).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) config)
                    .put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1")
                    .put("modelName", "review-model")
                    .put("apiKeyRequired", false)
                    .put("apiKey", "")
                    .put("runTimeoutSeconds", 30)
                    .put("maxSteps", 0);
            assertThat(post("/settings", config).statusCode()).isEqualTo(200);
            String session = json.readTree(
                            post("/sessions", Map.of("title", "file edit", "workspace", workspace.toString()))
                                    .body())
                    .path("id")
                    .asText();
            var submitted = post(
                    "/sessions/" + session + "/runs",
                    Map.of("requestId", UUID.randomUUID().toString(), "prompt", "修改 App.java 并新增 New.txt"));
            assertThat(submitted.statusCode()).isEqualTo(202);
            String run = json.readTree(submitted.body()).path("id").asText();
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> !sessions.run(run).status().active());
            assertThat(sessions.run(run).status())
                    .as(sessions.run(run).detail())
                    .isEqualTo(RunStatus.COMPLETED);
            assertThat(requests).hasSize(3);
            assertThat(requests.getFirst().path("tools").toString()).contains("propose_file_change");
            assertThat(requests.getLast().path("messages").toString()).contains("文件尚未修改");
            ModelMessage.validate(sessions.conversation(run), false);
            assertThat(sessions.tools(run)).extracting(ToolExecution::status).containsOnly("COMPLETED");
            var changes = json.readTree(get("/runs/" + run + "/file-changes").body());
            assertThat(changes).hasSize(2);
            String edit = "", create = "";
            for (JsonNode change : changes) {
                assertThat(change.path("status").asText()).isEqualTo("PENDING");
                if (change.path("operation").asText().equals("replace"))
                    edit = change.path("id").asText();
                else create = change.path("id").asText();
            }
            assertThat(Files.readString(file)).contains("value = 1");
            assertThat(workspace.resolve("New.txt")).doesNotExist();
            var foreign = client.send(
                    HttpRequest.newBuilder(uri("/file-changes/" + edit + "/apply"))
                            .header("Origin", "https://foreign.example")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(foreign.statusCode()).isEqualTo(403);
            assertThat(get("/file-changes/" + edit + "/apply").statusCode()).isEqualTo(405);
            assertThat(post("/file-changes/" + edit + "/apply", Map.of()).body())
                    .contains("APPLIED");
            assertThat(Files.readString(file)).contains("value = 2");
            assertThat(post("/file-changes/" + edit + "/apply", Map.of()).statusCode())
                    .isEqualTo(200);
            assertThat(post("/file-changes/" + create + "/reject", Map.of()).body())
                    .contains("REJECTED");
            assertThat(workspace.resolve("New.txt")).doesNotExist();
            assertThat(post("/file-changes/" + create + "/apply", Map.of()).statusCode())
                    .isEqualTo(409);
            assertThat(get("/runs/missing/file-changes").statusCode()).isEqualTo(404);
            assertThat(post("/file-changes/missing/apply", Map.of()).statusCode())
                    .isEqualTo(404);
        } finally {
            upstream.stop(0);
        }
    }
}
