package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.bot.BotLanStore;
import dev.javaharness.adapters.bot.BotLanStore.Device;
import dev.javaharness.adapters.bot.BotLanStore.LanSettings;
import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 独立按需启停的局域网移动伴侣服务（默认关闭，独立端口 4317）：
 * 主 Spring Boot 端口保持仅监听 127.0.0.1；本服务仅暴露受限的 Bot 移动接口，
 * 通过 4 位配对码（2 分钟有效）签发 HttpOnly 设备令牌，数据库仅保存令牌 SHA-256 哈希。
 */
public final class BotLanServer implements AutoCloseable {
    public static final String COOKIE_NAME = "harness_bot_device";
    private static final Duration PAIRING_TTL = Duration.ofMinutes(2);
    private static final Logger log = LoggerFactory.getLogger(BotLanServer.class);

    public record PendingPairingView(String requestId, String userAgent, Instant expiresAt) {}

    public record LanStatus(
            LanSettings settings,
            boolean running,
            int actualPort,
            List<String> urls,
            List<Device> devices,
            PendingPairingView pendingPairing) {}

    private static final class PendingPairing {
        final String requestId;
        final String code;
        final String userAgent;
        final Instant expiresAt;
        String approvedToken;
        String approvedDeviceName;

        PendingPairing(String requestId, String code, String userAgent, Instant expiresAt) {
            this.requestId = requestId;
            this.code = code;
            this.userAgent = userAgent;
            this.expiresAt = expiresAt;
        }
    }

    private final BotLanStore store;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final BotService bots;
    private final BotOrchestrator orchestrator;
    private final BotRoutineScheduler routines;
    private final BotMiniAppService miniApps;
    private final CommandService commands;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final byte[] phoneHtml;

    private HttpServer server;
    private int boundPort;
    private PendingPairing pending;
    private volatile boolean closed;

    public BotLanServer(
            BotLanStore store,
            SessionStore sessions,
            TransactionTemplate tx,
            BotService bots,
            BotOrchestrator orchestrator,
            BotRoutineScheduler routines,
            BotMiniAppService miniApps,
            CommandService commands,
            ObjectMapper json) {
        this.store = store;
        this.sessions = sessions;
        this.tx = tx;
        this.bots = bots;
        this.orchestrator = orchestrator;
        this.routines = routines;
        this.miniApps = miniApps;
        this.commands = commands;
        this.json = json;
        this.phoneHtml = loadPhoneHtml();
        write(store::ensureSettings);
    }

    private static byte[] loadPhoneHtml() {
        try (InputStream in = BotLanServer.class.getResourceAsStream("/bot-lan/phone.html")) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ignored) {
            // Fallback below.
        }
        return "<!doctype html><html><body><h1>Java Harness Bot</h1></body></html>".getBytes(StandardCharsets.UTF_8);
    }

    public synchronized void startIfEnabled() {
        if (closed) return;
        LanSettings settings = store.ensureSettings();
        if (settings.enabled()) {
            try {
                startListenerLocked(settings.port());
            } catch (RuntimeException error) {
                log.warn("Could not start Bot LAN server on port {}", settings.port(), error);
            }
        }
    }

    public synchronized LanStatus status() {
        LanSettings settings = store.ensureSettings();
        clearExpiredPairingLocked();
        PendingPairingView pendingView = pending != null && pending.approvedToken == null
                ? new PendingPairingView(pending.requestId, pending.userAgent, pending.expiresAt)
                : null;
        int port = server != null ? boundPort : settings.port();
        return new LanStatus(
                settings,
                server != null,
                server != null ? boundPort : 0,
                server != null ? lanUrls(port) : List.of(),
                store.devices(),
                pendingView);
    }

    public synchronized LanStatus updateSettings(
            boolean enabled, int port, boolean allowRemoteApproval, long revision) {
        if (port != 0 && (port < 1024 || port > 65535)) {
            throw new HarnessException("INVALID", "局域网端口需在 1024–65535 之间");
        }
        LanSettings previous = store.ensureSettings();
        stopListenerLocked();
        if (enabled) {
            startListenerLocked(port);
        }
        int savedPort = enabled && port == 0 ? boundPort : (port == 0 ? previous.port() : port);
        try {
            write(() -> store.saveSettings(enabled, savedPort, allowRemoteApproval, revision));
        } catch (RuntimeException error) {
            stopListenerLocked();
            if (previous.enabled()) {
                try {
                    startListenerLocked(previous.port());
                } catch (RuntimeException ignored) {
                    // Best effort rollback.
                }
            }
            throw error;
        }
        return status();
    }

    public synchronized Device confirmPairing(String code, String deviceName) {
        clearExpiredPairingLocked();
        if (pending == null || pending.approvedToken != null) {
            throw new HarnessException("NOT_FOUND", "当前没有等待确认的配对请求，请先在手机端打开链接");
        }
        String input = code == null ? "" : code.strip();
        if (!MessageDigest.isEqual(
                input.getBytes(StandardCharsets.UTF_8), pending.code.getBytes(StandardCharsets.UTF_8))) {
            throw new HarnessException("INVALID", "配对码不正确，请核对手机屏幕上的 4 位数字");
        }
        String name =
                deviceName == null || deviceName.isBlank() ? guessDeviceName(pending.userAgent) : deviceName.strip();
        if (name.length() > 80) name = name.substring(0, 80);
        String token = randomToken();
        String hash = sha256(token);
        String ua = pending.userAgent;
        String finalName = name;
        Device device = write(() -> store.registerDevice(finalName, ua, hash));
        pending.approvedToken = token;
        pending.approvedDeviceName = device.name();
        orchestrator.pushNotice("DEVICE_PAIRED", "", "设备配对", "新设备已连接", device.name() + " 已配对成功");
        return device;
    }

    public synchronized void revokeDevice(String deviceId) {
        write(() -> {
            store.revokeDevice(deviceId);
            return null;
        });
    }

    private void startListenerLocked(int port) {
        try {
            HttpServer listener = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            listener.createContext("/", this::handle);
            listener.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            listener.start();
            server = listener;
            boundPort = listener.getAddress().getPort();
            log.info("Bot LAN server listening on 0.0.0.0:{}", boundPort);
        } catch (IOException error) {
            throw new HarnessException("CONFLICT", "无法启动局域网端口 " + port + "，可能已被占用：" + error.getMessage());
        }
    }

    private void stopListenerLocked() {
        pending = null;
        if (server != null) {
            server.stop(0);
            server = null;
            boundPort = 0;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().set("Cache-Control", "no-store");

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendBytes(exchange, 200, "text/html; charset=utf-8", phoneHtml);
                return;
            }
            if ("POST".equals(method) && "/lan/api/pair/request".equals(path)) {
                handlePairRequest(exchange);
                return;
            }
            if ("GET".equals(method) && "/lan/api/pair/poll".equals(path)) {
                handlePairPoll(exchange);
                return;
            }
            if (!path.startsWith("/lan/api/")) {
                sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "未开放该接口"));
                return;
            }

            Device device = authenticate(exchange);
            if (device == null) {
                sendJson(exchange, 401, Map.of("code", "UNAUTHORIZED", "message", "设备未配对或已被移除"));
                return;
            }
            write(() -> {
                store.touchDevice(device.id());
                return null;
            });

            routeAuthenticated(exchange, method, path, device);
        } catch (HarnessException error) {
            int code = switch (error.code()) {
                case "INVALID" -> 400;
                case "NOT_FOUND" -> 404;
                case "CONFLICT", "BUSY" -> 409;
                case "UNAVAILABLE" -> 503;
                default -> 400;
            };
            sendJson(exchange, code, Map.of("code", error.code(), "message", error.getMessage()));
        } catch (Exception error) {
            log.warn("Bot LAN request failed", error);
            sendJson(exchange, 500, Map.of("code", "INTERNAL", "message", "请求处理失败"));
        }
    }

    private void routeAuthenticated(HttpExchange exchange, String method, String path, Device device)
            throws IOException {
        if ("GET".equals(method) && "/lan/api/me".equals(path)) {
            LanSettings settings = store.ensureSettings();
            sendJson(exchange, 200, Map.of("device", device, "allowRemoteApproval", settings.allowRemoteApproval()));
            return;
        }
        if ("GET".equals(method) && "/lan/api/bots/workspace".equals(path)) {
            sendJson(exchange, 200, bots.workspaceSnapshot());
            return;
        }
        if (path.startsWith("/lan/api/bots/")) {
            String rest = path.substring("/lan/api/bots/".length());
            String[] parts = rest.split("/");
            if (parts.length == 2) {
                String botId = parts[0];
                String action = parts[1];
                if ("GET".equals(method) && "thread".equals(action)) {
                    sendJson(exchange, 200, bots.threadSnapshot(botId));
                    return;
                }
                if ("POST".equals(method) && "messages".equals(action)) {
                    JsonNode body = readJson(exchange);
                    String reqId =
                            body.path("requestId").asText(UUID.randomUUID().toString());
                    String content = body.path("content").asText("");
                    Long quoteSeq =
                            body.has("quoteSeq") && !body.get("quoteSeq").isNull()
                                    ? body.get("quoteSeq").asLong()
                                    : null;
                    sendJson(exchange, 202, orchestrator.postMessage(botId, reqId, content, quoteSeq, "[]"));
                    return;
                }
                if ("POST".equals(method) && "stop".equals(action)) {
                    orchestrator.stopChat(botId);
                    sendJson(exchange, 200, Map.of("stopping", true));
                    return;
                }
                if ("POST".equals(method) && "seen".equals(action)) {
                    bots.markSeen(botId);
                    sendJson(exchange, 200, Map.of("seen", true));
                    return;
                }
            }
        }
        if (path.startsWith("/lan/api/bot-tasks/")) {
            String[] parts = path.substring("/lan/api/bot-tasks/".length()).split("/");
            if ("POST".equals(method) && parts.length == 2 && "cancel".equals(parts[1])) {
                sendJson(exchange, 200, orchestrator.cancelTask(parts[0]));
                return;
            }
        }
        if (path.startsWith("/lan/api/bot-routines/")) {
            String[] parts = path.substring("/lan/api/bot-routines/".length()).split("/");
            if ("POST".equals(method) && parts.length == 2) {
                if ("toggle".equals(parts[1])) {
                    JsonNode body = readJson(exchange);
                    sendJson(
                            exchange,
                            200,
                            routines.toggle(parts[0], body.path("enabled").asBoolean(true)));
                    return;
                }
                if ("run".equals(parts[1])) {
                    sendJson(exchange, 202, routines.runNow(parts[0]));
                    return;
                }
            }
        }
        if (path.startsWith("/lan/api/bot-apps/")) {
            String[] parts = path.substring("/lan/api/bot-apps/".length()).split("/");
            if ("GET".equals(method) && parts.length == 1) {
                sendJson(exchange, 200, miniApps.bundle(parts[0]));
                return;
            }
            if ("POST".equals(method) && parts.length == 2 && "data".equals(parts[1])) {
                JsonNode body = readJson(exchange);
                sendJson(exchange, 200, miniApps.updateAppData(parts[0], "", json.writeValueAsString(body)));
                return;
            }
        }
        if ("GET".equals(method) && "/lan/api/approvals".equals(path)) {
            LanSettings settings = store.ensureSettings();
            sendJson(exchange, 200, settings.allowRemoteApproval() ? store.pendingBotCommands() : List.of());
            return;
        }
        if (path.startsWith("/lan/api/approvals/")) {
            LanSettings settings = store.ensureSettings();
            if (!settings.allowRemoteApproval()) {
                sendJson(exchange, 403, Map.of("code", "FORBIDDEN", "message", "电脑端未开启远程审批权限"));
                return;
            }
            String[] parts = path.substring("/lan/api/approvals/".length()).split("/");
            if ("POST".equals(method) && parts.length == 2) {
                String cmdId = parts[0];
                if (!store.isBotTaskCommand(cmdId)) {
                    sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "仅支持审批 Bot 后台任务的命令"));
                    return;
                }
                if ("approve".equals(parts[1])) {
                    sendJson(exchange, 200, commands.approve(cmdId));
                    return;
                }
                if ("reject".equals(parts[1])) {
                    sendJson(exchange, 200, commands.reject(cmdId));
                    return;
                }
            }
        }
        sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "未开放该接口"));
    }

    private void handlePairRequest(HttpExchange exchange) throws IOException {
        String ua = exchange.getRequestHeaders().getFirst("User-Agent");
        PendingPairing created;
        synchronized (this) {
            String requestId = UUID.randomUUID().toString();
            String code = String.format("%04d", random.nextInt(10_000));
            created = new PendingPairing(
                    requestId, code, ua == null ? "" : ua, Instant.now().plus(PAIRING_TTL));
            pending = created;
        }
        orchestrator.pushNotice("PAIRING_REQUEST", "", "新设备请求配对", "有手机请求连接", "请打开「设置 → 设备」输入手机屏幕上的 4 位配对码");
        sendJson(
                exchange,
                201,
                Map.of(
                        "requestId",
                        created.requestId,
                        "code",
                        created.code,
                        "expiresAt",
                        created.expiresAt.toString()));
    }

    private void handlePairPoll(HttpExchange exchange) throws IOException {
        String requestId = queryParam(exchange.getRequestURI().getRawQuery(), "requestId");
        String tokenToIssue = null;
        String deviceName = "";
        String status;
        synchronized (this) {
            clearExpiredPairingLocked();
            if (pending == null || !pending.requestId.equals(requestId)) {
                status = "EXPIRED";
            } else if (pending.approvedToken != null) {
                status = "PAIRED";
                tokenToIssue = pending.approvedToken;
                deviceName = pending.approvedDeviceName;
                pending = null;
            } else {
                status = "PENDING";
            }
        }
        if (tokenToIssue != null) {
            exchange.getResponseHeaders()
                    .add(
                            "Set-Cookie",
                            COOKIE_NAME + "=" + tokenToIssue + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", status);
        if (!deviceName.isEmpty()) payload.put("deviceName", deviceName);
        sendJson(exchange, 200, payload);
    }

    private Device authenticate(HttpExchange exchange) {
        String token = extractCookie(exchange.getRequestHeaders().get("Cookie"), COOKIE_NAME);
        if (token == null || token.isBlank()) {
            token = exchange.getRequestHeaders().getFirst("X-Harness-Device-Token");
        }
        if (token == null || token.isBlank()) return null;
        return store.findActiveByTokenHash(sha256(token.strip()));
    }

    private void clearExpiredPairingLocked() {
        if (pending != null && Instant.now().isAfter(pending.expiresAt)) {
            pending = null;
        }
    }

    private static List<String> lanUrls(int port) {
        var urls = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        urls.add("http://" + addr.getHostAddress() + ":" + port + "/");
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to loopback below.
        }
        if (urls.isEmpty()) urls.add("http://127.0.0.1:" + port + "/");
        return urls;
    }

    private static String extractCookie(List<String> headers, String name) {
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).strip().equals(name)) {
                    return part.substring(eq + 1).strip();
                }
            }
        }
        return null;
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String guessDeviceName(String ua) {
        if (ua == null) return "移动设备";
        String lower = ua.toLowerCase();
        if (lower.contains("iphone")) return "iPhone";
        if (lower.contains("ipad")) return "iPad";
        if (lower.contains("android")) return "Android 手机";
        if (lower.contains("macintosh")) return "Mac 浏览器";
        if (lower.contains("windows")) return "Windows 浏览器";
        return "移动设备";
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length == 0) return json.createObjectNode();
        return json.readTree(raw);
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8", json.writeValueAsBytes(body));
    }

    private void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private <T> T write(Supplier<T> work) {
        synchronized (sessions) {
            return tx.execute(status -> work.get());
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        stopListenerLocked();
    }
}
