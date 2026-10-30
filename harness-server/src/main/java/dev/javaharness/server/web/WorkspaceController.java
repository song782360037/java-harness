package dev.javaharness.server.web;

import dev.javaharness.server.service.WorkspaceDirectories;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.Workspace;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reusable workspaces, local directory browsing and the built-in tool names available to a session's workspace. */
@RestController
public class WorkspaceController {
    private final SessionStore store;
    private final WorkspaceDirectories directories;

    public WorkspaceController(SessionStore store, WorkspaceDirectories directories) {
        this.store = store;
        this.directories = directories;
    }

    public record Info(boolean configured, String root, List<String> tools) {}

    @GetMapping("/api/workspace")
    public Info info(@RequestParam(required = false) String sessionId) {
        String root =
                sessionId == null ? "" : store.snapshot(sessionId).session().workspace();
        return new Info(
                !root.isEmpty(),
                root,
                root.isEmpty()
                        ? List.of()
                        : List.of(
                                "list_directory",
                                "read_file",
                                "search_files",
                                "git_status",
                                "git_diff",
                                "propose_file_change",
                                "propose_command"));
    }

    @GetMapping("/api/workspaces/directories")
    public WorkspaceDirectories.Listing directories(@RequestParam(required = false) String path) {
        return directories.list(path);
    }

    public record SaveWorkspace(String name, List<String> directories, String icon, String color, long revision) {}

    @GetMapping("/api/workspaces")
    public List<Workspace> workspaces() {
        return store.workspaces();
    }
    /** Create a reusable workspace with normalized, user-selected directories. */
    @PostMapping("/api/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    public Workspace create(@RequestBody SaveWorkspace request) {
        return save("", request);
    }

    @PostMapping("/api/workspaces/{id}")
    public Workspace update(@PathVariable String id, @RequestBody SaveWorkspace request) {
        return save(id, request);
    }
    /** Validate all directory boundaries before persisting the workspace. */
    private Workspace save(String id, SaveWorkspace request) {
        if (request.directories() == null
                || request.directories().isEmpty()
                || request.directories().size() > 16)
            throw new dev.javaharness.spi.HarnessException("INVALID", "请选择 1–16 个目录");
        return store.saveWorkspace(
                id,
                request.name(),
                request.directories().stream().map(directories::normalize).toList(),
                request.icon(),
                request.color(),
                request.revision());
    }
}
