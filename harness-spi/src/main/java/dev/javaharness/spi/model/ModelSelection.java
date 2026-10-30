package dev.javaharness.spi.model;

import dev.javaharness.spi.HarnessException;

import java.util.Set;

/** Null means use the saved profile/default; an empty effort explicitly omits reasoning_effort. */
public record ModelSelection(String profileId, String reasoningEffort) {
    public static final ModelSelection DEFAULT = new ModelSelection(null, null);
    public static final Set<String> EFFORTS = Set.of("", "none", "minimal", "low", "medium", "high", "xhigh", "max");
    public ModelSelection {
        if (profileId != null && (!profileId.matches("[a-zA-Z0-9-]{1,36}")))
            throw new HarnessException("INVALID", "无效模型配置 ID");
        if (reasoningEffort != null && !EFFORTS.contains(reasoningEffort))
            throw new HarnessException("INVALID", "不支持的思考强度");
    }
}
