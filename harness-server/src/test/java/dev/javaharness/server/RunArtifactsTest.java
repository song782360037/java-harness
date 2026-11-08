package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.adapters.workspace.RunArtifacts;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.workspace.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RunArtifactsTest {
    @TempDir
    Path directory;

    JdbcSessionStore sessions;
    FileChangeService changes;
    RunArtifacts artifacts;
    List<List<String>> launched = new ArrayList<>();
    String workspace;
    Run run;

    @BeforeEach
    void setup() throws Exception {
        workspace = directory.toRealPath().toString();
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx);
        changes = new FileChangeService(jdbc, tx, sessions);
        artifacts = new RunArtifacts(changes, sessions, launched::add, "Windows 11");
        var session = sessions.createSession("artifacts", workspace);
        var result = sessions.submit(
                        session.id(), UUID.randomUUID().toString(), "edit", "NORMAL",
                        new ModelInfo("openai-compatible", "test", true))
                .run();
        run = sessions.transition(result.id(), RunStatus.RUNNING, "");
        Files.writeString(directory.resolve("App.java"), "class App { int value = 1; }\n");
    }

    FileChange propose(String path, String operation, String oldText, String newText) {
        return changes.propose(run.id(), UUID.randomUUID().toString(), workspace, path, operation, oldText, newText);
    }

    @Test
    void listsOnlyAppliedFilesOncePerPathWithDiskState() throws Exception {
        var first = propose("App.java", "replace", "value = 1", "value = 2");
        var created = propose("notes.md", "create", "", "# notes\n");
        var pending = propose("build.sh", "create", "", "echo hi\n");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        changes.apply(first.id());
        changes.apply(created.id());

        var list = artifacts.list(run.id());
        assertThat(list).extracting(RunArtifacts.Artifact::path).containsExactlyInAnyOrder("App.java", "notes.md");
        assertThat(list).noneMatch(value -> value.changeId().equals(pending.id()));
        var notes = list.stream().filter(value -> value.changeId().equals(created.id())).findFirst().orElseThrow();
        assertThat(notes.operation()).isEqualTo("create");
        assertThat(notes.state()).isEqualTo("FILE");
        assertThat(notes.size()).isEqualTo(8);
        assertThat(notes.modifiedAt()).isNotNull();
        assertThat(notes.openable()).isTrue();

        Files.delete(directory.resolve("notes.md"));
        assertThat(artifacts.list(run.id()))
                .filteredOn(value -> value.changeId().equals(created.id()))
                .extracting(RunArtifacts.Artifact::state)
                .containsExactly("MISSING");
    }

    @Test
    void contentReadsCurrentDiskTextAndRejectsUnappliedChanges() throws Exception {
        var change = propose("App.java", "replace", "value = 1", "value = 2");
        assertThatThrownBy(() -> artifacts.content(change.id())).isInstanceOf(HarnessException.class);
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        changes.apply(change.id());
        Files.writeString(directory.resolve("App.java"), "edited later\n");
        assertThat(artifacts.content(change.id()).content()).isEqualTo("edited later\n");
        Files.delete(directory.resolve("App.java"));
        assertThatThrownBy(() -> artifacts.content(change.id()))
                .isInstanceOfSatisfying(HarnessException.class, error -> assertThat(error.code()).isEqualTo("CONFLICT"));
    }

    @Test
    void openLaunchesExplorerWithoutShellAndRefusesExecutables() throws Exception {
        var notes = propose("my notes.md", "create", "", "# notes\n");
        var script = propose("run.cmd", "create", "", "echo hi\r\n");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        changes.apply(notes.id());
        changes.apply(script.id());
        String file = directory.toRealPath().resolve("my notes.md").toString();

        artifacts.open(notes.id(), "open");
        artifacts.open(notes.id(), "reveal");
        assertThat(launched).containsExactly(List.of("explorer.exe", file), List.of("explorer.exe", "/select,", file));

        assertThatThrownBy(() -> artifacts.open(script.id(), "open")).isInstanceOf(HarnessException.class);
        assertThat(artifacts.list(run.id()))
                .filteredOn(value -> value.changeId().equals(script.id()))
                .singleElement()
                .satisfies(value -> assertThat(value.openable()).isFalse());
        artifacts.open(script.id(), "reveal");
        assertThat(launched).hasSize(3);
        assertThatThrownBy(() -> artifacts.open(notes.id(), "delete")).isInstanceOf(HarnessException.class);
    }

    @Test
    void commaCannotTruncateOpenedPathAndPlatformsUseOwnCommands() throws Exception {
        Files.writeString(directory.resolve("run.cmd"), "echo hi\r\n");
        var comma = propose("run.cmd,x.md", "create", "", "# notes\n");
        var page = propose("report.html", "create", "", "<p>hi</p>\n");
        var notes = propose("notes.md", "create", "", "# notes\n");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        changes.apply(comma.id());
        changes.apply(page.id());
        changes.apply(notes.id());
        assertThat(artifacts.list(run.id())).filteredOn(value -> !value.path().equals("notes.md"))
                .noneMatch(RunArtifacts.Artifact::openable);
        assertThatThrownBy(() -> artifacts.open(comma.id(), "open")).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> artifacts.open(page.id(), "open")).isInstanceOf(HarnessException.class);
        assertThat(launched).isEmpty();

        String file = directory.toRealPath().resolve("notes.md").toString();
        new RunArtifacts(changes, sessions, launched::add, "Mac OS X").open(notes.id(), "reveal");
        new RunArtifacts(changes, sessions, launched::add, "Linux").open(notes.id(), "reveal");
        assertThat(launched).containsExactly(
                List.of("open", "-R", file), List.of("xdg-open", directory.toRealPath().toString()));
    }

    @Test
    void openFailsWhenFileWasRemovedOrLauncherIsUnavailable() throws Exception {
        var notes = propose("notes.md", "create", "", "# notes\n");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        changes.apply(notes.id());
        var headless = new RunArtifacts(changes, sessions, command -> { throw new IOException("no display"); }, "Linux");
        assertThatThrownBy(() -> headless.open(notes.id(), "open"))
                .isInstanceOfSatisfying(HarnessException.class, error -> assertThat(error.code()).isEqualTo("UNAVAILABLE"));
        Files.delete(directory.resolve("notes.md"));
        assertThatThrownBy(() -> artifacts.open(notes.id(), "reveal"))
                .isInstanceOfSatisfying(HarnessException.class, error -> assertThat(error.code()).isEqualTo("CONFLICT"));
        assertThat(launched).isEmpty();
    }

    @Test
    void symbolicLinkReplacingArtifactIsNeverFollowed() throws Exception {
        var notes = propose("notes.md", "create", "", "# notes\n");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        changes.apply(notes.id());
        Path outside = Files.createTempFile("outside", ".md");
        Files.delete(directory.resolve("notes.md"));
        try {
            Files.createSymbolicLink(directory.resolve("notes.md"), outside);
        } catch (IOException | UnsupportedOperationException error) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are not available: " + error.getMessage());
        } finally {
            outside.toFile().deleteOnExit();
        }
        assertThat(artifacts.list(run.id()).getFirst().state()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(() -> artifacts.content(notes.id())).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> artifacts.open(notes.id(), "open")).isInstanceOf(HarnessException.class);
        assertThat(launched).isEmpty();
    }
}
