package dev.javaharness.server.web;

import dev.javaharness.adapters.flow.FlowStore.Item;
import dev.javaharness.server.service.FlowOrchestrator;
import dev.javaharness.server.service.FlowService;
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

/** 流水线接口：生成与保存流程、触发事项、处理等待中的事项。客户端按列表中的 version 判断是否需要重新读取快照。 */
@RestController
@RequestMapping("/api")
public class FlowController {
    private final FlowService flows;
    private final FlowOrchestrator orchestrator;

    public FlowController(FlowService flows, FlowOrchestrator orchestrator) {
        this.flows = flows;
        this.orchestrator = orchestrator;
    }

    public record StartItem(String title, String input) {}

    public record Decision(String label, String note) {}

    @GetMapping("/flows")
    public List<FlowService.Summary> list() {
        return flows.list();
    }

    /** Design a flow with the model; nothing is saved until the client posts the proposal back. */
    @PostMapping(value = "/flows/design", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FlowService.Proposal design(@RequestBody FlowService.Design request) {
        return flows.design(request);
    }

    @PostMapping(value = "/flows", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FlowService.Snapshot create(@RequestBody FlowService.Draft request) {
        return flows.create(request);
    }

    @GetMapping("/flows/{id}")
    public FlowService.Snapshot snapshot(@PathVariable String id) {
        return flows.snapshot(id);
    }

    @PostMapping(value = "/flows/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FlowService.Snapshot update(@PathVariable String id, @RequestBody FlowService.Draft request) {
        return flows.update(id, request);
    }

    @PostMapping(value = "/flows/{id}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> delete(@PathVariable String id) {
        orchestrator.delete(id);
        return Map.of("deleted", true);
    }

    @PostMapping(value = "/flows/{id}/items", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Item start(@PathVariable String id, @RequestBody StartItem request) {
        return orchestrator.start(id, request.title(), request.input());
    }

    /** Items of all flows waiting for the user. */
    @GetMapping("/flow-items/waiting")
    public List<FlowService.ItemView> inbox() {
        return flows.inbox();
    }

    @GetMapping("/flow-items/{id}")
    public FlowService.ItemDetail item(@PathVariable String id) {
        return flows.item(id);
    }

    @PostMapping(value = "/flow-items/{id}/decide", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FlowService.ItemDetail decide(@PathVariable String id, @RequestBody Decision request) {
        orchestrator.decide(id, request.label(), request.note());
        return flows.item(id);
    }

    @PostMapping(value = "/flow-items/{id}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FlowService.ItemDetail retry(@PathVariable String id) {
        orchestrator.retry(id);
        return flows.item(id);
    }

    @PostMapping(value = "/flow-items/{id}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FlowService.ItemDetail stop(@PathVariable String id) {
        orchestrator.stop(id);
        return flows.item(id);
    }

    @PostMapping(value = "/flow-items/{id}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> deleteItem(@PathVariable String id) {
        orchestrator.deleteItem(id);
        return Map.of("deleted", true);
    }
}
