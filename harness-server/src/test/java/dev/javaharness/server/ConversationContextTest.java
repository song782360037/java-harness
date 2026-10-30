package dev.javaharness.server;

import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.core.ConversationContext;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ConversationContextTest {
    private static final ModelInfo REAL = new ModelInfo("openai-compatible", "test-model", true);
    private JdbcSessionStore store;
    private JdbcTemplate jdbc;
    private final List<ModelRequest> received = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ModelProvider model = new ModelProvider() {
        @Override public ModelInfo info() { return REAL; }
        @Override public void stream(ModelRequest request, Cancellation cancellation, Consumer<String> text) {
            assertThat(store.modelRequest(request.runId())).isEqualTo(request);
            received.add(request);
            text.accept("回答：" + request.messages().getLast().content());
        }
    };

    @BeforeEach void setup() { open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"); }

    private void open(String url) {
        var ds = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        store = new JdbcSessionStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    private Run submit(DefaultAgentEngine engine, String sessionId, String prompt) {
        Run run = engine.submit(sessionId, UUID.randomUUID().toString(), prompt, "NORMAL").run();
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.run(run.id()).status().active());
        return store.run(run.id());
    }

    private Run history(String session, String prompt, String output, RunStatus end, ModelInfo info) {
        Run run = store.submit(session, UUID.randomUUID().toString(), prompt, "NORMAL", info).run();
        store.transition(run.id(), RunStatus.RUNNING, "");
        for (int offset = 0; offset < output.length(); offset += 4096) {
            store.appendText(run.id(), output.substring(offset, Math.min(offset + 4096, output.length())));
        }
        if (end == RunStatus.CANCELLED || end == RunStatus.TIMED_OUT) store.transition(run.id(), RunStatus.CANCELLING, "");
        return store.transition(run.id(), end, "");
    }

    @Test void multipleTurnsUseFrozenHistoryAndRetriesNeverRebuildIt() {
        String session = store.createSession("多轮").id();
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run first = submit(engine, session, "我的项目是 Java 🌱");
            Run second = submit(engine, session, "是什么语言？");
            assertThat(second.status()).isEqualTo(RunStatus.COMPLETED);
            ModelRequest frozen = store.modelRequest(second.id());
            assertThat(frozen.messages()).containsExactly(new ModelMessage("user", first.prompt()),
                    new ModelMessage("assistant", first.output()), new ModelMessage("user", second.prompt()));
            assertThat(second.context().includedTurns()).isEqualTo(1);
            submit(engine, session, "继续");
            assertThat(received.getLast().messages()).hasSize(5);
            assertThat(engine.submit(session, second.requestId(), second.prompt(), "NORMAL").created()).isFalse();
            assertThat(received).hasSize(3);
            assertThat(store.modelRequest(second.id())).isEqualTo(frozen);
            assertThatThrownBy(() -> frozen.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> store.saveModelRequest(frozen)).isInstanceOf(HarnessException.class);
            assertThat(submit(engine, store.createSession("独立").id(), "新问题").context().includedTurns()).isZero();
        }
    }

    @Test void onlyCompletedRealAnswersAreEligibleIncludingEarlierModelVersions() {
        String session = store.createSession("过滤").id();
        history(session, "模拟", "模拟回答", RunStatus.COMPLETED, TestModelProvider.INFO);
        history(session, "空回答", "  ", RunStatus.COMPLETED, REAL);
        for (RunStatus state : List.of(RunStatus.CANCELLED, RunStatus.FAILED, RunStatus.TIMED_OUT, RunStatus.INTERRUPTED)) {
            history(session, state.name(), "残缺回答", state, REAL);
        }
        Run valid = history(session, "旧模型的问题", "旧模型的完整回答", RunStatus.COMPLETED,
                new ModelInfo("deepseek", "previous-model", true));
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run current = submit(engine, session, "追问");
            assertThat(current.context().excludedTurns()).isEqualTo(6);
            assertThat(current.context().includedTurns()).isEqualTo(1);
            assertThat(received.getFirst().messages()).extracting(ModelMessage::content)
                    .containsExactly(valid.prompt(), valid.output(), "追问");
        }
    }

    @Test void budgetDropsOldestWholeTurnsAndAnOversizeRecentTurnBlocksOlderOnes() {
        String session = store.createSession("预算").id();
        history(session, "旧", "a".repeat(2500), RunStatus.COMPLETED, REAL);
        Run recent = history(session, "新", "🌱".repeat(1000), RunStatus.COMPLETED, REAL);
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5), 1400)) {
            Run current = submit(engine, session, "追问");
            var context = current.context();
            assertThat(context.includedTurns()).isEqualTo(1);
            assertThat(context.droppedTurns()).isEqualTo(1);
            assertThat(context.excludedTurns()).isZero();
            assertThat(context.characters()).isEqualTo(2003);
            assertThat(context.maxTokens()).isEqualTo(1400);
            assertThat(context.tokens()).isPositive();
            assertThat(received.getLast().messages()).extracting(ModelMessage::content)
                    .containsExactly(recent.prompt(), recent.output(), current.prompt());
        }
        String other = store.createSession("大轮次").id();
        history(other, "很早", "短回答", RunStatus.COMPLETED, REAL);
        history(other, "最近", "x".repeat(4100), RunStatus.COMPLETED, REAL);
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5), 1400)) {
            Run current = submit(engine, other, "追问");
            var context = current.context();
            assertThat(context.includedTurns()).isZero();
            assertThat(context.droppedTurns()).isEqualTo(2);
            assertThat(context.characters()).isEqualTo(2);
            assertThat(context.maxTokens()).isEqualTo(1400);
            assertThat(received.getLast().messages()).containsExactly(new ModelMessage("user", "追问"));
        }
    }

    @Test void eventSequenceDeterminesOrderEvenIfWallClockMovesBackwards() {
        String session = store.createSession("顺序").id();
        Run first = history(session, "first", "one", RunStatus.COMPLETED, REAL);
        Run second = history(session, "second", "two", RunStatus.COMPLETED, REAL);
        jdbc.update("UPDATE harness_run SET created_at=DATEADD('DAY',-1,created_at) WHERE id=?", second.id());
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run current = submit(engine, session, "third");
            assertThat(store.modelRequest(current.id()).messages()).extracting(ModelMessage::content)
                    .containsExactly(first.prompt(), first.output(), second.prompt(), second.output(), "third");
        }
    }

    @Test void snapshotFailureRollsBackSummaryAndPreventsNetworkCall() {
        String session = store.createSession("事务失败").id();
        jdbc.execute("ALTER TABLE harness_event ADD CONSTRAINT reject_context CHECK (event_type <> 'CONTEXT')");
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run failed = submit(engine, session, "不应发送");
            assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
            assertThat(failed.context()).isNull();
            assertThat(store.modelRequest(failed.id())).isNull();
            assertThat(received).isEmpty();
            jdbc.execute("ALTER TABLE harness_event DROP CONSTRAINT reject_context");
            assertThat(submit(engine, session, "恢复").status()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    @Test void queuedCancellationCreatesNoRequestSnapshot() {
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run run;
            synchronized (engine) {
                run = engine.submit(store.createSession("取消").id(), UUID.randomUUID().toString(), "不要发送", "NORMAL").run();
                engine.cancel(run.id());
            }
            await().atMost(Duration.ofSeconds(3)).until(() -> store.run(run.id()).status() == RunStatus.CANCELLED);
            assertThat(store.modelRequest(run.id())).isNull();
            assertThat(received).isEmpty();
        }
    }

    @Test void fileReopenPreservesSnapshotAndRestoresContextWithoutReexecuting(@TempDir Path directory) {
        String url = "jdbc:h2:file:" + directory.resolve("context").toString().replace('\\', '/');
        open(url);
        String session = store.createSession("持久化").id();
        Run first;
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            first = submit(engine, session, "记住这句话");
        }
        ModelRequest original = store.modelRequest(first.id());
        jdbc.execute("SHUTDOWN");
        open(url);
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            assertThat(received).hasSize(1);
            assertThat(store.modelRequest(first.id())).isEqualTo(original);
            Run next = submit(engine, session, "刚才说了什么？");
            assertThat(store.modelRequest(next.id()).messages()).extracting(ModelMessage::content)
                    .containsExactly(first.prompt(), first.output(), next.prompt());
        }
        jdbc.execute("SHUTDOWN");
    }

    @Test void v2RecordsRemainReadableWithoutInventedSnapshots() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).target("2").load().migrate();
        var old = new JdbcTemplate(ds);
        old.update("INSERT INTO harness_session(id,title,created_at) VALUES ('s','legacy',CURRENT_TIMESTAMP)");
        old.update("""
                INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at,provider,model)
                VALUES ('r','s',?,'old','NORMAL','COMPLETED','answer','',CURRENT_TIMESTAMP,'deepseek','old-model')
                """, UUID.randomUUID().toString());
        Flyway.configure().dataSource(ds).load().migrate();
        var migrated = new JdbcSessionStore(old, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        assertThat(migrated.run("r").output()).isEqualTo("answer");
        assertThat(migrated.run("r").context()).isNull();
        assertThat(migrated.modelRequest("r")).isNull();
    }

    @Test void budgetIncludesAFittingTurnAndRejectsInvalidConfiguration() {
        String session = store.createSession("精确预算").id();
        history(session, "问", "短回答", RunStatus.COMPLETED, REAL);
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5), 4000)) {
            Run current = submit(engine, session, "追");
            var context = current.context();
            assertThat(context.includedTurns()).isEqualTo(1);
            assertThat(context.droppedTurns()).isZero();
            assertThat(context.maxTokens()).isEqualTo(4000);
            assertThat(context.tokens()).isPositive();
        }
        assertThatThrownBy(() -> new ConversationContext(255)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationContext(2_000_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
