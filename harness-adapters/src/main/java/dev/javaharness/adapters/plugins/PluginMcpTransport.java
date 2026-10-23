package dev.javaharness.adapters.plugins;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.*;
import reactor.core.publisher.Mono;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static dev.javaharness.adapters.plugins.PluginJson.*;

/** Bounded stdio/Streamable HTTP framing for the official SDK, with host-owned process cleanup. */
final class PluginMcpTransport implements McpClientTransport {
    private static final int MAX_FRAME = 1_048_576;
    private final JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(JSON);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
    private final Map<String, com.fasterxml.jackson.databind.JsonNode> rawSchemas = new ConcurrentHashMap<>();
    private final List<String> command;
    private final Path cwd;
    private final Map<String, String> environment;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean closed;
    private volatile Process process;
    private volatile String sessionId;
    private volatile String protocol = "2025-06-18";
    private volatile Consumer<Throwable> errors = ignored -> {};
    private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

    PluginMcpTransport(List<String> command, Path cwd, Map<String, String> environment, URI endpoint, Map<String, String> headers) {
        this.command = command; this.cwd = cwd; this.environment = environment; this.endpoint = endpoint; this.headers = headers;
    }

    @Override public List<String> protocolVersions() { return List.of("2025-06-18", "2025-03-26", "2024-11-05"); }
    @Override public void setExceptionHandler(Consumer<Throwable> errors) { this.errors = errors; }

    /** Install the SDK message handler before starting any external process. */
    @Override public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.fromRunnable(() -> {
            this.handler = handler;
            if (endpoint != null) return;
            synchronized (this) {
                if (closed) throw new IllegalStateException("MCP 已关闭");
                try {
                    var builder = new ProcessBuilder(command).directory(cwd.toFile());
                    builder.environment().clear(); builder.environment().putAll(environment);
                    process = builder.start();
                } catch (IOException error) { throw invalid("MCP 程序无法启动，请检查启动配置"); }
            }
            workers.execute(() -> {
                try (InputStream input = new BufferedInputStream(process.getInputStream())) {
                    String line; int count = 0;
                    while (!closed && (line = line(input)) != null) {
                        if (++count > 4096) throw new IOException("MCP 消息数量超过限制");
                        if (!line.isBlank()) receive(line);
                    }
                    if (!closed) errors.accept(new IOException("MCP 进程已断开"));
                } catch (Exception error) { if (!closed) errors.accept(new IOException("MCP 协议或输出超过限制")); }
            });
            workers.execute(() -> {
                try (InputStream input = process.getErrorStream()) {
                    byte[] buffer = new byte[4096]; long total = 0; int count;
                    while ((count = input.read(buffer)) != -1) if ((total += count) > 1_048_576) { errors.accept(new IOException("MCP stderr 超过限制")); close(); break; }
                } catch (IOException ignored) { /* The owner closes streams during cancellation. */ }
            });
            workers.execute(() -> {
                try { while (!closed && process.isAlive()) { process.descendants().forEach(descendants::add); Thread.sleep(100); } }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
        });
    }

    /** Send exactly once; transport failure never replays a tool invocation. */
    @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromFuture(() -> CompletableFuture.runAsync(() -> {
            if (closed) throw new IllegalStateException("MCP 已关闭");
            try {
                String body = mapper.writeValueAsString(message);
                if (body.length() > MAX_FRAME) throw new IOException("MCP 请求过大");
                if (endpoint == null) {
                    OutputStream output = process.getOutputStream();
                    synchronized (output) { output.write((body + "\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }
                } else post(body);
            } catch (Exception error) { throw new CompletionException(new IOException("MCP 连接或协议失败，调用不会自动重试")); }
        }, workers));
    }

    private void post(String body) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        headers.forEach(request::header);
        if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
        if (!parse(body).path("method").asText().equals("initialize")) request.header("MCP-Protocol-Version", protocol);
        var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = new BufferedInputStream(response.body())) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("MCP HTTP " + response.statusCode());
            String receivedSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (receivedSession != null) {
                if (receivedSession.length() > 256 || sessionId != null && !sessionId.equals(receivedSession)) throw new IOException("MCP 会话改变");
                sessionId = receivedSession;
            }
            if (response.statusCode() == 202 || response.statusCode() == 204) return;
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (type.startsWith("application/json")) {
                byte[] bytes = input.readNBytes(MAX_FRAME + 1);
                if (bytes.length > MAX_FRAME) throw new IOException("MCP 响应过大");
                receive(new String(bytes, StandardCharsets.UTF_8));
            } else if (type.startsWith("text/event-stream")) {
                var data = new StringBuilder(); String line; int total = 0;
                while (!closed && (line = line(input)) != null) {
                    if ((total += line.length()) > MAX_FRAME) throw new IOException("MCP SSE 响应过大");
                    if (line.isEmpty() && !data.isEmpty()) {
                        var node = parse(data.toString()); receive(data.toString()); data.setLength(0);
                        if (node.has("result") || node.has("error")) return;
                    } else if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()).append('\n');
                }
                if (!data.isEmpty()) receive(data.toString());
            } else throw new IOException("MCP 响应类型不支持");
        }
    }

    private void receive(String value) throws Exception {
        var node = parse(value);
        if (node.path("result").path("tools").size() > 128) throw invalid("MCP 工具目录过大");
        if (node.path("result").path("tools").isArray()) for (var tool : node.path("result").path("tools")) {
            schema(tool.path("inputSchema"));
            if (!tool.path("inputSchema").path("type").asText().equals("object")) throw invalid("MCP 工具输入必须是 object");
            rawSchemas.put(tool.path("name").asText(), tool.path("inputSchema").deepCopy());
        }
        if (node.path("result").has("protocolVersion")) protocol = node.path("result").path("protocolVersion").asText();
        handler.apply(Mono.just(McpSchema.deserializeJsonRpcMessage(mapper, value))).block(Duration.ofSeconds(10));
    }

    /** Read one UTF-8 frame without allowing BufferedReader.readLine to grow unbounded. */
    private static String line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream(); int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() >= MAX_FRAME) throw new IOException("MCP 行过大");
            bytes.write(value);
        }
        return value == -1 && bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8).replaceFirst("\\r$", "");
    }

    @Override public <T> T unmarshalFrom(Object data, TypeRef<T> type) { return mapper.convertValue(data, type); }
    com.fasterxml.jackson.databind.JsonNode schemaFor(String name) { return rawSchemas.get(name).deepCopy(); }
    void beginToolDiscovery() { rawSchemas.clear(); }
    @Override public Mono<Void> closeGracefully() { return Mono.fromRunnable(this::close); }

    /** Stop readers and the owned process tree before releasing the run lease. */
    @Override public void close() {
        Process owned;
        synchronized (this) { if (closed) return; closed = true; owned = process; }
        boolean interrupted = Thread.interrupted();
        try {
            if (endpoint != null && sessionId != null) {
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).header("Mcp-Session-Id", sessionId).header("MCP-Protocol-Version", protocol);
                headers.forEach(request::header);
                try (InputStream ignored = http.send(request.DELETE().build(), HttpResponse.BodyHandlers.ofInputStream()).body()) { /* A session DELETE is best-effort and never replays a tool. */ }
                catch (IOException ignored) { /* Stateless or expired servers may reject DELETE. */ }
            }
        } catch (InterruptedException error) { interrupted = true; }
        finally { http.shutdownNow(); workers.shutdownNow(); }
        try {
            if (owned == null) return;
            owned.descendants().forEach(descendants::add);
            for (ProcessHandle child : descendants) if (child.isAlive()) child.destroyForcibly();
            owned.destroyForcibly();
            if (!owned.waitFor(3, TimeUnit.SECONDS)) throw new IllegalStateException("无法确认 MCP 进程退出");
            for (ProcessHandle child : descendants) if (child.isAlive()) child.onExit().get(3, TimeUnit.SECONDS);
        } catch (Exception error) { throw new IllegalStateException("无法确认 MCP 进程树退出"); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
