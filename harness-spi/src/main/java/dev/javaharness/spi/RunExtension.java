package dev.javaharness.spi;

/** A submission-owned extension snapshot; opening resources must happen outside coordination locks. */
public interface RunExtension extends AutoCloseable {
    String snapshot();
    String instructions();
    /** Attach the persisted run identity before its worker can start. */
    default void submitted(String runId) {}
    WorkspaceTools open(String runId, WorkspaceTools local, ModelProvider.Cancellation cancellation) throws Exception;
    @Override void close();

    static RunExtension none() {
        return new RunExtension() {
            public String snapshot() { return "{}"; }
            public String instructions() { return ""; }
            public WorkspaceTools open(String runId, WorkspaceTools local, ModelProvider.Cancellation cancellation) { return local; }
            public void close() {}
        };
    }
}
