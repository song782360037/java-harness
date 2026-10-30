package dev.javaharness.core;

import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import java.util.List;
import java.util.Map;

/** Estimates the input tokens of a request, including message and tool framing. */
public final class TokenBudget {
    private static final int MESSAGE_OVERHEAD = 4;
    private static final int TOOL_OVERHEAD = 8;
    private static final int REQUEST_OVERHEAD = 8;
    private final TokenCounter counter;

    public TokenBudget(TokenCounter counter) {
        this.counter = counter;
    }

    public int message(ModelMessage message) {
        int tokens = MESSAGE_OVERHEAD + counter.count(message.role()) + counter.count(message.content());
        for (ToolCall call : message.toolCalls()) {
            tokens += TOOL_OVERHEAD + counter.count(call.id()) + counter.count(call.name()) + counter.count(call.arguments());
        }
        if (message.toolCallId() != null) tokens += counter.count(message.toolCallId());
        return tokens;
    }

    public int messages(List<ModelMessage> messages) {
        int tokens = REQUEST_OVERHEAD;
        for (ModelMessage message : messages) tokens += message(message);
        return tokens;
    }

    public int tools(List<ToolDefinition> tools) {
        int tokens = 0;
        for (ToolDefinition tool : tools) {
            tokens += TOOL_OVERHEAD + counter.count(tool.name()) + counter.count(tool.description()) + value(tool.parameters());
        }
        return tokens;
    }

    public int input(List<ModelMessage> messages, String instructions, List<ToolDefinition> tools) {
        return messages(messages) + counter.count(instructions) + tools(tools);
    }

    /** 10% headroom, at least 64 tokens, for protocol details the local tokenizer cannot see. */
    public static int safety(int estimatedTokens) {
        return Math.max(64, (estimatedTokens + 9) / 10);
    }

    private int value(Object value) {
        if (value == null) return 0;
        if (value instanceof Map<?, ?> map) {
            int tokens = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                tokens += counter.count(String.valueOf(entry.getKey())) + value(entry.getValue()) + 2;
            }
            return tokens;
        }
        if (value instanceof Iterable<?> values) {
            int tokens = 2;
            for (Object item : values) tokens += value(item);
            return tokens;
        }
        return counter.count(String.valueOf(value));
    }
}
