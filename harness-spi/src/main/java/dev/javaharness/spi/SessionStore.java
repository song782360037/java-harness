package dev.javaharness.spi;

import java.util.List;

public interface SessionStore {
    record Submission(Run run, boolean created) {}

    Session createSession(String title);
    List<Session> sessions();
    SessionSnapshot snapshot(String sessionId);
    Run run(String runId);
    /** Runs preceding this run's QUEUED event, ordered by session sequence. */
    List<Run> contextHistory(String runId);
    void saveModelRequest(ModelRequest request);
    /** Null for legacy runs or work cancelled before request preparation. */
    ModelRequest modelRequest(String runId);
    List<ModelRequest> modelRequests(String runId);
    Run bindWorkspace(String runId, String workspace);
    List<ModelMessage> conversation(String runId);
    void completeRun(String runId, List<ModelMessage> messages);
    void completeRun(String runId, List<ModelMessage> messages, String detail);
    void beginTools(String runId, int step, List<ToolCall> calls);
    void updateTool(String runId, String callId, String status, String output);
    List<ToolExecution> tools(String runId);
    Submission submit(String sessionId, String requestId, String prompt, String scenario, ModelInfo model);
    Run transition(String runId, RunStatus status, String detail);
    void appendText(String runId, String text);
    List<RunEvent> events(String sessionId, long after, int limit);
    void recoverInterruptedRuns();
}
