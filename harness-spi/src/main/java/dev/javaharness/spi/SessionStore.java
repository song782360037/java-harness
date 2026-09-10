package dev.javaharness.spi;

import java.util.List;

public interface SessionStore {
    record Submission(Run run, boolean created) {}

    Session createSession(String title);
    List<Session> sessions();
    SessionSnapshot snapshot(String sessionId);
    Run run(String runId);
    Submission submit(String sessionId, String requestId, String prompt, String scenario);
    Run transition(String runId, RunStatus status, String detail);
    void appendText(String runId, String text);
    List<RunEvent> events(String sessionId, long after, int limit);
    void recoverInterruptedRuns();
}
