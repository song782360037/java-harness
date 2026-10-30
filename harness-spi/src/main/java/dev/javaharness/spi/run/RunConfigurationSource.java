package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.session.Session;

/** 提供运行提交时捕获的配置快照与扩展快照。 */
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
