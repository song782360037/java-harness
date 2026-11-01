package dev.javaharness.spi.memory;

import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.Run;
import java.time.Instant;
import java.util.List;

/** 长期记忆的召回、输入快照与完成后提取入口。 */
public interface LongTermMemory {
    record Entry(
            String id,
            String workspaceId,
            String title,
            String content,
            String kind,
            List<String> tags,
            String status,
            String sourceSessionId,
            String sourceRunId,
            String sourceExcerpt,
            Instant createdAt,
            Instant updatedAt,
            long revision) {}

    record Context(String text, List<Entry> entries) {
        public static final Context EMPTY = new Context("", List.of());
    }

    /** 返回预算内的参考资料；空工作区只能召回全局记忆。 */
    default Context recall(Run run, TokenCounter counter, int maxTokens) {
        return Context.EMPTY;
    }

    /** 在模型输入持久化后保存本次纳入的记忆正文快照。 */
    default void recordUsage(String runId, List<Entry> entries) {}

    /** 主任务成功后入队自动生成记忆，不改变任务结果。 */
    default void completed(Run run) {}

    static LongTermMemory disabled() {
        return new LongTermMemory() {};
    }
}
