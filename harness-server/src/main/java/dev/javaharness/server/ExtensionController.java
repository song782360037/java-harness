package dev.javaharness.server;

import dev.javaharness.adapters.plugins.PluginService;
import dev.javaharness.spi.SessionStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Local extension discovery, global activation and custom authoring. */
@RestController
@RequestMapping("/api/extensions")
public class ExtensionController {
    private final ExtensionCatalogService catalog;
    private final PluginService plugins;
    public ExtensionController(PluginService plugins, SessionStore sessions) { this.plugins = plugins; catalog = new ExtensionCatalogService(plugins, sessions); }
    public record Toggle(boolean enabled, long revision) {}
    public record Edit(long revision, com.fasterxml.jackson.databind.JsonNode form) {}
    @PostMapping("/installed/{id}/enabled")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void toggle(@PathVariable String id, @RequestBody Toggle request) { plugins.setEnabled(id, request.enabled(), request.revision()); }
    @GetMapping("/installed/{id}/edit") public PluginService.Authoring edit(@PathVariable String id) { return plugins.authoring(id); }
    @PostMapping("/installed/{id}/edit") public PluginService.Installation update(@PathVariable String id, @RequestBody Edit request) { return catalog.update(id, request.revision(), request.form()); }
    @GetMapping public List<ExtensionCatalogService.Item> list() { return catalog.items(); }
    @GetMapping("/items/{id}") public ExtensionCatalogService.Preview preview(@PathVariable String id) { return catalog.preview(id); }
    @PostMapping(value = "/catalog/{id}/install", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PluginService.Installation install(@PathVariable String id) { return catalog.install(id); }
    @PostMapping(value = "/skills", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PluginService.Installation skill(@RequestBody ExtensionCatalogService.Skill request) { return catalog.createSkill(request); }
    @PostMapping(value = "/connectors", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PluginService.Installation connector(@RequestBody ExtensionCatalogService.Connector request) { return catalog.createConnector(request); }
}
