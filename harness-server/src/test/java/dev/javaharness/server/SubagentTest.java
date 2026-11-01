package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.workspace.LocalWorkspaceTools;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.ModelTurn;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.run.SubagentRun;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.tool.ToolExecution;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class SubagentTest {
    @TempDir
    Path directory;

    JdbcSessionStore store;
    WorkspaceTools tools;
    List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    // (runId, callId, name) of every call that reached the proposal/command tools.
    List<List<String>> routed = new CopyOnWriteArrayList<>();
    static final ModelInfo INFO = new ModelInfo("openai-compatible", "test", true);

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        store = new JdbcSessionStore(new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
        Files.writeString(directory.resolve("App.java"), "class App {}\n");
        tools = withReviewTools(new LocalWorkspaceTools(directory, Duration.ofSeconds(5)));
    }

    /** Local read tools plus recording stand-ins for the proposal and command tools. */
    WorkspaceTools withReviewTools(WorkspaceTools local) {
        var extra = List.of(
                new ToolDefinition("propose_file_change", "propose", Map.of("type", "object")),
                new ToolDefinition("propose_command", "command", Map.of("type", "object")));
        return new WorkspaceTools() {
            public String workspace() {
                return local.workspace();
            }

            public List<ToolDefinition> definitions() {
                return java.util.stream.Stream.concat(local.definitions().stream(), extra.stream())
                        .toList();
            }

            public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
                return execute("", call, cancellation);
            }

            public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation)
                    throws Exception {
                if (!call.name().startsWith("propose_")) return local.execute(runId, call, cancellation);
                routed.add(List.of(runId, call.id(), call.name()));
                return new Result(true, "提案已保存");
            }
        };
    }

    @FunctionalInterface
    interface Reply {
        ModelTurn run(ModelRequest request, ModelProvider.Cancellation cancel) throws Exception;
    }

    ModelProvider model(Reply reply) {
        return new ModelProvider() {
            public ModelInfo info() {
                return INFO;
            }

            public void stream(ModelRequest request, Cancellation cancel, Consumer<String> text) {
                throw new AssertionError("Use complete");
            }

            public ModelTurn complete(ModelRequest request, Cancellation cancel, Consumer<String> text)
                    throws Exception {
                requests.add(request);
                ModelTurn result = reply.run(request, cancel);
                if (!result.text().isEmpty()) text.accept(result.text());
                return result;
            }
        };
    }

    static boolean child(ModelRequest request) {
        return request.instructions().contains("委派的子智能体");
    }

    static String task(ModelRequest request) {
        return request.messages().getFirst().content();
    }

    static ToolCall delegate(String id, String agent, String prompt) throws Exception {
        String json = new ObjectMapper()
                .writeValueAsString(Map.of("agent", agent, "description", "调查", "prompt", prompt));
        return new ToolCall(id, "delegate_task", json);
    }

    static ModelTurn answer(String text) {
        return new ModelTurn(text, List.of());
    }

    DefaultAgentEngine engine(ModelProvider model, Duration timeout) {
        return new DefaultAgentEngine(store, model, timeout, 32000, tools, 8);
    }

    Run submit(DefaultAgentEngine engine, String session) {
        return engine.submit(session, UUID.randomUUID().toString(), "分析项目", "NORMAL", ModelSelection.DEFAULT)
                .run();
    }

    Run settled(String runId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> !store.run(runId).status().active());
        return store.run(runId);
    }

    static List<String> names(ModelRequest request) {
        return request.tools().stream().map(ToolDefinition::name).toList();
    }

    /** Block until the run is stopped, like a slow upstream stream that honours cancellation. */
    static ModelTurn blockUntilStopped(ModelProvider.Cancellation cancel) throws Exception {
        while (true) {
            cancel.check();
            Thread.sleep(20);
        }
    }

    @Test
    void parallelDelegationsReturnInCallOrderAndStayOutOfHistory() throws Exception {
        var bothStarted = new CountDownLatch(2);
        String quoted = "检查 \"存储\" 层\n第二行 ✓";
        var model = model((request, cancel) -> {
            if (child(request)) {
                bothStarted.countDown();
                // Both children must be inside their model call at the same time.
                assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
                return answer("结论-" + task(request));
            }
            if (request.step() == 1 && request.messages().size() == 1)
                return new ModelTurn(
                        "并行调查", List.of(delegate("d1", "explore", quoted), delegate("d2", "general", "B")));
            return answer("汇总完成");
        });
        String session = store.createSession("subagents").id();
        try (var engine = engine(model, Duration.ofSeconds(60))) {
            Run parent = settled(submit(engine, session).id());
            assertThat(parent.status()).isEqualTo(RunStatus.COMPLETED);
            List<ModelMessage> conversation = store.conversation(parent.id());
            assertThat(conversation)
                    .extracting(ModelMessage::role)
                    .containsExactly("user", "assistant", "tool", "tool", "assistant");
            assertThat(conversation.get(2).content()).contains("子智能体 explore 已完成", "结论-" + quoted);
            assertThat(conversation.get(3).content()).contains("子智能体 general 已完成", "结论-B");

            List<SubagentRun> children = store.subagents(parent.id());
            assertThat(children).extracting(SubagentRun::parentCallId).containsExactly("d1", "d2");
            assertThat(children).extracting(SubagentRun::agent).containsExactly("explore", "general");
            assertThat(children).allSatisfy(child -> {
                assertThat(child.parentRunId()).isEqualTo(parent.id());
                assertThat(child.run().status()).isEqualTo(RunStatus.COMPLETED);
                assertThat(child.run().sessionId()).isEqualTo(session);
                assertThat(child.run().workspace()).isEqualTo(parent.workspace());
            });
            assertThat(children.getFirst().run().prompt()).isEqualTo(quoted);

            var snapshot = store.snapshot(session);
            assertThat(snapshot.runs()).extracting(Run::id).containsExactly(parent.id());
            assertThat(snapshot.subagents()).hasSize(2);
            assertThat(snapshot.session().workspace()).isEmpty();

            // Root tools: workspace tools, read_tool_output and delegate_task; children never delegate.
            ModelRequest root = requests.stream().filter(value -> !child(value)).findFirst().orElseThrow();
            assertThat(names(root)).contains("propose_command", "read_tool_output", "delegate_task");
            ModelRequest explore = requests.stream()
                    .filter(value -> child(value) && task(value).equals(quoted))
                    .findFirst()
                    .orElseThrow();
            assertThat(names(explore))
                    .contains("read_file", "search_files", "read_tool_output")
                    .doesNotContain("propose_file_change", "propose_command", "delegate_task");
            ModelRequest general = requests.stream()
                    .filter(value -> child(value) && task(value).equals("B"))
                    .findFirst()
                    .orElseThrow();
            assertThat(names(general))
                    .contains("read_file", "propose_file_change")
                    .doesNotContain("propose_command", "delegate_task");

            // The next turn's history holds only the parent's exchange, not the children's.
            requests.clear();
            Run next = settled(submit(engine, session).id());
            assertThat(next.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(requests.getFirst().messages())
                    .extracting(ModelMessage::role)
                    .containsExactly("user", "assistant", "tool", "tool", "assistant", "user");
        }
    }

    @Test
    void mixedBatchRunsOrdinaryToolsWhileChildrenWorkAndKeepsCallOrder() throws Exception {
        var bothStarted = new CountDownLatch(2);
        var model = model((request, cancel) -> {
            if (child(request)) {
                bothStarted.countDown();
                assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
                return answer("子结论-" + task(request));
            }
            if (request.step() == 1)
                return new ModelTurn(
                        "",
                        List.of(
                                delegate("d1", "explore", "A"),
                                new ToolCall("r1", "read_file", "{\"path\":\"App.java\"}"),
                                delegate("d2", "explore", "B")));
            return answer("完成");
        });
        String session = store.createSession("mixed").id();
        try (var engine = engine(model, Duration.ofSeconds(60))) {
            Run parent = settled(submit(engine, session).id());
            assertThat(parent.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(store.tools(parent.id()))
                    .extracting(ToolExecution::callId, ToolExecution::status)
                    .containsExactly(tuple("d1", "COMPLETED"), tuple("r1", "COMPLETED"), tuple("d2", "COMPLETED"));
            var results = store.conversation(parent.id()).subList(2, 5);
            assertThat(results).extracting(ModelMessage::toolCallId).containsExactly("d1", "r1", "d2");
            assertThat(results.get(0).content()).contains("子结论-A");
            assertThat(results.get(1).content()).contains("class App");
            assertThat(results.get(2).content()).contains("子结论-B");
        }
    }

    @Test
    void childToolsRouteSideEffectsToTheRootWithScopedCallIds() throws Exception {
        var model = model((request, cancel) -> {
            if (child(request)) {
                if (request.step() > 1) return answer("已提出修改");
                String tool = task(request).equals("修改") ? "propose_file_change" : "propose_command";
                return new ModelTurn("", List.of(new ToolCall("c1", tool, "{}")));
            }
            if (request.step() == 1 && request.messages().size() == 1)
                return new ModelTurn(
                        "",
                        List.of(
                                delegate("d1", "general", "修改"),
                                delegate("d2", "general", "运行"),
                                delegate("d3", "explore", "修改")));
            return answer("完成");
        });
        String session = store.createSession("routing").id();
        try (var engine = engine(model, Duration.ofSeconds(60))) {
            Run parent = settled(submit(engine, session).id());
            assertThat(parent.status()).isEqualTo(RunStatus.COMPLETED);
            var children = store.subagents(parent.id());
            String general = children.get(0).run().id();
            // Only general's proposal reached the tools: under the root, with the child-scoped call id.
            assertThat(routed).containsExactly(List.of(parent.id(), general + ":c1", "propose_file_change"));
            assertThat(store.tools(general)).extracting(ToolExecution::callId, ToolExecution::status)
                    .containsExactly(tuple("c1", "COMPLETED"));
            // Commands and explore's proposals are refused inside the child and reported back to it.
            for (int index : new int[] {1, 2})
                assertThat(store.tools(children.get(index).run().id()))
                        .singleElement()
                        .satisfies(tool -> {
                            assertThat(tool.status()).isEqualTo("FAILED");
                            assertThat(tool.output()).contains("不能使用");
                        });
        }
    }

    @Test
    void stoppingTheParentStopsRunningChildrenFirst() throws Exception {
        var model = model((request, cancel) -> {
            if (child(request)) return blockUntilStopped(cancel);
            return new ModelTurn("", List.of(delegate("d1", "explore", "慢任务")));
        });
        String session = store.createSession("cancel").id();
        try (var engine = engine(model, Duration.ofSeconds(60))) {
            Run parent = submit(engine, session);
            await().atMost(Duration.ofSeconds(5)).until(() -> !store.subagents(parent.id()).isEmpty());
            String child = store.subagents(parent.id()).getFirst().run().id();
            engine.cancel(parent.id());
            assertThat(settled(parent.id()).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(store.run(child).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(store.tools(parent.id())).singleElement().satisfies(tool -> assertThat(tool.status())
                    .isEqualTo("CANCELLED"));
        }
    }

    @Test
    void stoppingOneChildReportsBackAndTheParentContinues() throws Exception {
        var model = model((request, cancel) -> {
            if (child(request)) return blockUntilStopped(cancel);
            if (request.step() == 1) return new ModelTurn("", List.of(delegate("d1", "explore", "慢任务")));
            assertThat(request.messages().getLast().content()).contains("子智能体 explore 已停止");
            return answer("子任务已停止，改为直接回答");
        });
        String session = store.createSession("stop-child").id();
        try (var engine = engine(model, Duration.ofSeconds(60))) {
            Run parent = submit(engine, session);
            await().atMost(Duration.ofSeconds(5)).until(() -> !store.subagents(parent.id()).isEmpty());
            String child = store.subagents(parent.id()).getFirst().run().id();
            engine.cancel(child);
            assertThat(settled(parent.id()).status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(store.run(child).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(store.tools(parent.id())).singleElement().satisfies(tool -> assertThat(tool.status())
                    .isEqualTo("FAILED"));
        }
    }

    @Test
    void batchLimitInvalidArgumentsAndShortBudgetsAreReportedWithoutStartingChildren() throws Exception {
        var model = model((request, cancel) -> {
            if (child(request)) return answer("ok");
            if (request.step() > 1) return answer("完成");
            // An invalid call first: it fails without taking one of the four parallel slots.
            var calls = new java.util.ArrayList<ToolCall>();
            calls.add(new ToolCall("bad", "delegate_task", "{\"agent\":\"planner\",\"description\":\"x\",\"prompt\":\"y\"}"));
            for (int i = 1; i <= 5; i++) calls.add(delegate("d" + i, "explore", "任务" + i));
            return new ModelTurn("", calls);
        });
        String session = store.createSession("limits").id();
        try (var engine = engine(model, Duration.ofSeconds(60))) {
            Run parent = settled(submit(engine, session).id());
            assertThat(parent.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(store.subagents(parent.id())).hasSize(4);
            assertThat(store.tools(parent.id()))
                    .extracting(ToolExecution::callId, ToolExecution::status)
                    .containsExactly(
                            tuple("bad", "FAILED"),
                            tuple("d1", "COMPLETED"),
                            tuple("d2", "COMPLETED"),
                            tuple("d3", "COMPLETED"),
                            tuple("d4", "COMPLETED"),
                            tuple("d5", "SKIPPED"));
        }
        // A 15 s run reserves 3 s for its final answer, leaving less than a subagent's minimum.
        requests.clear();
        try (var engine = engine(model, Duration.ofSeconds(15))) {
            Run parent = settled(submit(engine, session).id());
            assertThat(store.subagents(parent.id())).isEmpty();
            assertThat(store.tools(parent.id()).get(1).output()).contains("剩余时间不足以启动子智能体");
        }
    }

    @Test
    void restartMarksUnfinishedChildrenInterrupted() {
        String session = store.createSession("restart").id();
        Run parent = store.submit(
                        session,
                        UUID.randomUUID().toString(),
                        "分析",
                        "NORMAL",
                        INFO,
                        ModelSelection.DEFAULT,
                        new ModelSelection("default", ""))
                .run();
        store.transition(parent.id(), RunStatus.RUNNING, "");
        Run child = store.startSubagent(parent.id(), "d1", "explore", "子任务");
        assertThat(child.status()).isEqualTo(RunStatus.RUNNING);
        assertThatThrownBy(() -> store.startSubagent(child.id(), "x", "explore", "嵌套"))
                .hasMessageContaining("不能再委派");
        store.recoverInterruptedRuns();
        assertThat(store.run(parent.id()).status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(store.run(child.id()).status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(store.snapshot(session).session()).isNotNull();
        store.deleteSession(session);
        assertThatThrownBy(() -> store.run(child.id())).hasMessageContaining("不存在");
    }
}
