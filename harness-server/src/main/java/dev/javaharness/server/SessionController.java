package dev.javaharness.server;

import dev.javaharness.spi.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SessionController {
    private final SessionStore store;
    private final AgentEngine engine;
    private final WorkspaceDirectories directories;

    public SessionController(SessionStore store, AgentEngine engine, WorkspaceDirectories directories) {
        this.store = store;
        this.engine = engine;
        this.directories = directories;
    }

    public record CreateSession(String title, String workspace, String workspaceId) {}
    public record ChangeWorkspace(String workspace, String workspaceId) {}
    public record RenameSession(String title) {}
    public record SubmitRun(String requestId, String prompt, String scenario, String modelProfileId, String reasoningEffort) {}

    @GetMapping("/sessions")
    public List<Session> sessions() {
        return store.sessions();
    }

    @PostMapping(value = "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Session create(@RequestBody CreateSession request) {
        return request.workspaceId() == null
                ? store.createSession(request.title(), directories.normalize(request.workspace()))
                : store.createSessionInWorkspace(request.title(), request.workspaceId());
    }

    @PostMapping(value = "/sessions/{id}/workspace", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Session workspace(@PathVariable String id, @RequestBody ChangeWorkspace request) {
        return request.workspaceId() == null
                ? store.updateWorkspace(id, directories.normalize(request.workspace()))
                : store.selectWorkspace(id, request.workspaceId());
    }

    /** Rename the session, including while a run is active. */
    @PostMapping(value = "/sessions/{id}/title", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Session rename(@PathVariable String id, @RequestBody RenameSession request) {
        return store.renameSession(id, request.title());
    }

    @GetMapping("/sessions/{id}")
    public SessionSnapshot snapshot(@PathVariable String id) {
        return store.snapshot(id);
    }

    @GetMapping("/runs/{id}/model-request")
    public ResponseEntity<ModelRequest> modelRequest(@PathVariable String id) {
        ModelRequest request = store.modelRequest(id);
        return request == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(request);
    }

    @GetMapping("/runs/{id}/model-requests")
    public List<ModelRequest> modelRequests(@PathVariable String id) {
        return store.modelRequests(id);
    }

    @GetMapping("/runs/{id}/tools")
    public List<ToolExecution> tools(@PathVariable String id) {
        return store.tools(id);
    }

    @PostMapping(value = "/sessions/{id}/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Run> submit(@PathVariable String id, @RequestBody SubmitRun request) {
        var result = engine.submit(id, request.requestId(), request.prompt(),
                request.scenario() == null ? "NORMAL" : request.scenario(), new ModelSelection(request.modelProfileId(), request.reasoningEffort()));
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.run());
    }

    @PostMapping(value = "/runs/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Run cancel(@PathVariable String id) {
        return engine.cancel(id);
    }
}
