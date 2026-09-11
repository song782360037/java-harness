package dev.javaharness.spi;

import java.util.List;
import java.util.Objects;

/** Immutable logical request, persisted before any provider invocation. Contains no credentials. */
public record ModelRequest(String runId, String model, String scenario,
                           List<ModelMessage> messages, ContextSummary context, int step,
                           String workspace, List<ToolDefinition> tools, String instructions, boolean finalAnswerOnly, String reasoningEffort) {
    public ModelRequest(String runId, String model, String scenario, List<ModelMessage> messages, ContextSummary context,
                        int step, String workspace, List<ToolDefinition> tools, String instructions, boolean finalAnswerOnly) {
        this(runId, model, scenario, messages, context, step, workspace, tools, instructions, finalAnswerOnly, "");
    }
    public ModelRequest(String runId, String model, String scenario, List<ModelMessage> messages, ContextSummary context,
                        int step, String workspace, List<ToolDefinition> tools) {
        this(runId, model, scenario, messages, context, step, workspace, tools, "", false);
    }
    public ModelRequest(String runId, String model, String scenario, List<ModelMessage> messages, ContextSummary context) {
        this(runId, model, scenario, messages, context, 1, "", List.of());
    }
    public ModelRequest {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(model);
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(context);
        messages = List.copyOf(messages);
        step = step == 0 ? 1 : step; // V3 JSON snapshots have no step field.
        if (step < 1) throw new IllegalArgumentException("Invalid model step");
        workspace = workspace == null ? "" : workspace;
        tools = tools == null ? List.of() : List.copyOf(tools);
        instructions = instructions == null ? "" : instructions; // Older snapshots have no runtime instructions.
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
        if (!ModelSelection.EFFORTS.contains(reasoningEffort)) throw new IllegalArgumentException("Invalid reasoning effort");
        ModelMessage.validate(messages, true);
    }
}
