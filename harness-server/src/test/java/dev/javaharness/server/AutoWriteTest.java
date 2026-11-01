package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.workspace.FileChange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class AutoWriteTest {
    @TempDir
    Path directory;

    JdbcSessionStore sessions;
    FileChangeService changes;
    Session session;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx);
        changes = new FileChangeService(jdbc, tx, sessions);
        Files.writeString(directory.resolve("App.java"), "class App {}\n");
        Files.createDirectories(directory.resolve(".github/workflows"));
        Files.writeString(directory.resolve(".github/workflows/ci.yml"), "on: push\n");
        session = sessions.createSession("write", directory.toRealPath().toString());
    }

    Run start(ApprovalMode mode) {
        var run = sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        "改代码",
                        "NORMAL",
                        new ModelInfo("test", "test", true),
                        ModelSelection.DEFAULT,
                        new ModelSelection("default", ""),
                        "{}",
                        mode)
                .run();
        return sessions.transition(run.id(), RunStatus.RUNNING, "");
    }

    FileChange replace(Run run, String path, String oldText, String newText) {
        return changes.propose(run.id(), UUID.randomUUID().toString(), run.workspace(), path, "replace", oldText, newText);
    }

    String read(String path) throws Exception {
        return Files.readString(directory.resolve(path));
    }

    @Test
    void manualModeKeepsReviewAfterRun() throws Exception {
        Run run = start(ApprovalMode.MANUAL);
        var change = replace(run, "App.java", "class App", "final class App");
        assertThat(change.status()).isEqualTo("PENDING");
        assertThat(change.decidedBy()).isNull();
        assertThat(read("App.java")).isEqualTo("class App {}\n");
    }

    @Test
    void smartModeWritesImmediatelyAndRevertsAfterTheRun() throws Exception {
        Run run = start(ApprovalMode.SMART);
        var first = replace(run, "App.java", "class App {}", "class App { int a; }");
        assertThat(first.status()).isEqualTo("APPLIED");
        assertThat(first.decidedBy()).isEqualTo("RULE");
        assertThat(read("App.java")).isEqualTo("class App { int a; }\n");
        // The next edit reads the written file, so dependent edits chain naturally.
        var second = replace(run, "App.java", "int a;", "int a; int b;");
        assertThat(read("App.java")).isEqualTo("class App { int a; int b; }\n");
        var created = changes.propose(run.id(), "create", run.workspace(), "New.java", "create", "", "class New {}\n");
        assertThat(created.status()).isEqualTo("APPLIED");

        assertThatThrownBy(() -> changes.revert(second.id()))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("任务结束");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        assertThatThrownBy(() -> changes.revert(first.id())).hasMessageContaining("后续修改");
        assertThat(changes.revert(second.id()).status()).isEqualTo("REVERTED");
        assertThat(changes.revert(first.id()).status()).isEqualTo("REVERTED");
        assertThat(read("App.java")).isEqualTo("class App {}\n");
        assertThat(changes.revert(created.id()).status()).isEqualTo("REVERTED");
        assertThat(directory.resolve("New.java")).doesNotExist();
        assertThat(changes.revert(first.id()).status()).isEqualTo("REVERTED");
    }

    @Test
    void protectedPathsWaitForReviewEvenWithFullAccess() throws Exception {
        Run run = start(ApprovalMode.FULL_ACCESS);
        var workflow = replace(run, ".github/workflows/ci.yml", "on: push", "on: pull_request");
        assertThat(workflow.status()).isEqualTo("PENDING");
        assertThat(workflow.detail()).contains("受保护");
        assertThat(read(".github/workflows/ci.yml")).isEqualTo("on: push\n");
        var agents = changes.propose(run.id(), "agents", run.workspace(), "AGENTS.md", "create", "", "# rules\n");
        assertThat(agents.status()).isEqualTo("PENDING");
        assertThat(directory.resolve("AGENTS.md")).doesNotExist();
        assertThat(changes.propose(run.id(), "short", run.workspace(), "GITHUB~1.TXT", "create", "", "x\n").status())
                .isEqualTo("PENDING");
        var code = replace(run, "App.java", "class App", "final class App");
        assertThat(code.decidedBy()).isEqualTo("FULL_ACCESS");
        assertThat(read("App.java")).isEqualTo("final class App {}\n");
    }

    @Test
    void revertNeverOverwritesALaterManualEdit() throws Exception {
        Run run = start(ApprovalMode.SMART);
        var change = replace(run, "App.java", "class App", "final class App");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        Files.writeString(directory.resolve("App.java"), "edited by hand\n");
        assertThatThrownBy(() -> changes.revert(change.id())).isInstanceOf(HarnessException.class);
        assertThat(read("App.java")).isEqualTo("edited by hand\n");
        assertThat(changes.list(run.id()).getFirst().status()).isEqualTo("APPLIED");
    }
}
