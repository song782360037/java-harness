package dev.javaharness.server.config;

import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.adapters.plugins.PluginService;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.settings.HarnessSettings;
import dev.javaharness.adapters.settings.JdbcSettingsStore;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.server.service.SessionNamingService;
import dev.javaharness.server.service.SettingsService;
import dev.javaharness.server.web.EventController;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.session.SessionStore;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运行时 Spring 装配：JDBC 存储、设置、插件、审核服务与 Agent 引擎。
 * 容器关闭时会停止进行中的运行和事件流。
 */
@Configuration
public class RuntimeConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    SessionStore sessionStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcSessionStore(jdbc, new TransactionTemplate(transactions));
    }

    @Bean
    @DependsOnDatabaseInitialization
    FileChangeService fileChangeService(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, SessionStore sessions) {
        return new FileChangeService(jdbc, new TransactionTemplate(transactions), sessions);
    }

    @Bean
    @DependsOnDatabaseInitialization
    SettingsService settingsService(
            JdbcTemplate jdbc,
            FileChangeService changes,
            CommandService commands,
            PluginService plugins,
            @Value("${harness.settings.key-file}") String keyFile,
            @Value("${harness.model.base-url}") String baseUrl,
            @Value("${harness.model.api-key}") String apiKey,
            @Value("${harness.model.name}") String modelName,
            @Value("${harness.model.api-key-required}") boolean apiKeyRequired,
            @Value("${harness.model.connect-timeout}") Duration connectTimeout,
            @Value("${harness.run-timeout}") Duration runTimeout,
            @Value("${harness.context.window-tokens}") int contextWindowTokens,
            @Value("${harness.context.max-output-tokens}") int maxOutputTokens,
            @Value("${harness.tools.max-tools-per-request}") int maxToolsPerRequest,
            @Value("${harness.tools.timeout}") Duration toolTimeout) {
        var storage = new JdbcSettingsStore(jdbc, new LocalSecretCipher(Path.of(keyFile)));
        return new SettingsService(
                storage,
                new HarnessSettings(
                        1,
                        baseUrl,
                        apiKey,
                        modelName,
                        apiKeyRequired,
                        (int) connectTimeout.toSeconds(),
                        (int) runTimeout.toSeconds(),
                        0,
                        0,
                        (int) toolTimeout.toSeconds(),
                        maxToolsPerRequest,
                        contextWindowTokens,
                        maxOutputTokens),
                changes,
                commands,
                plugins);
    }

    @Bean
    @DependsOnDatabaseInitialization
    PluginService pluginService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            @Value("${harness.plugins.directory:./data/plugins}") String directory,
            @Value("${harness.settings.key-file}") String keyFile) {
        return new PluginService(
                jdbc,
                new TransactionTemplate(transactions),
                sessions,
                new LocalSecretCipher(Path.of(keyFile)),
                Path.of(directory));
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

    @Bean(destroyMethod = "close")
    SessionNamingService sessionNamingService(
            SessionStore store,
            RunConfigurationSource configurations,
            @Value("${harness.session-naming.enabled:true}") boolean enabled) {
        return new SessionNamingService(store, configurations, enabled);
    }

    @Bean
    ApplicationListener<ContextClosedEvent> stopRuntime(AgentEngine engine, EventController events) {
        return event -> {
            events.close();
            engine.close();
        };
    }
}
