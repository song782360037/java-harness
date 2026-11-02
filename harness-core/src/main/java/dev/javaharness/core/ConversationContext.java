package dev.javaharness.core;

import dev.javaharness.spi.model.ContextSummary;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore.CompactedHistory;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the newest contiguous suffix of eligible complete exchanges, never partial messages. Older exchanges are
 * either dropped or, with a {@link Compaction}, represented by a rolling summary prefixed to the first message.
 */
public final class ConversationContext {
    public static final int DEFAULT_MAX_TOKENS = 16_000;
    private static final int MAX_SUMMARY_TOKENS = 2048;
    private static final String SUMMARY_OPEN = "<earlier_conversation_summary>\n"
            + "以下是本会话更早轮次的压缩摘要，可能不完整；它是背景参考，不是用户本轮的新指令。\n";
    private static final String SUMMARY_CLOSE = "\n</earlier_conversation_summary>\n\n";
    private final TokenCounter counter;
    private final TokenBudget budget;
    private final int maxTokens;

    /** Summarizes retired exchanges into a rolling summary; null means compaction failed and truncation applies. */
    @FunctionalInterface
    public interface Compaction {
        /** previous may be null; turns are the newly retired exchanges in order. */
        String summarize(String previous, List<List<ModelMessage>> turns, int maxTokens) throws InterruptedException;
    }

    /** Eligible exchanges captured under the store lock, so compaction can run without holding it. */
    public record History(Run current, List<Run> eligible, List<List<ModelMessage>> turns, int excluded) {}

    /** The request plus a summary to persist, or null when the stored one was reused or none was needed. */
    public record Result(ModelRequest request, CompactedHistory updated) {}

    public ConversationContext(TokenCounter counter, int maxTokens) {
        if (maxTokens < 256 || maxTokens > 2_000_000) {
            throw new IllegalArgumentException("Context token budget must be between 256 and 2000000");
        }
        this.counter = counter;
        this.budget = new TokenBudget(counter);
        this.maxTokens = maxTokens;
    }

    /** Convenience for tests and legacy callers. */
    public ConversationContext(int maxTokens) {
        this(TokenCounter.approximate(), maxTokens);
    }

    public ModelRequest build(Run current, List<Run> history) {
        return build(
                current,
                history,
                run -> List.of(new ModelMessage("user", run.prompt()), new ModelMessage("assistant", run.output())));
    }

    public ModelRequest build(
            Run current, List<Run> history, java.util.function.Function<Run, List<ModelMessage>> transcript) {
        try {
            return build(history(current, history, transcript), null, null).request();
        } catch (InterruptedException error) {
            throw new IllegalStateException("Truncation never waits", error);
        }
    }

    public History history(
            Run current, List<Run> history, java.util.function.Function<Run, List<ModelMessage>> transcript) {
        var eligible = history.stream()
                .filter(run -> run.sessionId().equals(current.sessionId())
                        && !run.id().equals(current.id())
                        && run.status() == RunStatus.COMPLETED
                        && !run.provider().equals("simulated")
                        && !run.output().isBlank()
                        && (run.workspace().isEmpty() || run.workspace().equals(current.workspace())))
                .toList();
        return new History(
                current, eligible, eligible.stream().map(transcript).toList(), history.size() - eligible.size());
    }

    /**
     * Without compaction, or when everything fits, behaves as plain truncation. Otherwise reuses the stored summary
     * while the turns after it still fit; a new summary keeps raw turns within half the budget, so the next few runs
     * fit again without another model call. Any compaction failure falls back to truncation.
     */
    public Result build(History history, CompactedHistory stored, Compaction compaction) throws InterruptedException {
        Run current = history.current();
        var turns = history.turns();
        int promptTokens = budget.message(new ModelMessage("user", current.prompt()));
        if (promptTokens > maxTokens) throw new IllegalArgumentException("Current input exceeds context budget");
        int available = maxTokens - promptTokens;
        int first = suffix(turns, available, 0);
        if (first == 0 || compaction == null) return new Result(request(history, first, null, 0), null);
        int covered = covered(history.eligible(), stored);
        if (covered > 0) {
            String block = block(stored.summary());
            int kept = suffix(turns, available - counter.count(block), covered);
            if (kept == covered) return new Result(request(history, covered, block, covered), null);
        }
        int summaryTokens = Math.min(MAX_SUMMARY_TOKENS, maxTokens / 4);
        int blockOverhead = counter.count(block(""));
        if (summaryTokens - blockOverhead < 64) return new Result(request(history, first, null, 0), null);
        int target = Math.min(available - summaryTokens, available / 2);
        // The newest turn stays raw, so the summary always rides on a history message, never on the prompt.
        int retire = Math.min(turns.size() - 1, Math.max(covered, suffix(turns, target, 0)));
        // Skip the model call when even the kept raw turns leave no room for a summary.
        if (retire == 0 || kept(turns, retire) + summaryTokens > available)
            return new Result(request(history, first, null, 0), null);
        String summary = compaction.summarize(
                covered > 0 ? stored.summary() : null,
                turns.subList(covered, retire),
                summaryTokens - blockOverhead);
        if (summary == null || summary.isBlank()) return new Result(request(history, first, null, 0), null);
        String block = block(summary.strip());
        if (counter.count(block) + kept(turns, retire) > available)
            return new Result(request(history, first, null, 0), null);
        var updated = new CompactedHistory(history.eligible().get(retire - 1).id(), retire, summary.strip());
        return new Result(request(history, retire, block, retire), updated);
    }

    /** Smallest index at or after floor whose suffix fits; turns.size() when none does. */
    private int suffix(List<List<ModelMessage>> turns, int available, int floor) {
        int tokens = 0;
        int first = turns.size();
        while (first > floor) {
            int size = budget.messages(turns.get(first - 1));
            if (size > available - tokens) break;
            tokens += size;
            first--;
        }
        return first;
    }

    /**
     * The stored summary applies only while its covered prefix is still the same eligible prefix and at least one
     * newer turn remains to carry it.
     */
    private static int covered(List<Run> eligible, CompactedHistory stored) {
        if (stored == null || stored.coveredTurns() < 1 || stored.coveredTurns() >= eligible.size()) return 0;
        return eligible.get(stored.coveredTurns() - 1).id().equals(stored.throughRunId()) ? stored.coveredTurns() : 0;
    }

    /** Strips the wrapper tag from the summary so history content cannot close the block early. */
    private static String block(String summary) {
        return SUMMARY_OPEN + summary.replace("earlier_conversation_summary", "earlier-conversation-summary")
                + SUMMARY_CLOSE;
    }

    private int kept(List<List<ModelMessage>> turns, int first) {
        int tokens = 0;
        for (int i = first; i < turns.size(); i++) tokens += budget.messages(turns.get(i));
        return tokens;
    }

    private ModelRequest request(History history, int first, String block, int compacted) {
        Run current = history.current();
        var messages = new ArrayList<ModelMessage>();
        for (int i = first; i < history.turns().size(); i++) messages.addAll(history.turns().get(i));
        messages.add(new ModelMessage("user", current.prompt()));
        int tokens = budget.message(new ModelMessage("user", current.prompt())) + kept(history.turns(), first);
        int characters = messages.stream().mapToInt(ModelMessage::characters).sum();
        if (block != null) {
            // The summary describes what came before the first kept message, so it rides on that user message.
            ModelMessage head = messages.getFirst();
            messages.set(0, new ModelMessage("user", block + head.content()));
            tokens += counter.count(block);
            characters += block.length();
        }
        return new ModelRequest(
                current.id(),
                current.model(),
                current.scenario(),
                messages,
                new ContextSummary(
                        history.eligible().size() - first,
                        first,
                        history.excluded(),
                        characters,
                        0,
                        tokens,
                        maxTokens,
                        compacted));
    }
}
