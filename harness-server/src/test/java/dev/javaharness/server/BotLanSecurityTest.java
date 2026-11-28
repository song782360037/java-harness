package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
            "spring.datasource.url=jdbc:h2:mem:bot-m4-lan;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class BotLanSecurityTest {
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
    int mainPort;

    @Autowired
    ObjectMapper json;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void lanCompanionEnforcesOptInIsolationPairingAndImmediateRevocation() throws Exception {
        // 1. Default state: LAN companion is OFF.
        JsonNode initial = mainGet("/api/bot-lan/status");
        assertThat(initial.path("running").asBoolean()).isFalse();
        assertThat(initial.path("settings").path("enabled").asBoolean()).isFalse();
        assertThat(initial.path("settings").path("allowRemoteApproval").asBoolean())
                .isFalse();
        assertThat(initial.path("devices")).isEmpty();

        // 2. Enable LAN companion on ephemeral port (0).
        long rev = initial.path("settings").path("revision").asLong();
        JsonNode enabled = mainPost(
                "/api/bot-lan/settings",
                Map.of("enabled", true, "port", 0, "allowRemoteApproval", false, "revision", rev));
        assertThat(enabled.path("running").asBoolean()).isTrue();
        int lanPort = enabled.path("actualPort").asInt();
        assertThat(lanPort).isGreaterThan(0).isNotEqualTo(mainPort);

        try {
            // 3. Mobile HTML shell is served on root and /phone.html of LAN port.
            HttpResponse<String> html = lanGet(lanPort, "/", null);
            assertThat(html.statusCode()).isEqualTo(200);
            assertThat(html.headers().firstValue("Content-Type").orElse("")).contains("text/html");
            assertThat(html.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
            assertThat(html.body()).contains("Java Harness");

            HttpResponse<String> phoneHtml = lanGet(lanPort, "/phone.html", null);
            assertThat(phoneHtml.statusCode()).isEqualTo(200);
            assertThat(phoneHtml.body()).contains("Java Harness");

            // Allowlisted static theme script and built phone SPA JS asset are served with nosniff.
            HttpResponse<String> themeJs = lanGet(lanPort, "/theme.js", null);
            assertThat(themeJs.statusCode()).isEqualTo(200);
            assertThat(themeJs.headers().firstValue("Content-Type").orElse("")).contains("javascript");
            assertThat(themeJs.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
            var assetMatcher = java.util.regex.Pattern.compile("src=\"(/assets/[a-zA-Z0-9._-]+\\.js)\"")
                    .matcher(phoneHtml.body());
            if (assetMatcher.find()) {
                HttpResponse<String> assetResp = lanGet(lanPort, assetMatcher.group(1), null);
                assertThat(assetResp.statusCode()).isEqualTo(200);
                assertThat(assetResp.headers().firstValue("Content-Type").orElse("")).contains("javascript");
                assertThat(assetResp.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
            }

            // 4. Desktop entry, traversal attempts, and desktop /api/* routes are NOT exposed (404).
            assertThat(lanGet(lanPort, "/index.html", null).statusCode()).isEqualTo(404);
            assertThat(lanGet(lanPort, "/assets/../index.html", null).statusCode()).isEqualTo(404);
            assertThat(lanGet(lanPort, "/assets/%2e%2e/index.html", null).statusCode()).isEqualTo(404);
            assertThat(lanGet(lanPort, "/assets/settings.key", null).statusCode()).isEqualTo(404);
            assertThat(lanGet(lanPort, "/api/settings", null).statusCode()).isEqualTo(404);
            assertThat(lanGet(lanPort, "/api/sessions", null).statusCode()).isEqualTo(404);

            // Unauthenticated LAN API returns 401 across all protected endpoints.
            assertThat(lanGet(lanPort, "/lan/api/bots/workspace", null).statusCode()).isEqualTo(401);
            assertThat(lanGet(lanPort, "/lan/api/bots/events", null).statusCode()).isEqualTo(401);
            assertThat(lanGet(lanPort, "/lan/api/bots/main/avatar", null).statusCode()).isEqualTo(401);
            assertThat(lanGet(lanPort, "/lan/api/bots/main/files/content?path=a.txt", null).statusCode())
                    .isEqualTo(401);
            assertThat(lanGet(lanPort, "/lan/api/bot-apps/demo/versions", null).statusCode()).isEqualTo(401);
            assertThat(lanPost(lanPort, "/lan/api/attachments", Map.of(), null).statusCode()).isEqualTo(401);

            // 5. Phone requests 4-digit pairing code.
            HttpResponse<String> pairReqResp = lanPost(lanPort, "/lan/api/pair/request", Map.of(), null);
            assertThat(pairReqResp.statusCode()).isEqualTo(201);
            JsonNode pairReq = json.readTree(pairReqResp.body());
            String requestId = pairReq.path("requestId").asText();
            String code = pairReq.path("code").asText();
            assertThat(requestId).isNotBlank();
            assertThat(code).matches("\\d{4}");

            // Desktop sees pending pairing request without exposing the secret code.
            JsonNode waitingStatus = mainGet("/api/bot-lan/status");
            assertThat(waitingStatus.path("pendingPairing").path("requestId").asText())
                    .isEqualTo(requestId);
            assertThat(waitingStatus.path("pendingPairing").has("code")).isFalse();

            // Wrong code is rejected with 400.
            String wrongCode = code.equals("0000") ? "9999" : "0000";
            HttpResponse<String> badConfirm =
                    mainPostRaw("/api/bot-lan/pair/confirm", Map.of("code", wrongCode, "deviceName", "入侵设备"));
            assertThat(badConfirm.statusCode()).isEqualTo(400);

            // Correct code pairs device.
            JsonNode device = mainPost("/api/bot-lan/pair/confirm", Map.of("code", code, "deviceName", "测试 iPhone"));
            String deviceId = device.path("id").asText();
            assertThat(deviceId).isNotBlank();
            assertThat(device.path("name").asText()).isEqualTo("测试 iPhone");

            // Phone polls and receives HttpOnly cookie.
            HttpResponse<String> pollResp = lanGet(lanPort, "/lan/api/pair/poll?requestId=" + requestId, null);
            assertThat(pollResp.statusCode()).isEqualTo(200);
            JsonNode pollBody = json.readTree(pollResp.body());
            assertThat(pollBody.path("status").asText()).isEqualTo("PAIRED");
            List<String> setCookies = pollResp.headers().allValues("Set-Cookie");
            assertThat(setCookies).anySatisfy(c -> {
                assertThat(c).contains("harness_bot_device=");
                assertThat(c).contains("HttpOnly");
            });
            String cookieHeader = setCookies.getFirst().split(";", 2)[0];

            // 6. Authenticated LAN API works with paired cookie.
            HttpResponse<String> meResp = lanGet(lanPort, "/lan/api/me", cookieHeader);
            assertThat(meResp.statusCode()).isEqualTo(200);
            JsonNode me = json.readTree(meResp.body());
            assertThat(me.path("device").path("name").asText()).isEqualTo("测试 iPhone");
            assertThat(me.path("allowRemoteApproval").asBoolean()).isFalse();

            HttpResponse<String> wsResp = lanGet(lanPort, "/lan/api/bots/workspace", cookieHeader);
            assertThat(wsResp.statusCode()).isEqualTo(200);
            JsonNode ws = json.readTree(wsResp.body());
            String mainBotId = ws.path("bots").get(0).path("bot").path("id").asText();
            assertThat(lanGet(lanPort, "/lan/api/bots/" + mainBotId + "/thread", cookieHeader)
                            .statusCode())
                    .isEqualTo(200);

            // 6b. Authenticated SSE stream returns 200 text/event-stream and emits initial ping.
            HttpRequest sseReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + lanPort + "/lan/api/bots/events"))
                    .header("Cookie", cookieHeader)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> sseResp = client.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(sseResp.statusCode()).isEqualTo(200);
            assertThat(sseResp.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
            try (InputStream stream = sseResp.body()) {
                byte[] buf = new byte[128];
                int read = stream.read(buf);
                assertThat(read).isGreaterThan(0);
                assertThat(new String(buf, 0, read, StandardCharsets.UTF_8)).contains("event: ping");
            }

            // 6c. Authenticated attachment multipart upload & download works and preserves nosniff.
            String boundary = "----HarnessLanTestBoundary";
            ByteArrayOutputStream multipart = new ByteArrayOutputStream();
            multipart.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            multipart.write("Content-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\n".getBytes(StandardCharsets.UTF_8));
            multipart.write("Content-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            multipart.write("hello from phone".getBytes(StandardCharsets.UTF_8));
            multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest uploadReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + lanPort + "/lan/api/attachments"))
                    .header("Cookie", cookieHeader)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
                    .build();
            HttpResponse<String> uploadResp = client.send(uploadReq, HttpResponse.BodyHandlers.ofString());
            assertThat(uploadResp.statusCode()).isEqualTo(201);
            JsonNode uploaded = json.readTree(uploadResp.body());
            String attachmentId = uploaded.path("id").asText();
            assertThat(attachmentId).isNotBlank();
            assertThat(uploaded.path("name").asText()).isEqualTo("note.txt");

            HttpResponse<String> dlResp = lanGet(lanPort, "/lan/api/attachments/" + attachmentId + "/content", cookieHeader);
            assertThat(dlResp.statusCode()).isEqualTo(200);
            assertThat(dlResp.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
            assertThat(dlResp.body()).isEqualTo("hello from phone");

            // 7. Remote approval is blocked (403) when disabled.
            HttpResponse<String> blockedApproval =
                    lanPost(lanPort, "/lan/api/approvals/fake-cmd/approve", Map.of(), cookieHeader);
            assertThat(blockedApproval.statusCode()).isEqualTo(403);

            // Even when allowRemoteApproval is enabled, non-bot command IDs are rejected (404).
            long rev2 = mainGet("/api/bot-lan/status")
                    .path("settings")
                    .path("revision")
                    .asLong();
            JsonNode enabledApproval = mainPost(
                    "/api/bot-lan/settings",
                    Map.of("enabled", true, "port", lanPort, "allowRemoteApproval", true, "revision", rev2));
            assertThat(enabledApproval
                            .path("settings")
                            .path("allowRemoteApproval")
                            .asBoolean())
                    .isTrue();
            HttpResponse<String> nonBotApproval =
                    lanPost(lanPort, "/lan/api/approvals/non-bot-cmd/approve", Map.of(), cookieHeader);
            assertThat(nonBotApproval.statusCode()).isEqualTo(404);

            // 8. Revoking the device immediately invalidates its cookie across all endpoints.
            mainPost("/api/bot-lan/devices/" + deviceId + "/revoke", Map.of());
            assertThat(lanGet(lanPort, "/lan/api/me", cookieHeader).statusCode()).isEqualTo(401);
            assertThat(lanGet(lanPort, "/lan/api/bots/events", cookieHeader).statusCode()).isEqualTo(401);
            assertThat(lanGet(lanPort, "/lan/api/attachments/" + attachmentId + "/content", cookieHeader).statusCode())
                    .isEqualTo(401);
        } finally {
            // 9. Hot-disable stops the LAN server cleanly.
            long latestRev = mainGet("/api/bot-lan/status")
                    .path("settings")
                    .path("revision")
                    .asLong();
            JsonNode disabled = mainPost(
                    "/api/bot-lan/settings",
                    Map.of("enabled", false, "port", 4317, "allowRemoteApproval", false, "revision", latestRev));
            assertThat(disabled.path("running").asBoolean()).isFalse();
            assertThat(disabled.path("actualPort").asInt()).isEqualTo(0);
        }
    }

    private JsonNode mainGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mainPort + path))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("GET %s -> %s", path, resp.body()).isBetween(200, 299);
        return json.readTree(resp.body());
    }

    private JsonNode mainPost(String path, Object body) throws Exception {
        HttpResponse<String> resp = mainPostRaw(path, body);
        assertThat(resp.statusCode()).as("POST %s -> %s", path, resp.body()).isBetween(200, 299);
        return json.readTree(resp.body());
    }

    private HttpResponse<String> mainPostRaw(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mainPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> lanGet(int lanPort, String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + lanPort + path))
                .GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> lanPost(int lanPort, String path, Object body, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + lanPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (cookie != null) builder.header("Cookie", cookie);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
