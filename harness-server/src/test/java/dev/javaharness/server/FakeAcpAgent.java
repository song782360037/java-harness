package dev.javaharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Standalone stdio fixture: permission, elicitation, cancellation and intentionally noisy replay. */
public final class FakeAcpAgent {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "fixture-session";

    public static void main(String[] args) throws Exception {
        var stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int prompt = 0;
        boolean loaded = false;
        for (String line; (line = stdin.readLine()) != null; ) {
            JsonNode input = JSON.readTree(line);
            int id = input.path("id").asInt();
            String method = input.path("method").asText();
            switch (method) {
                case "initialize" ->
                    reply(id, Map.of("protocolVersion", 1, "agentCapabilities", Map.of("loadSession", true)));
                case "session/new" -> {
                    reply(id, Map.of("sessionId", SESSION));
                    if (List.of(args).contains("stall")) {
                        java.nio.file.Files.writeString(
                                java.nio.file.Path.of("stalled"),
                                Long.toString(ProcessHandle.current().pid()));
                        Thread.sleep(60_000);
                    }
                }
                case "session/load" -> {
                    if (!SESSION.equals(input.path("params").path("sessionId").asText()))
                        throw new IllegalStateException();
                    loaded = true;
                    text("REPLAY");
                    reply(id, Map.of());
                }
                case "session/prompt" -> {
                    prompt = id;
                    String task = input.path("params")
                            .path("prompt")
                            .path(0)
                            .path("text")
                            .asText();
                    if (task.equals("crash")) {
                        System.err.println("simulated adapter crash");
                        return;
                    }
                    if (task.equals("rpc-error")) {
                        System.out.println(JSON.writeValueAsString(Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                id,
                                "error",
                                Map.of("code", -32603, "message", "upstream quota exceeded"))));
                        continue;
                    }
                    if (task.equals("crlf-blank")) {
                        System.out.print("\r\n");
                        text("crlf-ok");
                        reply(id, Map.of("stopReason", "end_turn"));
                        continue;
                    }
                    if (task.equals("permission"))
                        message(
                                "session/request_permission",
                                "allow?",
                                Map.of(
                                        "sessionId",
                                        SESSION,
                                        "toolCall",
                                        Map.of("title", "Read fixture", "toolCallId", "call-1"),
                                        "options",
                                        List.of(
                                                Map.of("kind", "allow_once", "name", "Allow once", "optionId", "yes"),
                                                Map.of("kind", "reject_once", "name", "Reject", "optionId", "no"),
                                                Map.of(
                                                        "kind",
                                                        "allow_always",
                                                        "name",
                                                        "Always",
                                                        "optionId",
                                                        "forever"))));
                    else if (task.equals("question"))
                        message(
                                "elicitation/create",
                                "ask?",
                                Map.of(
                                        "sessionId",
                                        SESSION,
                                        "mode",
                                        "form",
                                        "message",
                                        "Choose a value",
                                        "requestedSchema",
                                        Map.of(
                                                "type",
                                                "object",
                                                "required",
                                                List.of("choice"),
                                                "properties",
                                                Map.of(
                                                        "choice",
                                                        Map.of("type", "string", "enum", List.of("a", "b"))))));
                    else if (task.equals("wait")) text("waiting");
                    else {
                        message(
                                "session/update",
                                null,
                                Map.of(
                                        "sessionId",
                                        SESSION,
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "tool_call",
                                                "toolCallId",
                                                "t",
                                                "title",
                                                "Fixture tool",
                                                "rawInput",
                                                Map.of("path", "README.md"),
                                                "status",
                                                "completed")));
                        text(task.equals("second") && loaded ? "second:loaded" : task);
                        reply(id, Map.of("stopReason", "end_turn"));
                    }
                }
                case "session/cancel" -> reply(prompt, Map.of("stopReason", "cancelled"));
                case "" -> {
                    String rpc = input.path("id").asText();
                    if (rpc.equals("allow?"))
                        text(input.path("result")
                                .path("outcome")
                                .path("optionId")
                                .asText("cancelled"));
                    if (rpc.equals("ask?"))
                        text(input.path("result").path("content").path("choice").asText("declined"));
                    reply(prompt, Map.of("stopReason", "end_turn"));
                }
                default -> throw new IllegalArgumentException(method);
            }
        }
    }

    private static void text(String value) throws Exception {
        message(
                "session/update",
                null,
                Map.of(
                        "sessionId",
                        SESSION,
                        "update",
                        Map.of(
                                "sessionUpdate",
                                "agent_message_chunk",
                                "content",
                                Map.of("type", "text", "text", value))));
    }

    private static void reply(int id, Object result) throws Exception {
        System.out.println(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result", result)));
    }

    private static void message(String method, String id, Object params) throws Exception {
        var message = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        if (id != null) message.put("id", id);
        message.set("params", JSON.valueToTree(params));
        System.out.println(JSON.writeValueAsString(message));
    }
}
