package dev.javaharness.spi;

import java.time.Instant;

/** One model step: its text starts at outputStart in the run output; reasoning is display-only and never resent. */
public record RunStep(int step, int outputStart, String reasoning, Instant startedAt, Instant reasoningAt, Instant finishedAt) {}
