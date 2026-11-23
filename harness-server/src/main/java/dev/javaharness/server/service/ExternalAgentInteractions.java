package dev.javaharness.server.service;

import static dev.javaharness.adapters.acp.AcpProcess.JSON;
import static dev.javaharness.server.service.ExternalAgentCatalog.write;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** Records external permission requests and questions without granting native Harness tool authority. */
@Service
@DependsOnDatabaseInitialization
public class ExternalAgentInteractions {
    public record Choice(String id, String name, boolean allow) {}

    public record Interaction(
            String id,
            String runId,
            String kind,
            String title,
            String details,
            JsonNode choices,
            JsonNode schema,
            String status,
            JsonNode answer) {}

    private static final RowMapper<Interaction> MAPPER = (rs, row) -> new Interaction(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("interaction_kind"),
            rs.getString("title"),
            rs.getString("details"),
            parse(rs.getString("choices")),
            parse(rs.getString("form_schema")),
            rs.getString("status"),
            parse(rs.getString("answer")));
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;
    private final ConcurrentHashMap<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    public ExternalAgentInteractions(JdbcTemplate jdbc, SessionStore sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        jdbc.update("UPDATE harness_external_interaction SET status='CANCELLED' WHERE status='PENDING'");
    }

    /** Read the durable interaction history for a run. */
    public List<Interaction> list(String runId) {
        return jdbc.query("""
                SELECT * FROM harness_external_interaction WHERE run_id=? ORDER BY created_at,id
                """, MAPPER, runId);
    }

    /** Publish a pending request to the existing run event stream. */
    String open(String runId, String kind, String title, String details, List<Choice> choices, JsonNode schema) {
        synchronized (sessions) {
            if (sessions.run(runId).status() != RunStatus.RUNNING || list(runId).size() >= 40) return null;
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO harness_external_interaction(id,run_id,interaction_kind,title,details,choices,form_schema,status,answer)
                    VALUES (?,?,?,?,?,?,?,'PENDING','null')
                    """, id, runId, kind, clip(title, 500), clip(details, 16000), write(choices), write(schema));
            sessions.questionsUpdated(runId);
            return id;
        }
    }

    /** Accept only options from the original request and only while its run is active. */
    public Interaction answer(String runId, String id, String action, JsonNode value) {
        synchronized (sessions) {
            Interaction current = get(id);
            if (!current.runId().equals(runId)) throw new HarnessException("NOT_FOUND", "外部智能体请求不存在");
            if (!current.status().equals("PENDING") || sessions.run(runId).status() != RunStatus.RUNNING)
                throw new HarnessException("CONFLICT", "该请求已失效");
            Object result;
            if ("decline".equals(action)) {
                result = current.kind().equals("permission")
                        ? Map.of("outcome", Map.of("outcome", "cancelled"))
                        : Map.of("action", "decline");
            } else if ("accept".equals(action) && value != null) {
                if (current.kind().equals("permission")) {
                    if (!value.isTextual() || !hasChoice(current.choices(), value.asText())) throw invalid();
                    result = Map.of("outcome", Map.of("outcome", "selected", "optionId", value.asText()));
                } else {
                    if (!validForm(current.schema(), value)) throw invalid();
                    result = Map.of("action", "accept", "content", value);
                }
            } else throw invalid();
            jdbc.update(
                    "UPDATE harness_external_interaction SET status=?,answer=? WHERE id=?",
                    "decline".equals(action) ? "DECLINED" : "ANSWERED",
                    write(result),
                    id);
            revisions.computeIfAbsent(runId, ignored -> new AtomicLong()).incrementAndGet();
            sessions.questionsUpdated(runId);
            return get(id);
        }
    }

    /** Lock-free counter incremented whenever a decision is persisted for this run. */
    long revision(String runId) {
        var counter = revisions.get(runId);
        return counter == null ? 0 : counter.get();
    }

    /** Release per-run watch state after the run finishes. */
    void forget(String runId) {
        revisions.remove(runId);
    }

    /** Read a single request by its host-issued ID. */
    Interaction get(String id) {
        var rows = jdbc.query("SELECT * FROM harness_external_interaction WHERE id=?", MAPPER, id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "外部智能体请求不存在");
        return rows.getFirst();
    }

    /** Expire pending interactions when the connection can no longer receive a decision. */
    void expire(String runId) {
        synchronized (sessions) {
            if (jdbc.update(
                            "UPDATE harness_external_interaction SET status='CANCELLED' WHERE run_id=? AND status='PENDING'",
                            runId)
                    > 0) {
                revisions.computeIfAbsent(runId, ignored -> new AtomicLong()).incrementAndGet();
                sessions.questionsUpdated(runId);
            }
        }
    }

    /** Advertise form support for bounded scalar fields; decline unsupported schema features. */
    static boolean supportedForm(JsonNode schema) {
        JsonNode fields = schema.path("properties");
        if (!"object".equals(schema.path("type").asText())
                || !fields.isObject()
                || fields.size() > 12
                || schema.has("allOf")
                || schema.has("anyOf")
                || schema.has("oneOf")
                || schema.has("not")) return false;
        if (schema.has("required")) {
            if (!schema.path("required").isArray()) return false;
            for (JsonNode key : schema.path("required"))
                if (!key.isTextual() || !fields.has(key.asText())) return false;
        }
        for (var field : fields.properties()) {
            JsonNode s = field.getValue();
            if (field.getKey().length() > 80
                    || !Set.of("string", "boolean", "number", "integer")
                            .contains(s.path("type").asText())
                    || s.has("format")
                    || s.has("pattern")
                    || s.has("oneOf")
                    || s.has("anyOf")
                    || s.has("allOf")
                    || s.has("not")
                    || s.has("const")
                    || s.has("multipleOf")
                    || s.has("exclusiveMinimum")
                    || s.has("exclusiveMaximum")
                    || s.path("enum").size() > 30) return false;
            if (s.has("minimum") && !s.path("minimum").isNumber()) return false;
            if (s.has("maximum") && !s.path("maximum").isNumber()) return false;
            if (s.has("minimum")
                    && s.has("maximum")
                    && s.path("minimum")
                                    .decimalValue()
                                    .compareTo(s.path("maximum").decimalValue())
                            > 0) return false;
            if (s.has("minLength")
                    && (!s.path("minLength").isIntegralNumber()
                            || s.path("minLength").asInt() < 0)) return false;
            if (s.has("maxLength")
                    && (!s.path("maxLength").isIntegralNumber()
                            || s.path("maxLength").asInt() < 0)) return false;
            if (s.has("minLength")
                    && s.has("maxLength")
                    && s.path("minLength").asInt() > s.path("maxLength").asInt()) return false;
            if (s.has("enum")
                    && (!s.path("enum").isArray()
                            || !"string".equals(s.path("type").asText()))) return false;
            for (JsonNode choice : s.path("enum"))
                if (!choice.isTextual() || choice.asText().length() > 200) return false;
        }
        return true;
    }

    private static boolean validForm(JsonNode schema, JsonNode value) {
        if (!value.isObject() || value.size() > 12) return false;
        var required = new HashSet<String>();
        schema.path("required").forEach(key -> required.add(key.asText()));
        for (var field : value.properties()) {
            required.remove(field.getKey());
            JsonNode spec = schema.path("properties").path(field.getKey());
            JsonNode answer = field.getValue();
            boolean valid = switch (spec.path("type").asText()) {
                case "boolean" -> answer.isBoolean();
                case "string" ->
                    answer.isTextual()
                            && answer.asText().length()
                                    <= Math.min(2000, spec.path("maxLength").asInt(2000))
                            && answer.asText().length()
                                    >= spec.path("minLength").asInt(0);
                case "integer", "number" ->
                    answer.isNumber()
                            && (answer.isIntegralNumber()
                                    || spec.path("type").asText().equals("number"))
                            && (!spec.has("minimum")
                                    || answer.decimalValue()
                                                    .compareTo(
                                                            spec.path("minimum").decimalValue())
                                            >= 0)
                            && (!spec.has("maximum")
                                    || answer.decimalValue()
                                                    .compareTo(
                                                            spec.path("maximum").decimalValue())
                                            <= 0);
                default -> false;
            };
            if (!valid) return false;
            if (spec.has("enum")) {
                boolean found = false;
                for (JsonNode item : spec.path("enum")) if (item.equals(answer)) found = true;
                if (!found) return false;
            }
        }
        return required.isEmpty();
    }

    private static boolean hasChoice(JsonNode choices, String id) {
        for (var choice : choices) if (choice.path("id").asText().equals(id)) return true;
        return false;
    }

    static String clip(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end);
    }

    private static JsonNode parse(String value) throws java.sql.SQLException {
        try {
            return JSON.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new java.sql.SQLException("Invalid stored ACP interaction", error);
        }
    }

    private static HarnessException invalid() {
        return new HarnessException("INVALID", "请选择有效选项或填写符合要求的回答");
    }
}
