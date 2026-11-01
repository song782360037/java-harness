package dev.javaharness.adapters.approval;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按 HTTPS_PROXY / HTTP_PROXY / NO_PROXY 环境变量选择代理；未设置时沿用 JVM 默认代理设置。
 * Java HttpClient 不读取这些环境变量，而 TypeSafe 在部分地区直连会返回 451。不支持带用户名密码的代理。
 */
final class EnvironmentProxy extends ProxySelector {
    private final Proxy https;
    private final Proxy http;
    private final List<String> bypass;
    private final ProxySelector fallback;

    private EnvironmentProxy(Proxy https, Proxy http, List<String> bypass, ProxySelector fallback) {
        this.https = https;
        this.http = http;
        this.bypass = bypass;
        this.fallback = fallback;
    }

    static ProxySelector from(Map<String, String> environment) {
        Proxy https = parse(value(environment, "HTTPS_PROXY"));
        Proxy http = parse(value(environment, "HTTP_PROXY"));
        ProxySelector fallback = ProxySelector.getDefault();
        if (https == null && http == null) return fallback == null ? ProxySelector.of(null) : fallback;
        String noProxy = value(environment, "NO_PROXY");
        List<String> bypass = noProxy == null
                ? List.of()
                : Arrays.stream(noProxy.split(","))
                        .map(entry -> entry.strip().toLowerCase(Locale.ROOT))
                        .filter(entry -> !entry.isEmpty())
                        .toList();
        return new EnvironmentProxy(https == null ? http : https, http == null ? https : http, bypass, fallback);
    }

    @Override
    public List<Proxy> select(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");
        // Loopback never goes through a proxy; NO_PROXY entries match the domain and its subdomains, as curl does.
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) return List.of(Proxy.NO_PROXY);
        for (String entry : bypass) {
            String domain = entry.replaceFirst("^\\*?\\.", "");
            if (entry.equals("*") || host.equals(domain) || host.endsWith("." + domain)) return List.of(Proxy.NO_PROXY);
        }
        return List.of("https".equalsIgnoreCase(uri.getScheme()) ? https : http);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException error) {
        if (fallback != null) fallback.connectFailed(uri, address, error);
    }

    private static String value(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) value = environment.get(name.toLowerCase(Locale.ROOT));
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Accept host:port or http://host:port; anything else (including credentials) is ignored. */
    private static Proxy parse(String value) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                return null;
            int port = uri.getPort() < 0 ? 80 : uri.getPort();
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), port));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
