package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.automation.AutomationStore;
import dev.javaharness.adapters.group.GroupStore;
import dev.javaharness.server.service.AutomationService;
import dev.javaharness.server.service.DesktopUpdateGate;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:desktop-update-api;DB_CLOSE_DELAY=-1",
            "harness.desktop.update-token=desktop-test-token",
            "harness.automations.enabled=false",
            "harness.session-naming.enabled=false"
        })
class DesktopUpdateApiIntegrationTest {
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
    DesktopUpdateGate gate;

    @Autowired
    SessionStore sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void recover() {
        gate.release();
        sessions.recoverInterruptedRuns();
        jdbc.update("UPDATE harness_group_round SET status='STOPPED' WHERE status='RUNNING'");
        jdbc.update("UPDATE harness_command SET status='INTERRUPTED'");
    }

    @Test
    void onlyDesktopTokenCanPrepareOrRelease() throws Exception {
        assertThat(post("/desktop/update/prepare", "", "{}").statusCode()).isEqualTo(403);
        assertThat(post("/desktop/update/prepare", "wrong", "{}").statusCode()).isEqualTo(403);
        assertThat(post("/desktop/update/prepare", "desktop-test-token", "{}").statusCode())
                .isEqualTo(204);
        assertThat(post("/desktop/update/release", "wrong", "{}").statusCode()).isEqualTo(403);
        assertThat(post("/sessions", "", "{\"title\":\"blocked\"}").statusCode())
                .isEqualTo(503);
        assertThat(client.send(
                                HttpRequest.newBuilder(uri("/sessions")).GET().build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);
        assertThat(post("/desktop/update/release", "desktop-test-token", "{}").statusCode())
                .isEqualTo(204);
        assertThat(post("/sessions", "", "{\"title\":\"available\"}").statusCode())
                .isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api;x/sessions", "/%61pi/sessions"})
    void equivalentApiPathsCannotBypassPreparedState(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"path alias\"}"))
                .build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(201);
        assertThat(post("/desktop/update/prepare", "desktop-test-token", "{}").statusCode())
                .isEqualTo(204);
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(503);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "RUNNING", "CANCELLING"})
    void activeWorkInAnySessionPreventsPreparation(RunStatus status) throws Exception {
        var session = sessions.createSession("background session");
        var run = sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        "test",
                        "NORMAL",
                        new ModelInfo("test", "test", true))
                .run();
        if (status != RunStatus.QUEUED) sessions.transition(run.id(), status, "");
        var response = post("/desktop/update/prepare", "desktop-test-token", "{}");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(sessions.run(run.id()).status()).isEqualTo(status);
        assertThat(gate.enter()).isTrue();
        gate.leave();
    }

    @Test
    void groupPlanningWithoutMemberRunsPreventsPreparation() throws Exception {
        var response =
                post("/groups", "", "{\"name\":\"planning\",\"mode\":\"TASKS\",\"maxTurns\":3,\"templates\":[]}");
        assertThat(response.statusCode()).isEqualTo(201);
        String id = json.readTree(response.body()).path("group").path("id").asText();
        new GroupStore(jdbc).startRound(id);
        assertThat(post("/desktop/update/prepare", "desktop-test-token", "{}").statusCode())
                .isEqualTo(409);
    }

    @Test
    void commandStillRunningAfterRunSettlementPreventsPreparation() throws Exception {
        var session = sessions.createSession("command");
        var run = sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        "test",
                        "NORMAL",
                        new ModelInfo("test", "test", true))
                .run();
        sessions.transition(run.id(), RunStatus.CANCELLING, "");
        sessions.transition(run.id(), RunStatus.CANCELLED, "");
        jdbc.update(
                "INSERT INTO harness_command(id,run_id,call_id,command_text,directory,shell,purpose,timeout_seconds,status,created_at) VALUES (?,?,?,?,?,?,?,?,'RUNNING',CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),
                run.id(),
                "call",
                "echo test",
                directory.toString(),
                "powershell",
                "test",
                30);
        assertThat(post("/desktop/update/prepare", "desktop-test-token", "{}").statusCode())
                .isEqualTo(409);
    }

    @Test
    void inFlightSubmissionExcludesPreparationAndPreparedStateExcludesNewSubmission() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var request = worker.submit(() -> {
                assertThat(gate.enter()).isTrue();
                entered.countDown();
                try {
                    assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                } finally {
                    gate.leave();
                }
                return null;
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(gate::prepare).isInstanceOf(HarnessException.class);
            } finally {
                finish.countDown();
            }
            request.get(5, TimeUnit.SECONDS);
        }
        gate.prepare();
        assertThat(gate.enter()).isFalse();
        gate.release();
        assertThat(gate.enter()).isTrue();
        gate.leave();
    }

    @Test
    void preparedStateSkipsAutomaticTriggersWithoutConsumingSchedule() {
        var storage = mock(AutomationStore.class);
        var service = new AutomationService(storage, null, null, null, null, Clock.systemUTC(), ZoneId.of("UTC"));
        service.useUpdateGate(gate);
        gate.prepare();
        service.tick();
        verifyNoInteractions(storage);
        gate.release();
        service.tick();
        verify(storage).due(any());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api" + path);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .header("X-Harness-Desktop-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
