package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.settings.HarnessSettings;
import dev.javaharness.adapters.settings.JdbcSettingsStore;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.server.service.SettingsService;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the chain SettingsService actually assembles: git tools must be reachable through the
 * review and command wrappers, and must stay hidden for a workspace that is not a repository root.
 */
class GitToolAssemblyTest {
    @TempDir
    Path directory;

    private static void git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
    }

    SettingsService settings(JdbcTemplate jdbc, SessionStore sessions) {
        var tx =
                new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        return new SettingsService(
                new JdbcSettingsStore(jdbc, new LocalSecretCipher(directory.resolve("settings.key"))),
                new HarnessSettings(
                        1, "https://api.example.com/v1", "assembly-key", "assembly-model", true, 10, 120, 32000, 0, 5),
                new FileChangeService(jdbc, tx, sessions),
                new CommandService(jdbc, tx, sessions));
    }

    @Test
    void assembledChainExposesGitToolsInsideARepositoryRoot() throws Exception {
        try {
            git(directory, "--version");
        } catch (IOException unavailable) {
            abort("git unavailable: " + unavailable.getMessage());
        }
        git(directory, "init", "-q", "-b", "main", ".");
        git(directory, "config", "user.name", "tester");
        git(directory, "config", "user.email", "tester@example.com");
        Files.writeString(directory.resolve("tracked.txt"), "content\n");
        git(directory, "add", "-A");
        git(directory, "commit", "-qm", "init");

        var ds = new DriverManagerDataSource("jdbc:h2:mem:git-assembly;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var sessions = new JdbcSessionStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        var service = settings(jdbc, sessions);
        // Initializing a non-empty key writes settings.key into the workspace, which the name policy excludes.
        assertThat(directory.resolve("settings.key")).exists();
        Files.writeString(directory.resolve("tracked.txt"), "modified\n");

        try (var resources =
                service.snapshot().resources().open(directory.toRealPath().toString())) {
            assertThat(resources.tools().definitions())
                    .extracting(ToolDefinition::name)
                    .contains("git_status", "git_diff", "propose_file_change", "propose_command", "read_file");
            // The wrappers must route a git call through to the innermost implementation.
            var result = resources.tools().execute("run-1", new ToolCall("c1", "git_status", "{}"), () -> {});
            assertThat(result.success()).isTrue();
            assertThat(result.output()).contains("main", "tracked.txt", "共 1 处未提交改动");
            assertThat(result.output()).doesNotContain("settings.key");
        }

        // A plain directory keeps the same chain without the git tools.
        Path plain = Files.createDirectory(directory.resolve("plain"));
        try (var resources =
                service.snapshot().resources().open(plain.toRealPath().toString())) {
            assertThat(resources.tools().definitions())
                    .extracting(ToolDefinition::name)
                    .doesNotContain("git_status", "git_diff")
                    .contains("propose_file_change", "propose_command", "read_file");
            assertThat(resources
                            .tools()
                            .execute("run-2", new ToolCall("c2", "git_status", "{}"), () -> {})
                            .success())
                    .isFalse();
        }
    }
}
