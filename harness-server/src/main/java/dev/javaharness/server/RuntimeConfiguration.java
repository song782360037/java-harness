package dev.javaharness.server;

import dev.javaharness.adapters.JdbcSessionStore;
import dev.javaharness.adapters.OpenAiChatCompletionProvider;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.spi.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.net.URI;

@Configuration
public class RuntimeConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    SessionStore sessionStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcSessionStore(jdbc, new TransactionTemplate(transactions));
    }

    @Bean
    ModelProvider modelProvider(@Value("${harness.model.base-url}") URI baseUrl,
            @Value("${harness.model.api-key}") String apiKey,
            @Value("${harness.model.api-key-required}") boolean apiKeyRequired,
            @Value("${harness.model.name}") String model,
            @Value("${harness.model.connect-timeout}") Duration connectTimeout) {
        return new OpenAiChatCompletionProvider(baseUrl, apiKey, model, apiKeyRequired, connectTimeout);
    }

    @Bean(destroyMethod = "close")
    AgentEngine agentEngine(SessionStore store, ModelProvider model,
                            @Value("${harness.run-timeout}") Duration timeout,
                            @Value("${harness.context.max-characters}") int maxContextCharacters) {
        return new DefaultAgentEngine(store, model, timeout, maxContextCharacters);
    }

    @Bean
    ApplicationListener<ContextClosedEvent> stopRuntime(AgentEngine engine, EventController events) {
        return event -> {
            // Close long-lived SSE requests before Tomcat's graceful-shutdown phase begins.
            events.close();
            engine.close();
        };
    }
}
