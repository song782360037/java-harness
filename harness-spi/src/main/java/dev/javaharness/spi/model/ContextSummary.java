package dev.javaharness.spi.model;

/** Counts historical exchanges. Characters remain for reading snapshots written before token budgets. */
public record ContextSummary(int includedTurns, int droppedTurns, int excludedTurns,
                             int characters, int maxCharacters, int tokens, int maxTokens) {
    public ContextSummary(int includedTurns, int droppedTurns, int excludedTurns, int characters, int maxCharacters) {
        this(includedTurns, droppedTurns, excludedTurns, characters, maxCharacters, 0, 0);
    }
}
