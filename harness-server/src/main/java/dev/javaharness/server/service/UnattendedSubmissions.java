package dev.javaharness.server.service;

import java.util.function.Supplier;

/**
 * 标记当前线程正在由自动化无人值守提交的会话，以及这次提交是否允许执行命令。
 * 引擎在提交线程内同步捕获扩展，标记只在这次提交调用期间有效，同一会话之后的手动追问不受限制。
 */
public final class UnattendedSubmissions {
    private record Marker(String sessionId, boolean commands) {}

    private final ThreadLocal<Marker> marker = new ThreadLocal<>();

    /** Run the submission while extension capture on this thread sees the session as unattended. */
    public <T> T submit(String sessionId, boolean allowCommands, Supplier<T> submit) {
        marker.set(new Marker(sessionId, allowCommands));
        try {
            return submit.get();
        } finally {
            marker.remove();
        }
    }

    public boolean commandsBlocked(String sessionId) {
        var value = marker.get();
        return value != null && value.sessionId().equals(sessionId) && !value.commands();
    }

    /** True while an automation submits this session, so nobody is there to answer questions. */
    public boolean unattended(String sessionId) {
        var value = marker.get();
        return value != null && value.sessionId().equals(sessionId);
    }
}
