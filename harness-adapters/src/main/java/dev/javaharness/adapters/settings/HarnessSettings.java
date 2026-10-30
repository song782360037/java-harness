package dev.javaharness.adapters.settings;

/** Internal settings only. Never serialize directly to an API response or log credentials. */
public record HarnessSettings(
        long revision,
        String baseUrl,
        String apiKey,
        String modelName,
        boolean apiKeyRequired,
        int connectTimeoutSeconds,
        int runTimeoutSeconds,
        int contextMaxCharacters,
        int maxSteps,
        int toolTimeoutSeconds,
        int maxToolsPerRequest,
        int contextWindowTokens,
        int maxOutputTokens) {
    public HarnessSettings(
            long revision,
            String baseUrl,
            String apiKey,
            String modelName,
            boolean apiKeyRequired,
            int connectTimeoutSeconds,
            int runTimeoutSeconds,
            int contextMaxCharacters,
            int maxSteps,
            int toolTimeoutSeconds) {
        this(
                revision,
                baseUrl,
                apiKey,
                modelName,
                apiKeyRequired,
                connectTimeoutSeconds,
                runTimeoutSeconds,
                contextMaxCharacters,
                maxSteps,
                toolTimeoutSeconds,
                32,
                0,
                0);
    }

    public HarnessSettings(
            long revision,
            String baseUrl,
            String apiKey,
            String modelName,
            boolean apiKeyRequired,
            int connectTimeoutSeconds,
            int runTimeoutSeconds,
            int contextMaxCharacters,
            int maxSteps,
            int toolTimeoutSeconds,
            int maxToolsPerRequest) {
        this(
                revision,
                baseUrl,
                apiKey,
                modelName,
                apiKeyRequired,
                connectTimeoutSeconds,
                runTimeoutSeconds,
                contextMaxCharacters,
                maxSteps,
                toolTimeoutSeconds,
                maxToolsPerRequest,
                0,
                0);
    }

    public HarnessSettings {
        maxToolsPerRequest = maxToolsPerRequest <= 0 ? 32 : maxToolsPerRequest;
        contextWindowTokens = TokenLimits.window(contextWindowTokens, contextMaxCharacters);
        maxOutputTokens = TokenLimits.output(maxOutputTokens, contextWindowTokens);
    }

    public HarnessSettings withKeyAndRevision(String key, long version) {
        return new HarnessSettings(
                version,
                baseUrl,
                key,
                modelName,
                apiKeyRequired,
                connectTimeoutSeconds,
                runTimeoutSeconds,
                contextMaxCharacters,
                maxSteps,
                toolTimeoutSeconds,
                maxToolsPerRequest,
                contextWindowTokens,
                maxOutputTokens);
    }

    @Override
    public String toString() {
        return "HarnessSettings[revision=" + revision + ", credentials=redacted]";
    }
}
