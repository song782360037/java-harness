package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.workspace.WorkspaceTools;

/** A submission-owned extension snapshot; opening resources must happen outside coordination locks. */
public interface RunExtension extends AutoCloseable {
    String snapshot();

    String instructions();
    /** Attach the persisted run identity before its worker can start. */
    default void submitted(String runId) {}

    WorkspaceTools open(String runId, WorkspaceTools local, ModelProvider.Cancellation cancellation) throws Exception;

    /**
     * Shared context, such as a group chat transcript, that replaces the session history and is placed before the
     * prompt; it must fit {@code maxTokens}. Null keeps the session history.
     */
    default String context(TokenCounter counter, int maxTokens) {
        return null;
    }

    /** Notified once with the final run after the engine settled it and closed this extension. */
    default void settled(Run run) {}

    @Override
    void close();

    static RunExtension none() {
        return new RunExtension() {
            public String snapshot() {
                return "{}";
            }

            public String instructions() {
                return "";
            }

            public WorkspaceTools open(String runId, WorkspaceTools local, ModelProvider.Cancellation cancellation) {
                return local;
            }

            public void close() {}
        };
    }
}
