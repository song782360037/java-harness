package dev.javaharness.spi;

import java.time.Duration;
import java.util.Objects;

/** Immutable settings snapshot captured when a run is submitted. Resources open only for new runs. */
public record RunConfiguration(ModelInfo modelInfo, Duration timeout, int contextMaxCharacters, int maxSteps,
                               ResourceFactory resources, ModelSelection selection) {
    public RunConfiguration(ModelInfo modelInfo, Duration timeout, int contextMaxCharacters, int maxSteps, ResourceFactory resources) {
        this(modelInfo, timeout, contextMaxCharacters, maxSteps, resources, new ModelSelection("default", ""));
    }
    @FunctionalInterface public interface ResourceFactory { Resources open(String workspace) throws Exception; }
    public record Resources(ModelProvider model, WorkspaceTools tools, Runnable release) implements AutoCloseable {
        @Override public void close() { release.run(); }
    }
    public RunConfiguration {
        Objects.requireNonNull(modelInfo);
        Objects.requireNonNull(resources);
        Objects.requireNonNull(selection);
        if (timeout == null || timeout.isNegative() || timeout.isZero() || contextMaxCharacters < 4000
                || contextMaxCharacters > 256000 || maxSteps < 0) throw new IllegalArgumentException("Invalid run settings");
    }
}
