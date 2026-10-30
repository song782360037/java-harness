package dev.javaharness.server.web;

import dev.javaharness.adapters.settings.HarnessSettings;
import dev.javaharness.server.service.SettingsService;
import dev.javaharness.spi.model.ModelInfo;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsService settings;
    public SettingsController(SettingsService settings) { this.settings = settings; }
    public record View(long revision, String baseUrl, String modelName, boolean apiKeyConfigured, boolean apiKeyRequired,
                       int connectTimeoutSeconds, int runTimeoutSeconds, int contextWindowTokens, int maxOutputTokens,
                       int toolTimeoutSeconds, int maxToolsPerRequest, ModelInfo model) {}
    public record Save(long revision, String baseUrl, String modelName, String apiKey, boolean clearApiKey, boolean apiKeyRequired,
                       int connectTimeoutSeconds, int runTimeoutSeconds, int contextWindowTokens, int maxOutputTokens,
                       int toolTimeoutSeconds, int maxToolsPerRequest) {
        @Override public String toString() { return "SaveSettings[revision=" + revision + ", credentials=redacted]"; }
    }
    static View view(HarnessSettings value) {
        return new View(value.revision(), value.baseUrl(), value.modelName(), !value.apiKey().isEmpty(), value.apiKeyRequired(),
                value.connectTimeoutSeconds(), value.runTimeoutSeconds(), value.contextWindowTokens(), value.maxOutputTokens(),
                value.toolTimeoutSeconds(), value.maxToolsPerRequest(), SettingsService.info(value));
    }
    @GetMapping public View get() { return view(settings.current()); }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public View save(@RequestBody Save request) {
        return view(settings.save(new HarnessSettings(request.revision(), request.baseUrl(), request.apiKey(), request.modelName(),
                request.apiKeyRequired(), request.connectTimeoutSeconds(), request.runTimeoutSeconds(), 0,
                0, request.toolTimeoutSeconds(), request.maxToolsPerRequest(), request.contextWindowTokens(), request.maxOutputTokens()),
                request.clearApiKey()));
    }
}
