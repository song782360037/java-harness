package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.javaharness.adapters.redaction.SecretRedactor;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.spi.memory.LongTermMemory;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelTurn;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.tool.ToolExecution;
import dev.javaharness.spi.tool.ToolInterceptor;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ToolInterceptorTest {
    static final String SECRET = "sk-abcdefghijklmnopqrstuvwxyz0123456789";
    JdbcSessionStore store;
    List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    List<String> executed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        store = new JdbcSessionStore(
                new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    /** Two echo tools: each returns a leaked key, so redaction is visible in the stored and resent result. */
    WorkspaceTools tools() {
        return new WorkspaceTools() {
            public String workspace() {
                return "";
            }

            public List<ToolDefinition> definitions() {
                return List.of(
                        new ToolDefinition("echo", "echo", Map.of("type", "object")),
                        new ToolDefinition("danger", "danger", Map.of("type", "object")));
            }

            public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) {
                executed.add(call.name());
                return new Result(
                        true, "OPENAI_API_KEY=" + SECRET + "\nAuthorization: Bearer abcdefghijklmnopqrstuvwx");
            }
        };
    }

    ModelProvider model() {
        return new ModelProvider() {
            public ModelInfo info() {
                return new ModelInfo("openai-compatible", "test", true);
            }

            public void stream(ModelRequest request, Cancellation cancel, Consumer<String> text) {
                throw new AssertionError("Use complete");
            }

            public ModelTurn complete(ModelRequest request, Cancellation cancel, Consumer<String> text) {
                requests.add(request);
                return request.step() == 1
                        ? new ModelTurn("", List.of(new ToolCall("e", "echo", "{}"), new ToolCall("d", "danger", "{}")))
                        : new ModelTurn("完成", List.of());
            }
        };
    }

    @Test
    void interceptorsRefuseBeforeAndRedactAfterEveryTool() {
        WorkspaceTools tools = tools();
        var config = new RunConfiguration(
                model().info(),
                Duration.ofSeconds(15),
                32000,
                RunConfiguration.outputFor(32000),
                8,
                TokenCounter.approximate(),
                workspace -> new RunConfiguration.Resources(model(), tools, () -> {}));
        ToolInterceptor guard = new ToolInterceptor() {
            public String before(String runId, ToolCall call) {
                return call.name().equals("danger") ? "策略禁止调用 danger" : null;
            }
        };
        try (var engine = new DefaultAgentEngine(
                store, () -> config, LongTermMemory.disabled(), List.of(guard, new SecretRedactor()))) {
            Run run = engine.submit(
                            store.createSession("guard").id(), UUID.randomUUID().toString(), "go", "NORMAL")
                    .run();
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> !store.run(run.id()).status().active());
            assertThat(store.run(run.id()).status())
                    .as(store.run(run.id()).detail())
                    .isEqualTo(RunStatus.COMPLETED);
            assertThat(executed).containsExactly("echo");
            var stored = store.tools(run.id());
            assertThat(stored).extracting(ToolExecution::status).containsExactly("COMPLETED", "FAILED");
            assertThat(stored.get(0).output())
                    .doesNotContain(SECRET)
                    .contains("OPENAI_API_KEY=[REDACTED]", "Bearer [REDACTED]");
            assertThat(stored.get(1).output()).isEqualTo("策略禁止调用 danger");
            String resent = requests.getLast().messages().toString();
            assertThat(resent).doesNotContain(SECRET).contains("[REDACTED]", "策略禁止调用 danger");
        }
    }

    @Test
    void redactorMasksKnownCredentialShapesOnly() {
        var redactor = new SecretRedactor();
        String text = String.join(
                "\n",
                "ghp_" + "a".repeat(36),
                "AKIA" + "ABCDEFGHIJKLMNOP",
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop",
                "task-runner sk-short desk-top");
        var result = redactor.after("run", new ToolCall("c", "read_file", "{}"), new WorkspaceTools.Result(true, text));
        assertThat(result.output().split("\n"))
                .containsExactly(
                        "[REDACTED]",
                        "[REDACTED]",
                        "[REDACTED]",
                        "[REDACTED]",
                        "task-runner sk-short desk-top",
                        "[部分凭据已替换为 [REDACTED]；修改文件时不要写入该占位符，含凭据的行保持原样]");
        // A key truncated before its END line is masked to the end of the output.
        assertThat(redactor.after(
                                "run",
                                new ToolCall("c", "read_file", "{}"),
                                new WorkspaceTools.Result(true, "key:\n-----BEGIN PRIVATE KEY-----\nMIIEvQ\nMIIEvR"))
                        .output())
                .startsWith("key:\n[REDACTED]\n[部分凭据")
                .doesNotContain("MIIE");
        var plain = new WorkspaceTools.Result(false, "没有密钥");
        assertThat(redactor.after("run", new ToolCall("c", "read_file", "{}"), plain))
                .isSameAs(plain);
    }
}
