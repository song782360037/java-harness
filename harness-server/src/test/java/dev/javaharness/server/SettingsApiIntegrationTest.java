package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
            "spring.datasource.url=jdbc:h2:mem:settings-api;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.model.api-key-required=true",
            "harness.tools.max-steps=0",
            "harness.session-naming.enabled=false"
        })
class SettingsApiIntegrationTest {
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
    SessionStore store;

    private final HttpClient client = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api" + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object value) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode settings(String base, String model, String key) throws Exception {
        var value = (ObjectNode) json.readTree(get("/settings").body());
        value.put("baseUrl", base)
                .put("modelName", model)
                .put("apiKey", key)
                .put("clearApiKey", false)
                .put("apiKeyRequired", true)
                .put("runTimeoutSeconds", 60)
                .put("maxSteps", 0);
        return value;
    }

    private String provider(String base, String key) throws Exception {
        var result = post(
                "/model-providers",
                Map.of(
                        "revision",
                        0,
                        "displayName",
                        "Test provider",
                        "baseUrl",
                        base,
                        "apiKey",
                        key,
                        "apiKeyRequired",
                        true,
                        "connectTimeoutSeconds",
                        3));
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.body()).doesNotContain(key, "\"apiKey\":");
        return json.readTree(result.body()).path("id").asText();
    }

    private ObjectNode modelBody(String providerId, String model, String effort) {
        return json.createObjectNode()
                .put("providerId", providerId)
                .put("displayName", model)
                .put("modelName", model)
                .put("reasoningEffort", effort)
                .put("revision", 0)
                .put("runTimeoutSeconds", 60)
                .put("contextMaxCharacters", 32000)
                .put("maxSteps", 0)
                .put("toolTimeoutSeconds", 5);
    }

    private String create(String workspace) throws Exception {
        var response = post("/sessions", Map.of("title", "配置验证", "workspace", workspace));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).path("id").asText();
    }

    private Run run(String session) throws Exception {
        var response = post(
                "/sessions/" + session + "/runs",
                Map.of("requestId", UUID.randomUUID().toString(), "prompt", "read marker.txt"));
        assertThat(response.statusCode()).isEqualTo(202);
        return store.run(json.readTree(response.body()).path("id").asText());
    }

    private Run settled(Run run) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !store.run(run.id()).status().active());
        var result = store.run(run.id());
        assertThat(result.status()).as(result.detail()).isEqualTo(RunStatus.COMPLETED);
        return result;
    }

    @Test
    void pageSettingsSwitchRealHttpProviderAndKeepActiveRunsAndSessionWorkspacesIsolated() throws Exception {
        Path firstRoot = Files.createDirectory(directory.resolve("项目 A"));
        Path secondRoot = Files.createDirectory(directory.resolve("项目 B"));
        Files.writeString(firstRoot.resolve("marker.txt"), "workspace-alpha");
        Files.writeString(secondRoot.resolve("marker.txt"), "workspace-beta");
        String a = firstRoot.toRealPath().toString(),
                b = secondRoot.toRealPath().toString();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(handlers);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var handlerError = new AtomicReference<Throwable>();
        var requests = new CopyOnWriteArrayList<String>();
        upstream.createContext("/", exchange -> {
            try (exchange) {
                String route = exchange.getRequestURI().getPath();
                var request = json.readTree(exchange.getRequestBody());
                String model = request.path("model").asText();
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                requests.add(route + ":" + model + ":" + auth);
                if (first.compareAndSet(true, false)) {
                    entered.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                }
                var messages = request.path("messages");
                JsonNode last = messages.get(messages.size() - 1);
                String delta;
                String finish;
                if (last.path("role").asText().equals("tool")) {
                    delta = json.writeValueAsString(
                            Map.of("content", last.path("content").asText()));
                    finish = "stop";
                } else {
                    delta = json.writeValueAsString(Map.of(
                            "tool_calls",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "id",
                                    "call-marker",
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", "read_file", "arguments", "{\"path\":\"marker.txt\"}")))));
                    finish = "tool_calls";
                }
                String response = "data: {\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":\""
                        + finish + "\"}]}\n\ndata: [DONE]\n\n";
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable error) {
                handlerError.set(error);
            }
        });
        upstream.start();
        try {
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            var saved = post("/settings", settings(base + "/a", "model-a", "secret-a"));
            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(saved.body()).doesNotContain("secret-a", "\"apiKey\":");
            assertThat(json.readTree(saved.body()).path("apiKeyConfigured").asBoolean())
                    .isTrue();
            String sessionA = create(a), sessionB = create(b);
            Run active = run(sessionA);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(post("/sessions/" + sessionA + "/workspace", Map.of("workspace", b))
                            .statusCode())
                    .isEqualTo(409);
            assertThat(post("/settings", settings(base + "/b", "model-b", "secret-b"))
                            .statusCode())
                    .isEqualTo(200);
            release.countDown();
            assertThat(settled(active).output()).contains("workspace-alpha").doesNotContain("workspace-beta");
            assertThat(requests)
                    .containsExactly(
                            "/a/chat/completions:model-a:Bearer secret-a",
                            "/a/chat/completions:model-a:Bearer secret-a");

            Run next = settled(run(sessionB));
            assertThat(next.model()).isEqualTo("model-b");
            assertThat(next.workspace()).isEqualTo(b);
            assertThat(next.output()).contains("workspace-beta").doesNotContain("workspace-alpha");
            assertThat(requests.subList(2, 4)).containsOnly("/b/chat/completions:model-b:Bearer secret-b");
            assertThat(post("/sessions/" + sessionA + "/workspace", Map.of("workspace", b))
                            .statusCode())
                    .isEqualTo(200);
            Run switched = settled(run(sessionA));
            assertThat(switched.output()).contains("workspace-beta").doesNotContain("workspace-alpha");
            assertThat(switched.context().includedTurns()).isZero();
            assertThat(switched.context().excludedTurns()).isEqualTo(1);
            assertThat(store.run(active.id()).workspace()).isEqualTo(a);
            assertThat(store.events(sessionA, 0, 256))
                    .anyMatch(event -> event.type().equals("WORKSPACE") && event.runId() == null);
            assertThat(json.readTree(get("/sessions/" + sessionA).body())
                            .path("session")
                            .path("workspace")
                            .asText())
                    .isEqualTo(b);
            assertThat(post("/sessions/" + sessionA + "/workspace", Map.of("workspace", ""))
                            .statusCode())
                    .isEqualTo(200);
            assertThat(store.snapshot(sessionA).session().workspace()).isEmpty();
            assertThat(get("/settings").body()).doesNotContain("secret-a", "secret-b", "\"apiKey\":");
        } finally {
            release.countDown();
            upstream.stop(0);
            handlers.shutdownNow();
        }
        assertThat(handlerError.get()).isNull();
    }

    @Test
    void configurationValidationKeyActionsDirectoryListingAndOriginProtection() throws Exception {
        var original = settings("https://compatible.example/v1", "org/model:latest", "test-private-key");
        var response = post("/settings", original);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(post("/settings", original).statusCode()).isEqualTo(409);
        var retain = settings("https://compatible.example/v1", "model", "");
        assertThat(json.readTree(post("/settings", retain).body())
                        .path("apiKeyConfigured")
                        .asBoolean())
                .isTrue();
        var invalid = settings("http://remote.example", "model", "secret-invalid");
        var failure = post("/settings", invalid);
        assertThat(failure.statusCode()).isEqualTo(400);
        assertThat(failure.body()).doesNotContain("secret-invalid");
        var cleared = settings("https://compatible.example/v1", "model", "")
                .put("clearApiKey", true)
                .put("apiKeyRequired", false);
        var clearedResult = json.readTree(post("/settings", cleared).body());
        assertThat(clearedResult.path("apiKeyConfigured").asBoolean()).isFalse();
        assertThat(clearedResult.path("model").path("configured").asBoolean()).isTrue();
        var blocked = client.send(
                HttpRequest.newBuilder(uri("/settings"))
                        .header("Origin", "https://foreign.example")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(blocked.statusCode()).isEqualTo(403);

        Path root = Files.createDirectory(directory.resolve("选择 空格"));
        Files.createDirectory(root.resolve("child"));
        Files.writeString(root.resolve("not-a-directory.txt"), "test");
        var listing = get("/workspaces/directories?path=" + URLEncoder.encode(root.toString(), StandardCharsets.UTF_8));
        assertThat(listing.statusCode()).isEqualTo(200);
        assertThat(json.readTree(listing.body()).path("directories")).hasSize(1);
        assertThat(listing.body()).contains("child").doesNotContain("not-a-directory.txt");
        assertThat(json.readTree(get("/workspaces/directories").body()).path("directories"))
                .isNotEmpty();
        assertThat(post("/sessions", Map.of("title", "bad", "workspace", "relative/path"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(post(
                                "/sessions",
                                Map.of(
                                        "title",
                                        "bad",
                                        "workspace",
                                        root.resolve("not-a-directory.txt").toString()))
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void profileCrudKeepsCredentialsSeparateAndValidatesEffortAndRevisions() throws Exception {
        String providerId = provider("https://profiles.example/v1", "profile-key");
        var body = modelBody(providerId, "model-profile", "high");
        var created = post("/model-profiles", body);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json.readTree(created.body()).path("id").asText();
        assertThat(created.body()).doesNotContain("profile-key", "\"apiKey\":");
        var update = (ObjectNode) json.readTree(created.body());
        update.put("apiKey", "").put("reasoningEffort", "low");
        var saved = post("/model-profiles/" + id, update);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).path("model").path("configured").asBoolean())
                .isTrue();
        assertThat(post("/model-profiles/" + id, update).statusCode()).isEqualTo(409);
        update = (ObjectNode) json.readTree(saved.body());
        update.put("reasoningEffort", "invalid");
        assertThat(post("/model-profiles/" + id, update).statusCode()).isEqualTo(400);
        String session = create("");
        assertThat(post(
                                "/sessions/" + session + "/runs",
                                Map.of(
                                        "requestId",
                                        UUID.randomUUID().toString(),
                                        "prompt",
                                        "x",
                                        "modelProfileId",
                                        id,
                                        "reasoningEffort",
                                        "invalid"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(get("/model-profiles").body()).doesNotContain("profile-key", "\"apiKey\":");
        assertThat(post("/model-profiles/" + id + "/delete", Map.of("revision", 1))
                        .statusCode())
                .isEqualTo(409);
        assertThat(post("/model-profiles/" + id + "/delete", Map.of("revision", 2))
                        .statusCode())
                .isEqualTo(200);
        assertThat(post("/model-profiles/default/delete", Map.of("revision", 1)).statusCode())
                .isEqualTo(400);
    }

    @Test
    void providerCrudSharesConnectionAndAllowsMovingModelsBeforeDeletion() throws Exception {
        String providerId = provider("https://provider-one.example/v1", "shared-provider-key");
        var first = post("/model-profiles", modelBody(providerId, "one", "low"));
        var second = post("/model-profiles", modelBody(providerId, "two", "high"));
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(first.body()).doesNotContain("baseUrl", "apiKey", "connectTimeoutSeconds");
        assertThat(post("/model-providers/" + providerId + "/delete", Map.of("revision", 1))
                        .statusCode())
                .isEqualTo(409);
        var update = json.createObjectNode()
                .put("revision", 1)
                .put("displayName", "Shared provider")
                .put("baseUrl", "https://provider-updated.example/v1")
                .put("apiKey", "")
                .put("apiKeyRequired", true)
                .put("connectTimeoutSeconds", 4);
        var saved = post("/model-providers/" + providerId, update);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).path("apiKeyConfigured").asBoolean())
                .isTrue();
        assertThat(saved.body()).doesNotContain("shared-provider-key", "\"apiKey\":");
        assertThat(post("/model-providers/" + providerId, update).statusCode()).isEqualTo(409);
        String destination = provider("https://provider-two.example/v1", "other-provider-key");
        var models = json.readTree(get("/model-profiles").body());
        for (JsonNode model : models) {
            if (!model.path("providerId").asText().equals(providerId)) continue;
            assertThat(model.path("revision").asInt()).isEqualTo(2);
            var moved = ((ObjectNode) model).deepCopy().put("providerId", destination);
            assertThat(post("/model-profiles/" + model.path("id").asText(), moved)
                            .statusCode())
                    .isEqualTo(200);
        }
        assertThat(post("/model-providers/" + providerId + "/delete", Map.of("revision", 2))
                        .statusCode())
                .isEqualTo(200);
        assertThat(post("/model-profiles", modelBody(providerId, "removed-provider", ""))
                        .statusCode())
                .isEqualTo(404);
        var invalid = update.deepCopy()
                .put("revision", 0)
                .put("baseUrl", "http://remote.example/v1")
                .put("apiKey", "sensitive-invalid");
        var rejected = post("/model-providers", invalid);
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body()).doesNotContain("sensitive-invalid");
        var blocked = client.send(
                HttpRequest.newBuilder(uri("/model-providers"))
                        .header("Origin", "https://foreign.example")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(blocked.statusCode()).isEqualTo(403);
    }

    @Test
    void perSubmissionProfilesAndEffortsReachEveryHttpStepAndDeletedProfileRetriesStayIdempotent() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(handlers);
        var requests = new CopyOnWriteArrayList<String>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var handlerError = new AtomicReference<Throwable>();
        upstream.createContext("/", exchange -> {
            try (exchange) {
                var request = json.readTree(exchange.getRequestBody());
                String effort = request.has("reasoning_effort")
                        ? request.path("reasoning_effort").asText()
                        : "omitted";
                requests.add(exchange.getRequestURI().getPath() + ":"
                        + request.path("model").asText() + ":"
                        + exchange.getRequestHeaders().getFirst("Authorization") + ":" + effort);
                if (first.compareAndSet(true, false)) {
                    entered.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                }
                JsonNode messages = request.path("messages");
                boolean tool =
                        messages.get(messages.size() - 1).path("role").asText().equals("tool");
                String delta = tool
                        ? "{\"content\":\"finished\"}"
                        : "{\"tool_calls\":[{\"index\":0,\"id\":\"reasoning-read\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"marker.txt\\\"}\"}}]}";
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody()
                        .write(("data: {\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":\""
                                        + (tool ? "stop" : "tool_calls") + "\"}]}\n\ndata: [DONE]\n\n")
                                .getBytes(StandardCharsets.UTF_8));
            } catch (Throwable error) {
                handlerError.set(error);
            }
        });
        upstream.start();
        try {
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            var a = post("/model-profiles", modelBody(provider(base + "/a", "key-a"), "profile-a", "high"));
            String sharedProvider = provider(base + "/b", "key-b");
            var b = post("/model-profiles", modelBody(sharedProvider, "profile-b", "medium"));
            var sibling = post("/model-profiles", modelBody(sharedProvider, "profile-b-sibling", "high"));
            assertThat(sibling.statusCode()).isEqualTo(201);
            assertThat(a.statusCode()).isEqualTo(201);
            assertThat(b.statusCode()).isEqualTo(201);
            String idA = json.readTree(a.body()).path("id").asText(),
                    idB = json.readTree(b.body()).path("id").asText();
            Path root = Files.createDirectory(directory.resolve("reasoning-workspace"));
            Files.writeString(root.resolve("marker.txt"), "marker");
            String session = create(root.toString());
            var original = Map.of(
                    "requestId",
                    UUID.randomUUID().toString(),
                    "prompt",
                    "read marker",
                    "modelProfileId",
                    idA,
                    "reasoningEffort",
                    "low");
            var submitted = post("/sessions/" + session + "/runs", original);
            assertThat(submitted.statusCode()).isEqualTo(202);
            String runId = json.readTree(submitted.body()).path("id").asText();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(post("/model-profiles/" + idA + "/delete", Map.of("revision", 1))
                            .statusCode())
                    .isEqualTo(200);
            assertThat(post("/sessions/" + session + "/runs", original).statusCode())
                    .isEqualTo(200);
            release.countDown();
            Run completed = settled(store.run(runId));
            assertThat(completed.reasoningEffort()).isEqualTo("low");
            assertThat(completed.modelProfileId()).isEqualTo(idA);
            assertThat(store.modelRequests(runId))
                    .allMatch(request -> request.reasoningEffort().equals("low"));
            assertThat(requests)
                    .containsExactly(
                            "/a/chat/completions:profile-a:Bearer key-a:low",
                            "/a/chat/completions:profile-a:Bearer key-a:low");
            assertThat(post("/sessions/" + session + "/runs", new HashMap<>(original) {
                                {
                                    put("reasoningEffort", "high");
                                }
                            })
                            .statusCode())
                    .isEqualTo(409);
            assertThat(post("/sessions/" + session + "/runs", new HashMap<>(original) {
                                {
                                    put("requestId", UUID.randomUUID().toString());
                                }
                            })
                            .statusCode())
                    .isEqualTo(404);

            for (String effort : new String[] {"default", "", "max"}) {
                var next = new HashMap<>(Map.of(
                        "requestId", UUID.randomUUID().toString(), "prompt", "read again", "modelProfileId", idB));
                if (!effort.equals("default")) next.put("reasoningEffort", effort);
                var response = post("/sessions/" + session + "/runs", next);
                assertThat(response.statusCode()).isEqualTo(202);
                var done = settled(
                        store.run(json.readTree(response.body()).path("id").asText()));
                assertThat(done.model()).isEqualTo("profile-b");
                assertThat(done.reasoningEffort()).isEqualTo(effort.equals("default") ? "medium" : effort);
                assertThat(store.modelRequests(done.id()))
                        .allMatch(request -> request.reasoningEffort().equals(done.reasoningEffort()));
            }
            assertThat(requests.subList(2, 4)).containsOnly("/b/chat/completions:profile-b:Bearer key-b:medium");
            assertThat(requests.subList(4, 6)).containsOnly("/b/chat/completions:profile-b:Bearer key-b:omitted");
            assertThat(requests.subList(6, 8)).containsOnly("/b/chat/completions:profile-b:Bearer key-b:max");
            var siblingRun = post(
                    "/sessions/" + session + "/runs",
                    Map.of(
                            "requestId",
                            UUID.randomUUID().toString(),
                            "prompt",
                            "use sibling",
                            "modelProfileId",
                            json.readTree(sibling.body()).path("id").asText()));
            assertThat(siblingRun.statusCode()).isEqualTo(202);
            settled(store.run(json.readTree(siblingRun.body()).path("id").asText()));
            assertThat(requests.subList(8, 10)).containsOnly("/b/chat/completions:profile-b-sibling:Bearer key-b:high");
            assertThat(store.snapshot(session).session().modelProfileId())
                    .isEqualTo(json.readTree(sibling.body()).path("id").asText());
            assertThat(store.snapshot(session).session().reasoningEffort()).isEqualTo("high");
        } finally {
            release.countDown();
            upstream.stop(0);
            handlers.shutdownNow();
        }
        assertThat(handlerError.get()).isNull();
    }
}
