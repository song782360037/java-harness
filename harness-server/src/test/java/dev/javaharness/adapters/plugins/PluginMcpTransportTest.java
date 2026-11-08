package dev.javaharness.adapters.plugins;

import static org.assertj.core.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class PluginMcpTransportTest {
    private static final String JAVA = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();

    @TempDir
    Path directory;

    /** Closing native and batch servers must release their working directories before returning. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void closeReleasesWindowsWorkingDirectory() throws Exception {
        Path node = Arrays.stream(
                        System.getenv("PATH").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .map(path -> path.resolve("node.exe"))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
        Assumptions.assumeTrue(node != null, "Node is not installed on this Windows host");
        Path script = directory.resolve("server.cjs");
        Files.writeString(script, """
                process.stdout.write('{"jsonrpc":"2.0","method":"ready"}\\n');
                process.stdin.resume();
                """);
        Path batch = directory.resolve("server.cmd");
        Files.writeString(batch, "@\"" + node + "\" \"" + script + "\"\r\n");
        var commands = List.of(
                List.of(JAVA, "-cp", System.getProperty("java.class.path"), Probe.class.getName()),
                PluginProcessCommand.prepare(List.of(batch.toString())));
        for (var command : commands) {
            for (int index = 0; index < 30; index++) {
                Path cwd = Files.createTempDirectory(directory, "server-");
                var ready = new CountDownLatch(1);
                var transport = new PluginMcpTransport(command, cwd, System.getenv(), null, java.util.Map.of());
                try {
                    transport
                            .connect(message -> message.doOnNext(ignored -> ready.countDown())
                                    .then(Mono.empty()))
                            .block();
                    assertThat(ready.await(10, TimeUnit.SECONDS))
                            .as("server became ready")
                            .isTrue();
                } finally {
                    transport.close();
                }
                try {
                    Files.delete(cwd);
                } catch (java.io.IOException error) {
                    throw new AssertionError("command=" + command + ", iteration=" + index, error);
                }
            }
        }
    }

    /** A server that stops reading must not prevent cancellation while a large write is pending. */
    @Test
    void closeTerminatesServerWithBlockedInput() throws Exception {
        var ready = new CountDownLatch(1);
        var transport = new PluginMcpTransport(
                List.of(JAVA, "-cp", System.getProperty("java.class.path"), Probe.class.getName(), "blocked"),
                directory,
                System.getenv(),
                null,
                Map.of());
        ProcessHandle server = null;
        try {
            transport
                    .connect(message ->
                            message.doOnNext(ignored -> ready.countDown()).then(Mono.empty()))
                    .block();
            var write = transport
                    .sendMessage(new McpSchema.JSONRPCNotification("2.0", "test", Map.of("data", "x".repeat(524288))))
                    .toFuture();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            server = ProcessHandle.of(Long.parseLong(Files.readString(directory.resolve("pid"))))
                    .orElseThrow();
            assertThat(write).isNotDone();
            CompletableFuture.runAsync(transport::close).get(8, TimeUnit.SECONDS);
            assertThat(server.isAlive()).isFalse();
        } finally {
            if (server != null && server.isAlive()) server.destroyForcibly();
            transport.close();
        }
    }

    public static final class Probe {
        /** Hold a real Java server process until the transport closes it. */
        public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("child")) {
                System.out.println("{\"jsonrpc\":\"2.0\",\"method\":\"ready\"}");
                while (System.in.read() != -1) {}
                var child = new ProcessBuilder(
                                JAVA, "-cp", System.getProperty("java.class.path"), Probe.class.getName(), "blocked")
                        .start();
                Files.writeString(Path.of("pid"), Long.toString(child.pid()));
                Thread.sleep(200);
                Files.writeString(Path.of("parent-exit"), "exiting");
                return;
            }
            if (args.length > 0) {
                Files.writeString(
                        Path.of("pid"), Long.toString(ProcessHandle.current().pid()));
                System.in.read();
                System.out.println("{\"jsonrpc\":\"2.0\",\"method\":\"ready\"}");
                Thread.sleep(60000);
                return;
            }
            System.out.println("{\"jsonrpc\":\"2.0\",\"method\":\"ready\"}");
            while (System.in.read() != -1) {}
        }
    }

    /** Children started during EOF shutdown remain owned even if their parent exits first. */
    @Test
    void closeTerminatesChildrenStartedDuringShutdown() throws Exception {
        var ready = new CountDownLatch(1);
        var transport = new PluginMcpTransport(
                List.of(JAVA, "-cp", System.getProperty("java.class.path"), Probe.class.getName(), "child"),
                directory,
                System.getenv(),
                null,
                Map.of());
        ProcessHandle child = null;
        try {
            transport
                    .connect(message ->
                            message.doOnNext(ignored -> ready.countDown()).then(Mono.empty()))
                    .block();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            transport.close();
            assertThat(directory.resolve("parent-exit")).exists();
            child = ProcessHandle.of(Long.parseLong(Files.readString(directory.resolve("pid"))))
                    .orElse(null);
            assertThat(child == null || !child.isAlive())
                    .as("shutdown child exited")
                    .isTrue();
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.onExit().get(3, TimeUnit.SECONDS);
            }
            transport.close();
        }
    }
}
