package dev.javaharness.server.web;

import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.spi.workspace.FileChange;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lists a run's proposed file changes and applies or rejects them. */
@RestController
@RequestMapping("/api")
public class FileChangeController {
    private final FileChangeService changes;

    public FileChangeController(FileChangeService changes) {
        this.changes = changes;
    }

    @GetMapping("/runs/{id}/file-changes")
    public List<FileChange> list(@PathVariable String id) {
        return changes.list(id);
    }

    @PostMapping(value = "/file-changes/{id}/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileChange apply(@PathVariable String id) {
        return changes.apply(id);
    }

    @PostMapping(value = "/file-changes/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileChange reject(@PathVariable String id) {
        return changes.reject(id);
    }
}
