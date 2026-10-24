package dev.javaharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javaharness.adapters.plugins.*;
import dev.javaharness.spi.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

/** Local plugin management with global activation and installation-time grants. */
@RestController
@RequestMapping("/api")
public class PluginController {
    private final PluginService plugins;
    private final AgentEngine engine;
    public PluginController(PluginService plugins, AgentEngine engine) { this.plugins = plugins; this.engine = engine; }
    public record ImportDirectory(String path, String source) {}
    public record Trust(long revision, String reviewDigest) {}
    public record Revision(long revision) {}
    public record Removal(boolean revoke, boolean uninstall) {}
    public record Decision(boolean approved) {}

    @GetMapping("/plugins") public List<PluginService.Installation> list() { return plugins.installations(); }
    @GetMapping("/plugins/{id}") public PluginService.Detail detail(@PathVariable String id) { return plugins.detail(id); }
    @PostMapping(value = "/plugins/import-directory", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PluginService.Installation directory(@RequestBody ImportDirectory request) { return plugins.installDirectory(request.path(), request.source()); }
    @PostMapping(value = "/plugins/import-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PluginService.Installation zip(@RequestParam MultipartFile file, @RequestParam(defaultValue = "local") String source) {
        if (file.isEmpty() || file.getSize() > 16 * 1024 * 1024) throw PluginJson.invalid("ZIP 大小需为 1 字节至 16 MiB");
        try { return plugins.installZip(file.getInputStream(), source); }
        catch (java.io.IOException error) { throw PluginJson.invalid("无法读取上传文件"); }
    }
    @GetMapping("/plugin-bindings") public List<PluginService.BindingView> bindings(@RequestParam String scope) { return plugins.bindings(scope); }
    @PostMapping(value = "/plugin-bindings/{scope}/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PluginService.BindingView save(@PathVariable String scope, @PathVariable String name, @RequestBody PluginService.SaveBinding request) {
        if (!scope.equals("user")) throw PluginJson.invalid("扩展现已统一为全局管理，请刷新页面");
        return plugins.save(scope, name, new PluginService.SaveBinding(request.installationId(), request.enabled(), request.config(), request.secrets(), request.clearSecrets(), List.of("*"), request.revision()));
    }
    @PostMapping(value = "/plugin-bindings/{scope}/{name}/trust", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PluginService.BindingView trust(@PathVariable String scope, @PathVariable String name, @RequestBody Trust request) { return plugins.trust(scope, name, request.revision(), request.reviewDigest()); }
    @PostMapping(value = "/plugin-bindings/{scope}/{name}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable String scope, @PathVariable String name, @RequestBody Revision request) { plugins.unbind(scope, name, request.revision()); }
    @PostMapping(value = "/plugin-bindings/{scope}/{name}/diagnose", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> diagnose(@PathVariable String scope, @PathVariable String name) {
        try { return plugins.diagnose(scope, name); }
        catch (HarnessException error) { throw error; }
        catch (Exception error) { throw PluginJson.invalid("MCP 连接诊断失败或超时，请检查程序、协议和服务授权"); }
    }
    @PostMapping(value = "/plugins/{id}/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> remove(@PathVariable String id, @RequestBody Removal request) {
        var runs = plugins.remove(id, request.revoke(), request.uninstall());
        for (String run : runs) engine.cancel(run);
        return Map.of("cancelledRuns", runs);
    }
    @GetMapping("/sessions/{id}/plugin-commands") public List<PluginService.Command> commands(@PathVariable String id) { return plugins.commands(id); }
    @GetMapping("/runs/{id}/plugins") public JsonNode snapshot(@PathVariable String id) { return plugins.snapshot(id); }
    @GetMapping("/runs/{id}/external-approvals") public List<ExternalToolApprovals.Approval> approvals(@PathVariable String id) { return plugins.approvals().list(id); }
    @PostMapping(value = "/external-approvals/{id}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExternalToolApprovals.Approval decide(@PathVariable String id, @RequestBody Decision request) { return plugins.approvals().decide(id, request.approved()); }
}
