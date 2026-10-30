package dev.javaharness.server.service;

import java.util.function.Supplier;

/**
 * 标记当前线程正在由自动化无人值守提交、且不允许执行命令的会话。
 * 引擎在提交线程内同步捕获扩展，标记只在这次提交调用期间有效，同一会话之后的手动追问不受限制。
 */
public final class UnattendedSubmissions {
    private final ThreadLocal<String> session = new ThreadLocal<>();

    /** Run the submission while extension capture on this thread sees the session as command free. */
    public <T> T withoutCommands(String sessionId, Supplier<T> submit) {
        session.set(sessionId);
        try {
            return submit.get();
        } finally {
            session.remove();
        }
    }

    public boolean commandsBlocked(String sessionId) {
        return sessionId.equals(session.get());
    }
}
