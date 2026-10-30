package dev.javaharness.server.web;

import dev.javaharness.adapters.settings.ModelParameters;
import dev.javaharness.adapters.settings.ModelProfile;
import dev.javaharness.server.service.SettingsService;
import dev.javaharness.spi.model.ModelInfo;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-profiles")
public class ModelProfileController {
    private final SettingsService settings;

    public ModelProfileController(SettingsService settings) {
        this.settings = settings;
    }

    public record View(
            String id,
            String displayName,
            String reasoningEffort,
            long revision,
            String providerId,
            String providerName,
            String modelName,
            int runTimeoutSeconds,
            int contextWindowTokens,
            int maxOutputTokens,
            int toolTimeoutSeconds,
            int maxToolsPerRequest,
            ModelInfo model) {}

    public record Save(
            String displayName,
            String reasoningEffort,
            long revision,
            String providerId,
            String modelName,
            int runTimeoutSeconds,
            int contextWindowTokens,
            int maxOutputTokens,
            int toolTimeoutSeconds,
            int maxToolsPerRequest) {
        ModelParameters value() {
            return new ModelParameters(
                    modelName,
                    runTimeoutSeconds,
                    0,
                    0,
                    toolTimeoutSeconds,
                    maxToolsPerRequest,
                    contextWindowTokens,
                    maxOutputTokens);
        }
    }

    public record Delete(long revision) {}

    private View view(ModelProfile profile) {
        var value = profile.settings();
        return new View(
                profile.id(),
                profile.displayName(),
                profile.reasoningEffort(),
                value.revision(),
                profile.providerId(),
                profile.providerName(),
                value.modelName(),
                value.runTimeoutSeconds(),
                value.contextWindowTokens(),
                value.maxOutputTokens(),
                value.toolTimeoutSeconds(),
                value.maxToolsPerRequest(),
                SettingsService.info(value));
    }

    @GetMapping
    public List<View> list() {
        return settings.profiles().stream().map(this::view).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public View create(@RequestBody Save request) {
        return view(settings.saveModel(
                null,
                request.providerId(),
                request.displayName(),
                request.reasoningEffort(),
                request.value(),
                request.revision()));
    }

    @PostMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public View save(@PathVariable String id, @RequestBody Save request) {
        return view(settings.saveModel(
                id,
                request.providerId(),
                request.displayName(),
                request.reasoningEffort(),
                request.value(),
                request.revision()));
    }

    @PostMapping(value = "/{id}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public java.util.Map<String, Boolean> delete(@PathVariable String id, @RequestBody Delete request) {
        settings.deleteProfile(id, request.revision());
        return java.util.Map.of("deleted", true);
    }
}
