package dev.javaharness.spi;

public interface AgentEngine extends AutoCloseable {
    SessionStore.Submission submit(String sessionId, String requestId, String prompt, String scenario);
    SessionStore.Submission submit(String sessionId, String requestId, String prompt, String scenario, ModelSelection selection);
    Run cancel(String runId);
    @Override
    void close();
}
