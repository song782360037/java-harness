package dev.javaharness.spi;

import java.util.List;
import java.util.Objects;

/** Immutable logical request, persisted before any provider invocation. Contains no credentials. */
public record ModelRequest(String runId, String model, String scenario,
                           List<ModelMessage> messages, ContextSummary context) {
    public ModelRequest {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(model);
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(context);
        messages = List.copyOf(messages);
        if (messages.isEmpty() || messages.size() % 2 != 1) throw new IllegalArgumentException("Expected complete exchanges and current user message");
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).role().equals(i % 2 == 0 ? "user" : "assistant")) {
                throw new IllegalArgumentException("Invalid message order");
            }
        }
    }
}
