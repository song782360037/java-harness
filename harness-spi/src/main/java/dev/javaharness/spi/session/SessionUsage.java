package dev.javaharness.spi.session;

/**
 * 会话内服务端报告用量的累计；lastInputTokens 为最新运行最近一步报告的输入 tokens，
 * 用于展示当前上下文占用，最新运行未报告时为 0，由调用方回退到估算值。
 */
public record SessionUsage(
        long inputTokens, long outputTokens, long cachedTokens, long durationMillis, int lastInputTokens) {
    public static final SessionUsage EMPTY = new SessionUsage(0, 0, 0, 0, 0);
}
