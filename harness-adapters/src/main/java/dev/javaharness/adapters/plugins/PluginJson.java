package dev.javaharness.adapters.plugins;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.HarnessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Bounded JSON and the deliberately small, enforced schema subset shared by plugin inputs. */
public final class PluginJson {
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> KEYS = Set.of(
            "type",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "enum",
            "description",
            "title",
            "default",
            "minimum",
            "maximum",
            "minLength",
            "maxLength",
            "minItems",
            "maxItems",
            "$schema");

    private PluginJson() {}

    /** Parse one bounded JSON value without accepting duplicate fields or trailing values. */
    public static JsonNode parse(String value) {
        if (value == null || value.length() > 1_048_576) throw invalid("JSON 内容过大");
        try {
            return JSON.readTree(value);
        } catch (Exception error) {
            throw invalid("JSON 格式不正确");
        }
    }

    /** Encode data without exposing implementation exceptions. */
    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("无法保存插件记录");
        }
    }

    /** Stable SHA-256 for package and call identities. */
    public static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** Canonicalize every object key before persisting identities across JVM restarts. */
    public static String fingerprint(Object value) {
        return digest(canonical(value));
    }
    /** Deterministic JSON bytes for host-generated immutable packages, including nested object keys. */
    public static String canonical(Object value) {
        return write(sorted(JSON.valueToTree(value)));
    }

    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var object = JSON.createObjectNode();
            var keys = new TreeSet<String>();
            value.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) object.set(key, sorted(value.get(key)));
            return object;
        }
        if (value.isArray()) {
            var array = JSON.createArrayNode();
            for (JsonNode item : value) array.add(sorted(item));
            return array;
        }
        return value;
    }

    /** Reject schema constructs that this host cannot actually enforce. */
    public static void schema(JsonNode schema) {
        schema(schema, 0);
    }

    private static void schema(JsonNode schema, int depth) {
        if (depth > 12 || !schema.isObject() || schema.size() > 32) throw invalid("Schema 层级或结构不支持");
        schema.fieldNames().forEachRemaining(key -> {
            if (!KEYS.contains(key)) throw invalid("不支持的 Schema 字段：" + key);
        });
        String type = schema.path("type").asText();
        if (!Set.of("object", "array", "string", "integer", "number", "boolean", "null")
                .contains(type)) throw invalid("Schema 必须声明受支持的 type");
        if (schema.has("enum")
                && (!schema.get("enum").isArray()
                        || schema.get("enum").isEmpty()
                        || schema.get("enum").size() > 100)) throw invalid("enum 不正确");
        for (String key : List.of("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems"))
            if (schema.has(key) && !schema.get(key).isNumber()) throw invalid("Schema 数值约束不正确");
        if (type.equals("object")) {
            JsonNode properties = schema.path("properties");
            if (!properties.isMissingNode() && (!properties.isObject() || properties.size() > 100))
                throw invalid("properties 不正确");
            properties.elements().forEachRemaining(child -> schema(child, depth + 1));
            if (schema.has("additionalProperties")
                    && !schema.get("additionalProperties").isBoolean()) throw invalid("additionalProperties 仅支持布尔值");
            if (schema.has("required")) {
                if (!schema.get("required").isArray()) throw invalid("required 必须为数组");
                for (JsonNode key : schema.get("required"))
                    if (!key.isTextual() || !properties.has(key.asText())) throw invalid("required 引用了不存在的字段");
            }
        } else if (type.equals("array")) {
            if (!schema.has("items")) throw invalid("数组必须声明 items");
            schema(schema.get("items"), depth + 1);
        }
    }

    /** Enforce the supported schema before executing any plugin operation. */
    public static void validate(JsonNode schema, JsonNode value) {
        validate(schema, value, "$", 0);
    }

    private static void validate(JsonNode schema, JsonNode value, String path, int depth) {
        if (depth > 12 || value == null) throw invalid("参数层级不正确");
        boolean valid = switch (schema.path("type").asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
        if (!valid) throw invalid(path + " 参数类型不正确");
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode item : schema.get("enum")) if (item.equals(value)) found = true;
            if (!found) throw invalid(path + " 不在允许值中");
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required"))
                if (!value.has(required.asText())) throw invalid(path + " 缺少字段 " + required.asText());
            value.fields().forEachRemaining(field -> {
                JsonNode child = schema.path("properties").get(field.getKey());
                if (child != null) validate(child, field.getValue(), path + "." + field.getKey(), depth + 1);
                else if (!schema.path("additionalProperties").asBoolean(true)) throw invalid(path + " 包含额外字段");
            });
        }
        if (value.isArray()) for (JsonNode item : value) validate(schema.get("items"), item, path + "[]", depth + 1);
        if (value.isTextual()) range(schema, value.asText().length(), "minLength", "maxLength", path);
        if (value.isArray()) range(schema, value.size(), "minItems", "maxItems", path);
        if (value.isNumber()) {
            if (schema.has("minimum")
                            && value.decimalValue()
                                            .compareTo(schema.get("minimum").decimalValue())
                                    < 0
                    || schema.has("maximum")
                            && value.decimalValue()
                                            .compareTo(schema.get("maximum").decimalValue())
                                    > 0) throw invalid(path + " 超过数值范围");
        }
    }

    private static void range(JsonNode schema, int size, String min, String max, String path) {
        if (schema.has(min) && size < schema.get(min).asInt()
                || schema.has(max) && size > schema.get(max).asInt()) throw invalid(path + " 超过长度范围");
    }

    public static HarnessException invalid(String message) {
        return new HarnessException("INVALID", message);
    }
}
