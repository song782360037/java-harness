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
        var eligible = history.stream().filter(run -> run.sessionId().equals(current.sessionId())
                && !run.id().equals(current.id()) && run.status() == RunStatus.COMPLETED
                && !run.provider().equals("simulated") && !run.output().isBlank()).toList();
        int characters = current.prompt().length();
        if (characters > maxCharacters) throw new IllegalArgumentException("Current input exceeds context budget");
        int first = eligible.size();
        while (first > 0) {
            Run prior = eligible.get(first - 1);
            int size = prior.prompt().length() + prior.output().length();
            if (size > maxCharacters - characters) break;
            characters += size;
            first--;
        }
        var messages = new ArrayList<ModelMessage>();
        for (int i = first; i < eligible.size(); i++) {
            messages.add(new ModelMessage("user", eligible.get(i).prompt()));
            messages.add(new ModelMessage("assistant", eligible.get(i).output()));
        }
        messages.add(new ModelMessage("user", current.prompt()));
        return new ModelRequest(current.id(), current.model(), current.scenario(), messages,
                new ContextSummary(eligible.size() - first, first, history.size() - eligible.size(), characters, maxCharacters));
    }
}
