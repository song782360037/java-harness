package dev.javaharness.spi;

/** Only fixed, user-safe messages belong here; upstream bodies must never be included. */
public final class ModelException extends Exception {
    public ModelException(String message) {
        super(message);
    }
}
