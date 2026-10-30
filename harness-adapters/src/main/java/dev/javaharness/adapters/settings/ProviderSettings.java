package dev.javaharness.adapters.settings;

/** Connection and credentials shared by all models belonging to this provider. */
public record ProviderSettings(
        String id,
        long revision,
        String displayName,
        String baseUrl,
        String apiKey,
        boolean apiKeyRequired,
        int connectTimeoutSeconds) {
    @Override
    public String toString() {
        return "ProviderSettings[id=" + id + ", revision=" + revision + ", credentials=redacted]";
    }
}
