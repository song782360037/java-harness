package dev.javaharness.server.web;

import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.run.RunConfigurationSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 返回默认模型的公开摘要，不包含任何凭据。 */
@RestController
public class ModelController {
    private final RunConfigurationSource configurations;

    public ModelController(RunConfigurationSource configurations) {
        this.configurations = configurations;
    }

    @GetMapping("/api/model")
    public ModelInfo info() {
        return configurations.snapshot().modelInfo();
    }
}
