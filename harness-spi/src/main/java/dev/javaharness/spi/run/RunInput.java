package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ModelImage;
import java.util.List;
import java.util.Objects;

/**
 * 用户随提问附带的输入：context 是引用文件、上传文本等整理成的 {@code <attached_context>} 块，
 * 随本轮用户消息进入会话历史；images 只随本轮请求发送，历史中由 context 里的图片占位代替。
 */
public record RunInput(String context, List<ModelImage> images) {
    public static final String TAG = "<attached_context>";
    public static final RunInput EMPTY = new RunInput("", List.of());

    public RunInput {
        Objects.requireNonNull(context);
        images = images == null ? List.of() : List.copyOf(images);
        if (!context.isEmpty() && !context.startsWith(TAG)) throw new IllegalArgumentException("Invalid attached context");
    }

    public boolean empty() {
        return context.isEmpty() && images.isEmpty();
    }
}
