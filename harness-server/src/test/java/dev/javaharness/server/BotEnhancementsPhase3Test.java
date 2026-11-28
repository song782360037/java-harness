package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证 Phase 3 后端能力：自定义头像上传魔数校验/删除/读取、3 槽位装扮等级解锁校验（主 Bot 默认 goldCrown）、隐藏状态与未读标记。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:bot-phase3;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class BotEnhancementsPhase3Test {
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

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void avatarUploadValidatesMagicBytesAndOutfitsEnforceLevelUnlockAndMainCrown() throws Exception {
        JsonNode ws = get("/api/bots/workspace");
        JsonNode mainCard = ws.path("bots").get(0);
        String mainId = mainCard.path("bot").path("id").asText();
        // Main bot starts with goldCrown by default
        assertThat(mainCard.path("bot").path("outfitJson").asText()).contains("goldCrown");

        // Main bot cannot be hidden
        HttpResponse<String> hideMain = postRaw("/api/bots/" + mainId, Map.of("hidden", true));
        assertThat(hideMain.statusCode()).isEqualTo(409);

        // Main bot can wear goldCrown + Lv.1 pieces even at Lv.1, but not locked Lv.99 rainbowCrown
        JsonNode mainOutfitted = post("/api/bots/" + mainId, Map.of("outfit", List.of("goldCrown", "roundGlasses", "bowTie")));
        assertThat(mainOutfitted.path("bot").path("outfitJson").asText())
                .contains("goldCrown", "roundGlasses", "bowTie");
        HttpResponse<String> mainLocked = postRaw("/api/bots/" + mainId, Map.of("outfit", List.of("rainbowCrown")));
        assertThat(mainLocked.statusCode()).isEqualTo(409);

        // New specialist bot starts at Lv.1: cannot wear goldCrown (Lv.60), CAN wear Lv.1 pieces, CAN be hidden
        JsonNode worker = post("/api/bots", Map.of(
                "name", "装扮测试员" + UUID.randomUUID().toString().substring(0, 4),
                "roleSummary", "验证装扮与头像",
                "systemPrompt", "测试"));
        String workerId = worker.path("bot").path("id").asText();

        HttpResponse<String> workerLocked = postRaw("/api/bots/" + workerId, Map.of("outfit", List.of("goldCrown")));
        assertThat(workerLocked.statusCode()).isEqualTo(409);

        JsonNode workerEquipped = post("/api/bots/" + workerId, Map.of(
                "outfit", List.of("partyHat", "roundGlasses", "bowTie"),
                "hidden", true));
        assertThat(workerEquipped.path("bot").path("outfitJson").asText()).contains("partyHat", "roundGlasses", "bowTie");
        assertThat(workerEquipped.path("bot").path("hidden").asBoolean()).isTrue();

        // Reject SVG/HTML spoofed avatar upload
        HttpResponse<String> badSvg = uploadAvatarRaw(
                workerId, "evil.svg", "image/svg+xml", "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8));
        assertThat(badSvg.statusCode()).isEqualTo(400);

        // Accept valid PNG avatar upload and serve with ETag + nosniff
        byte[] png = Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpResponse<String> okUpload = uploadAvatarRaw(workerId, "me.png", "image/png", png);
        assertThat(okUpload.statusCode()).isEqualTo(200);
        JsonNode uploadedCard = json.readTree(okUpload.body());
        assertThat(uploadedCard.path("avatarUrl").asText()).contains("/api/bots/" + workerId + "/avatar");

        HttpResponse<byte[]> fetched = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/bots/" + workerId + "/avatar"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");
        assertThat(fetched.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(fetched.headers().firstValue("ETag")).isPresent();
        assertThat(fetched.body()).isEqualTo(png);

        // Delete avatar restores vector fallback
        JsonNode cleared = post("/api/bots/" + workerId + "/avatar/delete", Map.of());
        assertThat(cleared.path("avatarUrl").asText()).isEmpty();
        assertThat(getRaw("/api/bots/" + workerId + "/avatar").statusCode()).isEqualTo(404);

        // Manual unread toggle works
        post("/api/bots/" + workerId + "/unread", Map.of());
        post("/api/bots/" + workerId + "/seen", Map.of());
    }

    private HttpResponse<String> uploadAvatarRaw(String botId, String filename, String contentType, byte[] data)
            throws Exception {
        String boundary = "----AvatarBoundary" + UUID.randomUUID();
        var out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/bots/" + botId + "/avatar"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = getRaw(path);
        assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private HttpResponse<String> getRaw(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpResponse<String> res = postRaw(path, body);
        assertThat(res.statusCode()).as("POST %s -> %s", path, res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private HttpResponse<String> postRaw(String path, Object body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
