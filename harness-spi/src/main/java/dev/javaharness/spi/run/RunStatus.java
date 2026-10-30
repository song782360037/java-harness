package dev.javaharness.spi.run;

public enum RunStatus {
    QUEUED, RUNNING, CANCELLING, COMPLETED, CANCELLED, FAILED, TIMED_OUT, INTERRUPTED;

    public boolean active() {
        return this == QUEUED || this == RUNNING || this == CANCELLING;
    }
}
