package dev.javaharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.GatedNamingProvider;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.WorkspaceTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:naming-api;DB_CLOSE_DELAY=-1",
        "harness.run-timeout=5s", "harness.model.api-key=", "harness.model.name=",
        "harness.model.base-url=https://api.openai.com/v1"
})
@Import(NamingApiIntegrationTest.FixtureConfiguration.class)
class NamingApiIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean GatedNamingProvider gatedProvider() { return new GatedNamingProvider(); }

        @Bean @Primary
        RunConfigurationSource testConfiguration(GatedNamingProvider provider) {
            return () -> new RunConfiguration(provider.info(), Duration.ofSeconds(5), 32000, 32,
                    workspace -> new RunConfiguration.Resources(provider, WorkspaceTools.disabled(), () -> {}));
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired SessionStore store;
    @Autowired GatedNamingProvider provider;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void resetGate() { provider.resetGate(); }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api" + path); }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String newSession(String title) throws Exception {
        var response = post("/sessions", Map.of("title", title));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    private String submitFirstRun(String sessionId, String prompt) throws Exception {
        var response = post("/sessions/" + sessionId + "/runs",
                Map.of("requestId", UUID.randomUUID().toString(), "prompt", prompt));
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).get("id").asText();
    }

    @Test
    void firstRunOnPlaceholderTitleTriggersAiNamingWithinTenCharacters() throws Exception {
        provider.releaseNaming.countDown();
        String id = newSession("新会话");
        submitFirstRun(id, "介绍一下这个项目");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.snapshot(id).session().title().equals(GatedNamingProvider.GENERATED_TITLE));
        String title = store.snapshot(id).session().title();
        assertThat(title.codePointCount(0, title.length())).isLessThanOrEqualTo(10);
        assertThat(provider.namingCalls).isEqualTo(1);
        // 命名只发生一次：后续任务不再触发。
        String runId = store.snapshot(id).runs().getFirst().id();
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.run(runId).status().active());
        submitFirstRun(id, "第二轮提问");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.snapshot(id).runs().stream().noneMatch(run -> run.status().active()));
        assertThat(provider.namingCalls).isEqualTo(1);
    }

    @Test
    void userTitleSetBeforeFirstMessageIsNeverOverwritten() throws Exception {
        String id = newSession("我的项目");
        String runId = submitFirstRun(id, "介绍一下这个项目");
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.run(runId).status().active());
        assertThat(provider.namingCalls).isZero();
        assertThat(store.snapshot(id).session().title()).isEqualTo("我的项目");
    }

    @Test
    void renameDuringNamingFlightWinsOverAiResult() throws Exception {
        String id = newSession("新会话");
        submitFirstRun(id, "介绍一下这个项目");
        // 命名已发起并卡在模型门内，此时用户手动改名。
        await().atMost(Duration.ofSeconds(5)).until(() -> provider.namingEntered.getCount() == 0);
        assertThat(post("/sessions/" + id + "/title", Map.of("title", "用户标题")).statusCode()).isEqualTo(200);
        provider.releaseNaming.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> provider.namingCompleted.getCount() == 0);
        // 条件重命名在 provider 返回后同线程立即执行，短暂等待其落库。
        Thread.sleep(200);
        assertThat(store.snapshot(id).session().title()).isEqualTo("用户标题");
    }

    @Test
    void conditionalRenameIsAtomicCompareAndSet() {
        String id = store.createSession("新会话").id();
        assertThat(store.renameSessionIfTitle(id, "新会话", "AI 标题")).isTrue();
        assertThat(store.snapshot(id).session().title()).isEqualTo("AI 标题");
        // 期望标题已不匹配：写入被拒绝，标题保持不变。
        assertThat(store.renameSessionIfTitle(id, "新会话", "另一个")).isFalse();
        assertThat(store.snapshot(id).session().title()).isEqualTo("AI 标题");
        assertThatThrownBy(() -> store.renameSessionIfTitle("missing", "新会话", "x")).isInstanceOf(HarnessException.class);
    }
}
