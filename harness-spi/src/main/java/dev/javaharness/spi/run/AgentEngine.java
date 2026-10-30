package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.session.SessionStore;

/**
 * Accepts prompts as runs of a session and executes them asynchronously.
 * Resubmitting the same request id with the same input returns the existing run;
 * reusing it with a different prompt, scenario or model selection is a {@code CONFLICT}.
 */
public interface AgentEngine extends AutoCloseable {
    SessionStore.Submission submit(String sessionId, String requestId, String prompt, String scenario);

    SessionStore.Submission submit(
            String sessionId, String requestId, String prompt, String scenario, ModelSelection selection);

    Run cancel(String runId);

    @Override
    void close();
}
