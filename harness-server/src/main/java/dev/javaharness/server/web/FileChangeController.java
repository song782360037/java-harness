package dev.javaharness.server.web;

import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.adapters.workspace.RunArtifacts;
import dev.javaharness.spi.workspace.FileChange;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 查询运行中提议的文件修改，并应用或拒绝；以及已写入文件的产物查看与打开。 */
@RestController
@RequestMapping("/api")
public class FileChangeController {
    private final FileChangeService changes;
    private final RunArtifacts artifacts;

    public FileChangeController(FileChangeService changes, RunArtifacts artifacts) {
        this.changes = changes;
        this.artifacts = artifacts;
    }

    public record OpenRequest(String action) {}

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

    /** Undo an applied edit while the session is idle; a file changed since then is never overwritten. */
    @PostMapping(value = "/file-changes/{id}/revert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileChange revert(@PathVariable String id) {
        return changes.revert(id);
    }

    /** 运行中已写入工作区的文件及其当前磁盘状态。 */
    @GetMapping("/runs/{id}/artifacts")
    public List<RunArtifacts.Artifact> artifacts(@PathVariable String id) {
        return artifacts.list(id);
    }

    @GetMapping("/file-changes/{id}/content")
    public RunArtifacts.Content content(@PathVariable String id) {
        return artifacts.content(id);
    }

    /** 用系统默认程序打开文件，或在文件管理器中定位。 */
    @PostMapping(value = "/file-changes/{id}/open", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(@PathVariable String id, @RequestBody OpenRequest request) {
        artifacts.open(id, request.action());
    }
}
