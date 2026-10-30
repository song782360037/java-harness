package dev.javaharness.server;

import static dev.javaharness.adapters.plugins.PluginJson.write;
import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.plugins.PluginPackages;
import dev.javaharness.adapters.plugins.PluginService;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class PluginPackagesPathTest {
    @TempDir
    Path directory;

    /** Windows runner TEMP uses an 8.3 alias that must resolve to the same ordinary storage directory. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsShortPathCanInstallAndReadPackages() throws Exception {
        Path parent = shortDirectory();
        Path root = parent.resolve("packages"),
                real = Files.createDirectory(root).toRealPath();
        var packages = new PluginPackages(root);
        var item = packages.bundle(Map.of(
                "plugin.json",
                ("{\"$schema\":\"" + PluginPackages.SCHEMA + "\",\"name\":\"short-path\",\"version\":\"1.0.0\"}")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(packages.read(item.id(), item.digest()).name()).isEqualTo("short-path");
        assertThat(packages.path(item.id())).isEqualTo(real.resolve(item.id()));
    }

    /** Exercise both state and cwd validation before starting a real MCP process through an 8.3 path. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsShortPathCanStartConnectorInItsStateDirectory() throws Exception {
        Path root = shortDirectory().resolve("runtime");
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var sessions = new JdbcSessionStore(jdbc, tx);
        var plugins =
                new PluginService(jdbc, tx, sessions, new LocalSecretCipher(directory.resolve("settings.key")), root);
        var server = Map.of(
                "type",
                "stdio",
                "command",
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "args",
                BuiltinMcpServer.launchArguments("text"),
                "cwd",
                "${PLUGIN_DATA}");
        var item = plugins.activate(plugins.importBundle(
                Map.of(
                        "plugin.json",
                                write(Map.of(
                                                "$schema",
                                                PluginPackages.SCHEMA,
                                                "name",
                                                "short-path-mcp",
                                                "version",
                                                "1.0.0"))
                                        .getBytes(StandardCharsets.UTF_8),
                        "mcp.json",
                                write(Map.of(
                                                "$schema",
                                                "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                                                "mcpServers",
                                                Map.of("main", server)))
                                        .getBytes(StandardCharsets.UTF_8)),
                "local"));
        assertThat(write(plugins.diagnose("user", item.name()))).contains("analyze_text");
    }

    /** Obtain a genuine Windows short alias; skip only when the volume has 8.3 names disabled. */
    private Path shortDirectory() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("long plugin storage directory"));
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "for %I in (\"" + parent + "\") do @echo %~sI")
                .redirectErrorStream(true)
                .start();
        String alias;
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            alias = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset())
                    .strip();
            assertThat(process.exitValue()).as(alias).isZero();
        } finally {
            process.destroyForcibly();
        }
        Path shortPath = Path.of(alias);
        Assumptions.assumeFalse(
                shortPath.toAbsolutePath().normalize().equals(parent.toRealPath()),
                "8.3 aliases are unavailable on this volume");
        return shortPath;
    }

    /** Canonicalizing an alias must still reject storage roots redirected through a link or junction. */
    @Test
    void rejectsLinkedRootAndLinkedAncestor() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target")), link = directory.resolve("linked");
        try {
            try {
                Files.createSymbolicLink(link, target);
            } catch (java.io.IOException | UnsupportedOperationException unavailable) {
                if (!System.getProperty("os.name").startsWith("Windows")) Assumptions.abort("Symlinks unavailable");
                var process = new ProcessBuilder(
                                "cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true)
                        .start();
                try {
                    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                    String output = new String(
                            process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
                    assertThat(process.exitValue()).as(output).isZero();
                } finally {
                    process.destroyForcibly();
                }
            }
            assertThatThrownBy(() -> new PluginPackages(link)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new PluginPackages(link.resolve("packages")))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            Files.deleteIfExists(link);
        }
    }
}
