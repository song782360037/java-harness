package dev.javaharness.server;

import dev.javaharness.spi.SessionStore;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class WorkspaceController {
    private final SessionStore store;
    private final WorkspaceDirectories directories;
    public WorkspaceController(SessionStore store, WorkspaceDirectories directories) { this.store = store; this.directories = directories; }
    public record Info(boolean configured, String root, List<String> tools) {}
    @GetMapping("/api/workspace")
    public Info info(@RequestParam(required = false) String sessionId) {
        String root = sessionId == null ? "" : store.snapshot(sessionId).session().workspace();
        return new Info(!root.isEmpty(), root, root.isEmpty() ? List.of() : List.of("list_directory", "read_file", "search_files", "propose_file_change", "propose_command"));
    }
    @GetMapping("/api/workspaces/directories")
    public WorkspaceDirectories.Listing directories(@RequestParam(required = false) String path) { return directories.list(path); }
}
