package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.model.OpenAiChatCompletionProvider;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.session.SessionStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:real-model-config;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.model.api-key-required=true",
            // Retired configuration must never enable a simulated runtime again.
            "harness.model.provider=simulated"
        })
class RealModelConfigurationTest {
    @Autowired
    ApplicationContext context;

    @Autowired
    RunConfigurationSource configurations;

    @Autowired
    AgentEngine engine;

    @Autowired
    SessionStore store;

    @Test
    void realAdapterIsTheOnlyRuntimeProviderDespiteLegacySettings() throws Exception {
        assertThat(context.getBeansOfType(ModelProvider.class)).isEmpty();
        try (var resources = configurations.snapshot().resources().open("")) {
            assertThat(resources.model()).isInstanceOf(OpenAiChatCompletionProvider.class);
            assertThat(resources.model().info().provider()).isEqualTo("openai-compatible");
        }
    }

    @Test
    void missingConfigurationDisablesExecutionWithoutCreatingAFakeResponse() {
        var session = store.createSession("尚未配置模型");
        assertThat(configurations.snapshot().modelInfo().configured()).isFalse();
        assertThatThrownBy(() -> engine.submit(session.id(), UUID.randomUUID().toString(), "hello", "NORMAL"))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("模型配置");
        assertThat(store.snapshot(session.id()).runs()).isEmpty();
    }
}
