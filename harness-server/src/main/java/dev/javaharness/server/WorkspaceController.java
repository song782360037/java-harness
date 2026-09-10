package dev.javaharness.server;

import dev.javaharness.spi.WorkspaceTools;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {
    private final WorkspaceTools tools;
    public WorkspaceController(WorkspaceTools tools) { this.tools = tools; }
    public record Info(boolean configured, String root, List<String> tools) {}
    @GetMapping
    public Info info() {
        return new Info(!tools.workspace().isEmpty(), tools.workspace(),
                tools.definitions().stream().map(tool -> tool.name()).toList());
    }
}
