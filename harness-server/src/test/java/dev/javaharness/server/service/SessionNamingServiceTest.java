package dev.javaharness.server.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SessionNamingServiceTest {
    @Test
    void normalizeStripsQuotesAndKeepsOnlyFirstLine() {
        assertThat(SessionNamingService.normalize("  \"命名结果\"  ")).isEqualTo("命名结果");
        assertThat(SessionNamingService.normalize("「项目介绍」")).isEqualTo("项目介绍");
        assertThat(SessionNamingService.normalize("第一行标题\n第二行解释")).isEqualTo("第一行标题");
        assertThat(SessionNamingService.normalize("**加粗标题**")).isEqualTo("加粗标题");
        assertThat(SessionNamingService.normalize("`代码标题`")).isEqualTo("代码标题");
    }

    @Test
    void placeholderMatchesDefaultTitleOrPromptPrefixOnly() {
        assertThat(SessionNamingService.isPlaceholder("新会话", "任意输入")).isTrue();
        // 前端首条消息创建路径：标题为 prompt trim 后截断 40 字符。
        assertThat(SessionNamingService.isPlaceholder("介绍一下这个项目", "介绍一下这个项目")).isTrue();
        String longPrompt = "x".repeat(50);
        assertThat(SessionNamingService.isPlaceholder("x".repeat(40), longPrompt))
                .isTrue();
        assertThat(SessionNamingService.isPlaceholder("我的项目", "介绍一下这个项目")).isFalse();
        assertThat(SessionNamingService.isPlaceholder("新会话2", "任意输入")).isFalse();
    }

    @Test
    void normalizeClipsToTenCodePointsWithoutSplittingSurrogates() {
        assertThat(SessionNamingService.normalize("这是一个超过十个字符的会话标题哦")).isEqualTo("这是一个超过十个字符");
        var emojis = "🌱".repeat(12);
        var clipped = SessionNamingService.normalize(emojis);
        assertThat(clipped.codePointCount(0, clipped.length())).isEqualTo(10);
        assertThat(clipped).isEqualTo("🌱".repeat(10));
    }

    @Test
    void normalizeMapsBlankAndNullToEmpty() {
        assertThat(SessionNamingService.normalize(null)).isEmpty();
        assertThat(SessionNamingService.normalize("   ")).isEmpty();
        assertThat(SessionNamingService.normalize("\"\"")).isEmpty();
    }
}
