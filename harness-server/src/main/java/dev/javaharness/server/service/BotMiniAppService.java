package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bot 交互小程序服务：管理 data/bot-library/apps/<id>/ 下的单文件 HTML（按变更升版本）与 JSON 数据（换数据不升版本），
 * 并为前端 iframe 沙箱注入 window.harnessBot 双向桥接脚本。
 */
public final class BotMiniAppService {
    private static final int MAX_HTML_BYTES = 512_000;
    private static final int MAX_DATA_BYTES = 512_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BRIDGE_SCRIPT = """
            <script>
            (function () {
              if (window.harnessBot) return;
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
              window.addEventListener('message', function (event) {
                var msg = event.data;
                if (!msg || msg.source !== 'harness-host') return;
                if (msg.type === 'data') {
                  listeners.forEach(function (fn) { try { fn(msg.data); } catch (e) {} });
                  return;
                }
                if (msg.id && pending[msg.id]) {
                  var p = pending[msg.id];
                  delete pending[msg.id];
                  if (msg.error) p.reject(new Error(msg.error));
                  else p.resolve(msg.result);
                }
              });
              window.harnessBot = {
                getData: function () { return request('getData', null); },
                setData: function (data) { return request('setData', data); },
                sendMessage: function (text) { return request('sendMessage', String(text || '')); },
                onData: function (fn) {
                  if (typeof fn === 'function') listeners.push(fn);
                  return function () { listeners = listeners.filter(function (item) { return item !== fn; }); };
                }
              };
            })();
            </script>
            """;

    public record AppBundle(MiniApp app, JsonNode data, String html) {}

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

    public MiniApp saveApp(
            String botId,
            String taskId,
            String appId,
            String name,
            String description,
            String html,
            String initialDataJson) {
        Bot bot = store.getBot(botId);
        String cleanName =
                name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleanName.isEmpty() || cleanName.length() > 80) throw new HarnessException("INVALID", "小程序名称需为 1–80 个字符");
        String cleanDesc = description == null ? "" : description.strip();
        if (cleanDesc.length() > 300) cleanDesc = cleanDesc.substring(0, 300);
        if (html == null || html.isBlank()) throw new HarnessException("INVALID", "小程序 HTML 不能为空");
        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        if (htmlBytes.length > MAX_HTML_BYTES) throw new HarnessException("INVALID", "小程序页面超过 512KB 上限");

        String targetId = (appId == null || appId.isBlank()) ? UUID.randomUUID().toString() : appId.strip();
        if (!targetId.matches("[a-zA-Z0-9_-]{4,36}")) {
            throw new HarnessException("INVALID", "小程序 ID 格式无效");
        }

        Path appDir = safeAppDir(targetId);
        Path htmlFile = appDir.resolve("index.html");
        Path dataFile = appDir.resolve("data.json");
        try {
            Files.createDirectories(appDir);
            Files.write(htmlFile, htmlBytes);
            if (initialDataJson != null) {
                writeValidatedJson(dataFile, initialDataJson);
            } else if (!Files.exists(dataFile)) {
                Files.writeString(dataFile, "{}", StandardCharsets.UTF_8);
            }
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "保存小程序文件失败");
        }

        String relHtml = "apps/" + targetId + "/index.html";
        String relData = "apps/" + targetId + "/data.json";
        String finalDesc = cleanDesc;
        MiniApp saved =
                write(() -> store.upsertMiniApp(targetId, botId, cleanName, finalDesc, relHtml, relData, taskId));
        if (orchestrator != null) {
            orchestrator.pushNotice(
                    "APP_SAVED",
                    bot.id(),
                    bot.name(),
                    "小程序已更新",
                    bot.name() + " 生成了「" + saved.name() + "」v" + saved.version());
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
        MiniApp app = store.miniApp(appId);
        Path appDir = safeAppDir(app.id());
        Path htmlFile = appDir.resolve("index.html");
        Path dataFile = appDir.resolve("data.json");
        try {
            String rawHtml = Files.exists(htmlFile)
                    ? Files.readString(htmlFile, StandardCharsets.UTF_8)
                    : "<html><body>Empty</body></html>";
            String rawData = Files.exists(dataFile) ? Files.readString(dataFile, StandardCharsets.UTF_8) : "{}";
            JsonNode data = JSON.readTree(rawData);
            return new AppBundle(app, data, injectBridge(rawHtml));
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "读取小程序内容失败");
        }
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

    static String injectBridge(String html) {
        int headEnd = html.toLowerCase().indexOf("</head>");
        if (headEnd >= 0) {
            return html.substring(0, headEnd) + BRIDGE_SCRIPT + html.substring(headEnd);
        }
        int bodyStart = html.toLowerCase().indexOf("<body");
        if (bodyStart >= 0) {
            int gt = html.indexOf('>', bodyStart);
            if (gt >= 0) {
                return html.substring(0, gt + 1) + BRIDGE_SCRIPT + html.substring(gt + 1);
            }
        }
        return BRIDGE_SCRIPT + html;
    }

    private <T> T write(Supplier<T> work) {
        synchronized (sessions) {
            return tx.execute(status -> work.get());
        }
    }
}
