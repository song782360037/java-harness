package dev.javaharness.server.web;

import dev.javaharness.server.service.SessionNamingService;
import dev.javaharness.server.service.WorkspaceDirectories;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStep;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionSnapshot;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolExecution;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话与运行接口：新建、重命名、绑定工作区、删除会话，提交与取消运行，
 * 以及查看运行的模型请求、工具调用与执行步骤。
 */
@RestController
@RequestMapping("/api")
public class SessionController {
    private final SessionStore store;
    private final AgentEngine engine;
    private final WorkspaceDirectories directories;
    private final SessionNamingService naming;

    public SessionController(
            SessionStore store, AgentEngine engine, WorkspaceDirectories directories, SessionNamingService naming) {
        this.store = store;
        this.engine = engine;
        this.directories = directories;
        this.naming = naming;
    }

    public record CreateSession(String title, String workspace, String workspaceId) {}

    public record ChangeWorkspace(String workspace, String workspaceId) {}

    public record RenameSession(String title) {}

    public record SubmitRun(
            String requestId, String prompt, String scenario, String modelProfileId, String reasoningEffort) {}

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

    @GetMapping("/runs/{id}/steps")
    public List<RunStep> steps(@PathVariable String id) {
        return store.steps(id);
    }

    /** Delete the session and its history; active runs must be stopped first. */
    @PostMapping("/sessions/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.deleteSession(id);
    }

    @PostMapping(value = "/sessions/{id}/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Run> submit(@PathVariable String id, @RequestBody SubmitRun request) {
        var selection = new ModelSelection(request.modelProfileId(), request.reasoningEffort());
        var result = engine.submit(
                id,
                request.requestId(),
                request.prompt(),
                request.scenario() == null ? "NORMAL" : request.scenario(),
                selection);
        // 首个任务创建成功即触发 AI 命名，与任务执行并行；占位标题用于识别用户是否已手动改名。
        if (result.created()) {
            var snapshot = store.snapshot(id);
            if (snapshot.runs().size() == 1)
                naming.nameFromFirstMessage(
                        id, result.run().prompt(), selection, snapshot.session().title());
        }
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(result.run());
    }

    @PostMapping(value = "/runs/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Run cancel(@PathVariable String id) {
        return engine.cancel(id);
    }
}
