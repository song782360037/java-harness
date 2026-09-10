package dev.javaharness.spi;

import java.time.Instant;

public record Session(String id, String title, Instant createdAt) {}
