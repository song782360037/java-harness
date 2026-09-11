package dev.javaharness.spi;

@FunctionalInterface
public interface RunConfigurationSource {
    RunConfiguration snapshot();
    default RunConfiguration snapshot(ModelSelection selection) { return snapshot(); }
}
