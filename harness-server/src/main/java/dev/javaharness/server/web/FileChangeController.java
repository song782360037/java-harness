package dev.javaharness.server.web;

import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.spi.workspace.FileChange;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api")
public class FileChangeController {
    private final FileChangeService changes;
    public FileChangeController(FileChangeService changes) { this.changes = changes; }
    @GetMapping("/runs/{id}/file-changes")
    public List<FileChange> list(@PathVariable String id) { return changes.list(id); }
    @PostMapping(value = "/file-changes/{id}/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileChange apply(@PathVariable String id) { return changes.apply(id); }
    @PostMapping(value = "/file-changes/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileChange reject(@PathVariable String id) { return changes.reject(id); }
}
