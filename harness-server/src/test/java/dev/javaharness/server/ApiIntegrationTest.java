package dev.javaharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.*;
import dev.javaharness.adapters.SimulatedModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
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
        "harness.run-timeout=5s", "harness.model.api-key=", "harness.model.name=",
        "harness.model.base-url=https://api.openai.com/v1"
})
@Import(ApiIntegrationTest.FixtureConfiguration.class)
class ApiIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean @Primary
        RunConfigurationSource testConfiguration() {
            var provider = new SimulatedModelProvider(20);
            return () -> new RunConfiguration(provider.info(), Duration.ofSeconds(5), 32000, 0,
                    workspace -> new RunConfiguration.Resources(provider, WorkspaceTools.disabled(), () -> {}));
        }
    }

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
    void renameApiValidatesPersistsAndPublishesSessionEvent() throws Exception {
        String id = newSession();
        var response = post("/sessions/" + id + "/title", Map.of("title", "  已重命名  "));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("title").asText()).isEqualTo("已重命名");
        var snapshot = client.send(HttpRequest.newBuilder(uri("/sessions/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(snapshot.body()).get("session").get("title").asText()).isEqualTo("已重命名");
        var list = client.send(HttpRequest.newBuilder(uri("/sessions")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(list.body())).filteredOn(session -> session.get("id").asText().equals(id))
                .extracting(session -> session.get("title").asText()).containsExactly("已重命名");
        assertThat(readOneEvent(id, 0, null).get("type").asText()).isEqualTo("TITLE");
        for (String invalid : java.util.List.of("", "   ", "x".repeat(101))) {
            assertThat(post("/sessions/" + id + "/title", Map.of("title", invalid)).statusCode()).isEqualTo(400);
        }
        assertThat(post("/sessions/" + id + "/title", Map.of()).statusCode()).isEqualTo(400);
        assertThat(post("/sessions/missing/title", Map.of("title", "name")).statusCode()).isEqualTo(404);
        assertThat(store.snapshot(id).session().title()).isEqualTo("已重命名");
        assertThat(store.snapshot(id).lastSeq()).isEqualTo(1);
    }

    @Test
    void modelMetadataContainsOnlyPublicConfiguration() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/model")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("""
                {"provider":"simulated","model":"local-simulator","configured":true}
                """));
    }

    @Test void workspaceAndExecutionReadApisDistinguishEmptyAndMissingRecords() throws Exception {
        var workspace = client.send(HttpRequest.newBuilder(uri("/workspace")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(workspace.statusCode()).isEqualTo(200);
        assertThat(json.readTree(workspace.body())).isEqualTo(json.readTree("{\"configured\":false,\"root\":\"\",\"tools\":[]}"));
        String session = newSession();
        var submitted = post("/sessions/" + session + "/runs", Map.of("requestId", UUID.randomUUID().toString(), "prompt", "hello"));
        String id = json.readTree(submitted.body()).path("id").asText();
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.run(id).status().active());
        var steps = client.send(HttpRequest.newBuilder(uri("/runs/" + id + "/model-requests")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(steps.statusCode()).isEqualTo(200);
        assertThat(json.readTree(steps.body())).hasSize(1);
        assertThat(json.readTree(steps.body()).get(0).path("step").asInt()).isEqualTo(1);
        var tools = client.send(HttpRequest.newBuilder(uri("/runs/" + id + "/tools")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(tools.statusCode()).isEqualTo(200);
        assertThat(json.readTree(tools.body())).isEmpty();
        for (String endpoint : new String[] {"tools", "model-requests"}) {
            assertThat(client.send(HttpRequest.newBuilder(uri("/runs/missing/" + endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
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
    void modelRequestEndpointReturnsFrozenMessagesAndDistinguishesAbsentSnapshots() throws Exception {
        String id = newSession();
        var submitted = post("/sessions/" + id + "/runs", Map.of("requestId", UUID.randomUUID().toString(), "prompt", "你好 🌱"));
        String runId = json.readTree(submitted.body()).path("id").asText();
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.run(runId).status().active());
        var response = client.send(HttpRequest.newBuilder(uri("/runs/" + runId + "/model-request")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("messages").get(0).path("content").asText()).isEqualTo("你好 🌱");
        assertThat(response.body()).doesNotContain("apiKey", "Authorization");
        var legacy = store.submit(id, UUID.randomUUID().toString(), "legacy", "NORMAL", TestModelProvider.INFO).run();
        store.transition(legacy.id(), RunStatus.INTERRUPTED, "");
        assertThat(client.send(HttpRequest.newBuilder(uri("/runs/" + legacy.id() + "/model-request")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
        assertThat(client.send(HttpRequest.newBuilder(uri("/runs/missing/model-request")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
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
