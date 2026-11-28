package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.attachment.AttachmentStore.Attachment;
import dev.javaharness.adapters.attachment.AttachmentStore.Stored;
import dev.javaharness.adapters.bot.BotLanStore;
import dev.javaharness.adapters.bot.BotLanStore.Device;
import dev.javaharness.adapters.bot.BotLanStore.LanSettings;
import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 独立按需启停的局域网移动伴侣服务（默认关闭，独立端口 4317）：
 * 主 Spring Boot 端口保持仅监听 127.0.0.1；本服务仅暴露受限的 Bot 移动伴侣 SPA 与接口，
 * 通过 4 位配对码（2 分钟有效）签发 HttpOnly 设备令牌，数据库仅保存令牌 SHA-256 哈希。
 */
public final class BotLanServer implements AutoCloseable {
    public static final String COOKIE_NAME = "harness_bot_device";
    private static final Duration PAIRING_TTL = Duration.ofMinutes(2);
    private static final int MAX_UPLOAD_BYTES = 17 * 1024 * 1024;
    private static final Pattern SAFE_ASSET_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern MULTIPART_BOUNDARY = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*=UTF-8''([^;\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_QUOTED = Pattern.compile("filename=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PLAIN = Pattern.compile("filename=([^;\\s\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SAFE_IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final List<Path> DEV_DIST_CANDIDATES = List.of(
            Path.of("harness-web/dist"),
            Path.of("../harness-web/dist"));
    private static final List<Path> DEV_PUBLIC_CANDIDATES = List.of(
            Path.of("harness-web/public"),
            Path.of("../harness-web/public"));
    private static final Logger log = LoggerFactory.getLogger(BotLanServer.class);

    public record PendingPairingView(String requestId, String userAgent, Instant expiresAt) {}

    public record LanStatus(
            LanSettings settings,
            boolean running,
            int actualPort,
            List<String> urls,
            List<Device> devices,
            PendingPairingView pendingPairing) {}

    private record DeviceAuth(Device device, String tokenHash) {}

    private record MultipartFilePart(String filename, byte[] bytes) {}

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
    private final byte[] fallbackPhoneHtml;
    private final Semaphore sseSlots = new Semaphore(8);

    private volatile ComposerInputService composerInputs;
    private volatile BotSkillService skillService;
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
        this.fallbackPhoneHtml = loadFallbackPhoneHtml();
        write(store::ensureSettings);
    }

    public void useComposerInputs(ComposerInputService composerInputs) {
        this.composerInputs = composerInputs;
    }

    public void useSkills(BotSkillService skillService) {
        this.skillService = skillService;
    }

    private static byte[] loadFallbackPhoneHtml() {
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
            String rawPath = exchange.getRequestURI().getRawPath();
            String path = exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

            if (hasPathTraversal(rawPath)) {
                sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "未开放该接口"));
                return;
            }

            if ("GET".equals(method) && ("/".equals(path) || "/phone.html".equals(path))) {
                byte[] html = resolveStaticAsset("phone.html", false);
                sendBytes(exchange, 200, "text/html; charset=utf-8", html != null ? html : fallbackPhoneHtml);
                return;
            }
            if ("GET".equals(method) && "/harness-icon.svg".equals(path)) {
                byte[] icon = resolveStaticAsset("harness-icon.svg", true);
                if (icon == null) {
                    sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "资源不存在"));
                    return;
                }
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
                sendBytes(exchange, 200, "image/svg+xml", icon);
                return;
            }
            if ("GET".equals(method) && "/theme.js".equals(path)) {
                byte[] script = resolveStaticAsset("theme.js", true);
                if (script == null) {
                    sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "资源不存在"));
                    return;
                }
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
                sendBytes(exchange, 200, "application/javascript; charset=utf-8", script);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/assets/")) {
                handleStaticAsset(exchange, path.substring("/assets/".length()));
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

            DeviceAuth auth = authenticate(exchange);
            if (auth == null) {
                sendJson(exchange, 401, Map.of("code", "UNAUTHORIZED", "message", "设备未配对或已被移除"));
                return;
            }
            write(() -> {
                store.touchDevice(auth.device().id());
                return null;
            });

            routeAuthenticated(exchange, method, path, auth);
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

    private static boolean hasPathTraversal(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return false;
        String lower = rawPath.toLowerCase(Locale.ROOT);
        return lower.contains("..")
                || lower.contains("%2e")
                || lower.contains("%2f")
                || lower.contains("%5c")
                || lower.contains("\\");
    }

    private void handleStaticAsset(HttpExchange exchange, String filename) throws IOException {
        if (filename.isEmpty()
                || filename.contains("..")
                || !SAFE_ASSET_NAME.matcher(filename).matches()) {
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "未开放该资源"));
            return;
        }
        String contentType = staticAssetContentType(filename);
        if (contentType == null) {
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "未开放该资源类型"));
            return;
        }
        byte[] bytes = resolveStaticAsset("assets/" + filename, false);
        if (bytes == null) {
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "资源不存在"));
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        sendBytes(exchange, 200, contentType, bytes);
    }

    private static String staticAssetContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return null;
    }

    private static byte[] resolveStaticAsset(String relativePath, boolean allowPublicFallback) {
        try (InputStream in = BotLanServer.class.getResourceAsStream("/static/" + relativePath)) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ignored) {
            // Fall through to dev dist directory.
        }
        byte[] fromDist = readFromDirectoryCandidates(DEV_DIST_CANDIDATES, relativePath);
        if (fromDist != null) return fromDist;
        if (allowPublicFallback) {
            return readFromDirectoryCandidates(DEV_PUBLIC_CANDIDATES, relativePath);
        }
        return null;
    }

    private static byte[] readFromDirectoryCandidates(List<Path> roots, String relativePath) {
        for (Path root : roots) {
            try {
                if (!Files.isDirectory(root)) continue;
                Path canonicalRoot = root.toAbsolutePath().normalize();
                Path target = canonicalRoot.resolve(relativePath).normalize();
                if (!target.startsWith(canonicalRoot) || !Files.isRegularFile(target)) continue;
                return Files.readAllBytes(target);
            } catch (IOException ignored) {
                // Try next candidate.
            }
        }
        return null;
    }

    private void routeAuthenticated(HttpExchange exchange, String method, String path, DeviceAuth auth)
            throws IOException {
        Device device = auth.device();
        if ("GET".equals(method) && "/lan/api/me".equals(path)) {
            LanSettings settings = store.ensureSettings();
            sendJson(exchange, 200, Map.of("device", device, "allowRemoteApproval", settings.allowRemoteApproval()));
            return;
        }
        if ("GET".equals(method) && "/lan/api/bots/workspace".equals(path)) {
            sendJson(exchange, 200, bots.workspaceSnapshot());
            return;
        }
        if ("POST".equals(method) && "/lan/api/bots/seen-all".equals(path)) {
            bots.markAllSeen();
            sendJson(exchange, 200, Map.of("seenAll", true));
            return;
        }
        if ("GET".equals(method) && "/lan/api/bots/events".equals(path)) {
            handleSse(exchange, auth);
            return;
        }
        if ("POST".equals(method) && "/lan/api/attachments".equals(path)) {
            handleAttachmentUpload(exchange);
            return;
        }
        if (path.startsWith("/lan/api/attachments/")) {
            String[] parts = path.substring("/lan/api/attachments/".length()).split("/");
            if ("GET".equals(method) && parts.length == 2 && "content".equals(parts[1])) {
                handleAttachmentContent(exchange, parts[0]);
                return;
            }
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
                if ("GET".equals(method) && "avatar".equals(action)) {
                    handleBotAvatar(exchange, botId);
                    return;
                }
                if ("POST".equals(method) && "messages".equals(action)) {
                    JsonNode body = readJson(exchange);
                    String reqId = body.path("requestId").asText(UUID.randomUUID().toString());
                    String content = body.path("content").asText("");
                    Long quoteSeq = body.has("quoteSeq") && !body.get("quoteSeq").isNull()
                            ? body.get("quoteSeq").asLong()
                            : null;
                    List<String> attachmentIds = readStringList(body.path("attachmentIds"));
                    String attachmentsJson = body.has("attachmentsJson") && !body.get("attachmentsJson").isNull()
                            ? body.get("attachmentsJson").asText()
                            : null;
                    String viewingMiniAppId = body.path("viewingMiniAppId").asText("");
                    boolean fromMiniApp = body.path("fromMiniApp").asBoolean(false);
                    sendJson(
                            exchange,
                            202,
                            orchestrator.postMessage(
                                    botId,
                                    reqId,
                                    content,
                                    quoteSeq,
                                    attachmentIds,
                                    attachmentsJson,
                                    viewingMiniAppId,
                                    fromMiniApp));
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
                if ("GET".equals(method) && "skills".equals(action)) {
                    sendJson(exchange, 200, skillService == null ? List.of() : skillService.listViews(botId));
                    return;
                }
            }
            if (parts.length == 3) {
                String botId = parts[0];
                if ("GET".equals(method) && "entries".equals(parts[1]) && "search".equals(parts[2])) {
                    String q = queryParam(exchange.getRequestURI().getRawQuery(), "q");
                    sendJson(exchange, 200, bots.searchEntries(botId, q, 20));
                    return;
                }
                if ("POST".equals(method) && "entries".equals(parts[1]) && "delete".equals(parts[2])) {
                    JsonNode body = readJson(exchange);
                    List<Long> seqs = new ArrayList<>();
                    JsonNode arr = body.path("seqs");
                    if (arr.isArray()) {
                        for (JsonNode item : arr) {
                            if (item.isIntegralNumber()) seqs.add(item.asLong());
                        }
                    }
                    bots.deleteEntries(botId, seqs);
                    sendJson(exchange, 200, Map.of("deleted", true));
                    return;
                }
                if ("GET".equals(method) && "files".equals(parts[1]) && "content".equals(parts[2])) {
                    String relPath = queryParam(exchange.getRequestURI().getRawQuery(), "path");
                    sendJson(exchange, 200, bots.readLibraryFile(botId, relPath));
                    return;
                }
                if ("GET".equals(method) && "files".equals(parts[1]) && "raw".equals(parts[2])) {
                    String relPath = queryParam(exchange.getRequestURI().getRawQuery(), "path");
                    BotService.RawFile file = bots.readRawFile(botId, relPath);
                    byte[] bytes = Files.readAllBytes(file.path());
                    exchange.getResponseHeaders().set("Cache-Control", "private, max-age=60");
                    sendBytes(exchange, 200, file.mediaType(), bytes);
                    return;
                }
            }
            if (parts.length == 4 && "skills".equals(parts[1])) {
                String botId = parts[0];
                String skillName = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
                if (skillService == null) throw new HarnessException("UNAVAILABLE", "技能服务未启用");
                if ("GET".equals(method) && "files".equals(parts[3])) {
                    sendJson(exchange, 200, skillService.listInstalledFiles(botId, skillName));
                    return;
                }
                if ("GET".equals(method) && "file".equals(parts[3])) {
                    String relPath = queryParam(exchange.getRequestURI().getRawQuery(), "path");
                    sendJson(exchange, 200, skillService.readInstalledFile(botId, skillName, relPath));
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
            if ("GET".equals(method) && parts.length == 2 && "versions".equals(parts[1])) {
                sendJson(exchange, 200, miniApps.versions(parts[0]));
                return;
            }
            if ("GET".equals(method) && parts.length == 3 && "versions".equals(parts[1])) {
                int ver = parsePositiveInt(parts[2], "版本号无效");
                sendJson(exchange, 200, miniApps.bundleVersion(parts[0], ver));
                return;
            }
            if ("POST".equals(method) && parts.length == 2 && "restore".equals(parts[1])) {
                JsonNode body = readJson(exchange);
                sendJson(exchange, 200, miniApps.restoreVersion(parts[0], body.path("version").asInt()));
                return;
            }
            if ("GET".equals(method) && parts.length == 2 && "export".equals(parts[1])) {
                String verParam = queryParam(exchange.getRequestURI().getRawQuery(), "version");
                Integer ver = verParam.isBlank() ? null : parsePositiveInt(verParam, "版本号无效");
                BotMiniAppService.ExportFile exported = miniApps.exportStandaloneHtml(parts[0], ver);
                String encoded = URLEncoder.encode(exported.filename(), StandardCharsets.UTF_8).replace("+", "%20");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
                sendBytes(exchange, 200, "application/octet-stream", exported.bytes());
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

    private void handleSse(HttpExchange exchange, DeviceAuth auth) throws IOException {
        if (!sseSlots.tryAcquire()) {
            sendJson(exchange, 503, Map.of("code", "UNAVAILABLE", "message", "实时连接过多，请稍后重试"));
            return;
        }
        String version = bots.globalVersion();
        long lastNoticeId = orchestrator.latestNoticeId();
        Map<String, String> lastStreamSignature = new HashMap<>();
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);

            OutputStream out = exchange.getResponseBody();
            writeSseEvent(out, "ping", "ping");
            long heartbeat = System.nanoTime();
            long authCheck = heartbeat;
            long expires = heartbeat + TimeUnit.SECONDS.toNanos(55);

            while (!closed && !Thread.currentThread().isInterrupted() && System.nanoTime() < expires) {
                long now = System.nanoTime();
                if (now - authCheck > TimeUnit.SECONDS.toNanos(2)) {
                    if (store.findActiveByTokenHash(auth.tokenHash()) == null) {
                        writeSseEvent(out, "revoked", "{}");
                        break;
                    }
                    authCheck = now;
                }

                var activeStreams = orchestrator.activeChatSnapshots();
                var activeKeys = new HashMap<String, Boolean>();
                for (var stream : activeStreams) {
                    activeKeys.put(stream.botId(), Boolean.TRUE);
                    String sig = stream.runId() + ":" + stream.status() + ":" + stream.output().length();
                    if (!sig.equals(lastStreamSignature.get(stream.botId()))) {
                        writeSseEvent(out, "bot-stream", json.writeValueAsString(stream));
                        lastStreamSignature.put(stream.botId(), sig);
                    }
                }
                lastStreamSignature.keySet().removeIf(botId -> !activeKeys.containsKey(botId));

                String current = bots.globalVersion();
                if (!current.equals(version)) {
                    var freshNotices = orchestrator.recentNotices(lastNoticeId);
                    if (!freshNotices.isEmpty()) lastNoticeId = freshNotices.getLast().id();
                    writeSseEvent(
                            out,
                            "bot-event",
                            json.writeValueAsString(Map.of("version", current, "notices", freshNotices)));
                    version = current;
                }

                if (now - heartbeat > TimeUnit.SECONDS.toNanos(5)) {
                    writeSseEvent(out, "ping", "ping");
                    heartbeat = now;
                }
                Thread.sleep(orchestrator.hasLiveWork() ? 60 : 250);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | HarnessException ignored) {
            // Client disconnected; mobile client will reconnect cleanly.
        } finally {
            sseSlots.release();
        }
    }

    private static void writeSseEvent(OutputStream out, String eventName, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(eventName).append('\n');
        for (String line : data.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void handleBotAvatar(HttpExchange exchange, String botId) throws IOException {
        BotService.RawFile file = bots.readAvatar(botId);
        byte[] bytes = Files.readAllBytes(file.path());
        String etag = "\"" + file.filename() + "-" + file.sizeBytes() + "-"
                + Files.getLastModifiedTime(file.path()).toMillis() + "\"";
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=300");
        exchange.getResponseHeaders().set("ETag", etag);
        sendBytes(exchange, 200, file.mediaType(), bytes);
    }

    private void handleAttachmentUpload(HttpExchange exchange) throws IOException {
        ComposerInputService inputs = this.composerInputs;
        if (inputs == null) {
            throw new HarnessException("UNAVAILABLE", "附件服务未就绪");
        }
        byte[] body = readLimitedBytes(exchange.getRequestBody(), MAX_UPLOAD_BYTES);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String filename;
        byte[] fileBytes;
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            MultipartFilePart part = parseFirstMultipartFile(contentType, body);
            if (part == null) throw new HarnessException("INVALID", "未找到上传文件字段");
            filename = part.filename();
            fileBytes = part.bytes();
        } else {
            String rawName = exchange.getRequestHeaders().getFirst("X-Filename");
            filename = rawName == null || rawName.isBlank()
                    ? "attachment.bin"
                    : URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            fileBytes = body;
        }
        Attachment saved = inputs.upload(filename, fileBytes);
        sendJson(exchange, 201, saved);
    }

    private void handleAttachmentContent(HttpExchange exchange, String attachmentId) throws IOException {
        ComposerInputService inputs = this.composerInputs;
        if (inputs == null) {
            throw new HarnessException("UNAVAILABLE", "附件服务未就绪");
        }
        Stored stored = inputs.content(attachmentId);
        String mediaType = stored.attachment().mediaType();
        String safeType = SAFE_IMAGE_TYPES.contains(mediaType) ? mediaType : "text/plain; charset=utf-8";
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=300");
        sendBytes(exchange, 200, safeType, stored.content());
    }

    private static byte[] readLimitedBytes(InputStream in, int maxBytes) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new HarnessException("INVALID", "上传文件超过大小限制");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static MultipartFilePart parseFirstMultipartFile(String contentType, byte[] body) {
        Matcher matcher = MULTIPART_BOUNDARY.matcher(contentType);
        if (!matcher.find()) throw new HarnessException("INVALID", "缺少 multipart boundary");
        String boundary = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

        int pos = indexOf(body, delimiter, 0);
        while (pos >= 0) {
            int partStart = pos + delimiter.length;
            if (partStart + 1 < body.length && body[partStart] == '-' && body[partStart + 1] == '-') {
                break;
            }
            if (partStart + 1 < body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            }
            int headersStop = indexOf(body, headerEnd, partStart);
            if (headersStop < 0) break;
            String headers = new String(body, partStart, headersStop - partStart, StandardCharsets.UTF_8);
            int contentStart = headersStop + headerEnd.length;
            int nextDelimiter = indexOf(body, delimiter, contentStart);
            if (nextDelimiter < 0) break;
            int contentEnd = nextDelimiter;
            if (contentEnd >= 2 && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            }
            String filename = extractDispositionFilename(headers);
            if (filename != null) {
                return new MultipartFilePart(filename, Arrays.copyOfRange(body, contentStart, contentEnd));
            }
            pos = nextDelimiter;
        }
        return null;
    }

    private static String extractDispositionFilename(String headers) {
        Matcher star = FILENAME_STAR.matcher(headers);
        if (star.find()) {
            return URLDecoder.decode(star.group(1).strip(), StandardCharsets.UTF_8);
        }
        Matcher quoted = FILENAME_QUOTED.matcher(headers);
        if (quoted.find()) {
            return quoted.group(1).strip();
        }
        Matcher plain = FILENAME_PLAIN.matcher(headers);
        if (plain.find()) {
            return plain.group(1).strip();
        }
        return null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        if (needle.length == 0 || fromIndex < 0) return -1;
        int limit = haystack.length - needle.length;
        outer:
        for (int i = fromIndex; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) list.add(item.asText());
        }
        return list;
    }

    private static int parsePositiveInt(String raw, String message) {
        try {
            int v = Integer.parseInt(raw);
            if (v <= 0) throw new HarnessException("INVALID", message);
            return v;
        } catch (NumberFormatException error) {
            throw new HarnessException("INVALID", message);
        }
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

    private DeviceAuth authenticate(HttpExchange exchange) {
        String token = extractCookie(exchange.getRequestHeaders().get("Cookie"), COOKIE_NAME);
        if (token == null || token.isBlank()) {
            token = exchange.getRequestHeaders().getFirst("X-Harness-Device-Token");
        }
        if (token == null || token.isBlank()) return null;
        String hash = sha256(token.strip());
        Device device = store.findActiveByTokenHash(hash);
        return device == null ? null : new DeviceAuth(device, hash);
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
        String lower = ua.toLowerCase(Locale.ROOT);
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
