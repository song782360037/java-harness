package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.time.Duration;
import java.util.Objects;

/** Immutable settings snapshot captured when a run is submitted. Resources open only for new runs. */
public record RunConfiguration(
        ModelInfo modelInfo,
        Duration timeout,
        int contextWindowTokens,
        int maxOutputTokens,
        int maxToolsPerRequest,
        TokenCounter tokenCounter,
        ResourceFactory resources,
        ModelSelection selection) {
    public RunConfiguration(
            ModelInfo modelInfo,
            Duration timeout,
            int contextWindowTokens,
            int maxOutputTokens,
            int maxToolsPerRequest,
            TokenCounter tokenCounter,
            ResourceFactory resources) {
        this(
                modelInfo,
                timeout,
                contextWindowTokens,
                maxOutputTokens,
                maxToolsPerRequest,
                tokenCounter,
                resources,
                new ModelSelection("default", ""));
    }

    /** Legacy constructor: accept a character budget and derive a conservative token window. */
    public RunConfiguration(
            ModelInfo modelInfo,
            Duration timeout,
            int legacyContextCharacters,
            int maxToolsPerRequest,
            ResourceFactory resources) {
        this(
                modelInfo,
                timeout,
                Math.max(4096, legacyContextCharacters / 2),
                outputFor(Math.max(4096, legacyContextCharacters / 2)),
                maxToolsPerRequest,
                TokenCounter.approximate(),
                resources,
                new ModelSelection("default", ""));
    }

    @FunctionalInterface
    public interface ResourceFactory {
        Resources open(String workspace) throws Exception;
    }

    public record Resources(ModelProvider model, WorkspaceTools tools, Runnable release) implements AutoCloseable {
        @Override
        public void close() {
            release.run();
        }
    }

    public RunConfiguration {
        Objects.requireNonNull(modelInfo);
        Objects.requireNonNull(resources);
        Objects.requireNonNull(selection);
        Objects.requireNonNull(tokenCounter);
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || contextWindowTokens < 1024
                || contextWindowTokens > 2_000_000
                || maxOutputTokens < 64
                || maxOutputTokens >= contextWindowTokens
                || maxToolsPerRequest < 1
                || maxToolsPerRequest > ToolCall.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid run settings");
        }
    }

    public static int outputFor(int contextWindowTokens) {
        return Math.min(8192, Math.max(64, contextWindowTokens / 4));
    }
}
