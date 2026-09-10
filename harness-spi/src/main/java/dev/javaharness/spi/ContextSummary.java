package dev.javaharness.spi;

/** Counts historical exchanges only; characters count message content in UTF-16 code units. */
public record ContextSummary(int includedTurns, int droppedTurns, int excludedTurns,
                             int characters, int maxCharacters) {}
