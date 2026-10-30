package dev.javaharness.spi.run;

/** 运行的生命周期状态；在进入终态之前 {@link #active()} 均为 true。 */
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
