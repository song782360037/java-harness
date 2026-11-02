package dev.javaharness.spi.model;

/**
 * Counts historical exchanges. Characters remain for reading snapshots written before token budgets.
 * compactedTurns counts the dropped turns represented by a summary; snapshots before compaction read it as 0.
 */
public record ContextSummary(
        int includedTurns,
        int droppedTurns,
        int excludedTurns,
        int characters,
        int maxCharacters,
        int tokens,
        int maxTokens,
        int compactedTurns) {
    public ContextSummary(int includedTurns, int droppedTurns, int excludedTurns, int characters, int maxCharacters) {
        this(includedTurns, droppedTurns, excludedTurns, characters, maxCharacters, 0, 0);
    }

    public ContextSummary(
            int includedTurns,
            int droppedTurns,
            int excludedTurns,
            int characters,
            int maxCharacters,
            int tokens,
            int maxTokens) {
        this(includedTurns, droppedTurns, excludedTurns, characters, maxCharacters, tokens, maxTokens, 0);
    }
}
