package dev.javaharness.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javaharness.server.service.ExternalAgentCatalog;
import dev.javaharness.server.service.ExternalAgentCatalog.Profile;
import dev.javaharness.server.service.ExternalAgentInteractions;
import dev.javaharness.server.service.ExternalAgentRuns;
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

/** Settings and user decisions for explicitly invoked external agents. */
@RestController
@RequestMapping("/api")
public class ExternalAgentController {
    public record Remove(long revision) {}

    public record Answer(String action, JsonNode value) {}

    private final ExternalAgentCatalog catalog;
    private final ExternalAgentRuns runs;
    private final ExternalAgentInteractions interactions;

    public ExternalAgentController(
            ExternalAgentCatalog catalog, ExternalAgentRuns runs, ExternalAgentInteractions interactions) {
        this.catalog = catalog;
        this.runs = runs;
        this.interactions = interactions;
    }

    @GetMapping("/external-agents")
    public List<Profile> list() {
        return catalog.list();
    }

    @GetMapping("/external-agents/commands")
    public List<ExternalAgentCatalog.Command> commands() {
        return catalog.commands();
    }

    @PostMapping(value = "/external-agents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Profile save(@RequestBody Profile profile) {
        return catalog.save(profile);
    }

    @PostMapping(value = "/external-agents/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExternalAgentRuns.Probe test(@RequestBody Profile profile) {
        return runs.probe(profile);
    }

    @PostMapping(value = "/external-agents/{id}/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id, @RequestBody Remove request) {
        catalog.remove(id, request.revision());
    }

    @GetMapping("/runs/{id}/external-interactions")
    public List<ExternalAgentInteractions.Interaction> interactions(@PathVariable String id) {
        return interactions.list(id);
    }

    @PostMapping(
            value = "/runs/{id}/external-interactions/{interactionId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExternalAgentInteractions.Interaction answer(
            @PathVariable String id, @PathVariable String interactionId, @RequestBody Answer request) {
        return interactions.answer(id, interactionId, request.action(), request.value());
    }
}
