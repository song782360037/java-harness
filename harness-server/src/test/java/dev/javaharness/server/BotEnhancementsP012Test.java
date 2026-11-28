package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.server.service.BotSkillService;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
            "spring.datasource.url=jdbc:h2:mem:bot-p012-enhancements;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class BotEnhancementsP012Test {
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

    @Autowired
    BotSkillService skillService;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void skillsTwoPhaseUploadInstallUseMainCrossBotAndSecurityValidation() throws Exception {
        JsonNode ws = get("/api/bots/workspace");
        String mainBotId = ws.path("bots").get(0).path("bot").path("id").asText();

        // Create a specialist bot.
        JsonNode specialist = post(
                "/api/bots",
                Map.of(
                        "name",
                        "数据专员",
                        "roleSummary",
                        "擅长整理表格与数据报告",
                        "systemPrompt",
                        "认真整理每一份数据",
                        "shape",
                        "cat",
                        "color",
                        "#2d5d9c"));
        String specialistId = specialist.path("bot").path("id").asText();

        // 1. Zip-slip malicious archive is rejected with 400.
        byte[] maliciousZip = zipOf(Map.of(
                "../evil/SKILL.md", "---\nname: evil\ndescription: bad\n---\nhi"));
        HttpResponse<String> badResp = uploadZip("/api/bots/" + specialistId + "/skills/upload", "evil.zip", maliciousZip);
        assertThat(badResp.statusCode()).isEqualTo(400);

        // 2. Valid multi-skill collection zip with script detection.
        byte[] validZip = zipOf(Map.of(
                "collection/xlsx-helper/SKILL.md",
                "---\nname: xlsx-helper\ndescription: 处理 Excel 报表与公式校验\nversion: 1.2.0\n---\n# Excel 指南\n请先运行 scripts/check.py 校验。",
                "collection/xlsx-helper/scripts/check.py",
                "print('ok')\n",
                "collection/xlsx-helper/refs/guide.md",
                "参考手册内容",
                "collection/chart-maker/SKILL.md",
                "---\nname: chart-maker\ndescription: 生成 ECharts 图表\n---\n生成图表步骤"));

        HttpResponse<String> offerResp =
                uploadZip("/api/bots/" + specialistId + "/skills/upload", "data-skills.skill", validZip);
        assertThat(offerResp.statusCode()).as(offerResp.body()).isEqualTo(200);
        JsonNode offer = json.readTree(offerResp.body());
        String offerId = offer.path("offerId").asText();
        assertThat(offer.path("skills")).hasSize(2);
        assertThat(offer.path("installed").isNull()).isTrue();

        // Nothing installed before explicit user confirmation!
        assertThat(get("/api/bots/" + specialistId + "/skills")).isEmpty();

        // User can inspect offer files BEFORE confirming install.
        JsonNode offerFiles = get("/api/bot-skill-offers/" + offerId + "/xlsx-helper/files");
        assertThat(offerFiles.get(0).path("path").asText()).isEqualTo("SKILL.md");
        assertThat(offerFiles).anySatisfy(f -> {
            assertThat(f.path("path").asText()).isEqualTo("scripts/check.py");
            assertThat(f.path("script").asBoolean()).isTrue();
        });
        JsonNode scriptPreview = get("/api/bot-skill-offers/" + offerId + "/xlsx-helper/file?path=scripts/check.py");
        assertThat(scriptPreview.path("content").asText()).contains("print('ok')");

        // 3. User confirms installing only xlsx-helper.
        JsonNode answered = post(
                "/api/bots/" + specialistId + "/skills/offers/" + offerId + "/answer",
                Map.of("names", List.of("xlsx-helper")));
        assertThat(answered.path("installed")).hasSize(1);
        assertThat(answered.path("installed").get(0).asText()).isEqualTo("xlsx-helper");

        // Verify specialist sees its skill, and Main Bot sees team skills too!
        JsonNode specSkills = get("/api/bots/" + specialistId + "/skills");
        assertThat(specSkills).hasSize(1);
        assertThat(specSkills.get(0).path("name").asText()).isEqualTo("xlsx-helper");
        assertThat(specSkills.get(0).path("scripts").get(0).asText()).isEqualTo("scripts/check.py");

        JsonNode mainSkills = get("/api/bots/" + mainBotId + "/skills");
        assertThat(mainSkills).anySatisfy(s -> {
            assertThat(s.path("name").asText()).isEqualTo("xlsx-helper");
            assertThat(s.path("botId").asText()).isEqualTo(specialistId);
        });

        // Installed file preview works.
        JsonNode installedFiles = get("/api/bots/" + specialistId + "/skills/xlsx-helper/files");
        assertThat(installedFiles).hasSize(3);
        JsonNode refFile = get("/api/bots/" + specialistId + "/skills/xlsx-helper/file?path=refs/guide.md");
        assertThat(refFile.path("content").asText()).isEqualTo("参考手册内容");

        // 4. use_skill works on specialist AND cross-bot from Main Bot, incrementing useCount.
        String specBody = skillService.useSkill(specialistId, "xlsx-helper");
        assertThat(specBody).contains("Excel 指南").contains("_skills/" + specialistId + "/xlsx-helper");
        String mainBody = skillService.useSkill(mainBotId, specialistId + "/xlsx-helper");
        assertThat(mainBody).contains("Excel 指南");

        JsonNode afterUse = get("/api/bots/" + specialistId + "/skills");
        assertThat(afterUse.get(0).path("useCount").asInt()).isEqualTo(2);
        assertThat(afterUse.get(0).path("lastUsedAt").isNull()).isFalse();

        // 5. Chat offer creates a persisted SKILL_OFFER entry that collapses after answering.
        Path localSkillDir = directory.resolve("local-skill-sample");
        Files.createDirectories(localSkillDir);
        Files.writeString(
                localSkillDir.resolve("SKILL.md"),
                "---\nname: local-notes\ndescription: 整理本地会议纪要\n---\n会议纪要规范",
                StandardCharsets.UTF_8);
        var chatOffer = skillService.offerFromChat(mainBotId, specialistId, localSkillDir.toString());
        JsonNode threadBefore = get("/api/bots/" + mainBotId + "/thread");
        assertThat(threadBefore.path("entries")).anySatisfy(e -> {
            assertThat(e.path("kind").asText()).isEqualTo("SKILL_OFFER");
            assertThat(e.path("attachmentsJson").asText()).contains(chatOffer.offerId());
        });
        post(
                "/api/bots/" + mainBotId + "/skills/offers/" + chatOffer.offerId() + "/answer",
                Map.of("names", List.of("local-notes")));
        JsonNode threadAfter = get("/api/bots/" + mainBotId + "/thread");
        assertThat(threadAfter.path("entries")).anySatisfy(e -> {
            assertThat(e.path("kind").asText()).isEqualTo("SKILL_OFFER");
            assertThat(e.path("attachmentsJson").asText()).contains("\"installed\":[\"local-notes\"]");
        });

        // 6. Main Bot can remove specialist's skill cleanly.
        post(
                "/api/bots/" + mainBotId + "/skills/remove",
                Map.of("targetBotId", specialistId, "names", List.of("xlsx-helper")));
        JsonNode remaining = get("/api/bots/" + specialistId + "/skills");
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).path("name").asText()).isEqualTo("local-notes");
    }

    @Test
    void silenceTimeoutLimitEntrySearchSeenAllAndBotUsageEndpointsWork() throws Exception {
        // 1. Model silence timeout limit round-trip.
        JsonNode initialLimits = get("/api/bots/settings/limits");
        assertThat(initialLimits.path("modelSilenceSeconds").asInt()).isEqualTo(300);
        long rev = initialLimits.path("revision").asLong();
        JsonNode savedLimits = post(
                "/api/bots/settings/limits",
                Map.of(
                        "maxConcurrentTasks",
                        4,
                        "taskTimeoutMinutes",
                        20,
                        "commandTimeoutSeconds",
                        180,
                        "modelSilenceSeconds",
                        120,
                        "maxFactsPerScope",
                        60,
                        "revision",
                        rev));
        assertThat(savedLimits.path("modelSilenceSeconds").asInt()).isEqualTo(120);

        // 2. Entry search & mark-all-seen endpoints.
        JsonNode ws = get("/api/bots/workspace");
        String mainBotId = ws.path("bots").get(0).path("bot").path("id").asText();
        JsonNode hits = get("/api/bots/" + mainBotId + "/entries/search?q=nonexistent");
        assertThat(hits.isArray()).isTrue();
        JsonNode seenAll = post("/api/bots/seen-all", Map.of());
        assertThat(seenAll.path("seenAll").asBoolean()).isTrue();

        // 3. Bot usage report endpoint returns valid structure.
        JsonNode usage = get("/api/bots/usage?days=7");
        assertThat(usage.path("days").asInt()).isEqualTo(7);
        assertThat(usage.path("bySource").has("chat")).isTrue();
        assertThat(usage.path("bySource").has("task")).isTrue();
        assertThat(usage.path("bySource").has("routine")).isTrue();
        assertThat(usage.path("bots").isArray()).isTrue();
        assertThat(usage.path("bots").size()).isGreaterThanOrEqualTo(1);
    }

    private static byte[] zipOf(Map<String, String> entries) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private HttpResponse<String> uploadZip(String path, String filename, byte[] zipBytes) throws Exception {
        String boundary = "----SkillTestBoundary";
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: application/zip\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(zipBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("GET %s -> %s", path, resp.body()).isBetween(200, 299);
        return json.readTree(resp.body());
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("POST %s -> %s", path, resp.body()).isBetween(200, 299);
        return json.readTree(resp.body());
    }
}
