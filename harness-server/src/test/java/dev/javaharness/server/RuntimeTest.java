package dev.javaharness.server;

import dev.javaharness.adapters.JdbcSessionStore;
import dev.javaharness.adapters.SimulatedModelProvider;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RuntimeTest {
    private JdbcTemplate jdbc;
    private JdbcSessionStore store;

    @BeforeEach
    void setup() {
        open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private void open(String url) {
        var ds = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        store = new JdbcSessionStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private void settled(String id) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.run(id).status().active());
    }

    @Test void queuedRunCapturesSettingsAtSubmissionAndRetriesDoNotReopenResources() {
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        var released = new java.util.concurrent.atomic.AtomicInteger();
        TestModelProvider provider = (request, cancellation, text) -> text.accept(request.model());
        RunConfiguration.ResourceFactory factory = workspace -> {
            opened.incrementAndGet();
            return new RunConfiguration.Resources(provider, WorkspaceTools.disabled(), released::incrementAndGet);
        };
        var old = new RunConfiguration(new ModelInfo("openai-compatible", "old", true), Duration.ofSeconds(5), 32000, 0, factory);
        var changed = new RunConfiguration(new ModelInfo("openai-compatible", "new", true), Duration.ofSeconds(5), 64000, 2, factory);
        var source = new java.util.concurrent.atomic.AtomicReference<>(old);
        try (var engine = new DefaultAgentEngine(store, source::get)) {
            String session = store.createSession("snapshot").id();
            String requestId = key();
            Run first;
            synchronized (engine) {
                first = engine.submit(session, requestId, "hello", "NORMAL").run();
                source.set(changed);
            }
            settled(first.id());
            assertThat(store.run(first.id()).output()).isEqualTo("old");
            assertThat(store.run(first.id()).context().maxCharacters()).isEqualTo(32000);
            assertThat(engine.submit(session, requestId, "hello", "NORMAL").created()).isFalse();
            Run next = engine.submit(session, key(), "next", "NORMAL").run();
            settled(next.id());
            assertThat(store.run(next.id()).output()).isEqualTo("new");
            assertThat(store.run(next.id()).context().maxCharacters()).isEqualTo(64000);
            assertThat(opened).hasValue(2);
            assertThat(released).hasValue(2);
        }
    }

    @Test void cancellationClosesPerRunResourcesBeforeReleasingTheSession() throws Exception {
        var entered = new CountDownLatch(1);
        var released = new java.util.concurrent.atomic.AtomicInteger();
        TestModelProvider provider = (request, cancellation, text) -> { entered.countDown(); new CountDownLatch(1).await(); };
        var config = new RunConfiguration(provider.info(), Duration.ofSeconds(5), 32000, 0,
                workspace -> new RunConfiguration.Resources(provider, WorkspaceTools.disabled(), released::incrementAndGet));
        try (var engine = new DefaultAgentEngine(store, () -> config)) {
            var run = engine.submit(store.createSession("cancel").id(), key(), "hello", "NORMAL").run();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            engine.cancel(run.id());
            settled(run.id());
            assertThat(store.run(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(released).hasValue(1);
        }
    }

    @Test
    void completesAndReconstructsOutputFromOrderedEvents() {
        try (var engine = new DefaultAgentEngine(store, new SimulatedModelProvider(0), Duration.ofSeconds(5))) {
            Session session = store.createSession("验证 Unicode");
            Run run = engine.submit(session.id(), key(), "你好 🌱", "NORMAL").run();
            settled(run.id());
            var snapshot = store.snapshot(session.id());
            var events = store.events(session.id(), 0, 256);
            assertThat(store.run(run.id()).status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(events.stream().filter(e -> e.type().equals("TEXT")).map(RunEvent::text).collect(Collectors.joining()))
                    .isEqualTo(store.run(run.id()).output()).contains("你好 🌱");
            assertThat(events.getLast().seq()).isEqualTo(snapshot.lastSeq());
            for (int i = 0; i < events.size(); i++) assertThat(events.get(i).seq()).isEqualTo(i + 1);
            assertThat(store.events(session.id(), 2, 256)).allMatch(e -> e.seq() > 2);
            assertThatThrownBy(() -> store.events(session.id(), snapshot.lastSeq() + 1, 10))
                    .isInstanceOf(HarnessException.class);
        }
    }

    @Test
    void simultaneousDuplicateRequestsHaveOneIdentityAndBusyRejectsOtherInput() throws Exception {
        try (var engine = new DefaultAgentEngine(store, new SimulatedModelProvider(0), Duration.ofSeconds(30));
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Session session = store.createSession("并发");
            String requestId = key();
            var start = new CountDownLatch(1);
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(i -> callers.submit(() -> {
                start.await();
                return engine.submit(session.id(), requestId, "等待", "SLOW");
            })).toList();
            start.countDown();
            var results = new java.util.ArrayList<SessionStore.Submission>();
            for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(SessionStore.Submission::created)).hasSize(1);
            assertThat(results.stream().map(r -> r.run().id()).distinct()).hasSize(1);
            assertThat(store.snapshot(session.id()).runs()).hasSize(1);
            assertThatThrownBy(() -> engine.submit(session.id(), key(), "其他请求", "NORMAL"))
                    .isInstanceOf(HarnessException.class).hasMessageContaining("已有");
            assertThatThrownBy(() -> engine.submit(session.id(), requestId, "更换输入", "SLOW"))
                    .isInstanceOf(HarnessException.class).hasMessageContaining("requestId");
            String runId = results.getFirst().run().id();
            engine.cancel(runId);
            settled(runId);
            assertThat(engine.submit(session.id(), requestId, "等待", "SLOW").created()).isFalse();
            assertThat(engine.submit(session.id(), key(), "下一次", "NORMAL").created()).isTrue();
        }
    }

    @Test
    void cancellationWaitsForProviderQuiescenceAndDropsLateOutput() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        TestModelProvider model = (run, cancellation, text) -> {
            text.accept("已提交");
            started.countDown();
            boolean done = false;
            while (!done) {
                try { done = release.await(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* Simulate delayed cooperative cleanup. */ }
            }
            text.accept("迟到文本");
        };
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Session session = store.createSession("取消");
            Run run = engine.submit(session.id(), key(), "hello", "NORMAL").run();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(engine.cancel(run.id()).status()).isEqualTo(RunStatus.CANCELLING);
            assertThat(engine.cancel(run.id()).status()).isEqualTo(RunStatus.CANCELLING);
            assertThat(store.run(run.id()).output()).isEqualTo("已提交");
            assertThatThrownBy(() -> engine.submit(session.id(), key(), "blocked", "NORMAL"))
                    .isInstanceOf(HarnessException.class);
            release.countDown();
            settled(run.id());
            assertThat(store.run(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(store.run(run.id()).output()).isEqualTo("已提交");
            var events = store.events(session.id(), 0, 256);
            assertThat(events.getLast().status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(events).noneMatch(e -> e.status() == RunStatus.COMPLETED);
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellingQueuedWorkNeverInvokesTheModel() {
        var invocations = new java.util.concurrent.atomic.AtomicInteger();
        TestModelProvider model = (run, cancellation, text) -> invocations.incrementAndGet();
        try (var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Session session = store.createSession("排队取消");
            Run run;
            synchronized (engine) {
                run = engine.submit(session.id(), key(), "queued", "NORMAL").run();
                assertThat(engine.cancel(run.id()).status()).isEqualTo(RunStatus.CANCELLING);
            }
            settled(run.id());
            assertThat(store.run(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(invocations.get()).isZero();
            assertThat(store.events(session.id(), 0, 256)).noneMatch(e -> e.status() == RunStatus.RUNNING);
        }
    }

    @Test
    void timeoutAndFailureKeepPartialTextWithDistinctOutcomes() {
        try (var engine = new DefaultAgentEngine(store, new SimulatedModelProvider(0), Duration.ofMillis(250))) {
            Session session = store.createSession("超时");
            Run slow = engine.submit(session.id(), key(), "部分输出", "SLOW").run();
            settled(slow.id());
            assertThat(store.run(slow.id()).status()).isEqualTo(RunStatus.TIMED_OUT);
            assertThat(store.run(slow.id()).output()).contains("部分输出");
            Run failed = engine.submit(session.id(), key(), "失败前输出", "FAIL").run();
            settled(failed.id());
            assertThat(store.run(failed.id()).status()).isEqualTo(RunStatus.FAILED);
            assertThat(store.run(failed.id()).output()).contains("失败前输出");
        }
    }

    @Test
    void failedEventInsertRollsBackOutputAndSequence() {
        Session session = store.createSession("事务");
        Run run = store.submit(session.id(), key(), "test", "NORMAL", TestModelProvider.INFO).run();
        store.transition(run.id(), RunStatus.RUNNING, "");
        long before = store.snapshot(session.id()).lastSeq();
        jdbc.execute("ALTER TABLE harness_event ADD CONSTRAINT test_text_limit CHECK (LENGTH(event_text) <= 2)");
        assertThatThrownBy(() -> store.appendText(run.id(), "超过限制")).isInstanceOf(RuntimeException.class);
        assertThat(store.run(run.id()).output()).isEmpty();
        assertThat(store.snapshot(session.id()).lastSeq()).isEqualTo(before);
        jdbc.execute("ALTER TABLE harness_event DROP CONSTRAINT test_text_limit");
        store.appendText(run.id(), "恢复");
        assertThat(store.snapshot(session.id()).lastSeq()).isEqualTo(before + 1);
    }

    @Test
    void fileStoreReopensAndRecoversEveryActiveStateWithoutReexecution(@TempDir Path directory) {
        String url = "jdbc:h2:file:" + directory.resolve("runtime").toString().replace('\\', '/');
        open(url);
        var runIds = new java.util.ArrayList<String>();
        for (RunStatus status : List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.CANCELLING)) {
            var session = store.createSession(status.name());
            var run = store.submit(session.id(), key(), "保留", "NORMAL", TestModelProvider.INFO).run();
            if (status != RunStatus.QUEUED) {
                store.transition(run.id(), RunStatus.RUNNING, "");
                store.appendText(run.id(), "持久化内容");
            }
            if (status == RunStatus.CANCELLING) store.transition(run.id(), status, "停止中");
            runIds.add(run.id());
        }
        jdbc.execute("SHUTDOWN");
        open(url);
        store.recoverInterruptedRuns();
        for (String id : runIds) {
            Run run = store.run(id);
            assertThat(run.status()).isEqualTo(RunStatus.INTERRUPTED);
            long seq = store.snapshot(run.sessionId()).lastSeq();
            store.recoverInterruptedRuns();
            assertThat(store.snapshot(run.sessionId()).lastSeq()).isEqualTo(seq);
            assertThat(store.submit(run.sessionId(), key(), "新请求", "NORMAL", TestModelProvider.INFO).created()).isTrue();
        }
        assertThat(store.run(runIds.get(1)).output()).isEqualTo("持久化内容");
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void terminalStateCannotBeRevivedAndInvalidInputLeavesNoRun() {
        Session session = store.createSession("边界");
        assertThatThrownBy(() -> store.submit(session.id(), key(), " ", "NORMAL", TestModelProvider.INFO)).isInstanceOf(HarnessException.class);
        assertThat(store.snapshot(session.id()).runs()).isEmpty();
        Run run = store.submit(session.id(), key(), "test", "NORMAL", TestModelProvider.INFO).run();
        store.transition(run.id(), RunStatus.RUNNING, "");
        store.transition(run.id(), RunStatus.COMPLETED, "");
        long seq = store.snapshot(session.id()).lastSeq();
        assertThatThrownBy(() -> store.transition(run.id(), RunStatus.RUNNING, "")).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> store.appendText(run.id(), "late")).isInstanceOf(HarnessException.class);
        assertThat(store.snapshot(session.id()).lastSeq()).isEqualTo(seq);
    }
}
