package dev.javaharness.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.approval.ApprovalPolicy;
import dev.javaharness.adapters.approval.ApprovalReviewer;
import dev.javaharness.adapters.attachment.AttachmentStore;
import dev.javaharness.adapters.automation.AutomationStore;
import dev.javaharness.adapters.bot.BotLanStore;
import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.adapters.flow.FlowStore;
import dev.javaharness.adapters.memory.JdbcMemoryStore;
import dev.javaharness.adapters.plugins.PluginService;
import dev.javaharness.adapters.question.QuestionService;
import dev.javaharness.adapters.redaction.SecretRedactor;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.settings.HarnessSettings;
import dev.javaharness.adapters.settings.JdbcSettingsStore;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.adapters.usage.LocalAgentUsageReader;
import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.adapters.workspace.RunArtifacts;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.server.service.AutomationService;
import dev.javaharness.server.service.BotDebugService;
import dev.javaharness.server.service.BotLanServer;
import dev.javaharness.server.service.BotMiniAppService;
import dev.javaharness.server.service.BotOrchestrator;
import dev.javaharness.server.service.BotRoutineScheduler;
import dev.javaharness.server.service.BotService;
import dev.javaharness.server.service.BotSkillService;
import dev.javaharness.server.service.ComposerInputService;
import dev.javaharness.server.service.DesktopUpdateGate;
import dev.javaharness.server.service.ExternalAgentCatalog;
import dev.javaharness.server.service.ExternalAgentRuns;
import dev.javaharness.server.service.FlowOrchestrator;
import dev.javaharness.server.service.FlowService;
import dev.javaharness.server.service.LlmApprovalJudge;
import dev.javaharness.server.service.MemoryService;
import dev.javaharness.server.service.PromptOptimizationService;
import dev.javaharness.server.service.SessionNamingService;
import dev.javaharness.server.service.SettingsService;
import dev.javaharness.server.service.UnattendedSubmissions;
import dev.javaharness.server.service.UsageStatisticsService;
import dev.javaharness.server.web.EventController;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.session.SessionStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
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
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfiguration.class);

    @Bean
    @DependsOnDatabaseInitialization
    SessionStore sessionStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcSessionStore(jdbc, new TransactionTemplate(transactions));
    }

    @Bean
    UsageStatisticsService usageStatisticsService(
            SessionStore sessions,
            @Value("${user.home}") String home,
            @Value("${harness.usage.cache-file:}") String cacheFile) {
        return new UsageStatisticsService(
                sessions,
                new LocalAgentUsageReader(Path.of(home), cacheFile.isBlank() ? null : Path.of(cacheFile)),
                ZoneId.systemDefault());
    }

    /** 启动后在后台预读本机 agent 日志，避免首次打开使用统计时等待全量解析。 */
    @Bean
    ApplicationListener<ApplicationReadyEvent> warmUpUsageStatistics(
            UsageStatisticsService usage, @Value("${harness.usage.warm-up:true}") boolean enabled) {
        return event -> {
            if (enabled) usage.warmUp();
        };
    }

    @Bean
    @DependsOnDatabaseInitialization
    FileChangeService fileChangeService(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, SessionStore sessions) {
        return new FileChangeService(jdbc, new TransactionTemplate(transactions), sessions);
    }

    @Bean
    RunArtifacts runArtifacts(FileChangeService changes, SessionStore sessions) {
        return new RunArtifacts(changes, sessions);
    }

    @Bean
    @DependsOnDatabaseInitialization
    SettingsService settingsService(
            JdbcTemplate jdbc,
            FileChangeService changes,
            CommandService commands,
            PluginService plugins,
            UnattendedSubmissions unattended,
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
                plugins,
                unattended);
    }

    @Bean
    UnattendedSubmissions unattendedSubmissions() {
        return new UnattendedSubmissions();
    }

    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    AutomationService automationService(
            JdbcTemplate jdbc,
            SessionStore sessions,
            AgentEngine engine,
            SettingsService settings,
            UnattendedSubmissions unattended,
            DesktopUpdateGate updates,
            FlowOrchestrator flowOrchestrator) {
        var service = new AutomationService(
                new AutomationStore(jdbc),
                sessions,
                engine,
                settings,
                unattended,
                Clock.systemUTC(),
                ZoneId.systemDefault());
        service.useUpdateGate(updates);
        service.useFlows(new FlowStore(jdbc), flowOrchestrator);
        return service;
    }

    /** Start the schedule only after startup succeeds, so a failed boot does not consume a catch-up run. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> startAutomations(
            AutomationService automations,
            @Value("${harness.automations.enabled:true}") boolean enabled,
            @Value("${harness.automations.check-interval:30s}") Duration interval) {
        return event -> {
            if (enabled) automations.start(interval);
        };
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

    /** Attended runs reach the question tool through the settings service's extension capture. */
    @Bean
    @DependsOnDatabaseInitialization
    QuestionService questionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            SettingsService settings) {
        var service = new QuestionService(jdbc, new TransactionTemplate(transactions), sessions);
        settings.useQuestions(service);
        return service;
    }

    /** The LLM reviewer resolves model profiles lazily: the settings service itself depends on the command service. */
    @Bean(destroyMethod = "close")
    LlmApprovalJudge llmApprovalJudge(ObjectProvider<RunConfigurationSource> configurations) {
        return new LlmApprovalJudge(configurations::getObject);
    }

    @Bean
    @DependsOnDatabaseInitialization
    ApprovalReviewer approvalReviewer(
            JdbcTemplate jdbc, LlmApprovalJudge llm, @Value("${harness.settings.key-file}") String keyFile) {
        return new ApprovalReviewer(jdbc, new LocalSecretCipher(Path.of(keyFile)), llm);
    }

    /** Attach the reviewer after all singletons exist; no run can start before the context is ready. */
    @Bean
    SmartInitializingSingleton approvalReviewWiring(
            CommandService commands, PluginService plugins, ApprovalReviewer reviewer) {
        return () -> {
            commands.useReviewer(reviewer);
            plugins.approvals().useReviewer(reviewer);
        };
    }

    @Bean
    @DependsOnDatabaseInitialization
    ApprovalPolicy approvalPolicy(JdbcTemplate jdbc) {
        return new ApprovalPolicy(jdbc);
    }

    @Bean
    @DependsOnDatabaseInitialization
    ComposerInputService composerInputService(JdbcTemplate jdbc, SessionStore sessions) {
        return new ComposerInputService(sessions, new AttachmentStore(jdbc));
    }

    @Bean(destroyMethod = "close")
    AgentEngine agentEngine(SessionStore store, RunConfigurationSource configurations, MemoryService memories) {
        return new DefaultAgentEngine(store, configurations, memories, java.util.List.of(new SecretRedactor()));
    }

    /** 复用本地数据库保存长期记忆，不额外启动外部服务。 */
    @Bean
    @DependsOnDatabaseInitialization
    JdbcMemoryStore memoryStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcMemoryStore(jdbc, new TransactionTemplate(transactions));
    }

    @Bean(destroyMethod = "close")
    MemoryService memoryService(
            JdbcMemoryStore memories, SessionStore sessions, RunConfigurationSource configurations, ObjectMapper json) {
        return new MemoryService(memories, sessions, configurations, json);
    }

    @Bean(destroyMethod = "close")
    PromptOptimizationService promptOptimizationService(
            SessionStore sessions,
            RunConfigurationSource configurations,
            ComposerInputService inputs,
            MemoryService memories,
            ObjectMapper json) {
        return new PromptOptimizationService(sessions, configurations, inputs, memories, json);
    }

    /** 自动回填在应用完成启动后才运行。 */
    @Bean
    ApplicationListener<ApplicationReadyEvent> startAutomaticMemory(MemoryService memories) {
        return event -> memories.start();
    }

    @Bean(destroyMethod = "close")
    SessionNamingService sessionNamingService(
            SessionStore store,
            RunConfigurationSource configurations,
            @Value("${harness.session-naming.enabled:true}") boolean enabled) {
        return new SessionNamingService(store, configurations, enabled);
    }

    /** Flow items; created after the engine, whose startup recovery interrupts old item runs. */
    @Bean
    @DependsOnDatabaseInitialization
    FlowOrchestrator flowOrchestrator(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            AgentEngine engine,
            SettingsService settings,
            ExternalAgentRuns externalAgents) {
        var orchestrator =
                new FlowOrchestrator(new FlowStore(jdbc), sessions, new TransactionTemplate(transactions), engine);
        orchestrator.useExternalAgents(externalAgents);
        settings.useFlows(orchestrator::decorate);
        return orchestrator;
    }

    @Bean
    @DependsOnDatabaseInitialization
    FlowService flowService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            SettingsService settings,
            RunConfigurationSource configurations,
            ExternalAgentCatalog externalAgents) {
        var service = new FlowService(
                new FlowStore(jdbc), sessions, new TransactionTemplate(transactions), settings, configurations);
        service.useExternalAgents(externalAgents);
        return service;
    }

    /** Bot 团队协调器：在引擎启动恢复之后创建，自动中断旧后台任务并确保内置主 Bot 存在。 */
    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    BotOrchestrator botOrchestrator(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            AgentEngine engine,
            SettingsService settings,
            ExternalAgentRuns externalAgents,
            MemoryService memories,
            CommandService commands,
            ComposerInputService composerInputs,
            @Value("${harness.settings.key-file}") String keyFile,
            @Value("${harness.bots.library-directory:./data/bot-library}") String libraryDirectory) {
        Path libRoot = Path.of(libraryDirectory);
        commands.useBotLibraryRoot(libRoot);
        var orchestrator = new BotOrchestrator(
                new BotStore(jdbc),
                sessions,
                new TransactionTemplate(transactions),
                engine,
                settings,
                libRoot);
        orchestrator.useExternalAgents(externalAgents);
        orchestrator.useMemories(memories);
        orchestrator.useCommands(commands);
        orchestrator.useComposerInputs(composerInputs);
        orchestrator.useSecretCipher(new LocalSecretCipher(Path.of(keyFile)));
        settings.useBots(orchestrator::decorate);
        return orchestrator;
    }

    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    BotRoutineScheduler botRoutineScheduler(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            BotOrchestrator orchestrator,
            DesktopUpdateGate updates) {
        var scheduler = new BotRoutineScheduler(
                new BotStore(jdbc),
                sessions,
                new TransactionTemplate(transactions),
                orchestrator,
                Clock.systemUTC(),
                ZoneId.systemDefault());
        scheduler.useUpdateGate(updates);
        orchestrator.useRoutines(scheduler);
        return scheduler;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> startBotRoutines(
            BotRoutineScheduler routines,
            @Value("${harness.automations.enabled:true}") boolean enabled,
            @Value("${harness.automations.check-interval:30s}") Duration interval) {
        return event -> {
            if (enabled) routines.start(interval);
        };
    }

    @Bean
    @DependsOnDatabaseInitialization
    BotMiniAppService botMiniAppService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            BotOrchestrator orchestrator,
            @Value("${harness.bots.library-directory:./data/bot-library}") String libraryDirectory) {
        var service = new BotMiniAppService(
                new BotStore(jdbc), sessions, new TransactionTemplate(transactions), Path.of(libraryDirectory));
        service.useOrchestrator(orchestrator);
        orchestrator.useMiniApps(service);
        return service;
    }

    @Bean
    @DependsOnDatabaseInitialization
    BotSkillService botSkillService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            BotOrchestrator orchestrator,
            ObjectMapper json,
            @Value("${harness.bots.library-directory:./data/bot-library}") String libraryDirectory) {
        var service = new BotSkillService(
                new BotStore(jdbc), sessions, new TransactionTemplate(transactions), Path.of(libraryDirectory), json);
        service.useOrchestrator(orchestrator);
        orchestrator.useSkills(service);
        return service;
    }

    @Bean
    @DependsOnDatabaseInitialization
    BotDebugService botDebugService(JdbcTemplate jdbc, SessionStore sessions, SettingsService settings) {
        return new BotDebugService(new BotStore(jdbc), sessions, settings, jdbc);
    }

    @Bean
    @DependsOnDatabaseInitialization
    BotService botService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            SettingsService settings,
            BotOrchestrator orchestrator,
            BotSkillService botSkills,
            PluginService plugins,
            ExternalAgentCatalog externalAgents,
            CommandService commands,
            @Value("${harness.settings.key-file}") String keyFile,
            @Value("${harness.bots.library-directory:./data/bot-library}") String libraryDirectory) {
        var service = new BotService(
                new BotStore(jdbc),
                sessions,
                new TransactionTemplate(transactions),
                settings,
                orchestrator,
                Path.of(libraryDirectory));
        service.usePlugins(plugins);
        service.useExternalAgents(externalAgents);
        service.useCommands(commands);
        service.useSkills(botSkills);
        service.useSecretCipher(new LocalSecretCipher(Path.of(keyFile)));
        return service;
    }

    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    BotLanServer botLanServer(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            SessionStore sessions,
            BotService botService,
            BotOrchestrator botOrchestrator,
            BotRoutineScheduler botRoutines,
            BotMiniAppService botMiniApps,
            BotSkillService botSkills,
            CommandService commands,
            ComposerInputService composerInputs,
            ObjectMapper json) {
        var server = new BotLanServer(
                new BotLanStore(jdbc),
                sessions,
                new TransactionTemplate(transactions),
                botService,
                botOrchestrator,
                botRoutines,
                botMiniApps,
                commands,
                json);
        server.useComposerInputs(composerInputs);
        server.useSkills(botSkills);
        return server;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> startBotLanServer(BotLanServer lanServer) {
        return event -> lanServer.startIfEnabled();
    }

    /** 由桌面壳启动时开启：标准输入关闭即视为桌面壳已退出，随之关闭应用。 */
    @Bean
    @ConditionalOnProperty(name = "harness.desktop.exit-with-parent", havingValue = "true")
    ApplicationListener<ApplicationReadyEvent> exitWithParent(ConfigurableApplicationContext context) {
        return event -> new ParentProcessWatcher(System.in, () -> {
                    log.info("Desktop shell closed the stdin pipe, shutting down");
                    System.exit(SpringApplication.exit(context));
                })
                .start();
    }

    @Bean
    ApplicationListener<ContextClosedEvent> stopRuntime(
            AgentEngine engine,
            EventController events,
            AutomationService automations,
            BotRoutineScheduler botRoutines,
            BotLanServer botLanServer,
            BotOrchestrator bots) {
        return event -> {
            botLanServer.close();
            automations.close();
            botRoutines.close();
            events.close();
            bots.close();
            engine.close();
        };
    }
}
