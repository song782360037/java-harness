package dev.javaharness.adapters.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bot 后台任务内建联网工具装饰器：
 * 1. {@code web_fetch}：内置 IPv4/IPv6 私网与元数据地址 SSRF 防护（重定向逐跳校验），支持 HTML 转纯文本与分页续读；
 * 2. {@code web_search}：支持 Tavily / Brave / DeepSeek Search / 自定义 HTTP 搜索接口及可选免 Key 兜底。
 */
public final class BotWebTools implements WorkspaceTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_FETCH_BYTES = 2 * 1024 * 1024;
    private static final int WINDOW_CHARS = 12_000;
    private static final String USER_AGENT = "JavaHarnessBot/1.0";

    private static final Pattern STRIP_BLOCK =
            Pattern.compile("<(script|style|noscript|svg)[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_BREAK =
            Pattern.compile("<br\\s*/?>|</(p|div|li|tr|h[1-6]|section|article|pre)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern DDG_LINK = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>|<div[^>]*class=\"result__snippet\"[^>]*>([\\s\\S]*?)</div>",
            Pattern.CASE_INSENSITIVE);

    /** 动态读取的联网搜索配置快照。 */
    public record SearchConfig(
            String provider,
            String endpointUrl,
            String apiKey,
            String deepSeekKey,
            boolean fallbackFreeEnabled,
            int maxResults,
            int timeoutSeconds) {
        public static SearchConfig defaults() {
            return new SearchConfig("DUCKDUCKGO", "", "", "", true, 6, 15);
        }
    }

    /** 可替换的 DNS 解析函数，便于单元测试覆盖 SSRF 拦截。 */
    @FunctionalInterface
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    private static final List<ToolDefinition> DEFINITIONS = List.of(
            new ToolDefinition(
                    "web_search",
                    "搜索公开网页信息，返回标题、链接与摘要。查具体页面全文时配合 web_fetch 使用。",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "query", Map.of("type", "string", "description", "搜索关键词（1–200 字）"),
                                    "count", Map.of("type", "integer", "description", "可选返回条数（1–10）")),
                            "required",
                            List.of("query"),
                            "additionalProperties",
                            false)),
            new ToolDefinition(
                    "web_fetch",
                    "读取公开 http/https 网址或 JSON API 的文本内容（禁止访问本机与内网地址，一次返回一段，可用 offset 续读）。",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "url", Map.of("type", "string", "description", "完整 http:// 或 https:// 网址"),
                                    "accept", Map.of("type", "string", "description", "可选 Accept 请求头"),
                                    "offset", Map.of("type", "integer", "description", "可选字符偏移量，用于续读长网页")),
                            "required",
                            List.of("url"),
                            "additionalProperties",
                            false)));

    private final WorkspaceTools delegate;
    private final Supplier<SearchConfig> configSupplier;
    private final HttpClient http;
    private final DnsResolver resolver;

    public BotWebTools(WorkspaceTools delegate, Supplier<SearchConfig> configSupplier) {
        this(
                delegate,
                configSupplier,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                InetAddress::getAllByName);
    }

    public BotWebTools(
            WorkspaceTools delegate,
            Supplier<SearchConfig> configSupplier,
            HttpClient http,
            DnsResolver resolver) {
        this.delegate = delegate == null ? WorkspaceTools.disabled() : delegate;
        this.configSupplier = configSupplier == null ? SearchConfig::defaults : configSupplier;
        this.http = http;
        this.resolver = resolver;
    }

    @Override
    public String workspace() {
        return delegate.workspace();
    }

    @Override
    public List<ToolDefinition> definitions() {
        var all = new ArrayList<ToolDefinition>(delegate.definitions());
        for (ToolDefinition tool : DEFINITIONS) {
            if (all.stream().noneMatch(existing -> existing.name().equals(tool.name()))) {
                all.add(tool);
            }
        }
        return List.copyOf(all);
    }

    @Override
    public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return execute("", call, cancellation);
    }

    @Override
    public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return switch (call.name()) {
            case "web_fetch" -> webFetch(call.arguments(), cancellation);
            case "web_search" -> webSearch(call.arguments(), cancellation);
            default -> delegate.execute(runId, call, cancellation);
        };
    }

    Result webFetch(String argsJson, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode args = parse(argsJson);
        String rawUrl = args.path("url").asText("").strip();
        if (rawUrl.isEmpty()) return new Result(false, "缺少 url 参数");
        String accept = args.path("accept").asText("").strip();
        int offset = Math.max(0, args.path("offset").asInt(0));
        SearchConfig cfg = safeConfig();
        Duration timeout = Duration.ofSeconds(Math.max(5, Math.min(cfg.timeoutSeconds(), 60)));

        URI current;
        try {
            current = URI.create(rawUrl);
        } catch (IllegalArgumentException error) {
            return new Result(false, "网址格式不正确：" + rawUrl);
        }

        HttpResponse<InputStream> response = null;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                cancellation.check();
                String blocked = validatePublicUri(current, resolver);
                if (blocked != null) return new Result(false, blocked);
                var reqBuilder = HttpRequest.newBuilder(current)
                        .timeout(timeout)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", accept.isEmpty() ? "*/*" : accept)
                        .GET();
                response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
                int code = response.statusCode();
                if (code >= 300 && code < 400) {
                    String loc = response.headers().firstValue("location").orElse("");
                    closeQuietly(response.body());
                    if (loc.isBlank()) break;
                    if (hop == MAX_REDIRECTS) return new Result(false, "重定向次数超过上限");
                    current = current.resolve(loc);
                    continue;
                }
                break;
            }
            if (response == null) return new Result(false, "未能获取响应");
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.isBlank()
                    && !contentType.toLowerCase().matches(".*(text|json|xml|javascript|yaml|csv).*")) {
                closeQuietly(response.body());
                return new Result(false, "目标不是文本内容（" + contentType + "），无法读取");
            }
            byte[] bodyBytes = readCapped(response.body(), MAX_FETCH_BYTES, cancellation);
            boolean truncatedBytes = bodyBytes.length >= MAX_FETCH_BYTES;
            String raw = new String(bodyBytes, StandardCharsets.UTF_8);
            String text = contentType.toLowerCase().contains("html") ? htmlToText(raw) : raw;
            String header = "HTTP " + response.statusCode() + " " + current
                    + (truncatedBytes ? "\n（响应体积超过 2MB，仅截取前 2MB）" : "");
            return new Result(true, header + "\n\n" + sliceWindow(text, response.statusCode() < 400 ? offset : 0));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (IOException error) {
            return new Result(false, "网页读取失败：" + error.getMessage());
        } finally {
            if (response != null) closeQuietly(response.body());
        }
    }

    Result webSearch(String argsJson, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode args = parse(argsJson);
        String query = args.path("query").asText("").strip();
        if (query.isEmpty()) return new Result(false, "缺少 query 参数");
        if (query.length() > 200) query = query.substring(0, 200);
        SearchConfig cfg = safeConfig();
        int count = Math.max(1, Math.min(args.path("count").asInt(cfg.maxResults()), 10));
        String provider = cfg.provider() == null ? "NONE" : cfg.provider().strip().toUpperCase();
        Duration timeout = Duration.ofSeconds(Math.max(5, Math.min(cfg.timeoutSeconds(), 45)));

        if ("NONE".equals(provider)) {
            if (cfg.fallbackFreeEnabled()) {
                return searchDuckDuckGo(query, count, timeout, cancellation);
            }
            return new Result(false, "联网搜索未启用，请在「设置 → Bot 联网搜索」选择搜索引擎并配置。");
        }

        try {
            return switch (provider) {
                case "TAVILY" -> {
                    if (cfg.apiKey().isBlank()) {
                        yield fallbackOrError(cfg, query, count, timeout, cancellation, "未配置 Tavily API Key");
                    }
                    yield searchTavily(query, count, cfg.apiKey(), timeout);
                }
                case "BRAVE" -> {
                    if (cfg.apiKey().isBlank()) {
                        yield fallbackOrError(cfg, query, count, timeout, cancellation, "未配置 Brave Search API Key");
                    }
                    yield searchBrave(query, count, cfg.apiKey(), timeout);
                }
                case "DEEPSEEK" -> {
                    String key = !cfg.apiKey().isBlank() ? cfg.apiKey() : cfg.deepSeekKey();
                    if (key == null || key.isBlank()) {
                        yield fallbackOrError(
                                cfg, query, count, timeout, cancellation, "未配置 DeepSeek Search API Key（也未检测到已配置的 DeepSeek 模型服务商 Key）");
                    }
                    yield searchDeepSeek(query, count, key, timeout);
                }
                case "CUSTOM" -> {
                    if (cfg.endpointUrl().isBlank()) {
                        yield fallbackOrError(cfg, query, count, timeout, cancellation, "未配置自定义搜索接口 URL");
                    }
                    yield searchCustom(query, count, cfg.endpointUrl(), cfg.apiKey(), timeout);
                }
                case "DUCKDUCKGO" -> searchDuckDuckGo(query, count, timeout, cancellation);
                default -> new Result(false, "不支持的搜索引擎：" + provider);
            };
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (Exception error) {
            if (cfg.fallbackFreeEnabled() && !"DUCKDUCKGO".equals(provider)) {
                try {
                    Result free = searchDuckDuckGo(query, count, timeout, cancellation);
                    if (free.success()) {
                        return new Result(
                                true,
                                "（主搜索引擎 " + provider + " 失败：" + error.getMessage() + "，已自动切换免 Key 搜索兜底）\n\n"
                                        + free.output());
                    }
                } catch (Exception ignored) {
                    // Fall through to primary error.
                }
            }
            return new Result(false, "搜索失败（" + provider + "）：" + error.getMessage());
        }
    }

    private Result fallbackOrError(
            SearchConfig cfg,
            String query,
            int count,
            Duration timeout,
            ModelProvider.Cancellation cancellation,
            String reason)
            throws Exception {
        if (cfg.fallbackFreeEnabled()) {
            Result free = searchDuckDuckGo(query, count, timeout, cancellation);
            if (free.success()) {
                return new Result(true, "（" + reason + "，已自动使用免 Key 搜索兜底）\n\n" + free.output());
            }
        }
        return new Result(false, reason + "，请在「设置 → Bot 联网搜索」配置后再试。");
    }

    private Result searchTavily(String query, int count, String apiKey, Duration timeout) throws Exception {
        var payload = Map.of("api_key", apiKey, "query", query, "max_results", count, "search_depth", "basic");
        var req = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) throw new IOException("HTTP " + res.statusCode());
        JsonNode root = JSON.readTree(res.body());
        return formatJsonResults("Tavily", root.path("results"), "title", "url", "content");
    }

    private Result searchBrave(String query, int count, String apiKey, Duration timeout) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        var req = HttpRequest.newBuilder(
                        URI.create("https://api.search.brave.com/res/v1/web/search?q=" + encoded + "&count=" + count))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) throw new IOException("HTTP " + res.statusCode());
        JsonNode root = JSON.readTree(res.body());
        return formatJsonResults("Brave", root.path("web").path("results"), "title", "url", "description");
    }

    private Result searchDeepSeek(String query, int count, String apiKey, Duration timeout) throws Exception {
        var payload = Map.of("query", query, "count", count);
        var req = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/v1/tools/search"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 401) throw new IOException("DeepSeek API Key 无效");
        if (res.statusCode() == 402) throw new IOException("DeepSeek 账户余额不足");
        if (res.statusCode() == 429) throw new IOException("搜索过于频繁，请稍后重试");
        if (res.statusCode() >= 400) throw new IOException("HTTP " + res.statusCode());
        JsonNode root = JSON.readTree(res.body());
        return formatJsonResults("DeepSeek", root.path("results"), "title", "url", "summary");
    }

    private Result searchCustom(String query, int count, String endpointUrl, String apiKey, Duration timeout)
            throws Exception {
        URI uri = URI.create(endpointUrl.strip());
        String blocked = validatePublicUri(uri, resolver);
        if (blocked != null) return new Result(false, blocked);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("query", query);
        payload.put("count", count);
        var reqBuilder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) reqBuilder.header("Authorization", "Bearer " + apiKey);
        var res = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) throw new IOException("HTTP " + res.statusCode());
        JsonNode root = JSON.readTree(res.body());
        JsonNode list = root.isArray() ? root : root.path("results");
        return formatJsonResults("Custom", list, "title", "url", "snippet");
    }

    private Result searchDuckDuckGo(String query, int count, Duration timeout, ModelProvider.Cancellation cancellation)
            throws Exception {
        cancellation.check();
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://html.duckduckgo.com/html/?q=" + encoded);
        String blocked = validatePublicUri(uri, resolver);
        if (blocked != null) return new Result(false, blocked);
        var req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0 (compatible; JavaHarnessBot/1.0)")
                .header("Accept", "text/html")
                .GET()
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) throw new IOException("HTTP " + res.statusCode());
        String html = res.body();
        var titles = new ArrayList<String>();
        var urls = new ArrayList<String>();
        Matcher lm = DDG_LINK.matcher(html);
        while (lm.find() && titles.size() < count) {
            urls.add(cleanDdgUrl(lm.group(1)));
            titles.add(htmlToText(lm.group(2)));
        }
        var snippets = new ArrayList<String>();
        Matcher sm = DDG_SNIPPET.matcher(html);
        while (sm.find() && snippets.size() < titles.size()) {
            String raw = sm.group(1) != null ? sm.group(1) : sm.group(2);
            snippets.add(htmlToText(raw));
        }
        if (titles.isEmpty()) return new Result(true, "未搜索到相关结果（DuckDuckGo 免 Key 模式）");
        var sb = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            sb.append(i + 1).append(". ").append(titles.get(i)).append('\n');
            sb.append("   链接：").append(urls.get(i)).append('\n');
            if (i < snippets.size() && !snippets.get(i).isBlank()) {
                sb.append("   摘要：").append(clip(snippets.get(i), 300)).append('\n');
            }
        }
        return new Result(true, sb.toString().strip());
    }

    private static String cleanDdgUrl(String raw) {
        if (raw == null) return "";
        int idx = raw.indexOf("uddg=");
        if (idx >= 0) {
            String tail = raw.substring(idx + 5);
            int amp = tail.indexOf('&');
            String encoded = amp >= 0 ? tail.substring(0, amp) : tail;
            try {
                return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return raw;
            }
        }
        return raw.startsWith("//") ? "https:" + raw : raw;
    }

    private static Result formatJsonResults(
            String label, JsonNode array, String titleKey, String urlKey, String snippetKey) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return new Result(true, "未搜索到相关结果（" + label + "）");
        }
        var sb = new StringBuilder();
        int idx = 1;
        for (JsonNode item : array) {
            String title = item.path(titleKey).asText("").strip();
            String url = item.path(urlKey).asText("").strip();
            String snippet = item.path(snippetKey).asText("").strip();
            if (snippet.isEmpty()) snippet = item.path("snippet").asText("").strip();
            if (snippet.isEmpty()) snippet = item.path("content").asText("").strip();
            if (snippet.isEmpty()) snippet = item.path("description").asText("").strip();
            String date = item.path("date").asText("").strip();
            if (title.isEmpty() && url.isEmpty()) continue;
            sb.append(idx++).append(". ").append(title.isEmpty() ? url : title);
            if (!date.isEmpty()) sb.append("（").append(date).append("）");
            sb.append('\n');
            if (!url.isEmpty()) sb.append("   链接：").append(url).append('\n');
            if (!snippet.isEmpty()) sb.append("   摘要：").append(clip(snippet, 500)).append('\n');
        }
        return new Result(true, sb.isEmpty() ? "未搜索到相关结果" : sb.toString().strip());
    }

    /** 校验公开 http/https URL，拦截环回、私网、链路本地（含云元数据）与保留网段。 */
    public static String validatePublicUri(URI uri, DnsResolver resolver) {
        if (uri == null || uri.getScheme() == null) return "无效网址";
        String scheme = uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "仅允许读取 http/https 网址：" + scheme;
        }
        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isBlank()) {
            return "网址不能包含账号密码信息";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "网址缺少主机名";
        String lower = host.toLowerCase();
        if ("localhost".equals(lower) || lower.endsWith(".localhost") || lower.endsWith(".local")) {
            return "禁止访问本机或局域网域名：" + host;
        }
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses == null || addresses.length == 0) return "无法解析域名：" + host;
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    return "禁止访问内网、环回或保留地址：" + host;
                }
            }
            return null;
        } catch (IOException error) {
            return "域名解析失败：" + host;
        }
    }

    public static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw.length == 4) {
            return isBlockedIpv4(raw[0] & 0xFF, raw[1] & 0xFF);
        }
        if (address instanceof Inet6Address && raw.length == 16) {
            // Check IPv4-mapped IPv6 ::ffff:a.b.c.d
            boolean v4Mapped = true;
            for (int i = 0; i < 10; i++) if (raw[i] != 0) v4Mapped = false;
            if (v4Mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                return isBlockedIpv4(raw[12] & 0xFF, raw[13] & 0xFF);
            }
            // Unique local fc00::/7
            int first = raw[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) return true;
        }
        return false;
    }

    private static boolean isBlockedIpv4(int a, int b) {
        if (a == 0 || a == 10 || a == 127) return true;
        if (a == 100 && (b >= 64 && b <= 127)) return true; // CGNAT 100.64.0.0/10
        if (a == 169 && b == 254) return true; // Link-local / Cloud metadata
        if (a == 172 && (b >= 16 && b <= 31)) return true;
        if (a == 192 && b == 168) return true;
        return a >= 224;
    }

    static String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        String cleaned = STRIP_BLOCK.matcher(html).replaceAll("");
        cleaned = BLOCK_BREAK.matcher(cleaned).replaceAll("\n");
        cleaned = ANY_TAG.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        cleaned = cleaned.replaceAll("[ \\t]+", " ");
        cleaned = cleaned.replaceAll("\\n\\s*\\n+", "\n\n");
        return cleaned.strip();
    }

    private static String sliceWindow(String text, int offset) {
        if (text.isEmpty()) return "（页面内容为空）";
        if (offset >= text.length()) return "（offset 超出正文长度，共 " + text.length() + " 字符）";
        int end = Math.min(text.length(), offset + WINDOW_CHARS);
        String chunk = text.substring(offset, end);
        if (end < text.length()) {
            return chunk + "\n\n...（共 " + text.length() + " 字符，可传 offset=" + end + " 续读后续内容）";
        }
        return chunk;
    }

    private static byte[] readCapped(InputStream in, int maxBytes, ModelProvider.Cancellation cancellation)
            throws Exception {
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            cancellation.check();
            int take = Math.min(n, maxBytes - total);
            if (take > 0) {
                out.write(buf, 0, take);
                total += take;
            }
            if (total >= maxBytes) break;
        }
        return out.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Ignore close failure.
            }
        }
    }

    private SearchConfig safeConfig() {
        SearchConfig cfg = configSupplier.get();
        return cfg == null ? SearchConfig.defaults() : cfg;
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static JsonNode parse(String args) throws IOException {
        if (args == null || args.isBlank()) return JSON.createObjectNode();
        return JSON.readTree(args);
    }
}
