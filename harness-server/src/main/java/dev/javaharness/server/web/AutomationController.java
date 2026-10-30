package dev.javaharness.server.web;

import dev.javaharness.adapters.automation.AutomationStore.Automation;
import dev.javaharness.adapters.automation.AutomationStore.Execution;
import dev.javaharness.server.service.AutomationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 定时自动化接口：增删改、立即运行与触发历史。 */
@RestController
@RequestMapping("/api/automations")
public class AutomationController {
    private final AutomationService automations;

    public AutomationController(AutomationService automations) {
        this.automations = automations;
    }

    public record Delete(long revision) {}

    @GetMapping
    public List<Automation> list() {
        return automations.list();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Automation create(@RequestBody AutomationService.Draft request) {
        return automations.save("", request);
    }

    @PostMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Automation save(@PathVariable String id, @RequestBody AutomationService.Draft request) {
        return automations.save(id, request);
    }

    /** Delete the automation and its history; sessions it created are kept. */
    @PostMapping(value = "/{id}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> delete(@PathVariable String id, @RequestBody Delete request) {
        automations.delete(id, request.revision());
        return Map.of("deleted", true);
    }

    @PostMapping(value = "/{id}/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Execution run(@PathVariable String id) {
        return automations.runNow(id);
    }

    @GetMapping("/{id}/runs")
    public List<Execution> runs(@PathVariable String id) {
        return automations.executions(id);
    }
}
