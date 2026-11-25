package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionStore;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:session-search-tests;DB_CLOSE_DELAY=-1",
            "harness.run-timeout=5s",
            "harness.model.api-key=",
            "harness.model.name=",
            "harness.session-naming.enabled=false"
        })
class SessionSearchApiIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    SessionStore store;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void searchesTitlesPromptsAndOutputsWhileEscapingWildcardsAndExcludingHiddenSessions() throws Exception {
        var model = new ModelInfo("simulated", "simulated-1", true);

        Session byTitle = store.createSession("排查连接池告警");
        Session byPrompt = store.createSession("普通会话甲");
        Run promptRun = store.submit(
                        byPrompt.id(), UUID.randomUUID().toString(), "分析 H2_POOL 100% 占用原因", "NORMAL", model)
                .run();
        store.transition(promptRun.id(), RunStatus.RUNNING, "");
        store.appendText(promptRun.id(), "初步检查未发现死锁。");
        store.transition(promptRun.id(), RunStatus.COMPLETED, "");

        Session byOutput = store.createSession("普通会话乙");
        Run outputRun = store.submit(byOutput.id(), UUID.randomUUID().toString(), "帮我看下日志", "NORMAL", model)
                .run();
        store.transition(outputRun.id(), RunStatus.RUNNING, "");
        store.appendText(outputRun.id(), "根因是 upstream_gateway 返回了 502 Bad Gateway。");
        store.transition(outputRun.id(), RunStatus.COMPLETED, "");

        // 隐藏的群聊内部会话不应出现在全局检索结果中。
        Session hidden = store.createSession("内部隐藏会话");
        jdbc.update("UPDATE harness_session SET group_id='hidden-group' WHERE id=?", hidden.id());
        Run hiddenRun = store.submit(
                        hidden.id(), UUID.randomUUID().toString(), "upstream_gateway 内部排查", "NORMAL", model)
                .run();
        store.transition(hiddenRun.id(), RunStatus.RUNNING, "");
        store.transition(hiddenRun.id(), RunStatus.COMPLETED, "");

        // 空白查询返回空列表。
        assertThat(search("   ")).isEmpty();

        // 标题命中。
        JsonNode titleHits = search("连接池告警");
        assertThat(titleHits).hasSize(1);
        assertThat(titleHits.get(0).path("session").path("id").asText()).isEqualTo(byTitle.id());
        assertThat(titleHits.get(0).path("matchedIn").asText()).isEqualTo("title");

        // 提问正文命中且精确转义 LIKE 通配符（% 与 _）。
        JsonNode wildcardHits = search("H2_POOL 100%");
        assertThat(wildcardHits).hasSize(1);
        assertThat(wildcardHits.get(0).path("session").path("id").asText()).isEqualTo(byPrompt.id());
        assertThat(wildcardHits.get(0).path("matchedIn").asText()).isEqualTo("prompt");
        assertThat(wildcardHits.get(0).path("snippet").asText()).contains("H2_POOL 100%");

        // 回复正文命中（大小写不敏感）且不返回隐藏会话。
        JsonNode outputHits = search("UPSTREAM_GATEWAY");
        assertThat(outputHits).hasSize(1);
        assertThat(outputHits.get(0).path("session").path("id").asText()).isEqualTo(byOutput.id());
        assertThat(outputHits.get(0).path("matchedIn").asText()).isEqualTo("output");
        assertThat(outputHits.get(0).path("snippet").asText()).contains("502 Bad Gateway");
    }

    private JsonNode search(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/sessions/search?q=" + encoded))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
