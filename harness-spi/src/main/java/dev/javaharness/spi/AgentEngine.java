package dev.javaharness.spi;

public interface AgentEngine extends AutoCloseable {
    SessionStore.Submission submit(String sessionId, String requestId, String prompt, String scenario);
    Run cancel(String runId);
    @Override
    void close();
}
