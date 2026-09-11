package dev.javaharness.server;

import dev.javaharness.spi.ModelInfo;
import dev.javaharness.spi.RunConfigurationSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelController {
    private final RunConfigurationSource configurations;
    public ModelController(RunConfigurationSource configurations) { this.configurations = configurations; }
    @GetMapping("/api/model")
    public ModelInfo info() { return configurations.snapshot().modelInfo(); }
}
