package dev.javaharness.adapters;

/** Internal settings only. Never serialize directly to an API response or log credentials. */
public record HarnessSettings(long revision, String baseUrl, String apiKey, String modelName, boolean apiKeyRequired,
                              int connectTimeoutSeconds, int runTimeoutSeconds, int contextMaxCharacters,
                              int maxSteps, int toolTimeoutSeconds) {
    public HarnessSettings withKeyAndRevision(String key, long version) {
        return new HarnessSettings(version, baseUrl, key, modelName, apiKeyRequired, connectTimeoutSeconds,
                runTimeoutSeconds, contextMaxCharacters, maxSteps, toolTimeoutSeconds);
    }
    @Override public String toString() { return "HarnessSettings[revision=" + revision + ", credentials=redacted]"; }
}
