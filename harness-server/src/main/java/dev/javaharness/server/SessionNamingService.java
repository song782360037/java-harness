package dev.javaharness.server;

import dev.javaharness.core.TokenBudget;
import dev.javaharness.spi.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 会话自动命名：首条消息提交后与任务并行调用模型生成不超过 10 个字符的标题。
 * 仅当标题仍是创建时的占位值（"新会话"或首条消息截断）才命名，用户标题绝不覆盖；
 * 命名失败（模型不可用、超时、返回空、会话已删除）一律静默保留原标题，仅记录日志。
 */
public final class SessionNamingService implements AutoCloseable {
    static final String DEFAULT_TITLE = "新会话";
    private static final int MAX_TITLE_LENGTH = 10;
    private static final int PLACEHOLDER_TITLE_LENGTH = 40;
    private static final int MAX_PROMPT_CHARACTERS = 500;
    private static final Duration NAMING_TIMEOUT = Duration.ofSeconds(30);
    private final SessionStore store;
    private final RunConfigurationSource configurations;
    private final boolean enabled;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public SessionNamingService(SessionStore store, RunConfigurationSource configurations, boolean enabled) {
        this.store = store;
        this.configurations = configurations;
        this.enabled = enabled;
    }

    /** 占位标题只来自两条前端创建路径：默认"新会话"，或首条消息 trim 后截断 40 字符（与前端 slice 口径一致）。 */
    static boolean isPlaceholder(String title, String prompt) {
        if (DEFAULT_TITLE.equals(title)) return true;
        String clipped = prompt.strip();
        return title.equals(clipped.substring(0, Math.min(PLACEHOLDER_TITLE_LENGTH, clipped.length())));
    }

    /**
     * 根据首条消息异步命名。placeholder 为提交时的会话标题：不是占位值直接跳过；
     * 命名返回时标题已被用户修改则条件重命名不匹配，同样跳过，保证用户操作优先。
     */
    public void nameFromFirstMessage(String sessionId, String prompt, ModelSelection selection, String placeholder) {
        if (!enabled || !isPlaceholder(placeholder, prompt)) return;
        try {
            workers.execute(() -> {
                try {
                    String title = generateWithinTimeout(prompt, selection);
                    if (!title.isEmpty()) store.renameSessionIfTitle(sessionId, placeholder, title);
                } catch (Exception error) {
                    System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                            "Session naming skipped for " + sessionId, error);
                }
            });
        } catch (RuntimeException error) {
            // 服务关闭窗口内执行器已拒绝任务，静默放弃命名。
            System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                    "Session naming not scheduled for " + sessionId, error);
        }
    }

    /** 命名调用限时完成；超时取消底层 HTTP 读取并放弃本次命名。 */
    private String generateWithinTimeout(String prompt, ModelSelection selection) throws Exception {
        var future = workers.submit(() -> generate(prompt, selection));
        try {
            return future.get(NAMING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            return "";
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    /** 复用本次任务的模型配置发起单轮命名请求；空工作区即不挂载任何工具。 */
    private String generate(String prompt, ModelSelection selection) throws Exception {
        RunConfiguration configuration = configurations.snapshot(selection);
        if (!configuration.modelInfo().configured()) return "";
        try (RunConfiguration.Resources resources = configuration.resources().open("")) {
            String clipped = clip(prompt, MAX_PROMPT_CHARACTERS);
            String instructions = "请用不超过 10 个字符概括以下内容的主题作为会话标题，只输出标题本身，不要输出标点、引号、换行或解释。\n\n";
            int tokens = new TokenBudget(configuration.tokenCounter())
                    .message(new ModelMessage("user", instructions + clipped));
            var request = new ModelRequest(UUID.randomUUID().toString(), configuration.modelInfo().model(), "NORMAL",
                    List.of(new ModelMessage("user", instructions + clipped)),
                    new ContextSummary(0, 0, 0, instructions.length() + clipped.length(), 0, tokens, configuration.contextWindowTokens()));
            return normalize(resources.model().complete(request, () -> {}, text -> {}).text());
        }
    }

    /** 按码点截断，避免切断代理对。 */
    private static String clip(String text, int limit) {
        return text.codePoints().limit(limit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    /** 清洗模型输出：取首行、去包裹引号与 markdown 符号、按码点截断到标题上限；空串表示保留原标题。 */
    static String normalize(String text) {
        if (text == null) return "";
        String title = text.strip().lines().findFirst().orElse("").strip();
        title = title.replaceAll("^[*`\"'“”‘’「」『』《》]+|[*`\"'“”‘’「」『』《》]+$", "").strip();
        return clip(title, MAX_TITLE_LENGTH);
    }

    @Override public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException error) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
