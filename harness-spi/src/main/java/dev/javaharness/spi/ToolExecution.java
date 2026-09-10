package dev.javaharness.spi;

public record ToolExecution(String runId, int step, String callId, String name,
                            String arguments, String status, String output) {}
