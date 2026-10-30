package dev.javaharness.server.web;

import dev.javaharness.adapters.settings.ProviderSettings;
import dev.javaharness.server.service.SettingsService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 模型提供商的查询、新建、修改与删除。API Key 只写不读，接口从不返回。 */
@RestController
@RequestMapping("/api/model-providers")
public class ProviderController {
    private final SettingsService settings;

    public ProviderController(SettingsService settings) {
        this.settings = settings;
    }

    public record View(
            String id,
            long revision,
            String displayName,
            String baseUrl,
            boolean apiKeyConfigured,
            boolean apiKeyRequired,
            int connectTimeoutSeconds) {}

    public record Save(
            long revision,
            String displayName,
            String baseUrl,
            String apiKey,
            boolean clearApiKey,
            boolean apiKeyRequired,
            int connectTimeoutSeconds) {
        ProviderSettings value(String id) {
            return new ProviderSettings(
                    id, revision, displayName, baseUrl, apiKey, apiKeyRequired, connectTimeoutSeconds);
        }

        @Override
        public String toString() {
            return "SaveProvider[credentials=redacted]";
        }
    }

    public record Delete(long revision) {}

    private static View view(ProviderSettings p) {
        return new View(
                p.id(),
                p.revision(),
                p.displayName(),
                p.baseUrl(),
                !p.apiKey().isEmpty(),
                p.apiKeyRequired(),
                p.connectTimeoutSeconds());
    }

    @GetMapping
    public List<View> list() {
        return settings.providers().stream().map(ProviderController::view).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public View create(@RequestBody Save request) {
        return view(settings.saveProvider(request.value(null), request.clearApiKey()));
    }

    @PostMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public View save(@PathVariable String id, @RequestBody Save request) {
        return view(settings.saveProvider(request.value(id), request.clearApiKey()));
    }

    @PostMapping(value = "/{id}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> delete(@PathVariable String id, @RequestBody Delete request) {
        settings.deleteProvider(id, request.revision());
        return Map.of("deleted", true);
    }
}
