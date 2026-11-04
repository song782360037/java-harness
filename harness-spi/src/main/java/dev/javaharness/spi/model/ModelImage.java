package dev.javaharness.spi.model;

import java.util.Objects;
import java.util.Set;

/** 用户消息携带的一张图片；data 为 Base64，保存的请求快照中为空串以免重复存储图片内容。 */
public record ModelImage(String name, String mediaType, String data) {
    public static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    public ModelImage {
        Objects.requireNonNull(name);
        Objects.requireNonNull(data);
        if (!MEDIA_TYPES.contains(mediaType)) throw new IllegalArgumentException("Unsupported image type");
    }

    /** 去掉图片内容，只保留名称和类型。 */
    public ModelImage withoutData() {
        return data.isEmpty() ? this : new ModelImage(name, mediaType, "");
    }
}
