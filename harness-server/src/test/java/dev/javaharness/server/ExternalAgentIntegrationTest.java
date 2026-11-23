package dev.javaharness.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javaharness.server.service.ExternalAgentCatalog.Profile;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:external-agent-tests;DB_CLOSE_DELAY=-1",
            "harness.model.api-key=",
            "harness.model.name=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class ExternalAgentIntegrationTest {
    @Autowired
    TestRestTemplate http;

    @Autowired
    SessionStore sessions;

    @TempDir
    Path cwd;

    @Test
    void externalRunsDoNotNeedModelAndReuseOnlyTheirOwnSession() throws Exception {
        var agent = configure("fixture");
        assertThat(http.postForEntity("/api/external-agents/test", agent, JsonNode.class)
                        .getBody()
                        .path("resumable")
                        .asBoolean())
                .isTrue();
        var chat = sessions.createSession("Fixture", cwd.toString());
        String id = chat.id();
        var input = prompt(agent, "first");
        var first = http.postForEntity(runs(id), input, JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String run = first.getBody().path("id").asText();
        terminal(run, RunStatus.COMPLETED);
        assertThat(sessions.run(run).output()).isEqualTo("first");
        assertThat(sessions.tools(run)).hasSize(1);
        assertThat(sessions.snapshot(id).session().modelProfileId()).isEqualTo(chat.modelProfileId());
        assertThat(http.postForEntity(runs(id), input, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(sessions.snapshot(id).runs()).hasSize(1);
        String next = start(id, agent, "second");
        terminal(next, RunStatus.COMPLETED);
        assertThat(sessions.run(next).output()).isEqualTo("second:loaded");
        assertThat(sessions.contextHistory(next)).isEmpty();
        http.postForEntity(
                "/api/external-agents/" + agent.id() + "/remove", Map.of("revision", agent.revision()), Void.class);
        assertThat(http.postForEntity(runs(id), input, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.postForEntity(runs(id), prompt(agent, "third"), JsonNode.class)
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
    }

    @Test
    void deliversDecisionsAndCleansUpCancelledOrCrashedRuns() throws Exception {
        var agent = configure("interactive");
        String id = sessions.createSession("Interactive", cwd.toString()).id();
        String permission = start(id, agent, "permission");
        String approval = awaitInteraction(permission);
        assertThat(answer(permission, approval, "forever")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(answer(permission, approval, "no")).isEqualTo(HttpStatus.OK);
        terminal(permission, RunStatus.COMPLETED);
        assertThat(sessions.run(permission).output()).isEqualTo("no");
        String question = start(id, agent, "question");
        String questionId = awaitInteraction(question);
        assertThat(answer(question, questionId, Map.of("choice", "invalid"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(answer(question, questionId, Map.of("choice", "b"))).isEqualTo(HttpStatus.OK);
        terminal(question, RunStatus.COMPLETED);
        assertThat(sessions.run(question).output()).isEqualTo("b");
        String waiting = start(id, agent, "wait");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> sessions.run(waiting).output().equals("waiting"));
        assertThat(http.postForEntity("/api/runs/" + waiting + "/cancel", Map.of(), JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        terminal(waiting, RunStatus.CANCELLED);
        assertThat(sessions.run(waiting).output()).isEqualTo("waiting");
        String crashed = start(id, agent, "crash");
        terminal(crashed, RunStatus.FAILED);
        assertThat(sessions.run(crashed).detail()).contains("simulated adapter crash");
        String rpcError = start(id, agent, "rpc-error");
        terminal(rpcError, RunStatus.FAILED);
        assertThat(sessions.run(rpcError).detail()).contains("upstream quota exceeded");
        String crlf = start(id, agent, "crlf-blank");
        terminal(crlf, RunStatus.COMPLETED);
        assertThat(sessions.run(crlf).output()).isEqualTo("crlf-ok");
        terminal(start(id, agent, "after crash"), RunStatus.COMPLETED);
    }

    @Test
    void stoppingAnAdapterThatDoesNotReadStdinReleasesItsProcess() throws Exception {
        var agent = configure("blocked", "stall");
        String id = sessions.createSession("Blocked", cwd.toString()).id();
        String run = start(id, agent, "a".repeat(90_000));
        Path marker = cwd.resolve("stalled");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> java.nio.file.Files.isRegularFile(marker) && java.nio.file.Files.size(marker) > 0);
        long pid = Long.parseLong(java.nio.file.Files.readString(marker));
        assertThat(http.postForEntity("/api/runs/" + run + "/cancel", Map.of(), JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        terminal(run, RunStatus.CANCELLED);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }

    private Profile configure(String alias, String... extra) throws Exception {
        String bin = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String executable = Path.of(System.getProperty("java.home"), "bin", bin).toString();
        String classpath = String.join(
                java.io.File.pathSeparator,
                classPath(FakeAcpAgent.class),
                classPath(com.fasterxml.jackson.databind.ObjectMapper.class),
                classPath(com.fasterxml.jackson.core.JsonFactory.class),
                classPath(com.fasterxml.jackson.annotation.JsonProperty.class));
        var arguments = new java.util.ArrayList<>(List.of("-cp", classpath, FakeAcpAgent.class.getName()));
        arguments.addAll(List.of(extra));
        var form = new Profile("", alias, "Fixture", "custom", executable, arguments, false, true, 0);
        var result = http.postForEntity("/api/external-agents", form, Profile.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        return result.getBody();
    }

    private static String classPath(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
    }

    private String start(String session, Profile agent, String text) {
        var result = http.postForEntity(runs(session), prompt(agent, text), JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return result.getBody().path("id").asText();
    }

    private Map<String, String> prompt(Profile agent, String text) {
        return Map.of(
                "requestId",
                UUID.randomUUID().toString(),
                "prompt",
                "/" + agent.alias() + " " + text,
                "externalAgentId",
                agent.id());
    }

    private static String runs(String id) {
        return "/api/sessions/" + id + "/runs";
    }

    private static String interactions(String run) {
        return "/api/runs/" + run + "/external-interactions";
    }

    private String awaitInteraction(String run) {
        await().atMost(Duration.ofSeconds(10))
                .until(() ->
                        http.getForObject(interactions(run), JsonNode.class).size() == 1);
        return http.getForObject(interactions(run), JsonNode.class)
                .path(0)
                .path("id")
                .asText();
    }

    private HttpStatus answer(String run, String id, Object value) {
        return (HttpStatus) http.postForEntity(
                        interactions(run) + "/" + id, Map.of("action", "accept", "value", value), JsonNode.class)
                .getStatusCode();
    }

    private void terminal(String run, RunStatus expected) {
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !sessions.run(run).status().active());
        assertThat(sessions.run(run).status())
                .withFailMessage(sessions.run(run).detail())
                .isEqualTo(expected);
    }
}
