package dev.javaharness.spi.session;

import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.SubagentRun;
import java.util.List;

/** Runs, usage and lastSeq must be read from the same committed view. Runs are top-level; subagents are listed apart. */
public record SessionSnapshot(
        Session session, List<Run> runs, long lastSeq, SessionUsage usage, List<SubagentRun> subagents) {
    public SessionSnapshot {
        runs = List.copyOf(runs);
        subagents = List.copyOf(subagents);
    }
}
