package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.spi.model.ModelMessage;
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
            "spring.datasource.url=jdbc:h2:mem:question-api;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false"
        })
class QuestionApiIntegrationTest {
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

    JsonNode get(String path) throws Exception {
        return json.readTree(
                client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .body());
    }

    @Test
    void attendedRunAsksTheUserAndResumesWithTheAnswer() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
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
                    String todos = json.writeValueAsString(
                            Map.of("todos", List.of(Map.of("content", "确认存储方案", "status", "in_progress"))));
                    String question = json.writeValueAsString(Map.of(
                            "question",
                            "选哪种存储？",
                            "options",
                            List.of(
                                    Map.of("label", "H2", "description", "推荐"),
                                    Map.of("label", "MySQL", "description", "外部服务"))));
                    delta = Map.of(
                            "tool_calls",
                            List.of(
                                    Map.of(
                                            "index",
                                            0,
                                            "id",
                                            "todo-call",
                                            "type",
                                            "function",
                                            "function",
                                            Map.of("name", "update_todos", "arguments", todos)),
                                    Map.of(
                                            "index",
                                            1,
                                            "id",
                                            "question-call",
                                            "type",
                                            "function",
                                            "function",
                                            Map.of("name", "ask_user_question", "arguments", question))));
                } else delta = Map.of("content", "按 H2 继续。");
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
            var config = (ObjectNode) get("/settings");
            config.put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1")
                    .put("modelName", "question-model")
                    .put("apiKeyRequired", false)
                    .put("apiKey", "")
                    .put("runTimeoutSeconds", 45)
                    .put("maxSteps", 0);
            assertThat(post("/settings", config).statusCode()).isEqualTo(200);
            String session = json.readTree(
                            post("/sessions", Map.of("title", "question test", "workspace", workspace.toString()))
                                    .body())
                    .path("id")
                    .asText();
            String run = json.readTree(post(
                                    "/sessions/" + session + "/runs",
                                    Map.of("requestId", UUID.randomUUID().toString(), "prompt", "帮我选存储"))
                            .body())
                    .path("id")
                    .asText();
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> get("/runs/" + run + "/questions").size() == 1);
            JsonNode question = get("/runs/" + run + "/questions").get(0);
            assertThat(question.path("status").asText()).isEqualTo("PENDING");
            assertThat(requests.getFirst().path("tools").toString()).contains("ask_user_question", "update_todos");
            String id = question.path("id").asText();
            assertThat(post("/questions/" + id + "/answer", Map.of("selected", List.of("Oracle"), "note", ""))
                            .statusCode())
                    .isEqualTo(400);
            assertThat(post("/questions/" + id + "/answer", Map.of("selected", List.of("H2"), "note", "先简单"))
                            .statusCode())
                    .isEqualTo(200);
            assertThat(post("/questions/" + id + "/skip", Map.of()).statusCode())
                    .isEqualTo(409);
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> !sessions.run(run).status().active());
            assertThat(sessions.run(run).status())
                    .as(sessions.run(run).detail())
                    .isEqualTo(RunStatus.COMPLETED);
            assertThat(requests).hasSize(2);
            assertThat(requests.getLast().path("messages").toString()).contains("用户回答：H2", "补充说明：先简单", "已完成 0/1");
            ModelMessage.validate(sessions.conversation(run), false);
            assertThat(sessions.events(session, 0, 128)).anyMatch(e -> e.type().equals("QUESTION"));
            assertThat(post("/questions/missing/answer", Map.of("selected", List.of("H2")))
                            .statusCode())
                    .isEqualTo(404);
        } finally {
            upstream.stop(0);
        }
    }
}
