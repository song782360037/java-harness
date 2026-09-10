package dev.javaharness.core;

import dev.javaharness.spi.*;
import java.util.ArrayList;
import java.util.List;

/** Keeps the newest contiguous suffix of eligible complete exchanges, never partial messages. */
public final class ConversationContext {
    public static final int DEFAULT_MAX_CHARACTERS = 32_000;
    private final int maxCharacters;

    public ConversationContext(int maxCharacters) {
        if (maxCharacters < 4000 || maxCharacters > 256_000) {
            throw new IllegalArgumentException("Context character budget must be between 4000 and 256000");
        }
        this.maxCharacters = maxCharacters;
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
        int characters = current.prompt().length();
        if (characters > maxCharacters) throw new IllegalArgumentException("Current input exceeds context budget");
        int first = eligible.size();
        while (first > 0) {
            int size = turns.get(first - 1).stream().mapToInt(ModelMessage::characters).sum();
            if (size > maxCharacters - characters) break;
            characters += size;
            first--;
        }
        var messages = new ArrayList<ModelMessage>();
        for (int i = first; i < eligible.size(); i++) {
            messages.addAll(turns.get(i));
        }
        messages.add(new ModelMessage("user", current.prompt()));
        return new ModelRequest(current.id(), current.model(), current.scenario(), messages,
                new ContextSummary(eligible.size() - first, first, history.size() - eligible.size(), characters, maxCharacters));
    }
}
