package dev.javaharness.core;

import dev.javaharness.spi.model.ContextSummary;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import java.util.ArrayList;
import java.util.List;

/** Keeps the newest contiguous suffix of eligible complete exchanges, never partial messages. */
public final class ConversationContext {
    public static final int DEFAULT_MAX_TOKENS = 16_000;
    private final TokenBudget budget;
    private final int maxTokens;

    public ConversationContext(TokenCounter counter, int maxTokens) {
        if (maxTokens < 256 || maxTokens > 2_000_000) {
            throw new IllegalArgumentException("Context token budget must be between 256 and 2000000");
        }
        this.budget = new TokenBudget(counter);
        this.maxTokens = maxTokens;
    }

    /** Convenience for tests and legacy callers. */
    public ConversationContext(int maxTokens) {
        this(TokenCounter.approximate(), maxTokens);
    }

    public ModelRequest build(Run current, List<Run> history) {
        return build(current, history, run -> List.of(new ModelMessage("user", run.prompt()), new ModelMessage("assistant", run.output())));
    }

    public ModelRequest build(Run current, List<Run> history, java.util.function.Function<Run, List<ModelMessage>> transcript) {
        var eligible = history.stream().filter(run -> run.sessionId().equals(current.sessionId())
                && !run.id().equals(current.id()) && run.status() == RunStatus.COMPLETED
                && !run.provider().equals("simulated") && !run.output().isBlank()
                && (run.workspace().isEmpty() || run.workspace().equals(current.workspace()))).toList();
        var turns = eligible.stream().map(transcript).toList();
        int tokens = budget.message(new ModelMessage("user", current.prompt()));
        int characters = current.prompt().length();
        if (tokens > maxTokens) throw new IllegalArgumentException("Current input exceeds context budget");
        int first = eligible.size();
        while (first > 0) {
            List<ModelMessage> turn = turns.get(first - 1);
            int size = budget.messages(turn);
            if (size > maxTokens - tokens) break;
            tokens += size;
            characters += turn.stream().mapToInt(ModelMessage::characters).sum();
            first--;
        }
        var messages = new ArrayList<ModelMessage>();
        for (int i = first; i < eligible.size(); i++) {
            messages.addAll(turns.get(i));
        }
        messages.add(new ModelMessage("user", current.prompt()));
        return new ModelRequest(current.id(), current.model(), current.scenario(), messages,
                new ContextSummary(eligible.size() - first, first, history.size() - eligible.size(),
                        characters, 0, tokens, maxTokens));
    }
}
