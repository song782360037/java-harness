package dev.javaharness.server;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.LocalCommandRunner;
import dev.javaharness.spi.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:command-api;DB_CLOSE_DELAY=-1",
        "harness.model.base-url=https://api.openai.com/v1", "harness.model.name=", "harness.model.api-key=",
        "harness.session-naming.enabled=false"
})
class CommandApiIntegrationTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("harness.settings.key-file", () -> directory.resolve("settings.key").toString());
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired SessionStore sessions;
    private final HttpClient client = HttpClient.newHttpClient();
    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api" + path); }
    HttpResponse<String> post(String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode get(String path) throws Exception {
        return json.readTree(client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }
    @Test void approvalGatesRealProcessAndResultReturnsToNextModelRequest() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<JsonNode>();
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                JsonNode request = json.readTree(exchange.getRequestBody()); requests.add(request);
                JsonNode last = request.path("messages").get(request.path("messages").size() - 1);
                Map<String, Object> delta;
                if (last.path("role").asText().equals("user")) {
                    String arguments = json.writeValueAsString(Map.of("command", LocalCommandRunner.WINDOWS
                            ? "Add-Content marker.txt once; Write-Output '测试成功'" : "echo once >> marker.txt; echo '测试成功'",
                            "directory", ".", "purpose", "运行验证", "timeout_seconds", 15));
                    delta = Map.of("tool_calls", List.of(Map.of("index", 0, "id", "command-call", "type", "function",
                            "function", Map.of("name", "propose_command", "arguments", arguments))));
                } else delta = Map.of("content", "命令结果已收到。");
                String data = json.writeValueAsString(Map.of("choices", List.of(Map.of("index", 0, "delta", delta,
                        "finish_reason", delta.containsKey("content") ? "stop" : "tool_calls"))));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(("data: " + data + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        upstream.start();
        try {
            var config = (ObjectNode) get("/settings");
            config.put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1")
                    .put("modelName", "command-model").put("apiKeyRequired", false).put("apiKey", "").put("runTimeoutSeconds", 45).put("maxSteps", 0);
            assertThat(post("/settings", config).statusCode()).isEqualTo(200);
            String session = json.readTree(post("/sessions", Map.of("title", "command test", "workspace", workspace.toString())).body()).path("id").asText();
            String run = json.readTree(post("/sessions/" + session + "/runs", Map.of("requestId", UUID.randomUUID().toString(), "prompt", "测试命令审批")).body()).path("id").asText();
            await().atMost(Duration.ofSeconds(10)).until(() -> get("/runs/" + run + "/commands").size() == 1);
            String id = get("/runs/" + run + "/commands").get(0).path("id").asText();
            assertThat(get("/runs/" + run + "/commands").get(0).path("status").asText()).isEqualTo("PENDING");
            assertThat(workspace.resolve("marker.txt")).doesNotExist(); assertThat(requests).hasSize(1);
            var foreign = client.send(HttpRequest.newBuilder(uri("/commands/" + id + "/approve"))
                    .header("Origin", "https://foreign.example").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(foreign.statusCode()).isEqualTo(403);
            assertThat(post("/commands/" + id + "/approve", Map.of()).statusCode()).isEqualTo(200);
            assertThat(post("/commands/" + id + "/approve", Map.of()).statusCode()).isEqualTo(200);
            await().atMost(Duration.ofSeconds(20)).until(() -> !sessions.run(run).status().active());
            assertThat(sessions.run(run).status()).as(sessions.run(run).detail()).isEqualTo(RunStatus.COMPLETED);
            assertThat(Files.readAllLines(workspace.resolve("marker.txt"))).containsExactly("once");
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst().path("tools").toString()).contains("propose_command");
            assertThat(requests.getLast().path("messages").toString()).contains("测试成功", "退出码：0", "command-call");
            ModelMessage.validate(sessions.conversation(run), false);
            assertThat(get("/runs/" + run + "/commands").get(0).path("status").asText()).isEqualTo("COMPLETED");
            assertThat(sessions.events(session, 0, 128)).anyMatch(e -> e.type().equals("COMMAND"));
            assertThat(post("/commands/missing/approve", Map.of()).statusCode()).isEqualTo(404);
        } finally { upstream.stop(0); }
    }
}
