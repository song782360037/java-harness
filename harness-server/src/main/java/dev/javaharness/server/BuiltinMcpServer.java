package dev.javaharness.server;

import dev.javaharness.adapters.plugins.PluginJson;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** First-party MCP utilities run in their own JVM and only process explicit tool input. */
public final class BuiltinMcpServer {
    private BuiltinMcpServer() {}

    /** Resolve both IDE classpaths and Spring Boot executable JARs to an independent stdio process. */
    static List<String> launchArguments(String feature) {
        var entries = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(value -> Path.of(value).toAbsolutePath().normalize().toString()).toList();
        boolean boot = false;
        if (entries.size() == 1 && entries.getFirst().endsWith(".jar")) {
            try (var jar = new JarFile(entries.getFirst())) { boot = jar.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") != null; }
            catch (java.io.IOException error) { throw new IllegalStateException("无法定位内置连接器运行环境", error); }
        }
        var args = new ArrayList<String>();
        if (boot) args.add("-Dloader.main=" + BuiltinMcpServer.class.getName());
        args.add("-cp"); args.add(String.join(File.pathSeparator, entries));
        args.add(boot ? "org.springframework.boot.loader.launch.PropertiesLauncher" : BuiltinMcpServer.class.getName());
        args.add(feature); return List.copyOf(args);
    }

    /** stdout contains MCP frames only; framework diagnostics go to stderr in this child process. */
    public static void main(String[] args) throws Exception {
        System.setProperty("logback.configurationFile", Objects.requireNonNull(BuiltinMcpServer.class.getResource("/extensions/mcp-logback.xml")).toString());
        if (args.length != 1 || !Set.of("text", "json", "time").contains(args[0])) throw new IllegalArgumentException("Unknown built-in connector");
        String feature = args[0]; var mapper = new JacksonMcpJsonMapper(PluginJson.JSON);
        String property = feature.equals("time") ? "timezone" : feature.equals("json") ? "json" : "text";
        var schema = new LinkedHashMap<String, Object>(); schema.put("type", "object"); schema.put("additionalProperties", false);
        schema.put("properties", Map.of(property, Map.of("type", "string", "maxLength", feature.equals("time") ? 64 : 16000)));
        if (!feature.equals("time")) schema.put("required", List.of(property));
        var tool = McpSchema.Tool.builder().name(feature.equals("time") ? "current_time" : feature.equals("json") ? "format_json" : "analyze_text")
                .description(feature.equals("time") ? "Get current time in an explicit IANA timezone; defaults to Asia/Hong_Kong."
                        : feature.equals("json") ? "Validate and format the supplied JSON; no filesystem or network access." : "Count characters, lines and UTF-8 bytes, and compute SHA-256 for supplied text.")
                .inputSchema(mapper, PluginJson.write(schema)).build();
        var server = McpServer.sync(new StdioServerTransportProvider(mapper)).serverInfo("harness-" + feature, "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tool(tool, (exchange, input) -> execute(feature, input)).build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully)); new CountDownLatch(1).await();
    }

    /** Return bounded utility results without running commands or accessing user files. */
    private static McpSchema.CallToolResult execute(String feature, Map<String, Object> input) {
        try {
            String result;
            if (feature.equals("time")) {
                Object supplied = input.getOrDefault("timezone", "Asia/Hong_Kong");
                if (!(supplied instanceof String zone) || zone.length() > 64) throw new IllegalArgumentException();
                var now = ZonedDateTime.now(ZoneId.of(zone)); result = PluginJson.write(Map.of("timezone", zone, "time", now.toString(), "epochMillis", now.toInstant().toEpochMilli()));
            } else {
                Object supplied = input.get(feature.equals("json") ? "json" : "text");
                if (!(supplied instanceof String text) || text.length() > 16000) throw new IllegalArgumentException();
                result = feature.equals("json") ? PluginJson.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(PluginJson.parse(text))
                        : PluginJson.write(Map.of("characters", text.codePointCount(0, text.length()), "lines", text.isEmpty() ? 0 : text.split("\\R", -1).length,
                                "utf8Bytes", text.getBytes(StandardCharsets.UTF_8).length, "sha256", PluginJson.digest(text)));
            }
            return new McpSchema.CallToolResult(result, false);
        } catch (Exception error) { return new McpSchema.CallToolResult("输入格式不正确，请检查文本、JSON 或 IANA 时区。", true); }
    }
}
