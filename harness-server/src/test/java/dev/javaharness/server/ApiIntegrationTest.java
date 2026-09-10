package dev.javaharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:api-tests;DB_CLOSE_DELAY=-1",
        "harness.simulated-chunk-delay=20", "harness.run-timeout=5s"
})
class ApiIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired SessionStore store;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api" + path); }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String newSession() throws Exception {
        var response = post("/sessions", Map.of("title", "API 验证"));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    @Test
    void submitIdempotencyBusyCancelAndOriginPolicy() throws Exception {
        String id = newSession();
        var body = Map.of("requestId", UUID.randomUUID().toString(), "prompt", "test", "scenario", "SLOW");
        var first = post("/sessions/" + id + "/runs", body);
        assertThat(first.statusCode()).isEqualTo(202);
        String runId = json.readTree(first.body()).get("id").asText();
        var retry = post("/sessions/" + id + "/runs", body);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(json.readTree(retry.body()).get("id").asText()).isEqualTo(runId);
        assertThat(post("/sessions/" + id + "/runs", Map.of("requestId", UUID.randomUUID().toString(),
                "prompt", "another")).statusCode()).isEqualTo(409);
        assertThat(post("/runs/" + runId + "/cancel", Map.of()).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(3)).until(() -> store.run(runId).status() == RunStatus.CANCELLED);
        var blocked = client.send(HttpRequest.newBuilder(uri("/sessions")).header("Origin", "https://foreign.example")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(post("/sessions/" + id + "/runs", Map.of("requestId", "bad", "prompt", "x")).statusCode())
                .isEqualTo(400);
        assertThat(post("/sessions/missing/runs", body).statusCode()).isEqualTo(404);
    }

    @Test
    void sseReplaysAndReconnectsUsingLastEventIdWithoutCancellingRun() throws Exception {
        String id = newSession();
        var submitted = post("/sessions/" + id + "/runs", Map.of("requestId", UUID.randomUUID().toString(),
                "prompt", "stream", "scenario", "SLOW"));
        String runId = json.readTree(submitted.body()).get("id").asText();
        JsonNode first = readOneEvent(id, 0, null);
        assertThat(first.get("seq").asLong()).isEqualTo(1);
        // Closing the first subscriber must not cancel the provider.
        assertThat(store.run(runId).status().active()).isTrue();
        JsonNode second = readOneEvent(id, 0, "1");
        assertThat(second.get("seq").asLong()).isEqualTo(2);
        assertThat(second.get("runId").asText()).isEqualTo(runId);
        assertThat(client.send(HttpRequest.newBuilder(uri("/sessions/" + id + "/events?after=-1")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        post("/runs/" + runId + "/cancel", Map.of());
        await().atMost(Duration.ofSeconds(3)).until(() -> store.run(runId).status() == RunStatus.CANCELLED);
        var snapshot = client.send(HttpRequest.newBuilder(uri("/sessions/" + id)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(snapshot.body()).get("runs").get(0).get("status").asText()).isEqualTo("CANCELLED");
    }

    private JsonNode readOneEvent(String id, long after, String lastId) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/sessions/" + id + "/events?after=" + after))
                .timeout(Duration.ofSeconds(5)).GET();
        if (lastId != null) builder.header("Last-Event-ID", lastId);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var input = response.body(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JsonNode> result = executor.submit(() -> {
                var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) return json.readTree(line.substring(5));
                }
                throw new AssertionError("SSE ended without an event");
            });
            try { return result.get(3, TimeUnit.SECONDS); }
            finally { input.close(); }
        }
    }
}
