package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.javaharness.adapters.question.QuestionService;
import dev.javaharness.adapters.question.QuestionWorkspaceTools;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.todo.TodoWorkspaceTools;
import dev.javaharness.adapters.workspace.LocalWorkspaceTools;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class QuestionServiceTest {
    static final String ASK = """
            {"question":"选哪种存储？","options":[{"label":"H2","description":"推荐，零依赖"},{"label":"MySQL","description":"需要外部服务"}]}""";

    @TempDir
    Path directory;

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    JdbcSessionStore sessions;
    QuestionService questions;
    WorkspaceTools tools;
    Run run;
    ExecutorService workers;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx);
        questions = new QuestionService(jdbc, tx, sessions);
        tools = new QuestionWorkspaceTools(
                new TodoWorkspaceTools(new LocalWorkspaceTools(directory, Duration.ofSeconds(5))), questions);
        var session = sessions.createSession("question", directory.toRealPath().toString());
        run = sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        "test",
                        "NORMAL",
                        new ModelInfo("test", "test", true))
                .run();
        run = sessions.transition(run.id(), RunStatus.RUNNING, "");
        workers = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void close() throws Exception {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    Future<WorkspaceTools.Result> ask(String callId, String arguments, ModelProvider.Cancellation cancellation) {
        return workers.submit(() ->
                tools.execute(run.id(), new ToolCall(callId, QuestionWorkspaceTools.ASK, arguments), cancellation));
    }

    QuestionService.Question pending() {
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !questions.list(run.id()).isEmpty());
        return questions.list(run.id()).getFirst();
    }

    @Test
    void definitionsAddBothToolsOnlyWhenOtherToolsExist() {
        assertThat(tools.definitions())
                .extracting(ToolDefinition::name)
                .contains(QuestionWorkspaceTools.ASK, TodoWorkspaceTools.UPDATE, "read_file");
        var empty = new QuestionWorkspaceTools(new TodoWorkspaceTools(WorkspaceTools.disabled()), questions);
        assertThat(empty.definitions()).isEmpty();
    }

    @Test
    void answerReachesTheWaitingCallOnce() throws Exception {
        var result = ask("call-1", ASK, () -> {});
        var question = pending();
        assertThat(question.status()).isEqualTo("PENDING");
        assertThat(question.options()).extracting(QuestionService.Option::label).containsExactly("H2", "MySQL");
        assertThatThrownBy(() -> questions.answer(question.id(), List.of("Oracle"), ""))
                .isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> questions.answer(question.id(), List.of("H2", "MySQL"), ""))
                .hasMessageContaining("只能选择一个");
        assertThatThrownBy(() -> questions.answer(question.id(), List.of(), " "))
                .hasMessageContaining("请选择");
        assertThatThrownBy(() -> questions.answer(question.id(), java.util.Arrays.asList("H2", null), ""))
                .hasMessageContaining("选项不能为空");
        assertThat(questions.answer(question.id(), List.of("H2"), "保持简单").status())
                .isEqualTo("ANSWERED");
        var value = result.get(5, TimeUnit.SECONDS);
        assertThat(value.success()).isTrue();
        assertThat(value.output()).contains("用户回答：H2", "补充说明：保持简单");
        assertThatThrownBy(() -> questions.answer(question.id(), List.of("MySQL"), ""))
                .hasMessageContaining("已处理");
        // A retried call id returns the settled question instead of asking again.
        assertThat(tools.execute(run.id(), new ToolCall("call-1", QuestionWorkspaceTools.ASK, ASK), () -> {})
                        .output())
                .contains("用户回答：H2");
        assertThat(questions.list(run.id())).hasSize(1);
        assertThat(sessions.events(run.sessionId(), 0, 64))
                .anyMatch(event -> event.type().equals("QUESTION"));
    }

    @Test
    void skipCancellationAndRestartSettleThePendingQuestion() throws Exception {
        var skipped = ask("skip", ASK, () -> {});
        questions.skip(pending().id());
        assertThat(skipped.get(5, TimeUnit.SECONDS).output()).contains("跳过");

        var stop = new AtomicBoolean();
        var cancelled = ask("cancel", ASK, () -> {
            if (stop.get()) throw new InterruptedException();
        });
        await().atMost(Duration.ofSeconds(5))
                .until(() -> questions.list(run.id()).size() == 2);
        stop.set(true);
        assertThatThrownBy(() -> cancelled.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        assertThat(questions.list(run.id()).get(1).status()).isEqualTo("CANCELLED");

        var abandoned = ask("restart", ASK, () -> {});
        await().atMost(Duration.ofSeconds(5))
                .until(() -> questions.list(run.id()).size() == 3);
        new QuestionService(jdbc, tx, sessions);
        assertThat(questions.list(run.id()).get(2).status()).isEqualTo("INTERRUPTED");
        assertThat(abandoned.get(5, TimeUnit.SECONDS).success()).isFalse();
    }

    @Test
    void sessionWithQuestionsCanBeDeleted() throws Exception {
        var result = ask("delete", ASK, () -> {});
        questions.skip(pending().id());
        result.get(5, TimeUnit.SECONDS);
        sessions.transition(run.id(), RunStatus.FAILED, "");
        sessions.deleteSession(run.sessionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM harness_question", Integer.class))
                .isZero();
    }

    @Test
    void invalidQuestionsAndTheRunLimitAreReportedToTheModel() throws Exception {
        assertThat(tools.execute(
                                run.id(),
                                new ToolCall("bad", QuestionWorkspaceTools.ASK, "{\"question\":\"x\"}"),
                                () -> {})
                        .output())
                .contains("options");
        assertThat(tools.execute(
                                run.id(),
                                new ToolCall(
                                        "one",
                                        QuestionWorkspaceTools.ASK,
                                        "{\"question\":\"x\",\"options\":[{\"label\":\"A\",\"description\":\"\"}]}"),
                                () -> {})
                        .output())
                .contains("2–4");
        assertThat(tools.execute(
                                run.id(),
                                new ToolCall(
                                        "dup",
                                        QuestionWorkspaceTools.ASK,
                                        "{\"question\":\"x\",\"options\":[{\"label\":\"A\",\"description\":\"\"},{\"label\":\"A\",\"description\":\"\"}]}"),
                                () -> {})
                        .output())
                .contains("不能重复");
        for (int i = 0; i < QuestionService.MAX_PER_RUN; i++) {
            var question = questions.ask(
                    run.id(),
                    "limit-" + i,
                    "问题 " + i,
                    List.of(new QuestionService.Option("A", ""), new QuestionService.Option("B", "")),
                    false);
            questions.skip(question.id());
        }
        assertThat(tools.execute(run.id(), new ToolCall("over", QuestionWorkspaceTools.ASK, ASK), () -> {})
                        .output())
                .contains("最多向用户提问");
        sessions.transition(run.id(), RunStatus.FAILED, "");
        assertThatThrownBy(() -> questions.ask(
                        run.id(),
                        "stopped",
                        "x",
                        List.of(new QuestionService.Option("A", ""), new QuestionService.Option("B", "")),
                        false))
                .hasMessageContaining("任务已停止");
    }

    @Test
    void todosReplaceTheWholeListAndRejectInvalidShapes() throws Exception {
        var ok = tools.execute(
                run.id(),
                new ToolCall(
                        "todo",
                        TodoWorkspaceTools.UPDATE,
                        "{\"todos\":[{\"content\":\"读代码\",\"status\":\"completed\"},{\"content\":\"写测试\",\"status\":\"in_progress\"},{\"content\":\"提交\",\"status\":\"pending\"}]}"),
                () -> {});
        assertThat(ok.success()).isTrue();
        assertThat(ok.output()).contains("已完成 1/3", "[x] 读代码", "[>] 写测试", "[ ] 提交");
        assertThat(tools.execute(
                                run.id(),
                                new ToolCall(
                                        "two",
                                        TodoWorkspaceTools.UPDATE,
                                        "{\"todos\":[{\"content\":\"a\",\"status\":\"in_progress\"},{\"content\":\"b\",\"status\":\"in_progress\"}]}"),
                                () -> {})
                        .output())
                .contains("最多一项");
        assertThat(tools.execute(
                                run.id(),
                                new ToolCall(
                                        "status",
                                        TodoWorkspaceTools.UPDATE,
                                        "{\"todos\":[{\"content\":\"a\",\"status\":\"done\"}]}"),
                                () -> {})
                        .success())
                .isFalse();
        assertThat(tools.execute(run.id(), new ToolCall("empty", TodoWorkspaceTools.UPDATE, "{\"todos\":[]}"), () -> {})
                        .success())
                .isFalse();
    }
}
