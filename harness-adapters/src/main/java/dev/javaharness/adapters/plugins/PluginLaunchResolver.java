package dev.javaharness.adapters.plugins;

import static dev.javaharness.adapters.plugins.PluginJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.ModelException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves plugin MCP server launch configurations, validates headers/paths, and opens sandboxed clients. */
final class PluginLaunchResolver {
    private final JdbcTemplate jdbc;
    private final PluginPackages packages;
    private final Path data;

    PluginLaunchResolver(JdbcTemplate jdbc, PluginPackages packages, Path data) {
        this.jdbc = jdbc;
        this.packages = packages;
        this.data = data;
    }

    PluginMcpClient.Launch launch(
            PluginService.Installation item,
            PluginService.Binding binding,
            String server,
            JsonNode config,
            Map<String, String> secrets) {
        boolean stdio = config.path("type").asText().equals("stdio");
        config.fieldNames().forEachRemaining(key -> {
            if (!(stdio ? Set.of("type", "command", "args", "env", "cwd") : Set.of("type", "url", "headers"))
                    .contains(key)) throw invalid("MCP 启动字段不支持：" + key);
        });
        // Reuse the selected legacy directory so migration does not hide plugin state or login caches.
        var origins = binding.scope().equals("user")
                ? jdbc.queryForList(
                        "SELECT scope FROM harness_plugin_binding_archive WHERE name=? ORDER BY CASE WHEN scope='user' THEN 0 ELSE 1 END,revision DESC,scope LIMIT 1",
                        String.class,
                        binding.name())
                : List.<String>of();
        String dataScope = origins.isEmpty() ? binding.scope() : origins.getFirst();
        Path plugin = packages.path(item.id()),
                state =
                        data.resolve(digest(item.source() + "/" + item.name()).substring(0, 32))
                                .resolve(digest(dataScope).substring(0, 16));
        String cwd = expand(config.path("cwd").asText("${PLUGIN_ROOT}"), plugin, state, binding.config());
        Path directory = Path.of(cwd);
        directory = (directory.isAbsolute() ? directory : plugin.resolve(directory)).normalize();
        if (!directory.startsWith(plugin) && !directory.startsWith(state)) throw invalid("MCP cwd 必须位于插件或插件数据目录");
        var environment = new TreeMap<String, String>();
        for (String key : List.of("PATH", "SystemRoot", "TEMP", "TMP", "USERPROFILE", "HOME", "LANG"))
            if (System.getenv(key) != null) environment.put(key, System.getenv(key));
        environment.put("PLUGIN_ROOT", plugin.toString());
        environment.put("PLUGIN_DATA", state.toString());
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        JsonNode variables = config.path(stdio ? "env" : "headers");
        if (!variables.isMissingNode() && !variables.isObject()) throw invalid("MCP env/headers 必须为对象");
        variables.fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()
                    || Set.of("PLUGIN_ROOT", "PLUGIN_DATA")
                            .contains(field.getKey().toUpperCase(Locale.ROOT))) throw invalid("MCP 变量不正确");
            (stdio ? environment : headers)
                    .put(field.getKey(), expand(field.getValue().asText(), plugin, state, binding.config()));
        });
        secrets.forEach((key, value) -> {
            String[] parts = key.split(":", 3);
            if (parts[0].equals(server)) {
                if (!parts[1].equals(stdio ? "env" : "header")) throw invalid("凭据位置与传输类型不匹配");
                (stdio ? environment : headers).put(parts[2], value);
            }
        });
        var command = new ArrayList<String>();
        String url = "";
        if (stdio) {
            String executable = expand(config.path("command").asText(), plugin, state, binding.config());
            command.add(resolveExecutable(executable, plugin, environment));
            if (config.has("args") && !config.get("args").isArray()) throw invalid("MCP args 必须为数组");
            for (JsonNode arg : config.path("args")) {
                if (!arg.isTextual()) throw invalid("MCP 参数必须为字符串");
                command.add(expand(arg.asText(), plugin, state, binding.config()));
            }
        } else {
            url = expand(config.path("url").asText(), plugin, state, binding.config());
            URI uri;
            try {
                uri = URI.create(url);
            } catch (Exception error) {
                throw invalid("MCP URL 不正确");
            }
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getScheme().equals("https")
                            || uri.getScheme().equals("http")
                                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
                throw invalid("远程 MCP 需要 HTTPS，本机服务允许 HTTP；凭据请通过安全 Header 绑定");
            validateHeaders(uri, headers);
        }
        if (write(command).length() > 16000
                || write(environment).length() > 32000
                || write(headers).length() > 16000) throw invalid("启动配置过大");
        return new PluginMcpClient.Launch(
                stdio ? PluginProcessCommand.prepare(command) : List.of(),
                directory.toString(),
                Map.copyOf(environment),
                url,
                Map.copyOf(headers),
                30);
    }

    /** Prepare writable state even when a stdio server starts in its read-only package directory. */
    PluginMcpClient openClient(PluginMcpClient.Launch launch) throws IOException {
        Path state = Path.of(launch.environment().get("PLUGIN_DATA"));
        if (!state.normalize().startsWith(data)) throw invalid("插件数据目录越界");
        Files.createDirectories(state);
        if (Files.isSymbolicLink(state) || !state.toRealPath().equals(state.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            throw invalid("插件数据目录不允许链接");
        Path cwd = Path.of(launch.cwd());
        if (cwd.startsWith(state)) Files.createDirectories(cwd);
        if (Files.isSymbolicLink(cwd) || !cwd.toRealPath().equals(cwd.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            throw invalid("插件工作目录不允许链接");
        return new PluginMcpClient(launch);
    }

    /** Validate with the runtime HTTP implementation before storing any executable binding. */
    static void validateHeaders(URI uri, Map<String, String> headers) {
        var request = HttpRequest.newBuilder(uri);
        headers.forEach((key, value) -> {
            if (Set.of(
                            "host",
                            "connection",
                            "content-length",
                            "expect",
                            "upgrade",
                            "content-type",
                            "accept",
                            "mcp-session-id",
                            "mcp-protocol-version")
                    .contains(key.toLowerCase(Locale.ROOT))) throw invalid("不允许覆盖协议或受限 Header：" + key);
            if (key.length() > 128) throw invalid("HTTP Header 名称过长");
            try {
                request.header(key, value);
            } catch (IllegalArgumentException error) {
                throw invalid("HTTP Header 名称或值不合法");
            }
        });
    }

    static boolean connectionFailure(Exception error) {
        return error instanceof IOException
                || error instanceof ModelException
                || error instanceof java.util.concurrent.TimeoutException;
    }

    static String connectionMessage(Exception error) {
        return error instanceof HarnessException || error instanceof ModelException
                ? error.getMessage()
                : "MCP 连接失败或超时，请在扩展详情中测试连接";
    }

    private static String expand(String value, Path plugin, Path data, JsonNode config) {
        String result = value.replace("${PLUGIN_ROOT}", plugin.toString()).replace("${PLUGIN_DATA}", data.toString());
        Matcher matcher = Pattern.compile("\\$\\{config\\.([a-zA-Z0-9_-]+)}").matcher(result);
        var output = new StringBuffer();
        while (matcher.find()) {
            JsonNode setting = config.get(matcher.group(1));
            if (setting == null || !setting.isValueNode()) throw invalid("启动引用了缺失或非标量配置");
            matcher.appendReplacement(output, Matcher.quoteReplacement(setting.asText()));
        }
        matcher.appendTail(output);
        result = output.toString();
        if (result.contains("${") || result.indexOf('\0') >= 0) throw invalid("未支持或未解析的插件变量");
        return result;
    }

    private static String resolveExecutable(String value, Path plugin, Map<String, String> environment) {
        try {
            if (value.isBlank()) throw invalid("MCP 缺少 command");
            Path path = Path.of(value);
            if (path.isAbsolute() || value.contains("/") || value.contains("\\")) {
                path = path.isAbsolute() ? path : plugin.resolve(path).normalize();
                if (!path.isAbsolute() || !Files.isRegularFile(path)) throw invalid("MCP 可执行文件不存在");
                return path.toRealPath().toString();
            }
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            if (value.equals("java"))
                return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                        .toString();
            for (String entry : environment.getOrDefault("PATH", "").split(Pattern.quote(java.io.File.pathSeparator))) {
                if (entry.isBlank()) continue;
                Path folder = Path.of(entry);
                if (!folder.isAbsolute()) continue;
                for (String suffix : windows && !value.matches("(?i).*\\.(exe|com|cmd|bat)$")
                        ? List.of(".exe", ".com", ".cmd", ".bat")
                        : List.of("")) {
                    Path candidate = folder.resolve(value + suffix);
                    if (Files.isRegularFile(candidate))
                        return candidate.toRealPath().toString();
                }
            }
            throw invalid("无法解析 MCP 可执行文件，请使用已安装程序的完整路径");
        } catch (IOException | InvalidPathException error) {
            throw invalid("MCP 可执行路径不正确");
        }
    }
}
