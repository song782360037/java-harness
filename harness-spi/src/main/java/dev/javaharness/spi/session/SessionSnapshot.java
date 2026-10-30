package dev.javaharness.spi.session;

import dev.javaharness.spi.run.Run;

import java.util.List;

/** Runs and lastSeq must be read from the same committed view. */
public record SessionSnapshot(Session session, List<Run> runs, long lastSeq) {
    public SessionSnapshot {
        runs = List.copyOf(runs);
    }
}
