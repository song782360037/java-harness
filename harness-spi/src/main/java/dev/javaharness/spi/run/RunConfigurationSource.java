package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.session.Session;

/** Supplies the configuration snapshot and extensions captured when a run is submitted. */
@FunctionalInterface
public interface RunConfigurationSource {
    RunConfiguration snapshot();

    default RunConfiguration snapshot(ModelSelection selection) {
        return snapshot();
    }
    /** Capture extension selection from the stable session after checking an idempotent retry. */
    default RunExtension extensions(Session session, String prompt) {
        return RunExtension.none();
    }
}
