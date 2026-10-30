package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.session.SessionStore;

/**
 * 把提示词作为会话中的一次运行接收并异步执行。
 * 以相同输入重复提交同一 request id 会返回已有运行；同一 request id 搭配不同的提示词、场景或模型选择会返回 {@code CONFLICT}。
 */
public interface AgentEngine extends AutoCloseable {
    SessionStore.Submission submit(String sessionId, String requestId, String prompt, String scenario);

    SessionStore.Submission submit(
            String sessionId, String requestId, String prompt, String scenario, ModelSelection selection);

    Run cancel(String runId);

    @Override
    void close();
}
