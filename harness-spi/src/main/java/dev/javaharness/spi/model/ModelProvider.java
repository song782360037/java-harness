package dev.javaharness.spi.model;

import java.util.function.Consumer;

/**
 * A stream owns all of its work until this method returns.
 * Implementations must cooperate with interruption and cancellation.
 */
public interface ModelProvider {
    ModelInfo info();

    interface Cancellation {
        void check() throws InterruptedException;
    }

    void stream(ModelRequest request, Cancellation cancellation, Consumer<String> onText) throws Exception;

    default ModelTurn complete(ModelRequest request, Cancellation cancellation, Consumer<String> onText)
            throws Exception {
        var text = new StringBuilder();
        stream(request, cancellation, chunk -> {
            text.append(chunk);
            onText.accept(chunk);
        });
        return new ModelTurn(text.toString(), java.util.List.of());
    }

    /** Also streams display-only reasoning; providers without reasoning ignore the extra callback. */
    default ModelTurn complete(
            ModelRequest request, Cancellation cancellation, Consumer<String> onText, Consumer<String> onReasoning)
            throws Exception {
        return complete(request, cancellation, onText);
    }
}
