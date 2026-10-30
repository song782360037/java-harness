package dev.javaharness.spi.session;

import dev.javaharness.spi.run.Run;
import java.util.List;

/** Runs, usage and lastSeq must be read from the same committed view. */
public record SessionSnapshot(Session session, List<Run> runs, long lastSeq, SessionUsage usage) {
    public SessionSnapshot {
        runs = List.copyOf(runs);
    }
}
