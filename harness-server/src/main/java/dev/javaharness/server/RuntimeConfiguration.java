package dev.javaharness.server;

import dev.javaharness.adapters.JdbcSessionStore;
import dev.javaharness.adapters.SimulatedModelProvider;
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

@Configuration
public class RuntimeConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    SessionStore sessionStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcSessionStore(jdbc, new TransactionTemplate(transactions));
    }

    @Bean
    ModelProvider modelProvider(@Value("${harness.simulated-chunk-delay}") long delay) {
        if (delay < 0 || delay > 1000) throw new IllegalArgumentException("Invalid simulated chunk delay");
        return new SimulatedModelProvider(delay);
    }

    @Bean(destroyMethod = "close")
    AgentEngine agentEngine(SessionStore store, ModelProvider model,
                            @Value("${harness.run-timeout}") Duration timeout) {
        return new DefaultAgentEngine(store, model, timeout);
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
