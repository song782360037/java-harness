package dev.javaharness.adapters;

/** Persisted model-specific values. Contains no endpoint or credentials. */
public record ModelParameters(String modelName, int runTimeoutSeconds, int contextMaxCharacters,
                              int maxSteps, int toolTimeoutSeconds) {
    public static ModelParameters from(HarnessSettings settings) {
        return new ModelParameters(settings.modelName(), settings.runTimeoutSeconds(), settings.contextMaxCharacters(),
                settings.maxSteps(), settings.toolTimeoutSeconds());
    }
    public HarnessSettings resolve(long revision, ProviderSettings provider) {
        return new HarnessSettings(revision, provider.baseUrl(), provider.apiKey(), modelName, provider.apiKeyRequired(),
                provider.connectTimeoutSeconds(), runTimeoutSeconds, contextMaxCharacters, maxSteps, toolTimeoutSeconds);
    }
}
