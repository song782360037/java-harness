package dev.javaharness.server.service;

import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.adapters.command.CommandWorkspaceTools;
import dev.javaharness.adapters.model.JtokkitTokenCounter;
import dev.javaharness.adapters.model.OpenAiChatCompletionProvider;
import dev.javaharness.adapters.plugins.PluginService;
import dev.javaharness.adapters.settings.HarnessSettings;
import dev.javaharness.adapters.settings.JdbcSettingsStore;
import dev.javaharness.adapters.settings.ModelParameters;
import dev.javaharness.adapters.settings.ModelProfile;
import dev.javaharness.adapters.settings.ProviderSettings;
import dev.javaharness.adapters.workspace.FileChangeService;
import dev.javaharness.adapters.workspace.GitWorkspaceTools;
import dev.javaharness.adapters.workspace.LocalWorkspaceTools;
import dev.javaharness.adapters.workspace.MultiWorkspaceTools;
import dev.javaharness.adapters.workspace.ReviewWorkspaceTools;
import dev.javaharness.adapters.workspace.WorkspaceRoots;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.run.RunExtension;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Validates and saves providers, model profiles and settings with optimistic revision checks, and captures an
 * immutable {@link RunConfiguration} per run that layers local, git, review and command tools for each workspace root.
 */
public final class SettingsService implements RunConfigurationSource {
    private final JdbcSettingsStore store;
    private final FileChangeService changes;
    private final CommandService commands;
    private final PluginService plugins;

    public SettingsService(JdbcSettingsStore store, HarnessSettings initial) {
        this(store, initial, null);
    }

    public SettingsService(JdbcSettingsStore store, HarnessSettings initial, FileChangeService changes) {
        this(store, initial, changes, null);
    }

    public SettingsService(
            JdbcSettingsStore store, HarnessSettings initial, FileChangeService changes, CommandService commands) {
        this(store, initial, changes, commands, null);
    }

    public SettingsService(
            JdbcSettingsStore store,
            HarnessSettings initial,
            FileChangeService changes,
            CommandService commands,
            PluginService plugins) {
        this.store = store;
        this.changes = changes;
        this.commands = commands;
        this.plugins = plugins;
        if (store.current() == null) {
            validate(initial);
            store.initialize(initial);
        }
    }

    public HarnessSettings current() {
        return store.current();
    }

    public java.util.List<ModelProfile> profiles() {
        return store.profiles();
    }

    public ModelProfile profile(String id) {
        return store.profile(id);
    }

    public synchronized void deleteProfile(String id, long revision) {
        store.deleteProfile(id, revision);
    }

    public java.util.List<ProviderSettings> providers() {
        return store.providers();
    }

    public ProviderSettings provider(String id) {
        return store.provider(id);
    }

    public synchronized void deleteProvider(String id, long revision) {
        store.deleteProvider(id, revision);
    }

    public synchronized ProviderSettings saveProvider(ProviderSettings requested, boolean clearApiKey) {
        if (requested.displayName() == null
                || requested.displayName().isBlank()
                || requested.displayName().length() > 100) throw new HarnessException("INVALID", "提供商名称需为 1–100 个字符");
        var old = requested.id() == null ? null : store.provider(requested.id());
        if (requested.revision() != (old == null ? 0 : old.revision()))
            throw new HarnessException("CONFLICT", "提供商已更新，请重新加载");
        String key = requested.apiKey() == null ? "" : requested.apiKey().strip();
        if (clearApiKey && !key.isEmpty()) throw new HarnessException("INVALID", "清除 API Key 时不能同时填写新 Key");
        if (key.isEmpty() && !clearApiKey && old != null) key = old.apiKey();
        String base = requested.baseUrl() == null ? "" : requested.baseUrl().strip();
        validate(new HarnessSettings(
                1,
                base,
                key,
                "",
                requested.apiKeyRequired(),
                requested.connectTimeoutSeconds(),
                120,
                0,
                0,
                5,
                32,
                16000,
                4096));
        return store.saveProvider(new ProviderSettings(
                requested.id(),
                requested.revision(),
                requested.displayName().strip(),
                base,
                key,
                requested.apiKeyRequired(),
                requested.connectTimeoutSeconds()));
    }

    public synchronized ModelProfile saveModel(
            String id, String providerId, String name, String effort, ModelParameters parameters, long revision) {
        if (providerId == null || providerId.isBlank()) throw new HarnessException("INVALID", "请先选择提供商");
        if (name == null || name.isBlank() || name.length() > 100)
            throw new HarnessException("INVALID", "模型显示名称需为 1–100 个字符");
        if (effort == null) effort = "";
        new ModelSelection(id, effort);
        if (id == null && revision != 0) throw new HarnessException("INVALID", "新模型版本需为 0");
        if (id != null) store.profile(id);
        var normalized = new ModelParameters(
                parameters.modelName() == null ? "" : parameters.modelName().strip(),
                parameters.runTimeoutSeconds(),
                parameters.contextMaxCharacters(),
                parameters.maxSteps(),
                parameters.toolTimeoutSeconds(),
                parameters.maxToolsPerRequest(),
                parameters.contextWindowTokens(),
                parameters.maxOutputTokens());
        validate(normalized.resolve(revision, store.provider(providerId)));
        return store.saveModel(id, providerId, name.strip(), effort, normalized, revision);
    }

    public synchronized HarnessSettings save(HarnessSettings requested, boolean clearApiKey) {
        var profile = store.profile("default");
        return saveProfile("default", profile.displayName(), profile.reasoningEffort(), requested, clearApiKey)
                .settings();
    }

    public synchronized ModelProfile saveProfile(
            String id, String name, String effort, HarnessSettings requested, boolean clearApiKey) {
        if (name == null || name.isBlank() || name.length() > 100)
            throw new HarnessException("INVALID", "配置名称需为 1–100 个字符");
        if (effort == null) effort = "";
        new ModelSelection(id, effort);
        var current = id == null
                ? requested.withKeyAndRevision("", 0)
                : store.profile(id).settings();
        if (requested.revision() != current.revision()) throw new HarnessException("CONFLICT", "配置已更新，请重新加载后编辑");
        String key = requested.apiKey() == null ? "" : requested.apiKey().strip();
        if (clearApiKey && !key.isEmpty()) throw new HarnessException("INVALID", "清除 API Key 时不能同时填写新 Key");
        if (key.isEmpty() && !clearApiKey) key = current.apiKey();
        var value = new HarnessSettings(
                current.revision(),
                requested.baseUrl() == null ? "" : requested.baseUrl().strip(),
                key,
                requested.modelName() == null ? "" : requested.modelName().strip(),
                requested.apiKeyRequired(),
                requested.connectTimeoutSeconds(),
                requested.runTimeoutSeconds(),
                requested.contextMaxCharacters(),
                requested.maxSteps(),
                requested.toolTimeoutSeconds(),
                requested.maxToolsPerRequest(),
                requested.contextWindowTokens(),
                requested.maxOutputTokens());
        validate(value);
        return id == null
                ? store.createProfile(name.strip(), effort, value)
                : store.saveProfile(id, name.strip(), effort, value, current.revision());
    }

    private static OpenAiChatCompletionProvider provider(HarnessSettings settings) {
        return new OpenAiChatCompletionProvider(
                URI.create(settings.baseUrl()),
                settings.apiKey(),
                settings.modelName(),
                settings.apiKeyRequired(),
                Duration.ofSeconds(settings.connectTimeoutSeconds()));
    }

    private static void validate(HarnessSettings settings) {
        if (settings.baseUrl() == null
                || settings.baseUrl().length() > 2048
                || settings.apiKey() == null
                || settings.apiKey().length() > 8192
                || settings.connectTimeoutSeconds() < 1
                || settings.connectTimeoutSeconds() > 300
                || settings.runTimeoutSeconds() < 1
                || settings.runTimeoutSeconds() > 86400
                || settings.contextWindowTokens() < 1024
                || settings.contextWindowTokens() > 2_000_000
                || settings.maxOutputTokens() < 64
                || settings.maxOutputTokens() >= settings.contextWindowTokens()
                || settings.toolTimeoutSeconds() < 1
                || settings.toolTimeoutSeconds() > 300
                || settings.maxToolsPerRequest() < 1
                || settings.maxToolsPerRequest() > ToolCall.MAX_BATCH_SIZE) {
            throw new HarnessException(
                    "INVALID",
                    "请检查配置：连接/工具超时 1–300 秒，任务超时 1–86400 秒，上下文窗口 1024–2000000 tokens 且大于最大输出，单次工具上限 1–"
                            + ToolCall.MAX_BATCH_SIZE);
        }
        try (var ignored = provider(settings)) {
            /* Constructor validates endpoint, credentials and model without networking. */
        } catch (IllegalArgumentException error) {
            throw new HarnessException("INVALID", "模型配置无效：请检查基础地址、模型名称和 API Key 格式；远程地址需 HTTPS，本机可用 HTTP");
        }
    }

    public static ModelInfo info(HarnessSettings value) {
        return new ModelInfo(
                "openai-compatible",
                value.modelName(),
                !value.modelName().isBlank()
                        && (!value.apiKeyRequired() || !value.apiKey().isEmpty()));
    }

    @Override
    public RunConfiguration snapshot() {
        return snapshot(ModelSelection.DEFAULT);
    }

    @Override
    public RunExtension extensions(Session session, String prompt) {
        return plugins == null ? RunExtension.none() : plugins.prepare(session, prompt);
    }

    @Override
    public RunConfiguration snapshot(ModelSelection selection) {
        var profile = store.profile(selection.profileId() == null ? "default" : selection.profileId());
        HarnessSettings captured = profile.settings();
        var chosen = new ModelSelection(
                profile.id(),
                selection.reasoningEffort() == null ? profile.reasoningEffort() : selection.reasoningEffort());
        return new RunConfiguration(
                info(captured),
                Duration.ofSeconds(captured.runTimeoutSeconds()),
                captured.contextWindowTokens(),
                captured.maxOutputTokens(),
                captured.maxToolsPerRequest(),
                new JtokkitTokenCounter(captured.modelName()),
                workspace -> {
                    var model = provider(captured);
                    try {
                        var roots = WorkspaceRoots.directories(workspace);
                        var delegates = new java.util.ArrayList<WorkspaceTools>();
                        for (int i = 0; i < roots.size(); i++) {
                            WorkspaceTools root = new LocalWorkspaceTools(
                                    Path.of(roots.get(i)), Duration.ofSeconds(captured.toolTimeoutSeconds()));
                            // Revalidate the captured root before using its immutable review-directory mapping.
                            if (!root.workspace().equals(roots.get(i)))
                                throw new java.io.IOException("Workspace changed");
                            root = new GitWorkspaceTools(root, Duration.ofSeconds(captured.toolTimeoutSeconds()));
                            String prefix = roots.size() > 1 ? "@" + (i + 1) + "/" : "";
                            if (changes != null) root = new ReviewWorkspaceTools(root, changes, workspace, prefix);
                            if (commands != null) root = new CommandWorkspaceTools(root, commands, workspace, prefix);
                            delegates.add(root);
                        }
                        WorkspaceTools tools = roots.isEmpty()
                                ? WorkspaceTools.disabled()
                                : roots.size() == 1
                                        ? delegates.getFirst()
                                        : new MultiWorkspaceTools(workspace, delegates);
                        return new RunConfiguration.Resources(model, tools, model::close);
                    } catch (Exception error) {
                        model.close();
                        throw new ModelException("会话工作区不可访问，请确认目录仍存在，或切换工作区后重试");
                    }
                },
                chosen);
    }
}
