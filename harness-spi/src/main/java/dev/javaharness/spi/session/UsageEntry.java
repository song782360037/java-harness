package dev.javaharness.spi.session;

import java.time.Instant;

/** 用量台账中的一条模型步骤记录；删除会话后仍保留，用于全局使用统计。inputTokens 含缓存命中部分。 */
public record UsageEntry(
        Instant recordedAt, String provider, String model, int inputTokens, int outputTokens, int cachedTokens) {}
