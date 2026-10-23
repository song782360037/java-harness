package dev.javaharness.server;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:plugin-api-tests;DB_CLOSE_DELAY=-1",
        "harness.plugins.directory=./target/plugin-api-tests",
        "harness.settings.key-file=./target/plugin-api-tests.key", "harness.session-naming.enabled=false"
})
class PluginApiTest {
    @LocalServerPort int port;
    @TempDir Path source;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** Exercise real HTTP parsing, scoped binding, command discovery and uninstall responses. */
    @Test void directoryBindingAndCommandLifecycleUsesPublicEndpoints() throws Exception {
        Files.createDirectories(source.resolve("dev.javaharness/commands"));
        Files.writeString(source.resolve("plugin.json"), "{\"$schema\":\"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json\",\"name\":\"api-example\",\"version\":\"1.0.0\"}");
        Files.writeString(source.resolve("dev.javaharness/commands/review.md"), "Review $ARGUMENTS");
        var installed = post("/plugins/import-directory", Map.of("path", source.toString(), "source", "test"));
        assertThat(installed.statusCode()).as(installed.body()).isEqualTo(200);
        String id = json.readTree(installed.body()).path("id").asText();
        var saved = post("/plugin-bindings/user/api-example", Map.of("installationId", id, "enabled", true, "config", Map.of(), "revision", 1));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        long revision = json.readTree(saved.body()).path("revision").asLong();
        var session = post("/sessions", Map.of("title", "插件接口测试"));
        String sessionId = json.readTree(session.body()).path("id").asText();
        var commands = get("/sessions/" + sessionId + "/plugin-commands");
        assertThat(commands.statusCode()).isEqualTo(200);
        assertThat(commands.body()).contains("api-example:review");
        var stale = post("/plugin-bindings/user/api-example/delete", Map.of("revision", revision - 1));
        assertThat(stale.statusCode()).isEqualTo(409);
        var unbound = post("/plugin-bindings/user/api-example/delete", Map.of("revision", revision));
        assertThat(unbound.statusCode()).isEqualTo(204); assertThat(unbound.body()).isEmpty();
        assertThat(json.readTree(get("/sessions/" + sessionId + "/plugin-commands").body()).isEmpty()).isTrue();
        assertThat(post("/plugins/" + id + "/remove", Map.of("uninstall", true, "revoke", false)).statusCode()).isEqualTo(200);
        assertThat(json.readTree(get("/plugins").body()).isEmpty()).isTrue();
    }

    /** A multipart upload uses the same validator and remains protected by the local-origin filter. */
    @Test void multipartZipInstallAndForeignOriginProtection() throws Exception {
        var zipped = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(zipped)) {
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write("{\"$schema\":\"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json\",\"name\":\"zip-example\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        String boundary = "plugin-boundary";
        var bytes = new ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"plugin.zip\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(zipped.toByteArray()); bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri("/plugins/import-zip")).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()));
        var foreign = client.send(request.copy().header("Origin", "https://untrusted.example").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(403);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String id = json.readTree(response.body()).path("id").asText();
        assertThat(post("/plugins/" + id + "/remove", Map.of("uninstall", true)).statusCode()).isEqualTo(200);
    }

    /** Broken launch dependencies must not prevent listing, disabling or uninstalling the binding. */
    @Test void missingExecutableReturnsLaunchErrorAndManagementRemainsAvailable() throws Exception {
        Path plugin = Files.createDirectory(source.resolve("plugin")), executable = source.resolve("fixture-program");
        Files.writeString(executable, "never executed");
        Files.writeString(plugin.resolve("plugin.json"), "{\"$schema\":\"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json\",\"name\":\"broken-example\",\"version\":\"1.0.0\"}");
        Files.writeString(plugin.resolve("mcp.json"), json.writeValueAsString(Map.of("$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                "mcpServers", Map.of("report", Map.of("type", "stdio", "command", executable.toString())))));
        var installed = post("/plugins/import-directory", Map.of("path", plugin.toString(), "source", "test"));
        assertThat(installed.statusCode()).as(installed.body()).isEqualTo(200); String id = json.readTree(installed.body()).path("id").asText();
        var saved = post("/plugin-bindings/user/broken-example", Map.of("installationId", id, "enabled", true, "config", Map.of(), "revision", 1));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200); long revision = json.readTree(saved.body()).path("revision").asLong();
        Files.delete(executable);
        var listing = get("/plugin-bindings?scope=user");
        assertThat(listing.statusCode()).as(listing.body()).isEqualTo(200);
        JsonNode binding = json.readTree(listing.body()).findParents("name").stream().filter(value -> value.path("name").asText().equals("broken-example")).findFirst().orElseThrow();
        assertThat(binding.path("trusted").asBoolean()).isFalse(); assertThat(binding.path("launchError").asText()).contains("可执行文件不存在");
        assertThat(get("/plugins/" + id).statusCode()).isEqualTo(200);
        var disabled = post("/plugin-bindings/user/broken-example", Map.of("installationId", id, "enabled", false, "config", Map.of(), "revision", revision));
        assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(200); assertThat(json.readTree(disabled.body()).path("enabled").asBoolean()).isFalse();
        assertThat(post("/plugins/" + id + "/remove", Map.of("uninstall", true)).statusCode()).isEqualTo(200);
    }

    /** Discovery and standalone authoring work through the public JSON API without enabling the new entry. */
    @Test void extensionDiscoveryAndStandaloneSkillApi() throws Exception {
        var catalog = get("/extensions"); assertThat(catalog.statusCode()).isEqualTo(200);
        assertThat(json.readTree(catalog.body()).findValuesAsText("id")).contains("skill-java-review", "connector-text", "harness-project-map");
        var added = post("/extensions/skills", Map.of("name", "api-skill", "title", "API 技能", "description", "测试独立添加", "version", "1.0.0", "content", "读取项目并给出建议。", "scope", "user"));
        assertThat(added.statusCode()).as(added.body()).isEqualTo(200);
        String id = json.readTree(added.body()).path("id").asText();
        var listing = json.readTree(get("/extensions").body());
        var item = java.util.stream.StreamSupport.stream(listing.spliterator(), false).filter(value -> value.path("installationId").asText().equals(id)).findFirst().orElseThrow();
        assertThat(item.path("kind").asText()).isEqualTo("skill"); assertThat(item.path("official").asBoolean()).isFalse();
        assertThat(item.path("enabled").asBoolean()).isTrue(); assertThat(item.path("editable").asBoolean()).isTrue();
        var toggled = post("/extensions/installed/" + id + "/enabled", Map.of("enabled", false, "revision", item.path("revision").asLong()));
        assertThat(toggled.statusCode()).isEqualTo(204); assertThat(toggled.body()).isEmpty();
        assertThat(get("/extensions/items/" + java.net.URLEncoder.encode(item.path("id").asText(), StandardCharsets.UTF_8)).body()).contains("读取项目");
        assertThat(post("/extensions/skills", Map.of("name", "api-skill", "title", "API 技能", "description", "重复", "version", "1.0.0", "content", "body", "scope", "user")).statusCode()).isEqualTo(409);
        assertThat(post("/plugins/" + id + "/remove", Map.of("uninstall", true)).statusCode()).isEqualTo(200);
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api" + path); }
    private HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private HttpResponse<String> post(String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
