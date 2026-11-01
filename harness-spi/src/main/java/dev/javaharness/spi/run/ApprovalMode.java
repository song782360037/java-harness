package dev.javaharness.spi.run;

import dev.javaharness.spi.HarnessException;

/** Who may settle approvals of a run: the user only, rules first, or everything except hard guards. */
public enum ApprovalMode {
    MANUAL,
    SMART,
    FULL_ACCESS;

    /** Parse a request value; callers decide what a missing value means. */
    public static ApprovalMode parse(String value) {
        try {
            return ApprovalMode.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new HarnessException("INVALID", "不支持的审批模式");
        }
    }
}
