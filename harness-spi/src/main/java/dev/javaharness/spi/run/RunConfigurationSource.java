package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.session.Session;

@FunctionalInterface
public interface RunConfigurationSource {
    RunConfiguration snapshot();
    default RunConfiguration snapshot(ModelSelection selection) { return snapshot(); }
    /** Capture extension selection from the stable session after checking an idempotent retry. */
    default RunExtension extensions(Session session, String prompt) { return RunExtension.none(); }
}
