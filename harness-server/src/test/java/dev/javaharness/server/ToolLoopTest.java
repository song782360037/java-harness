package dev.javaharness.server;

import dev.javaharness.adapters.*;
import dev.javaharness.core.*;
import dev.javaharness.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ToolLoopTest {
    @TempDir Path directory;
    JdbcSessionStore store;
    JdbcTemplate jdbc;
    WorkspaceTools tools;
    List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    static final ModelInfo INFO = new ModelInfo("openai-compatible", "test", true);
    @BeforeEach void setup() throws Exception {
        open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Files.writeString(directory.resolve("App.java"), "class App {}\n");
        tools = new LocalWorkspaceTools(directory, Duration.ofSeconds(5));
    }
    void open(String url) {
        var ds = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        store = new JdbcSessionStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }
    @FunctionalInterface interface Reply { ModelTurn run(ModelRequest request, ModelProvider.Cancellation cancel) throws Exception; }
    ModelProvider model(Reply reply) {
        return new ModelProvider() {
            public ModelInfo info() { return INFO; }
            public void stream(ModelRequest request, Cancellation cancel, Consumer<String> text) { throw new AssertionError("Use complete"); }
            public ModelTurn complete(ModelRequest request, Cancellation cancel, Consumer<String> text) throws Exception {
                requests.add(request);
                assertThat(store.modelRequests(request.runId()).getLast()).isEqualTo(request);
                ModelTurn result = reply.run(request, cancel);
                if (!result.text().isEmpty()) text.accept(result.text());
                return result;
            }
        };
    }
    ToolCall read(String id) { return new ToolCall(id, "read_file", "{\"path\":\"App.java\"}"); }
    ModelTurn answer() { return new ModelTurn("分析完成", List.of()); }
    DefaultAgentEngine engine(ModelProvider model, WorkspaceTools workspace, int steps, int budget) {
        return new DefaultAgentEngine(store, model, Duration.ofSeconds(15), budget, workspace, steps);
    }
    Run submit(DefaultAgentEngine engine, String session) {
        return engine.submit(session, UUID.randomUUID().toString(), "分析项目", "NORMAL").run();
    }
    Run settled(Run run) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.run(run.id()).status().active());
        return store.run(run.id());
    }
    @Test void orderedToolsSnapshotsAndWholeHistorySurviveDatabaseReopen() {
        String url = "jdbc:h2:file:" + directory.resolve("history").toString().replace('\\', '/');
        open(url);
        ModelProvider model = model((request, cancel) -> request.step() == 1
                ? new ModelTurn("先读取代码", List.of(read("read"), new ToolCall("search", "search_files", "{\"query\":\"class App\"}"))) : answer());
        String session = store.createSession("tools").id();
        Run first;
        List<ModelMessage> transcript;
        try (var engine = engine(model, tools, 8, 32000)) {
            first = settled(submit(engine, session));
            assertThat(first.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(first.workspace()).isEqualTo(tools.workspace());
            assertThat(store.tools(first.id())).extracting(ToolExecution::status).containsExactly("COMPLETED", "COMPLETED");
            assertThat(store.tools(first.id())).extracting(ToolExecution::output).allMatch(text -> text.contains("class App"));
            assertThat(store.modelRequests(first.id())).extracting(ModelRequest::step).containsExactly(1, 2);
            transcript = store.conversation(first.id());
            assertThat(transcript).extracting(ModelMessage::role).containsExactly("user", "assistant", "tool", "tool", "assistant");
            ModelMessage.validate(transcript, false);
        }
        jdbc.execute("SHUTDOWN");
        open(url);
        try (var engine = engine(model((request, cancel) -> answer()), tools, 8, 32000)) {
            assertThat(store.conversation(first.id())).isEqualTo(transcript);
            assertThat(store.tools(first.id())).hasSize(2);
            Run next = settled(submit(engine, session));
            assertThat(next.context().includedTurns()).isEqualTo(1);
            assertThat(store.modelRequest(next.id()).messages().subList(0, 5)).isEqualTo(transcript);
            ModelMessage.validate(store.modelRequest(next.id()).messages(), true);
        }
        jdbc.execute("SHUTDOWN");
    }
    @Test void toolFailureIsPairedAndReturnedToModelForCorrection() {
        var model = model((request, cancel) -> switch (request.step()) {
            case 1 -> new ModelTurn("", List.of(new ToolCall("bad", "read_file", "{\"path\":\"../outside\"}")));
            case 2 -> {
                assertThat(request.messages().getLast().content()).contains("无法访问");
                yield new ModelTurn("", List.of(read("good")));
            }
            default -> answer();
        });
        try (var engine = engine(model, tools, 8, 32000)) {
            Run run = settled(submit(engine, store.createSession("correction").id()));
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(store.tools(run.id())).extracting(ToolExecution::status).containsExactly("FAILED", "COMPLETED");
            assertThat(store.modelRequests(run.id())).hasSize(3);
        }
    }
    @Test void finalStepDoesNotExecuteAnotherBatchAndBudgetPreventsAnotherModelCall() throws Exception {
        var model = model((request, cancel) -> new ModelTurn("", List.of(read("r" + request.step()))));
        try (var engine = engine(model, tools, 1, 32000)) {
            Run run = settled(submit(engine, store.createSession("limit").id()));
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.detail()).contains("tool_choice=none");
            assertThat(store.tools(run.id())).extracting(ToolExecution::status).containsExactly("SKIPPED");
            assertThat(requests).hasSize(1);
        }
        requests.clear();
        Files.writeString(directory.resolve("App.java"), "x".repeat(5000));
        try (var engine = engine(model, tools, 8, 4000)) {
            Run run = settled(submit(engine, store.createSession("budget").id()));
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.detail()).contains("上下文容量");
            assertThat(requests).hasSize(1);
        }
    }
    @Test void cancellationDuringToolDropsLateResultAndPreventsNextToolAndModel() throws Exception {
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        WorkspaceTools blocking = new WorkspaceTools() {
            public String workspace() { return tools.workspace(); }
            public List<ToolDefinition> definitions() { return tools.definitions(); }
            public Result execute(ToolCall call, ModelProvider.Cancellation cancel) throws Exception {
                executions.incrementAndGet(); reached.countDown();
                try { release.await(); } catch (InterruptedException ignored) { release.await(); }
                return new Result(true, "late result");
            }
        };
        try (var engine = engine(model((request, cancel) -> new ModelTurn("", List.of(read("a"), read("b")))), blocking, 8, 32000)) {
            Run run = submit(engine, store.createSession("cancel").id());
            assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue();
            engine.cancel(run.id());
            assertThat(store.run(run.id()).status()).isEqualTo(RunStatus.CANCELLING);
            release.countDown();
            assertThat(settled(run).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(executions).hasValue(1);
            assertThat(requests).hasSize(1);
            assertThat(store.tools(run.id())).allMatch(tool -> tool.status().equals("CANCELLED") && !tool.output().contains("late result"));
        } finally { release.countDown(); }
    }
    @Test void excessToolsDoNoWorkAndCanBeRequestedAgainInTheNextStep() {
        var executions = new AtomicInteger();
        WorkspaceTools counted = new WorkspaceTools() {
            public String workspace() { return tools.workspace(); }
            public List<ToolDefinition> definitions() { return tools.definitions(); }
            public Result execute(ToolCall call, ModelProvider.Cancellation cancel) {
                executions.incrementAndGet();
                return new Result(true, call.id());
            }
        };
        var model = model((request, cancel) -> switch (request.step()) {
            case 1 -> new ModelTurn("", java.util.stream.IntStream.range(0, 9).mapToObj(i -> read("r" + i)).toList());
            case 2 -> {
                assertThat(executions).hasValue(8);
                assertThat(request.messages().getLast().toolCallId()).isEqualTo("r8");
                assertThat(request.messages().getLast().content()).contains("未执行");
                yield new ModelTurn("", List.of(read("retry-r8")));
            }
            default -> answer();
        });
        try (var engine = engine(model, counted, 8, 32000)) {
            Run run = settled(submit(engine, store.createSession("tool-budget").id()));
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(executions).hasValue(9);
            assertThat(store.tools(run.id()).get(8).status()).isEqualTo("SKIPPED");
            assertThat(store.tools(run.id()).get(9).status()).isEqualTo("COMPLETED");
            assertThat(requests).hasSize(3);
            ModelMessage.validate(store.conversation(run.id()), false);
        }
    }

    @Test void recoveryInterruptsPendingAndRunningToolsWithoutReexecution() {
        Run run = store.submit(store.createSession("restart").id(), UUID.randomUUID().toString(), "prompt", "NORMAL", INFO).run();
        store.transition(run.id(), RunStatus.RUNNING, "");
        store.beginTools(run.id(), 1, List.of(read("a"), read("b")));
        store.updateTool(run.id(), "a", "RUNNING", "");
        try (var engine = engine(model((request, cancel) -> answer()), tools, 8, 32000)) {
            assertThat(store.run(run.id()).status()).isEqualTo(RunStatus.INTERRUPTED);
            assertThat(store.tools(run.id())).extracting(ToolExecution::status).containsExactly("INTERRUPTED", "INTERRUPTED");
            assertThat(requests).isEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 64})
    void longInvestigationPassesPreviousLimitsAndEndsWhenModelHasEnoughEvidence(int maxSteps) {
        var model = model((request, cancel) -> {
            assertThat(request.finalAnswerOnly()).isFalse();
            if (maxSteps == 0) assertThat(request.instructions()).contains("未设置固定轮次上限").doesNotContain("最后一次模型请求", "最后一步");
            return request.step() <= 40 ? new ModelTurn("", List.of(read("r" + request.step()))) : answer();
        });
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(30), 32000, tools, maxSteps)) {
            Run submitted = submit(engine, store.createSession("long-investigation").id());
            await().atMost(Duration.ofSeconds(20)).until(() -> !store.run(submitted.id()).status().active());
            Run run = store.run(submitted.id());
            assertThat(run.status()).as(run.detail()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.output()).isEqualTo("分析完成");
            assertThat(run.detail()).isEmpty();
            assertThat(requests).hasSize(41);
            assertThat(store.modelRequests(run.id()).getLast().step()).isEqualTo(41);
            assertThat(store.tools(run.id())).hasSize(40).allMatch(tool -> tool.status().equals("COMPLETED"));
            ModelMessage.validate(store.conversation(run.id()), false);
        }
    }

    @Test void unboundedInvestigationCanStillBeStoppedByUser() throws Exception {
        var reached = new CountDownLatch(1);
        var model = model((request, cancel) -> {
            if (request.step() < 10) return new ModelTurn("", List.of(read("r" + request.step())));
            assertThat(request.finalAnswerOnly()).isFalse();
            reached.countDown();
            new CountDownLatch(1).await();
            return answer();
        });
        try (var engine = engine(model, tools, 0, 32000)) {
            Run run = submit(engine, store.createSession("stop-unbounded").id());
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            engine.cancel(run.id());
            assertThat(settled(run).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(requests).hasSize(10);
            assertThat(store.modelRequests(run.id()).getLast().finalAnswerOnly()).isFalse();
        }
    }

    @Test void lastStepClosesExplorationAndPersistsAnHonestAnswerWithoutExtraRequests() {
        var model = model((request, cancel) -> {
            assertThat(request.context().characters()).isEqualTo(request.instructions().length()
                    + request.messages().stream().mapToInt(ModelMessage::characters).sum());
            if (request.finalAnswerOnly()) {
                assertThat(request.step()).isEqualTo(3);
                assertThat(request.instructions()).contains("停止工具调用", "缺少的证据", "不能虚构");
                return new ModelTurn("已确认 App.java 声明了 App 类；未找到主方法，不能确认程序入口。", List.of());
            }
            assertThat(request.instructions()).contains("search_files", "还可探索");
            return new ModelTurn("", List.of(read("r" + request.step())));
        });
        String session = store.createSession("final-answer").id();
        try (var engine = engine(model, tools, 3, 32000)) {
            Run run = settled(submit(engine, session));
            assertThat(run.status()).as(run.detail()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.output()).contains("不能确认程序入口");
            assertThat(run.detail()).contains("探索步数上限", "未确认");
            assertThat(store.tools(run.id())).hasSize(2).allMatch(tool -> tool.status().equals("COMPLETED"));
            assertThat(requests).hasSize(3);
            var persisted = store.modelRequests(run.id()).getLast();
            assertThat(persisted.finalAnswerOnly()).isTrue();
            assertThat(persisted.instructions()).isEqualTo(requests.getLast().instructions());
            assertThat(store.conversation(run.id())).allMatch(message -> !message.content().contains("这是最后一次模型请求"));
        }
    }

    @Test void emptyFinalResponseIsNotRecordedAsSuccessfulAnalysis() {
        try (var engine = engine(model((request, cancel) -> new ModelTurn("", List.of())), tools, 1, 32000)) {
            Run run = settled(submit(engine, store.createSession("empty-final").id()));
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.detail()).contains("收尾阶段未返回文字");
            assertThat(store.tools(run.id())).isEmpty();
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst().finalAnswerOnly()).isTrue();
        }
    }

    @Test void finalAnswerStillCooperatesWithCancellation() throws Exception {
        var reached = new CountDownLatch(1);
        var model = model((request, cancel) -> {
            if (!request.finalAnswerOnly()) return new ModelTurn("", List.of(read("r")));
            reached.countDown();
            new CountDownLatch(1).await();
            return answer();
        });
        try (var engine = engine(model, tools, 2, 32000)) {
            Run run = submit(engine, store.createSession("cancel-final").id());
            assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue();
            engine.cancel(run.id());
            assertThat(settled(run).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(requests).hasSize(2);
            assertThat(store.tools(run.id())).extracting(ToolExecution::status).containsExactly("COMPLETED");
        }
    }
    @Test void historyWithToolsIsDroppedAsAWholeAndOtherWorkspacesAreExcluded() throws Exception {
        Files.writeString(directory.resolve("App.java"), "x".repeat(5000));
        String session = store.createSession("history").id();
        var model = model((request, cancel) -> request.step() == 1 ? new ModelTurn("", List.of(read("r"))) : answer());
        try (var engine = engine(model, tools, 8, 32000)) {
            assertThat(settled(submit(engine, session)).status()).isEqualTo(RunStatus.COMPLETED);
        }
        try (var engine = engine(model((request, cancel) -> answer()), tools, 8, 4000)) {
            Run run = settled(submit(engine, session));
            assertThat(run.context().droppedTurns()).isEqualTo(1);
            assertThat(store.modelRequest(run.id()).messages()).hasSize(1);
        }
        try (var engine = engine(model((request, cancel) -> answer()), WorkspaceTools.disabled(), 8, 32000)) {
            Run run = settled(submit(engine, session));
            assertThat(run.context().excludedTurns()).isEqualTo(2);
            assertThat(store.modelRequest(run.id()).messages()).hasSize(1);
        }
    }
    @Test void incompleteOrDuplicateToolPairsCannotBecomeRequests() {
        var user = new ModelMessage("user", "prompt");
        var assistant = new ModelMessage("assistant", "", List.of(read("a")), null);
        assertThatThrownBy(() -> ModelMessage.validate(List.of(user, assistant), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelMessage.validate(List.of(user, assistant, new ModelMessage("tool", "result", List.of(), "wrong")), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelMessage.validate(List.of(user, new ModelMessage("assistant", "", List.of(read("a"), read("a")), null)), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void v3SnapshotsRemainReadableAfterV4Migration() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).target("3").load().migrate();
        var old = new JdbcTemplate(ds);
        old.update("INSERT INTO harness_session(id,title,created_at) VALUES ('s','legacy',CURRENT_TIMESTAMP)");
        old.update("""
                INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at,provider,model)
                VALUES ('r','s',?,'old','NORMAL','COMPLETED','answer','',CURRENT_TIMESTAMP,'openai-compatible','test')
                """, UUID.randomUUID().toString());
        old.update("INSERT INTO harness_model_request(run_id,request_json) VALUES ('r',?)", """
                {"runId":"r","model":"test","scenario":"NORMAL","messages":[{"role":"user","content":"old"}],
                 "context":{"includedTurns":0,"droppedTurns":0,"excludedTurns":0,"characters":3,"maxCharacters":32000}}
                """);
        Flyway.configure().dataSource(ds).load().migrate();
        var migrated = new JdbcSessionStore(old, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        assertThat(migrated.modelRequest("r").step()).isEqualTo(1);
        assertThat(migrated.modelRequests("r")).hasSize(1);
        assertThat(migrated.modelRequest("r").tools()).isEmpty();
        assertThat(migrated.modelRequest("r").instructions()).isEmpty();
        assertThat(migrated.modelRequest("r").finalAnswerOnly()).isFalse();
        assertThat(migrated.run("r").workspace()).isEmpty();
        assertThat(migrated.conversation("r")).containsExactly(new ModelMessage("user", "old"), new ModelMessage("assistant", "answer"));
    }
}
