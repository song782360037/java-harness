package dev.javaharness.server;

import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelProvider;

/** Functional test fixture for lifecycle tests, with an explicit non-production identity. */
@FunctionalInterface
public interface TestModelProvider extends ModelProvider {
    ModelInfo INFO = new ModelInfo("simulated", "local-simulator", true);
    @Override default ModelInfo info() { return INFO; }
}
