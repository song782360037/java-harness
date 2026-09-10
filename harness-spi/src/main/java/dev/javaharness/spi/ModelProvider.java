package dev.javaharness.spi;

import java.util.function.Consumer;

/**
 * A stream owns all of its work until this method returns.
 * Implementations must cooperate with interruption and cancellation.
 */
public interface ModelProvider {
    interface Cancellation {
        void check() throws InterruptedException;
    }

    void stream(Run request, Cancellation cancellation, Consumer<String> onText) throws Exception;
}
