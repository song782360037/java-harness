package dev.javaharness.server;

import dev.javaharness.spi.ModelInfo;
import dev.javaharness.spi.ModelProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelController {
    private final ModelProvider model;
    public ModelController(ModelProvider model) { this.model = model; }
    @GetMapping("/api/model")
    public ModelInfo info() { return model.info(); }
}
