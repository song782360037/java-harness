package dev.javaharness.spi.model;

import dev.javaharness.spi.tool.ToolCall;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 发送给模型的一条对话消息。assistant 消息可携带工具调用，tool 消息引用其应答的调用。 */
public record ModelMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public ModelMessage(String role, String content) {
        this(role, content, List.of(), null);
    }

    public ModelMessage {
        if (!List.of("user", "assistant", "tool").contains(role))
            throw new IllegalArgumentException("Unsupported message role");
        Objects.requireNonNull(content);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (!toolCalls.isEmpty() && !role.equals("assistant"))
            throw new IllegalArgumentException("Only assistant can call tools");
        if (role.equals("tool") != (toolCallId != null && !toolCallId.isBlank()))
            throw new IllegalArgumentException("Tool result requires call id");
        if (!role.equals("tool") && toolCallId != null)
            throw new IllegalArgumentException("Only tool results have call id");
    }

    public int characters() {
        return content.length()
                + toolCalls.stream()
                        .mapToInt(call -> call.id().length()
                                + call.name().length()
                                + call.arguments().length())
                        .sum()
                + (toolCallId == null ? 0 : toolCallId.length());
    }

    /** Validate complete tool pairs and user/assistant turns, allowing a pending final user/tool input. */
    public static void validate(List<ModelMessage> messages, boolean request) {
        Set<String> pending = new HashSet<>();
        Set<String> ids = new HashSet<>();
        boolean assistantExpected = false;
        for (ModelMessage message : messages) {
            switch (message.role()) {
                case "user" -> {
                    if (assistantExpected || !pending.isEmpty())
                        throw new IllegalArgumentException("Incomplete exchange");
                    assistantExpected = true;
                    ids.clear();
                }
                case "assistant" -> {
                    if (!assistantExpected || !pending.isEmpty())
                        throw new IllegalArgumentException("Unexpected assistant");
                    assistantExpected = !message.toolCalls().isEmpty();
                    for (ToolCall call : message.toolCalls()) {
                        if (!ids.add(call.id())) throw new IllegalArgumentException("Duplicate tool call id");
                        pending.add(call.id());
                    }
                }
                case "tool" -> {
                    if (!pending.remove(message.toolCallId()))
                        throw new IllegalArgumentException("Unpaired tool result");
                }
                default -> throw new IllegalArgumentException("Invalid role");
            }
        }
        if (messages.isEmpty() || !pending.isEmpty() || assistantExpected != request)
            throw new IllegalArgumentException("Incomplete conversation");
    }
}
