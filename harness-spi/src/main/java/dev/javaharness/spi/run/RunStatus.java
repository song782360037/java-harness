package dev.javaharness.spi.run;

/** Run lifecycle states; {@link #active()} is true until the run reaches a terminal state. */
public enum RunStatus {
    QUEUED,
    RUNNING,
    CANCELLING,
    COMPLETED,
    CANCELLED,
    FAILED,
    TIMED_OUT,
    INTERRUPTED;

    public boolean active() {
        return this == QUEUED || this == RUNNING || this == CANCELLING;
    }
}
