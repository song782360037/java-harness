package dev.javaharness.spi;

import java.util.List;

public interface SessionStore {
    record Submission(Run run, boolean created) {}

    Session createSession(String title);
    Session createSession(String title, String workspace);
    Session updateWorkspace(String sessionId, String workspace);
    /** List reusable workspaces independently of their sessions. */
    List<Workspace> workspaces();
    Workspace workspace(String id);
    /** Save a workspace; an empty id creates it, otherwise revision must match. */
    Workspace saveWorkspace(String id, String name, List<String> directories, String icon, String color, long revision);
    /** Bind a session to a reusable workspace, or clear the binding with an empty id. */
    Session selectWorkspace(String sessionId, String workspaceId);
    Session createSessionInWorkspace(String title, String workspaceId);
    /** Rename a session without changing its workspace or active run. */
    Session renameSession(String sessionId, String title);
    /** Rename only when the current title still matches, atomically; false when it changed first. */
    boolean renameSessionIfTitle(String sessionId, String expectedTitle, String title);
    /** Delete a session and all of its run artifacts; rejects while a run is active. */
    void deleteSession(String sessionId);
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
    /** Append display-only reasoning to a running step; text beyond the step budget is dropped. */
    void appendReasoning(String runId, int step, String text);
    /** Steps recorded since request snapshots began; empty for legacy runs. */
    List<RunStep> steps(String runId);
    Submission submit(String sessionId, String requestId, String prompt, String scenario, ModelInfo model);
    Submission submit(String sessionId, String requestId, String prompt, String scenario, ModelInfo model,
                      ModelSelection requested, ModelSelection selected);
    /** Persist the extension selection in the same transaction as the new run. */
    default Submission submit(String sessionId, String requestId, String prompt, String scenario, ModelInfo model,
                              ModelSelection requested, ModelSelection selected, String extensions) {
        return submit(sessionId, requestId, prompt, scenario, model, requested, selected);
    }
    /** Notify clients that extension preparation or an external approval changed. */
    default void pluginsUpdated(String runId) { commandsUpdated(runId); }
    /** Validate an exact retry before resolving a profile that may have changed or been deleted. */
    Submission retry(String sessionId, String requestId, String prompt, String scenario, ModelSelection requested);
    Run transition(String runId, RunStatus status, String detail);
    void appendText(String runId, String text);
    List<RunEvent> events(String sessionId, long after, int limit);
    void recoverInterruptedRuns();
    void fileChangesUpdated(String runId);
    void commandsUpdated(String runId);
}
