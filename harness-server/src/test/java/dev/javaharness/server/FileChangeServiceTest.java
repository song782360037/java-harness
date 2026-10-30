package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.workspace.FileChange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class FileChangeServiceTest {
    @TempDir
    Path directory;

    JdbcTemplate jdbc;
    JdbcSessionStore sessions;
    FileChangeService changes;
    TransactionTemplate tx;
    String workspace;
    Run run;

    @BeforeEach
    void setup() throws Exception {
        workspace = directory.toRealPath().toString();
        open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        run = start();
        Files.writeString(directory.resolve("App.java"), "class App { int value = 1; }\n");
    }

    void open(String url) {
        var ds = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx);
        changes = new FileChangeService(jdbc, tx, sessions);
    }

    Run start() {
        var session = sessions.createSession("review", workspace);
        var result = sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        "edit",
                        "NORMAL",
                        new ModelInfo("openai-compatible", "test", true))
                .run();
        return sessions.transition(result.id(), RunStatus.RUNNING, "");
    }

    FileChange replace() {
        return changes.propose(
                run.id(), UUID.randomUUID().toString(), workspace, "App.java", "replace", "value = 1", "value = 2");
    }

    FileChange create(String path) {
        return changes.propose(run.id(), UUID.randomUUID().toString(), workspace, path, "create", "", "new content\n");
    }

    void complete() {
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
    }

    @Test
    void proposalDoesNotWriteAndAppliedRetryNeverOverwritesLaterEdits() throws Exception {
        var change = replace();
        assertThat(Files.readString(directory.resolve("App.java"))).contains("value = 1");
        assertThat(change.beforeContent()).contains("value = 1");
        assertThat(change.afterContent()).contains("value = 2");
        assertThat(change.status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> changes.apply(change.id())).isInstanceOf(HarnessException.class);
        complete();
        assertThat(changes.apply(change.id()).status()).isEqualTo("APPLIED");
        assertThat(Files.readString(directory.resolve("App.java"))).contains("value = 2");
        Files.writeString(directory.resolve("App.java"), "IDE edit");
        assertThat(changes.apply(change.id()).status()).isEqualTo("APPLIED");
        assertThat(Files.readString(directory.resolve("App.java"))).isEqualTo("IDE edit");
        assertThat(sessions.events(run.sessionId(), 0, 256))
                .filteredOn(e -> e.type().equals("FILE_CHANGE"))
                .hasSize(3);
    }

    @Test
    void externalEditAndDeletionBecomeConflictsWithoutWriting() throws Exception {
        var changed = replace();
        var deleted = replace();
        complete();
        Files.writeString(directory.resolve("App.java"), "IDE changed this");
        assertThat(changes.apply(changed.id()).status()).isEqualTo("CONFLICT");
        assertThat(Files.readString(directory.resolve("App.java"))).isEqualTo("IDE changed this");
        Files.delete(directory.resolve("App.java"));
        assertThat(changes.apply(deleted.id()).status()).isEqualTo("CONFLICT");
        assertThat(directory.resolve("App.java")).doesNotExist();
    }

    @Test
    void creationAndEmptyFilesRequireAbsentTarget() throws Exception {
        var created = create("New.java");
        var collision = create("collision.txt");
        var empty = changes.propose(run.id(), "empty", workspace, "empty.txt", "create", "", "");
        assertThat(directory.resolve("New.java")).doesNotExist();
        complete();
        Files.writeString(directory.resolve("collision.txt"), "existing");
        assertThat(changes.apply(created.id()).status()).isEqualTo("APPLIED");
        assertThat(Files.readString(directory.resolve("New.java"))).isEqualTo("new content\n");
        assertThat(changes.apply(empty.id()).status()).isEqualTo("APPLIED");
        assertThat(Files.size(directory.resolve("empty.txt"))).isZero();
        assertThat(changes.apply(collision.id()).status()).isEqualTo("CONFLICT");
        assertThat(Files.readString(directory.resolve("collision.txt"))).isEqualTo("existing");
    }

    @Test
    void rejectionIsPersistentAndCannotLaterBeApplied() throws Exception {
        var change = replace();
        assertThat(changes.reject(change.id()).status()).isEqualTo("REJECTED");
        assertThat(changes.reject(change.id()).status()).isEqualTo("REJECTED");
        complete();
        assertThatThrownBy(() -> changes.apply(change.id())).isInstanceOf(HarnessException.class);
        assertThat(Files.readString(directory.resolve("App.java"))).contains("value = 1");
    }

    @Test
    void wrongWorkspaceActiveSessionAndFailedRunCannotApply() {
        var change = replace();
        complete();
        sessions.updateWorkspace(run.sessionId(), "");
        assertThatThrownBy(() -> changes.apply(change.id())).hasMessageContaining("工作区已改变");
        sessions.updateWorkspace(run.sessionId(), workspace);
        var next = sessions.submit(
                        run.sessionId(),
                        UUID.randomUUID().toString(),
                        "another",
                        "NORMAL",
                        new ModelInfo("openai-compatible", "test", true))
                .run();
        assertThatThrownBy(() -> changes.apply(change.id())).hasMessageContaining("任务结束");
        sessions.transition(next.id(), RunStatus.FAILED, "test");
        run = start();
        var failed = replace();
        sessions.transition(run.id(), RunStatus.FAILED, "test");
        assertThatThrownBy(() -> changes.apply(failed.id())).hasMessageContaining("正常完成");
    }

    @Test
    void cancelledTasksCannotProposeOrApplyButCanReject() {
        var change = replace();
        sessions.transition(run.id(), RunStatus.CANCELLING, "");
        assertThatThrownBy(this::replace).isInstanceOf(HarnessException.class);
        sessions.transition(run.id(), RunStatus.CANCELLED, "");
        assertThatThrownBy(() -> changes.apply(change.id())).isInstanceOf(HarnessException.class);
        assertThat(changes.reject(change.id()).status()).isEqualTo("REJECTED");
    }

    @Test
    void unsafePathsAndMissingParentsAreRejected() {
        for (String path : List.of(
                "../outside.txt",
                "/absolute",
                "C:/outside",
                "dir\\file",
                ".git/config",
                ".env.local",
                "secret.key",
                ".git./config",
                "NUL.txt",
                "x/../App.java",
                "missing/child.txt",
                ".ssh/config")) assertThatThrownBy(() -> create(path)).as(path).isInstanceOf(HarnessException.class);
        assertThat(changes.list(run.id())).isEmpty();
    }

    @Test
    void ambiguousMissingNoopAndBinaryReplacementsAreRejected() throws Exception {
        Files.writeString(directory.resolve("App.java"), "same same");
        assertThatThrownBy(
                        () -> changes.propose(run.id(), "ambiguous", workspace, "App.java", "replace", "same", "other"))
                .isInstanceOf(HarnessException.class);
        assertThatThrownBy(this::replace).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() ->
                        changes.propose(run.id(), "noop", workspace, "App.java", "replace", "same same", "same same"))
                .isInstanceOf(HarnessException.class);
        Files.write(directory.resolve("App.java"), new byte[] {(byte) 0xff, 0});
        assertThatThrownBy(this::replace).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> changes.propose(run.id(), "nul", workspace, "bad.txt", "create", "", "\0"))
                .isInstanceOf(HarnessException.class);
        assertThatThrownBy(() ->
                        changes.propose(run.id(), "big", workspace, "big.txt", "create", "", "x".repeat(1_048_577)))
                .isInstanceOf(HarnessException.class);
    }

    @Test
    void bomAndCrLfArePreservedForReplacement() throws Exception {
        Files.writeString(directory.resolve("App.java"), "\uFEFFone\r\ntwo\r\nthree\r\n");
        var change = changes.propose(run.id(), "crlf", workspace, "App.java", "replace", "one\ntwo", "one\nchanged");
        complete();
        assertThat(changes.apply(change.id()).status()).isEqualTo("APPLIED");
        assertThat(Files.readString(directory.resolve("App.java"))).isEqualTo("\uFEFFone\r\nchanged\r\nthree\r\n");
    }

    @Test
    void duplicateCallAndConcurrentApplyAreIdempotent() throws Exception {
        var first = changes.propose(run.id(), "same-call", workspace, "App.java", "replace", "value = 1", "value = 2");
        assertThat(changes.propose(run.id(), "same-call", workspace, "App.java", "replace", "value = 1", "value = 2")
                        .id())
                .isEqualTo(first.id());
        complete();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> changes.apply(first.id()));
            var b = executor.submit(() -> changes.apply(first.id()));
            assertThat(a.get(5, TimeUnit.SECONDS).status()).isEqualTo("APPLIED");
            assertThat(b.get(5, TimeUnit.SECONDS).status()).isEqualTo("APPLIED");
        }
        assertThat(Files.readString(directory.resolve("App.java"))).contains("value = 2");
    }

    @Test
    void recordsSurviveReopenAndInterruptedApplyIsNeverReplayed() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("review-db").toString().replace('\\', '/');
        open(url);
        run = start();
        var pending = replace();
        var applying = create("not-written.txt");
        complete();
        jdbc.update("UPDATE harness_file_change SET status='APPLYING' WHERE id=?", applying.id());
        jdbc.execute("SHUTDOWN");
        open(url);
        assertThat(changes.list(run.id()))
                .filteredOn(c -> c.id().equals(pending.id()))
                .extracting(FileChange::status)
                .containsExactly("PENDING");
        assertThat(changes.list(run.id()))
                .filteredOn(c -> c.id().equals(applying.id()))
                .extracting(FileChange::status)
                .containsExactly("UNCERTAIN");
        assertThat(directory.resolve("not-written.txt")).doesNotExist();
        assertThatThrownBy(() -> changes.apply(applying.id())).isInstanceOf(HarnessException.class);
        assertThat(changes.apply(pending.id()).status()).isEqualTo("APPLIED");
        jdbc.execute("SHUTDOWN");
        open(url);
        assertThat(changes.list(run.id()))
                .filteredOn(c -> c.id().equals(pending.id()))
                .extracting(FileChange::afterContent)
                .containsExactly("class App { int value = 2; }\n");
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void failedIntentCommitPreventsAnyDiskWrite() throws Exception {
        var change = replace();
        complete();
        jdbc.execute("ALTER TABLE harness_file_change ADD CONSTRAINT no_applying CHECK (status <> 'APPLYING')");
        assertThatThrownBy(() -> changes.apply(change.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(Files.readString(directory.resolve("App.java"))).contains("value = 1");
        assertThat(changes.list(run.id()).getFirst().status()).isEqualTo("PENDING");
    }

    @Test
    void failedResultCommitLeavesRecoverableUncertaintyWithoutReplayingWrite() throws Exception {
        var change = replace();
        complete();
        jdbc.execute("ALTER TABLE harness_file_change ADD CONSTRAINT no_applied CHECK (status <> 'APPLIED')");
        assertThatThrownBy(() -> changes.apply(change.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(Files.readString(directory.resolve("App.java"))).contains("value = 2");
        assertThat(changes.list(run.id()).getFirst().status()).isEqualTo("APPLYING");
        changes = new FileChangeService(jdbc, tx, sessions);
        assertThat(changes.list(run.id()).getFirst().status()).isEqualTo("UNCERTAIN");
        assertThatThrownBy(() -> changes.apply(change.id())).isInstanceOf(HarnessException.class);
    }

    @Test
    void symlinkIntroducedAfterProposalCannotRedirectWrite() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path parent = Files.createDirectory(directory.resolve("inside"));
        Files.writeString(parent.resolve("file.txt"), "original");
        Files.writeString(outside.resolve("file.txt"), "original");
        var change = changes.propose(run.id(), "link", workspace, "inside/file.txt", "replace", "original", "changed");
        complete();
        Files.delete(parent.resolve("file.txt"));
        Files.delete(parent);
        try {
            Files.createSymbolicLink(parent, outside);
        } catch (java.io.IOException | UnsupportedOperationException error) {
            Assumptions.abort("Symbolic links not available on this filesystem");
        }
        assertThat(changes.apply(change.id()).status()).isEqualTo("CONFLICT");
        assertThat(Files.readString(outside.resolve("file.txt"))).isEqualTo("original");
    }
}
