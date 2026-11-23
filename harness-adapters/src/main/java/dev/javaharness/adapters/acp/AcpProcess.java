package dev.javaharness.adapters.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javaharness.spi.HarnessException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Owns one local ACP v1 stdio connection with bounded, independent pipe workers. */
public final class AcpProcess implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FRAME = 512 * 1024;
    private static final int MAX_STDERR = 2048;
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private final ArrayBlockingQueue<JsonNode> incoming = new ArrayBlockingQueue<>(64);
    private final ArrayBlockingQueue<String> outgoing = new ArrayBlockingQueue<>(32);
    private final Set<ProcessHandle> descendants = new HashSet<>();
    private final StringBuilder stderrBuffer = new StringBuilder();
    private final Process process;
    private final BufferedWriter input;
    private final Thread reader;
    private final Thread writer;
    private final Thread stderrReader;
    private volatile String broken;
    private volatile String writeFailure;
    private int id;

    /** Resolve a trusted local command before starting it; never invoke a shell or package installer. */
    public AcpProcess(String executable, List<String> args, String preset, Path cwd, boolean workspaceWrite) {
        var command = command(executable, preset);
        command.addAll(args);
        var builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.environment()
                .keySet()
                .removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("HARNESS_"));
        if (preset.equals("codex")) {
            builder.environment().put("INITIAL_AGENT_MODE", workspaceWrite ? "workspace-write" : "read-only");
        }
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new HarnessException("INVALID", "无法启动 ACP 程序，请检查可执行文件及其依赖");
        }
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = Thread.ofPlatform().daemon().name("acp-stdout").start(this::read);
        writer = Thread.ofPlatform().daemon().name("acp-stdin").start(this::write);
        stderrReader = Thread.ofPlatform().daemon().name("acp-stderr").start(this::readStderr);
    }

    /** Send a request and return its connection-local correlation ID. */
    public int request(String method, Object params) throws IOException {
        int request = ++id;
        send(message(method, params).put("id", request));
        return request;
    }

    /** Send a notification without waiting for a response. */
    public void notify(String method, Object params) throws IOException {
        send(message(method, params));
    }

    /** Reply with a result, echoing the agent's opaque JSON-RPC ID. */
    public void reply(JsonNode request, Object result) throws IOException {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request);
        response.set("result", JSON.valueToTree(result));
        send(response);
    }

    /** Reject a reverse request for a capability we do not provide. */
    public void unsupported(JsonNode request) throws IOException {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request);
        response.putObject("error").put("code", -32601).put("message", "Unsupported client method");
        send(response);
    }

    /** Poll in short intervals so the owner can react to cancellation and track child processes. */
    public JsonNode poll() throws IOException, InterruptedException {
        process.descendants().forEach(descendants::add);
        if (writeFailure != null) throw new IOException(writeFailure);
        JsonNode next = incoming.poll(80, TimeUnit.MILLISECONDS);
        // Wait for stdout reader EOF (`broken`) instead of `!process.isAlive()`, so buffered final frames are not lost.
        if (next == null && broken != null) throw new IOException(broken);
        return next;
    }

    /** Return a compact tail of stderr output for diagnostics without exposing unbounded process logs. */
    public String stderrSummary() {
        synchronized (stderrBuffer) {
            String text = stderrBuffer.toString().replaceAll("\\s+", " ").strip();
            return text.length() <= 240 ? text : "…" + text.substring(text.length() - 240);
        }
    }

    @Override
    public void close() {
        process.descendants().forEach(descendants::add);
        reader.interrupt();
        writer.interrupt();
        stderrReader.interrupt();
        // Kill tracked children even if the adapter already exited or ignores closing stdin.
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
            for (ProcessHandle child : descendants) if (child.isAlive()) child.destroyForcibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static ArrayList<String> command(String executable, String preset) {
        Path path = safePath(executable);
        if (path == null) throw new HarnessException("INVALID", "无效的可执行文件路径");
        if (path.isAbsolute() && runnable(path)) return new ArrayList<>(List.of(path.toString()));
        if (path.isAbsolute() && WINDOWS && Files.isRegularFile(path) && presetExecutable(path, preset)) {
            var npm = npmCommand(path.getParent(), preset);
            if (npm != null) return npm;
        }
        if (!path.isAbsolute() && path.getNameCount() == 1) {
            String search = System.getenv("PATH");
            String fileName =
                    WINDOWS && !executable.toLowerCase(Locale.ROOT).endsWith(".exe") ? executable + ".exe" : executable;
            if (search != null)
                for (String directory : search.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (directory.isBlank()) continue;
                    Path root = safePath(directory.replace("\"", ""));
                    if (root == null || !root.isAbsolute()) continue;
                    Path candidate = root.resolve(fileName);
                    if (runnable(candidate)) return new ArrayList<>(List.of(candidate.toString()));
                    if (WINDOWS && presetExecutable(path, preset)) {
                        var npm = npmCommand(root, preset);
                        if (npm != null) return npm;
                    }
                }
        }
        throw new HarnessException("NOT_FOUND", "找不到 ACP 可执行程序；请选择已安装的程序绝对路径");
    }

    private static Path safePath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static boolean runnable(Path candidate) {
        return Files.isRegularFile(candidate)
                && Files.isExecutable(candidate)
                && (!WINDOWS || candidate.toString().toLowerCase(Locale.ROOT).endsWith(".exe"));
    }

    private static boolean presetExecutable(Path path, String preset) {
        String bin = switch (preset) {
            case "codex" -> "codex-acp";
            case "claude" -> "claude-agent-acp";
            default -> "";
        };
        if (bin.isEmpty()) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(bin) || name.equals(bin + ".cmd") || name.equals(bin + ".ps1");
    }

    private static ArrayList<String> npmCommand(Path root, String preset) {
        String bin = preset.equals("codex") ? "codex-acp" : "claude-agent-acp";
        Path pkg = root.resolve("node_modules").resolve("@agentclientprotocol").resolve(bin);
        Path node = root.resolve("node.exe");
        if (!runnable(node)) {
            String search = System.getenv("PATH");
            if (search != null)
                for (String entry : search.split(";")) {
                    Path dir = safePath(entry.replace("\"", ""));
                    if (dir == null || !dir.isAbsolute()) continue;
                    Path candidate = dir.resolve("node.exe");
                    if (runnable(candidate)) {
                        node = candidate;
                        break;
                    }
                }
        }
        if (!runnable(node) || !Files.isRegularFile(pkg.resolve("package.json"))) return null;
        try {
            JsonNode manifest = JSON.readTree(pkg.resolve("package.json").toFile());
            JsonNode entry = manifest.path("bin");
            String script = entry.isTextual() ? entry.asText() : entry.path(bin).asText();
            Path resolved = pkg.resolve(script).normalize();
            if (resolved.startsWith(pkg) && Files.isRegularFile(resolved))
                return new ArrayList<>(List.of(node.toString(), resolved.toString()));
        } catch (IOException ignored) {
            // Treat an incomplete npm install as unavailable.
        }
        return null;
    }

    private static ObjectNode message(String method, Object params) {
        ObjectNode message = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        message.set("params", JSON.valueToTree(params));
        return message;
    }

    private void send(ObjectNode message) throws IOException {
        String frame = JSON.writeValueAsString(message);
        if (frame.length() > MAX_FRAME || !outgoing.offer(frame)) throw new IOException("ACP input limit exceeded");
    }

    private void write() {
        try (input) {
            while (true) {
                input.write(outgoing.take());
                input.write('\n');
                input.flush();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            writeFailure = "ACP stdin 已断开";
        }
    }

    private void read() {
        try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            var line = new StringBuilder();
            for (int ch; (ch = output.read()) != -1; ) {
                if (ch == '\n') {
                    int length = line.length();
                    if (length > 0 && line.charAt(length - 1) == '\r') line.setLength(length - 1);
                    if (!line.toString().isBlank()) {
                        JsonNode value = JSON.readTree(line.toString());
                        if (value == null
                                || !value.isObject()
                                || !"2.0".equals(value.path("jsonrpc").asText()))
                            throw new IOException("Invalid ACP envelope");
                        incoming.put(value);
                    }
                    line.setLength(0);
                } else {
                    if (line.length() >= MAX_FRAME) throw new IOException("ACP frame too large");
                    line.append((char) ch);
                }
            }
            broken = "ACP 进程已退出";
        } catch (IOException error) {
            broken = "ACP 输出无效或连接已断开";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (broken == null) broken = "ACP 进程已退出";
        }
    }

    private void readStderr() {
        try (var errorStream =
                new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            char[] chunk = new char[512];
            for (int count; (count = errorStream.read(chunk)) != -1; ) {
                synchronized (stderrBuffer) {
                    stderrBuffer.append(chunk, 0, count);
                    if (stderrBuffer.length() > MAX_STDERR) stderrBuffer.delete(0, stderrBuffer.length() - MAX_STDERR);
                }
            }
        } catch (IOException ignored) {
            // Best-effort diagnostics only; closing the process tears down stderr.
        }
    }
}
