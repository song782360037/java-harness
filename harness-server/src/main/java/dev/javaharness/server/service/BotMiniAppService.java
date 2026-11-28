package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.adapters.bot.BotStore.MiniAppVersion;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bot 交互小程序服务：
 * 1. 管理 data/bot-library/apps/<id>/ 下的多版本单文件 HTML（v<N>.html + index.html 同步）与 JSON 数据；
 * 2. 注入 window.harnessBot 双向桥接脚本（同时兼容异步 getData/setData/sendMessage/onData 与同步 data/save/ask，并捕获运行时 JS 报错）；
 * 3. 支持历史版本预览、无损恢复（生成新版本）与可离线打开的独立 HTML 导出。
 */
public final class BotMiniAppService {
    private static final int MAX_HTML_BYTES = 512_000;
    private static final int MAX_DATA_BYTES = 512_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_ICONS =
            Set.of("chart", "table", "calendar", "doc", "check", "clock", "team", "chat", "folder", "cube");
    private static final Pattern HEAD_ANCHOR = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ANCHOR = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCTYPE_ANCHOR = Pattern.compile("^\\s*<!doctype[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final String LIVE_RUNTIME_TEMPLATE = """
            <script>
            (function () {
              if (window.harnessBot) return;
              var currentData = __INITIAL_DATA__;
              var seq = 0;
              var pending = Object.create(null);
              var listeners = [];
              function request(action, payload) {
                return new Promise(function (resolve, reject) {
                  var id = 'hb_' + (++seq);
                  pending[id] = { resolve: resolve, reject: reject };
                  window.parent.postMessage({ source: 'harness-mini-app', id: id, action: action, payload: payload }, '*');
                });
              }
              function reportError(raw) {
                try {
                  var message = raw && raw.message ? String(raw.message) : String(raw || '未知脚本错误');
                  window.parent.postMessage({ source: 'harness-mini-app', action: 'error', payload: message }, '*');
                } catch (e) {}
              }
              window.addEventListener('error', function (event) {
                reportError(event && (event.message || event.error) ? (event.message || event.error) : '页面脚本运行异常');
              });
              window.addEventListener('unhandledrejection', function (event) {
                reportError(event ? event.reason : '未处理的 Promise 异常');
              });
              window.addEventListener('message', function (event) {
                var msg = event.data;
                if (!msg || msg.source !== 'harness-host') return;
                if (msg.type === 'data') {
                  currentData = msg.data;
                  listeners.forEach(function (fn) { try { fn(msg.data); } catch (e) { reportError(e); } });
                  return;
                }
                if (msg.id && pending[msg.id]) {
                  var p = pending[msg.id];
                  delete pending[msg.id];
                  if (msg.error) p.reject(new Error(msg.error));
                  else p.resolve(msg.result);
                }
              });
              var api = {
                get data() { return currentData; },
                getData: function () { return request('getData', null).then(function (d) { currentData = d; return d; }); },
                setData: function (data) { currentData = data; return request('setData', data); },
                save: function (data) { currentData = data; return request('setData', data); },
                sendMessage: function (text) { return request('sendMessage', String(text || '')); },
                ask: function (text) { return request('sendMessage', String(text || '')); },
                onData: function (fn) {
                  if (typeof fn === 'function') listeners.push(fn);
                  return function () { listeners = listeners.filter(function (item) { return item !== fn; }); };
                }
              };
              window.harnessBot = Object.freeze(api);
            })();
            </script>
            """;

    private static final String STANDALONE_RUNTIME_TEMPLATE = """
            <script>
            (function () {
              var currentData = __INITIAL_DATA__;
              var listeners = [];
              var api = {
                get data() { return currentData; },
                getData: function () { return Promise.resolve(currentData); },
                setData: function (next) {
                  currentData = next;
                  listeners.forEach(function (fn) { try { fn(next); } catch (e) {} });
                  return Promise.resolve({ ok: true });
                },
                save: function (next) { return api.setData(next); },
                sendMessage: function () { return Promise.resolve({ ok: false, offline: true }); },
                ask: function () { return Promise.resolve({ ok: false, offline: true }); },
                onData: function (fn) {
                  if (typeof fn === 'function') listeners.push(fn);
                  return function () { listeners = listeners.filter(function (item) { return item !== fn; }); };
                }
              };
              window.harnessBot = Object.freeze(api);
            })();
            </script>
            """;

    public record AppBundle(
            MiniApp app, JsonNode data, String html, List<MiniAppVersion> versions, int shownVersion) {}

    public record ExportFile(String filename, byte[] bytes) {}

    private final BotStore store;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final Path appsRoot;
    private BotOrchestrator orchestrator;

    public BotMiniAppService(BotStore store, SessionStore sessions, TransactionTemplate tx, Path libraryRoot) {
        this.store = store;
        this.sessions = sessions;
        this.tx = tx;
        this.appsRoot = libraryRoot.toAbsolutePath().normalize().resolve("apps");
    }

    public void useOrchestrator(BotOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public List<MiniApp> listForBot(String botId) {
        return store.miniApps(botId);
    }

    public List<MiniAppVersion> versions(String appId) {
        store.miniApp(appId);
        return store.miniAppVersions(appId);
    }

    public MiniApp saveApp(
            String botId,
            String taskId,
            String appId,
            String name,
            String description,
            String html,
            String initialDataJson) {
        return saveApp(botId, taskId, appId, name, description, "", "", "", html, initialDataJson);
    }

    public MiniApp saveApp(
            String botId,
            String taskId,
            String appId,
            String name,
            String description,
            String icon,
            String color,
            String changeSummary,
            String html,
            String initialDataJson) {
        Bot bot = store.getBot(botId);
        String cleanName =
                name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleanName.isEmpty() || cleanName.length() > 80) throw new HarnessException("INVALID", "小程序名称需为 1–80 个字符");
        String cleanDesc = description == null ? "" : description.strip();
        if (cleanDesc.length() > 300) cleanDesc = cleanDesc.substring(0, 300);
        String cleanIcon = normalizeIcon(icon);
        String cleanColor = normalizeColor(color);
        String cleanChange = changeSummary == null ? "" : changeSummary.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleanChange.length() > 260) cleanChange = cleanChange.substring(0, 260);
        if (html == null || html.isBlank()) throw new HarnessException("INVALID", "小程序 HTML 不能为空");
        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        if (htmlBytes.length > MAX_HTML_BYTES) throw new HarnessException("INVALID", "小程序页面超过 512KB 上限");

        String targetId = (appId == null || appId.isBlank()) ? UUID.randomUUID().toString() : appId.strip();
        if (!targetId.matches("[a-zA-Z0-9_-]{4,36}")) {
            throw new HarnessException("INVALID", "小程序 ID 格式无效");
        }

        String finalDesc = cleanDesc;
        String finalChange = cleanChange;
        MiniApp saved = write(() -> {
            int nextVer = store.nextMiniAppVersion(targetId);
            String versionFilename = "v" + nextVer + ".html";
            Path appDir = safeAppDir(targetId);
            Path versionFile = appDir.resolve(versionFilename);
            Path indexFile = appDir.resolve("index.html");
            Path dataFile = appDir.resolve("data.json");
            try {
                Files.createDirectories(appDir);
                Files.write(versionFile, htmlBytes);
                Files.write(indexFile, htmlBytes);
                if (initialDataJson != null) {
                    writeValidatedJson(dataFile, initialDataJson);
                } else if (!Files.exists(dataFile)) {
                    Files.writeString(dataFile, "{}", StandardCharsets.UTF_8);
                }
            } catch (IOException error) {
                throw new HarnessException("UNAVAILABLE", "保存小程序文件失败");
            }
            String relHtml = "apps/" + targetId + "/" + versionFilename;
            String relData = "apps/" + targetId + "/data.json";
            return store.upsertMiniApp(
                    targetId, botId, cleanName, finalDesc, cleanIcon, cleanColor, relHtml, relData, taskId, finalChange);
        });
        if (orchestrator != null) {
            orchestrator.pushNotice(
                    "APP_SAVED",
                    bot.id(),
                    bot.name(),
                    "小程序已更新",
                    bot.name() + " 保存了「" + saved.name() + "」v" + saved.version());
        }
        return saved;
    }

    public MiniApp updateAppData(String appId, String taskId, String dataJson) {
        MiniApp existing = store.miniApp(appId);
        Path dataFile = safeAppDir(existing.id()).resolve("data.json");
        try {
            Files.createDirectories(dataFile.getParent());
            writeValidatedJson(dataFile, dataJson);
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "更新小程序数据失败");
        }
        return write(() -> store.touchMiniAppData(existing.id(), taskId));
    }

    public AppBundle bundle(String appId) {
        return bundleVersion(appId, null);
    }

    public AppBundle bundleVersion(String appId, Integer requestedVersion) {
        MiniApp app = store.miniApp(appId);
        List<MiniAppVersion> versionList = store.miniAppVersions(app.id());
        int shown = requestedVersion == null ? app.version() : requestedVersion;
        String rawHtml = readVersionHtml(app, shown);
        JsonNode data = readDataJson(app);
        return new AppBundle(app, data, injectBridge(rawHtml, data, false), versionList, shown);
    }

    /** 将某个历史版本无损恢复为最新版本（version = latest + 1），保留完整线性历史。 */
    public MiniApp restoreVersion(String appId, int targetVersion) {
        MiniApp existing = store.miniApp(appId);
        MiniAppVersion ver = store.miniAppVersion(existing.id(), targetVersion);
        String rawHtml = readVersionHtml(existing, ver.version());
        return saveApp(
                existing.botId(),
                "",
                existing.id(),
                existing.name(),
                existing.description(),
                existing.icon(),
                existing.color(),
                "回滚至 v" + targetVersion,
                rawHtml,
                null);
    }

    /** 导出可独立在本地浏览器打开的单文件 HTML（内嵌当前数据快照与离线 window.harnessBot 垫片）。 */
    public ExportFile exportStandaloneHtml(String appId, Integer requestedVersion) {
        MiniApp app = store.miniApp(appId);
        int shown = requestedVersion == null ? app.version() : requestedVersion;
        String rawHtml = readVersionHtml(app, shown);
        JsonNode data = readDataJson(app);
        String standalone = injectBridge(rawHtml, data, true);
        String safeName = app.name().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (safeName.isEmpty()) safeName = app.id();
        String filename = safeName + "-v" + shown + ".html";
        return new ExportFile(filename, standalone.getBytes(StandardCharsets.UTF_8));
    }

    public void deleteApp(String appId) {
        MiniApp existing = store.miniApp(appId);
        write(() -> {
            store.deleteMiniApp(existing.id());
            return null;
        });
        Path appDir = safeAppDir(existing.id());
        if (Files.exists(appDir)) {
            try (var stream = Files.walk(appDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort cleanup.
                    }
                });
            } catch (IOException ignored) {
                // Best effort cleanup.
            }
        }
    }

    private String readVersionHtml(MiniApp app, int version) {
        Path appDir = safeAppDir(app.id());
        Path versionFile = appDir.resolve("v" + version + ".html");
        Path fallback = appDir.resolve("index.html");
        try {
            if (Files.exists(versionFile)) {
                return Files.readString(versionFile, StandardCharsets.UTF_8);
            }
            if (version == app.version() && Files.exists(fallback)) {
                return Files.readString(fallback, StandardCharsets.UTF_8);
            }
            MiniAppVersion rec = store.miniAppVersion(app.id(), version);
            Path byRecord = appsRoot.getParent().resolve(rec.htmlPath()).normalize();
            if (byRecord.startsWith(appDir) && Files.exists(byRecord)) {
                return Files.readString(byRecord, StandardCharsets.UTF_8);
            }
            if (Files.exists(fallback)) {
                return Files.readString(fallback, StandardCharsets.UTF_8);
            }
            return "<!doctype html><html><body>Empty</body></html>";
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "读取小程序内容失败");
        }
    }

    private JsonNode readDataJson(MiniApp app) {
        Path dataFile = safeAppDir(app.id()).resolve("data.json");
        try {
            String rawData = Files.exists(dataFile) ? Files.readString(dataFile, StandardCharsets.UTF_8) : "{}";
            return JSON.readTree(rawData);
        } catch (IOException error) {
            return JSON.createObjectNode();
        }
    }

    private Path safeAppDir(String appId) {
        Path dir = appsRoot.resolve(appId).normalize();
        if (!dir.startsWith(appsRoot)) throw new HarnessException("INVALID", "非法的小程序路径");
        return dir;
    }

    private void writeValidatedJson(Path file, String rawJson) throws IOException {
        String text = rawJson == null || rawJson.isBlank() ? "{}" : rawJson;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DATA_BYTES) throw new HarnessException("INVALID", "小程序数据超过 512KB 上限");
        try {
            JSON.readTree(text);
        } catch (Exception error) {
            throw new HarnessException("INVALID", "小程序数据必须是合法 JSON");
        }
        Files.write(file, bytes);
    }

    private static String normalizeIcon(String icon) {
        if (icon == null || icon.isBlank()) return "";
        String lower = icon.strip().toLowerCase();
        return ALLOWED_ICONS.contains(lower) ? lower : "cube";
    }

    private static String normalizeColor(String color) {
        if (color == null || color.isBlank()) return "";
        String trimmed = color.strip();
        return trimmed.matches("#[0-9a-fA-F]{6}") ? trimmed : "";
    }

    static String injectBridge(String html) {
        return injectBridge(html, JSON.createObjectNode(), false);
    }

    static String injectBridge(String html, JsonNode data, boolean standalone) {
        String safeJson;
        try {
            safeJson = JSON.writeValueAsString(data == null ? JSON.createObjectNode() : data)
                    .replace("<", "\\u003c");
        } catch (Exception error) {
            safeJson = "{}";
        }
        String template = standalone ? STANDALONE_RUNTIME_TEMPLATE : LIVE_RUNTIME_TEMPLATE;
        String script = template.replace("__INITIAL_DATA__", safeJson);
        Matcher head = HEAD_ANCHOR.matcher(html);
        if (head.find()) {
            return html.substring(0, head.end()) + "\n" + script + html.substring(head.end());
        }
        Matcher htmlTag = HTML_ANCHOR.matcher(html);
        if (htmlTag.find()) {
            return html.substring(0, htmlTag.end()) + "\n" + script + html.substring(htmlTag.end());
        }
        Matcher doctype = DOCTYPE_ANCHOR.matcher(html);
        if (doctype.find()) {
            return html.substring(0, doctype.end()) + "\n" + script + html.substring(doctype.end());
        }
        return script + html;
    }

    private <T> T write(Supplier<T> work) {
        synchronized (sessions) {
            return tx.execute(status -> work.get());
        }
    }
}
