package dev.javaharness.adapters;

import dev.javaharness.spi.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Noninteractive shell execution. This is NOT an OS sandbox. */
public final class LocalCommandRunner {
    public static final int MAX_OUTPUT = 32000;
    public static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    public static String shell() { return WINDOWS ? "PowerShell" : "sh"; }
    public record Outcome(String status, String stdout, String stderr, Integer exitCode, String detail) {}

    public Outcome run(String command, Path directory, int timeoutSeconds, ModelProvider.Cancellation cancellation,
                       BooleanSupplier stopped, BiConsumer<String, String> output) throws Exception {
        cancellation.check();
        var builder = new ProcessBuilder(arguments(command)).directory(directory.toFile());
        // Model credentials are never deliberately supplied to a command.
        builder.environment().keySet().removeIf(key -> key.equalsIgnoreCase("HARNESS_MODEL_API_KEY"));
        Process process = builder.start();
        var capture = new Capture();
        var descendants = new HashSet<ProcessHandle>();
        Thread out = Thread.ofVirtual().start(() -> capture.read(process.getInputStream(), false));
        Thread err = Thread.ofVirtual().start(() -> capture.read(process.getErrorStream(), true));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        String status = "COMPLETED", detail = "命令执行完成";
        Integer exit = null;
        try {
            process.getOutputStream().close();
            while (true) {
                process.descendants().forEach(descendants::add);
                cancellation.check();
                if (stopped.getAsBoolean()) { status = "CANCELLED"; detail = "命令已停止"; break; }
                if (capture.exceeded()) { status = "OUTPUT_LIMIT"; detail = "输出超过 32000 字符，命令已停止，保留截断输出"; break; }
                if (System.nanoTime() >= deadline) { status = "TIMED_OUT"; detail = "命令超过审批的执行时限，已停止"; break; }
                var text = capture.snapshot(); output.accept(text[0], text[1]);
                if (process.waitFor(150, TimeUnit.MILLISECONDS)) {
                    exit = process.exitValue();
                    if (exit != 0) { status = "FAILED"; detail = "命令以非零退出码结束"; }
                    break;
                }
            }
        } finally {
            // Cleanup also runs for engine cancellation, SQL output failures and stream failures.
            boolean interrupted = Thread.interrupted();
            try {
                terminate(process, descendants);
                out.join(2000); err.join(2000);
                process.getInputStream().close(); process.getErrorStream().close();
            } finally {
                try { var text = capture.snapshot(); output.accept(text[0], text[1]); }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
        var text = capture.snapshot(); output.accept(text[0], text[1]);
        if (capture.exceeded()) { status = "OUTPUT_LIMIT"; detail = "输出超过 32000 字符，保留截断输出"; }
        else if (capture.failed() && status.equals("COMPLETED")) { status = "FAILED"; detail = "命令输出读取不完整"; }
        return new Outcome(status, text[0], text[1], exit, detail);
    }

    private static List<String> arguments(String command) {
        if (!WINDOWS) return List.of("/bin/sh", "-c", command);
        String script = "$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false);\n"
                + "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue';\n" + command
                + "\nif (-not $?) { exit 1 }; if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }";
        // -EncodedCommand serializes Windows PowerShell errors as CLIXML. Passing raw -Command
        // loses nested quotes through Windows argv parsing. Decode an ASCII-only payload inside
        // -Command instead, preserving exactly the script the user approved and plain text streams.
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        String invocation = "try { & ([scriptblock]::Create([Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('"
                + encoded + "')))) } catch { [Console]::Error.WriteLine($_.ToString()); exit 1 }";
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return List.of(Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-Command", invocation);
    }

    private static void terminate(Process process, Set<ProcessHandle> descendants) throws Exception {
        process.descendants().forEach(descendants::add);
        if (WINDOWS && process.isAlive()) {
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            try {
                Process kill = new ProcessBuilder(Path.of(systemRoot, "System32", "taskkill.exe").toString(),
                    "/PID", Long.toString(process.pid()), "/T", "/F")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!kill.waitFor(3, TimeUnit.SECONDS)) kill.destroyForcibly();
            } catch (IOException unavailable) { /* Fall back to the captured process handles. */ }
        }
        descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
        if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException("无法确认命令进程已退出");
    }

    private static final class Capture {
        private final StringBuilder out = new StringBuilder(), err = new StringBuilder();
        private boolean exceeded, failed;
        void read(InputStream input, boolean error) {
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048]; int count;
                while ((count = reader.read(buffer)) != -1) append(error, buffer, count);
            } catch (IOException problem) { synchronized (this) { failed = true; } }
        }
        private synchronized void append(boolean error, char[] buffer, int count) {
            int remaining = MAX_OUTPUT - out.length() - err.length();
            (error ? err : out).append(buffer, 0, Math.min(count, remaining));
            if (count > remaining) exceeded = true;
        }
        synchronized String[] snapshot() { return new String[]{out.toString(), err.toString()}; }
        synchronized boolean exceeded() { return exceeded; }
        synchronized boolean failed() { return failed; }
    }
}
