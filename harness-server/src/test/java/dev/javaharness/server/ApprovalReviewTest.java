package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.approval.ApprovalPolicy.Verdict;
import dev.javaharness.adapters.approval.ApprovalReviewer;
import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.workspace.CommandExecution;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ApprovalReviewTest {
    static final String LOW = """
            {"model":"jev-1.14.0","answers":{
              "risk":{"type":"score","score":0.1,"confidence":0.9,"probabilities":{"0":0.9,"1":0.08,"2":0.01,"3":0.01}},
              "authorization":{"type":"score","score":2.9,"confidence":0.9,"probabilities":{"0":0.01,"1":0.02,"2":0.07,"3":0.9}},
              "category":{"type":"choice","choice":"routine","confidence":0.9,"probabilities":{"routine":0.95,"destructive":0.05}}},
             "usage":{"input_tokens":321,"output_tokens":0}}""";
    static final String CRITICAL = """
            {"model":"jev-1.14.0","answers":{
              "risk":{"type":"score","score":2.9,"confidence":0.9,"probabilities":{"0":0.01,"1":0.01,"2":0.08,"3":0.9}},
              "authorization":{"type":"score","score":0.1,"confidence":0.9,"probabilities":{"0":0.9,"1":0.08,"2":0.01,"3":0.01}},
              "category":{"type":"choice","choice":"exfiltration","confidence":0.9,"probabilities":{"routine":0.05,"exfiltration":0.95}}},
             "usage":{"input_tokens":300,"output_tokens":0}}""";
    static final String UNSURE = """
            {"model":"jev-1.14.0","answers":{
              "risk":{"type":"score","score":1.5,"confidence":0.1,"probabilities":{"0":0.5,"1":0.0,"2":0.0,"3":0.5}},
              "authorization":{"type":"score","score":2,"confidence":0.3,"probabilities":{"0":0.2,"1":0.2,"2":0.3,"3":0.3}},
              "category":{"type":"choice","choice":"routine","confidence":0.2,"probabilities":{"routine":0.5,"destructive":0.5}}},
             "usage":{"input_tokens":300,"output_tokens":0}}""";

    @TempDir
    Path directory;

    JdbcTemplate jdbc;
    JdbcSessionStore sessions;
    CommandService commands;
    ApprovalReviewer reviewer;
    HttpServer jev;
    ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<String> response = new AtomicReference<>(LOW);
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<CountDownLatch> gate = new AtomicReference<>();
    final CopyOnWriteArrayList<JsonNode> bodies = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> keys = new CopyOnWriteArrayList<>();
    final AtomicReference<ApprovalReviewer.Judgement> llm = new AtomicReference<>();
    Run run;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx);
        commands = new CommandService(jdbc, tx, sessions);
        reviewer = new ApprovalReviewer(
                jdbc, new LocalSecretCipher(directory.resolve("key")), (profile, state, timeout, cancellation) -> {
                    var value = llm.get();
                    if (value == null) throw new IllegalStateException("LLM unavailable");
                    return value;
                });
        commands.useReviewer(reviewer);
        jev = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jev.createContext("/v1/systemone", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                keys.add(exchange.getRequestHeaders().getFirst("Authorization"));
                bodies.add(json.readTree(exchange.getRequestBody()));
                var wait = gate.get();
                if (wait != null) wait.await(10, TimeUnit.SECONDS);
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        jev.start();
        var session = sessions.createSession("review", directory.toRealPath().toString());
        run = sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        "运行构建并告诉我结果",
                        "NORMAL",
                        new ModelInfo("test", "test", true),
                        ModelSelection.DEFAULT,
                        new ModelSelection("default", ""),
                        "{}",
                        ApprovalMode.SMART)
                .run();
        run = sessions.transition(run.id(), RunStatus.RUNNING, "");
    }

    @AfterEach
    void close() throws Exception {
        var wait = gate.get();
        if (wait != null) wait.countDown();
        jev.stop(0);
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    void useJev(String reviewers, int timeout) {
        var settings = reviewer.settings();
        reviewer.save(new ApprovalReviewer.SaveSettings(
                settings.revision(),
                reviewers,
                "http://127.0.0.1:" + jev.getAddress().getPort(),
                "jev-1.14.0",
                settings.jevApiKeyConfigured() ? "" : "ts-test-key",
                false,
                timeout,
                reviewers.contains("LLM") ? "default" : "",
                5));
    }

    CommandExecution propose(String command) {
        return commands.propose(run.id(), UUID.randomUUID().toString(), run.workspace(), command, ".", "构建验证", 10);
    }

    CommandExecution saved(CommandExecution value) {
        return commands.list(run.id()).stream()
                .filter(c -> c.id().equals(value.id()))
                .findFirst()
                .orElseThrow();
    }

    Future<WorkspaceTools.Result> execute(CommandExecution value) {
        return workers.submit(() -> commands.awaitAndExecute(value, () -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        }));
    }

    @Test
    void settingsValidateAndNeverReturnTheKey() {
        assertThat(reviewer.settings().reviewers()).isEqualTo("NONE");
        assertThatThrownBy(() -> reviewer.save(
                        new ApprovalReviewer.SaveSettings(0, "JEV", "", "", "", false, 10, "", 20)))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("API Key");
        assertThatThrownBy(() -> reviewer.save(
                        new ApprovalReviewer.SaveSettings(0, "LLM", "", "", "", false, 10, "", 20)))
                .hasMessageContaining("评审模型");
        assertThatThrownBy(() -> reviewer.save(
                        new ApprovalReviewer.SaveSettings(0, "NONE", "http://example.com", "", "", false, 10, "", 20)))
                .hasMessageContaining("HTTPS");
        useJev("JEV", 5);
        var settings = reviewer.settings();
        assertThat(settings.jevApiKeyConfigured()).isTrue();
        assertThat(settings.toString()).doesNotContain("ts-test-key");
        assertThat(jdbc.queryForObject("SELECT settings FROM harness_approval_reviewer", String.class))
                .doesNotContain("ts-test-key");
        assertThatThrownBy(() -> reviewer.save(
                        new ApprovalReviewer.SaveSettings(0, "NONE", "", "", "", false, 10, "", 20)))
                .hasMessageContaining("已被修改");
        assertThatThrownBy(() -> reviewer.save(new ApprovalReviewer.SaveSettings(
                        settings.revision(), "JEV", "https://jev.example.com", "", "", false, 10, "", 20)))
                .hasMessageContaining("重新填写");
        var cleared = reviewer.save(new ApprovalReviewer.SaveSettings(
                settings.revision(), "NONE", "", "", "", true, 10, "", 20));
        assertThat(cleared.jevApiKeyConfigured()).isFalse();
    }

    @Test
    void jevApprovesConfidentRoutineCommandsAndSendsOnlyTrustedContext() throws Exception {
        useJev("JEV", 5);
        jdbc.update(
                "INSERT INTO harness_tool(run_id,step,call_id,name,arguments,status,output,ordinal) VALUES (?,1,'c1','read_file',?,'COMPLETED','SECRET-OUTPUT',0)",
                run.id(),
                "{\"path\":\"pom.xml\"}");
        var value = propose("echo hi");
        assertThat(value.status()).isEqualTo("REVIEWING");
        var result = execute(value).get(20, TimeUnit.SECONDS);
        assertThat(result.success()).isTrue();
        assertThat(saved(value).decidedBy()).isEqualTo("JUDGE");
        assertThat(saved(value).status()).isEqualTo("COMPLETED");
        assertThat(keys).containsExactly("Bearer ts-test-key");
        JsonNode body = bodies.getFirst();
        assertThat(body.path("model").asText()).isEqualTo("jev-1.14.0");
        assertThat(body.path("questions").path("risk").path("criteria")).hasSize(4);
        assertThat(body.path("state").path("user_requests").get(0).asText()).isEqualTo("运行构建并告诉我结果");
        assertThat(body.path("state").path("trajectory").get(0).path("tool").asText()).isEqualTo("read_file");
        assertThat(body.path("state").path("action").path("command").asText()).isEqualTo("echo hi");
        assertThat(body.path("state").path("action").path("directory").asText()).isEqualTo(".");
        assertThat(body.toString()).doesNotContain("SECRET-OUTPUT").doesNotContain(directory.toString().replace("\\", "\\\\"));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM harness_usage_ledger WHERE provider='typesafe-jev' AND input_tokens=321",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void criticalIsRejectedAndUncertainOrFailingReviewsEscalate() throws Exception {
        useJev("JEV", 1);
        response.set(CRITICAL);
        var rejected = propose("echo secret");
        var output = execute(rejected).get(20, TimeUnit.SECONDS);
        assertThat(output.success()).isFalse();
        assertThat(saved(rejected).status()).isEqualTo("REJECTED");
        assertThat(saved(rejected).detail()).contains("Jev", "critical");
        assertThat(saved(rejected).stdout()).isEmpty();

        response.set(UNSURE);
        var unsure = propose("echo maybe");
        var waiting = execute(unsure);
        await().atMost(Duration.ofSeconds(10)).until(() -> saved(unsure).status().equals("PENDING"));
        assertThat(saved(unsure).detail()).contains("需要人工确认");
        commands.reject(unsure.id());
        assertThat(waiting.get(10, TimeUnit.SECONDS).success()).isFalse();

        status.set(500);
        var failed = propose("echo broken");
        var failing = execute(failed);
        await().atMost(Duration.ofSeconds(10)).until(() -> saved(failed).status().equals("PENDING"));
        assertThat(saved(failed).detail()).contains("评审失败");
        commands.stop(failed.id());
        failing.get(10, TimeUnit.SECONDS);

        status.set(200);
        response.set(LOW);
        gate.set(new CountDownLatch(1));
        var slow = propose("echo slow");
        var timed = execute(slow);
        await().atMost(Duration.ofSeconds(10)).until(() -> saved(slow).status().equals("PENDING"));
        assertThat(saved(slow).detail()).contains("超时");
        gate.get().countDown();
        commands.stop(slow.id());
        timed.get(10, TimeUnit.SECONDS);
        assertThat(jdbc.queryForList("SELECT decision FROM harness_approval_review ORDER BY created_at", String.class))
                .containsExactly("REJECT", "ASK", "ASK", "ASK");
    }

    @Test
    void userDecisionDuringReviewWinsOverALateVerdict() throws Exception {
        useJev("JEV", 5);
        response.set(CRITICAL);
        gate.set(new CountDownLatch(1));
        var value = propose("echo hi");
        var future = execute(value);
        await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() == 1);
        assertThat(commands.approve(value.id()).decidedBy()).isEqualTo("USER");
        // The reviewer is still blocked; the approval must take effect without waiting for its timeout.
        assertThat(future.get(4, TimeUnit.SECONDS).success()).isTrue();
        gate.get().countDown();
        assertThat(saved(value).decidedBy()).isEqualTo("USER");
        assertThat(saved(value).status()).isEqualTo("COMPLETED");
    }

    @Test
    void llmStageHandlesJevUncertaintyAndRepeatedRejectionsTripTheBreaker() throws Exception {
        useJev("JEV_LLM", 5);
        response.set(UNSURE);
        llm.set(new ApprovalReviewer.Judgement(Verdict.APPROVE, "low", "high", null, null, "常规构建", "gpt-test", 10, 5));
        var approved = propose("echo build");
        assertThat(execute(approved).get(20, TimeUnit.SECONDS).success()).isTrue();
        assertThat(saved(approved).decidedBy()).isEqualTo("JUDGE");
        assertThat(jdbc.queryForList("SELECT stage FROM harness_approval_review ORDER BY created_at", String.class))
                .containsExactly("JEV", "LLM");

        response.set(CRITICAL);
        for (int i = 0; i < 3; i++) {
            var rejected = propose("echo bad " + i);
            execute(rejected).get(20, TimeUnit.SECONDS);
            assertThat(saved(rejected).status()).isEqualTo("REJECTED");
        }
        int before = calls.get();
        var blocked = propose("echo again");
        var waiting = execute(blocked);
        await().atMost(Duration.ofSeconds(10)).until(() -> saved(blocked).status().equals("PENDING"));
        assertThat(saved(blocked).detail()).contains("拒绝过多");
        assertThat(calls.get()).isEqualTo(before);
        commands.stop(blocked.id());
        waiting.get(10, TimeUnit.SECONDS);
    }

    @Test
    void rulesAndHardGuardsAreNeverSentToReviewers() {
        useJev("JEV", 5);
        assertThat(propose("git status").status()).isEqualTo("APPROVED");
        var guarded = propose("rm -rf /");
        assertThat(guarded.status()).isEqualTo("PENDING");
        assertThat(guarded.detail()).contains("高危");
        assertThat(calls.get()).isZero();
    }
}
