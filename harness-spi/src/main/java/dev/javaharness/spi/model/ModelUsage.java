package dev.javaharness.spi.model;

/** 服务端报告的单步 token 用量；durationMillis 为首个到最后一个 choices 分片的耗时（含推理输出）。 */
public record ModelUsage(int inputTokens, int outputTokens, int cachedTokens, long durationMillis) {
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || cachedTokens < 0 || durationMillis < 0)
            throw new IllegalArgumentException("Invalid model usage");
    }
}
