package dev.javaharness.server.web;

import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.spi.workspace.CommandExecution;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CommandController {
    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    @GetMapping("/runs/{id}/commands")
    public List<CommandExecution> list(@PathVariable String id) {
        return commands.list(id);
    }

    @PostMapping(value = "/commands/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommandExecution approve(@PathVariable String id) {
        return commands.approve(id);
    }

    @PostMapping(value = "/commands/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommandExecution reject(@PathVariable String id) {
        return commands.reject(id);
    }

    @PostMapping(value = "/commands/{id}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommandExecution stop(@PathVariable String id) {
        return commands.stop(id);
    }
}
