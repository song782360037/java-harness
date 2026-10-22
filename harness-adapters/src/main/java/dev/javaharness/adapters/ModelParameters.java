package dev.javaharness.adapters;

/** Persisted model-specific values. Contains no endpoint or credentials. maxSteps is legacy and ignored. */
public record ModelParameters(String modelName, int runTimeoutSeconds, int contextMaxCharacters,
                              int maxSteps, int toolTimeoutSeconds, int maxToolsPerRequest,
                              int contextWindowTokens, int maxOutputTokens) {
    public ModelParameters(String modelName, int runTimeoutSeconds, int contextMaxCharacters,
                           int maxSteps, int toolTimeoutSeconds) {
        this(modelName, runTimeoutSeconds, contextMaxCharacters, maxSteps, toolTimeoutSeconds, 32, 0, 0);
    }
    public ModelParameters(String modelName, int runTimeoutSeconds, int contextMaxCharacters,
                           int maxSteps, int toolTimeoutSeconds, int maxToolsPerRequest) {
        this(modelName, runTimeoutSeconds, contextMaxCharacters, maxSteps, toolTimeoutSeconds, maxToolsPerRequest, 0, 0);
    }
    public ModelParameters {
        maxToolsPerRequest = maxToolsPerRequest <= 0 ? 32 : maxToolsPerRequest;
        contextWindowTokens = TokenLimits.window(contextWindowTokens, contextMaxCharacters);
        maxOutputTokens = TokenLimits.output(maxOutputTokens, contextWindowTokens);
    }
    public static ModelParameters from(HarnessSettings settings) {
        return new ModelParameters(settings.modelName(), settings.runTimeoutSeconds(), settings.contextMaxCharacters(),
                settings.maxSteps(), settings.toolTimeoutSeconds(), settings.maxToolsPerRequest(),
                settings.contextWindowTokens(), settings.maxOutputTokens());
    }
    public HarnessSettings resolve(long revision, ProviderSettings provider) {
        return new HarnessSettings(revision, provider.baseUrl(), provider.apiKey(), modelName, provider.apiKeyRequired(),
                provider.connectTimeoutSeconds(), runTimeoutSeconds, contextMaxCharacters, maxSteps, toolTimeoutSeconds,
                maxToolsPerRequest, contextWindowTokens, maxOutputTokens);
    }
}
