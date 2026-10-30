package dev.javaharness.adapters.plugins;

import static dev.javaharness.adapters.plugins.PluginJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.workspace.WorkspaceTools;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

/** Run-owned SDK client; captures a tool directory and refuses directory drift before calls. */
public final class PluginMcpClient implements AutoCloseable {
    private final PluginMcpTransport transport;
    private final McpAsyncClient client;
    private Map<String, McpSchema.Tool> tools;
    private final Map<String, JsonNode> schemas = new HashMap<>();
    private final int timeout;

    public record Launch(
            List<String> command,
            String cwd,
            Map<String, String> environment,
            String url,
            Map<String, String> headers,
            int timeoutSeconds) {}

    public PluginMcpClient(Launch launch) {
        timeout = launch.timeoutSeconds();
        transport = new PluginMcpTransport(
                launch.command(),
                Path.of(launch.cwd()),
                launch.environment(),
                launch.url().isEmpty() ? null : URI.create(launch.url()),
                launch.headers());
        client = McpClient.async(transport)
                .requestTimeout(Duration.ofSeconds(timeout))
                .initializationTimeout(Duration.ofSeconds(15))
                .clientInfo(new McpSchema.Implementation("java-harness", "0.1.0"))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .loggingConsumer(ignored -> Mono.empty())
                .enableCallToolSchemaCaching(false)
                .build();
    }

    /** Negotiate and capture a bounded, validated tool directory. */
    public Map<String, McpSchema.Tool> initialize(ModelProvider.Cancellation cancellation) throws Exception {
        waitFor(client.initialize(), cancellation);
        tools = discover(cancellation);
        for (String name : tools.keySet()) schemas.put(name, transport.schemaFor(name));
        return tools;
    }

    private Map<String, McpSchema.Tool> discover(ModelProvider.Cancellation cancellation) throws Exception {
        transport.beginToolDiscovery();
        var result = new LinkedHashMap<String, McpSchema.Tool>();
        var cursors = new HashSet<String>();
        String cursor = null;
        do {
            var page = waitFor(client.listTools(cursor), cancellation);
            for (var tool : page.tools()) {
                if (tool.name() == null
                        || tool.name().length() > 256
                        || tool.name().isBlank()
                        || result.putIfAbsent(tool.name(), tool) != null
                        || result.size() > 128) throw invalid("MCP 工具目录过大或重名");
                schema(transport.schemaFor(tool.name()));
            }
            cursor = page.nextCursor();
            if (cursor != null && (!cursors.add(cursor) || cursors.size() > 16)) throw invalid("MCP 工具分页异常");
        } while (cursor != null);
        if (write(result).length() > 131072) throw invalid("MCP 工具 Schema 总量过大");
        return Map.copyOf(result);
    }

    /** Preserve raw schemas because the SDK's typed schema does not retain every validation keyword. */
    public JsonNode inputSchema(String name) {
        return schemas.get(name).deepCopy();
    }

    /** Execute once against the captured directory; unknown results are not retried. */
    public WorkspaceTools.Result call(
            String tool,
            JsonNode arguments,
            ModelProvider.Cancellation cancellation,
            ExternalToolApprovals.Dispatch dispatch)
            throws Exception {
        if (!discover(cancellation).equals(tools)) throw new HarnessException("CONFLICT", "MCP 工具目录已变化，旧批准失效，请开始新任务");
        for (String name : tools.keySet())
            if (!transport.schemaFor(name).equals(schemas.get(name)))
                throw new HarnessException("CONFLICT", "MCP Schema 已变化，调用未执行");
        validate(inputSchema(tool), arguments);
        var result = waitFor(
                client.callTool(new McpSchema.CallToolRequest(
                        tool,
                        JSON.convertValue(
                                arguments,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}))),
                cancellation,
                dispatch);
        var output = new StringBuilder();
        boolean unsupported = false;
        if (result.content() != null)
            for (var content : result.content()) {
                if (content instanceof McpSchema.TextContent text)
                    output.append(text.text()).append('\n');
                else {
                    output.append("[此版本不支持该非文本内容]\n");
                    unsupported = true;
                }
            }
        if (result.structuredContent() != null) output.append(write(result.structuredContent()));
        if (output.length() > 32000)
            return new WorkspaceTools.Result(false, output.substring(0, 32000) + "\n[输出超过限制，已截断；外部操作可能已完成，不会重试]");
        return new WorkspaceTools.Result(!Boolean.TRUE.equals(result.isError()) && !unsupported, output.toString());
    }

    private <T> T waitFor(Mono<T> operation, ModelProvider.Cancellation cancellation) throws Exception {
        return waitFor(operation, cancellation, () -> {});
    }

    private <T> T waitFor(
            Mono<T> operation, ModelProvider.Cancellation cancellation, ExternalToolApprovals.Dispatch dispatch)
            throws Exception {
        cancellation.check();
        dispatch.beforeSend();
        CompletableFuture<T> future = operation.toFuture();
        long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
        try {
            while (true) {
                cancellation.check();
                if (System.nanoTime() >= deadline) throw new TimeoutException("MCP 调用超时，结果可能未知");
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waiting) {
                    /* Poll cancellation while the SDK request is pending. */
                }
            }
        } catch (ExecutionException error) {
            throw new ModelException("MCP 初始化或协议请求失败，请检查插件服务；不会自动重试");
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
                close();
            }
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            transport.close();
        }
    }
}
