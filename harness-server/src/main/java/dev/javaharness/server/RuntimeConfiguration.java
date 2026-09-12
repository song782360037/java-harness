package dev.javaharness.server;

import dev.javaharness.adapters.*;
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
import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class RuntimeConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    SessionStore sessionStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcSessionStore(jdbc, new TransactionTemplate(transactions));
    }

    @Bean
    @DependsOnDatabaseInitialization
    FileChangeService fileChangeService(JdbcTemplate jdbc, PlatformTransactionManager transactions, SessionStore sessions) {
        return new FileChangeService(jdbc, new TransactionTemplate(transactions), sessions);
    }

    @Bean
    @DependsOnDatabaseInitialization
    SettingsService settingsService(JdbcTemplate jdbc, FileChangeService changes, CommandService commands,
            @Value("${harness.settings.key-file}") String keyFile,
            @Value("${harness.model.base-url}") String baseUrl,
            @Value("${harness.model.api-key}") String apiKey,
            @Value("${harness.model.name}") String modelName,
            @Value("${harness.model.api-key-required}") boolean apiKeyRequired,
            @Value("${harness.model.connect-timeout}") Duration connectTimeout,
            @Value("${harness.run-timeout}") Duration runTimeout,
            @Value("${harness.context.max-characters}") int maxCharacters,
            @Value("${harness.tools.max-steps}") int maxSteps,
            @Value("${harness.tools.timeout}") Duration toolTimeout) {
        var storage = new JdbcSettingsStore(jdbc, new LocalSecretCipher(Path.of(keyFile)));
        return new SettingsService(storage, new HarnessSettings(1, baseUrl, apiKey, modelName, apiKeyRequired,
                (int) connectTimeout.toSeconds(), (int) runTimeout.toSeconds(), maxCharacters, maxSteps, (int) toolTimeout.toSeconds()), changes, commands);
    }

    @Bean
    @DependsOnDatabaseInitialization
    CommandService commandService(JdbcTemplate jdbc, PlatformTransactionManager transactions, SessionStore sessions) {
        return new CommandService(jdbc, new TransactionTemplate(transactions), sessions);
    }

    @Bean(destroyMethod = "close")
    AgentEngine agentEngine(SessionStore store, RunConfigurationSource configurations) {
        return new DefaultAgentEngine(store, configurations);
    }

    @Bean
    ApplicationListener<ContextClosedEvent> stopRuntime(AgentEngine engine, EventController events) {
        return event -> { events.close(); engine.close(); };
    }
}
