package com.fixai.platform.certification.adapter.out.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.scenario.FieldExpectation;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.ScenarioCategory;
import com.fixai.platform.certification.domain.scenario.Step;
import com.fixai.platform.certification.domain.scenario.Suite;
import com.fixai.platform.fixcore.FixVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict YAML parser for scenario and suite definitions. Unknown keys are errors so that typos cannot silently weaken
 * a certification check.
 */
public final class ScenarioYamlParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final long DEFAULT_TIMEOUT_MS = 5_000;

    public Scenario parseScenario(String source, String content) {
        JsonNode root = read(source, content);
        allowOnly(source, root, "id", "version", "title", "description", "category", "fixVersions", "tags", "mandatory",
                "session", "allowInboundSessionRejects", "steps");
        Set<FixVersion> versions = new LinkedHashSet<>();
        required(source, root, "fixVersions").forEach(v -> versions.add(FixVersion.parse(v.asText())));
        Set<String> tags = new LinkedHashSet<>();
        root.path("tags").forEach(t -> tags.add(t.asText()));
        List<Step> steps = new ArrayList<>();
        int index = 0;
        for (JsonNode stepNode : required(source, root, "steps")) {
            steps.add(step(source + " step " + (++index), stepNode));
        }
        return new Scenario(
                required(source, root, "id").asText(),
                required(source, root, "version").asInt(),
                required(source, root, "title").asText(),
                root.path("description").asText(""),
                ScenarioCategory.valueOf(required(source, root, "category").asText()),
                versions,
                tags,
                root.path("mandatory").asBoolean(true),
                session(source, root.get("session")),
                root.path("allowInboundSessionRejects").asBoolean(false),
                steps);
    }

    public Suite parseSuite(String source, String content) {
        JsonNode root = read(source, content);
        allowOnly(source, root, "id", "title", "description", "scenarios");
        List<String> ids = new ArrayList<>();
        required(source, root, "scenarios").forEach(n -> ids.add(n.asText()));
        return new Suite(required(source, root, "id").asText(), required(source, root, "title").asText(),
                root.path("description").asText(""), ids);
    }

    private Scenario.SessionOverrides session(String source, JsonNode node) {
        if (node == null || node.isNull()) {
            return Scenario.SessionOverrides.NONE;
        }
        allowOnly(source + " session", node, "heartbeatIntervalSeconds", "resetOnLogon", "targetCompIdSuffix");
        return new Scenario.SessionOverrides(
                node.has("heartbeatIntervalSeconds") ? node.get("heartbeatIntervalSeconds").asInt() : null,
                node.has("resetOnLogon") ? node.get("resetOnLogon").asBoolean() : null,
                node.has("targetCompIdSuffix") ? node.get("targetCompIdSuffix").asText() : null);
    }

    private Step step(String source, JsonNode node) {
        if (!node.isObject() || node.size() != 1) {
            throw new ScenarioDefinitionException(source + ": each step must be a single-key mapping");
        }
        String type = node.fieldNames().next();
        JsonNode body = node.get(type);
        String description = body.path("description").asText(type);
        return switch (type) {
            case "logon" -> {
                allowOnly(source, body, "description", "timeoutMs");
                yield new Step.Logon(description, body.path("timeoutMs").asLong(10_000));
            }
            case "logonRejected" -> {
                allowOnly(source, body, "description", "timeoutMs");
                yield new Step.LogonRejected(description, body.path("timeoutMs").asLong(10_000));
            }
            case "send" -> {
                allowOnly(source, body, "description", "msgType", "fields");
                yield new Step.Send(description, required(source, body, "msgType").asText(), fields(body.path("fields")));
            }
            case "expect" -> {
                allowOnly(source, body, "description", "direction", "msgType", "timeoutMs", "match", "assert", "invariants", "capture");
                List<String> invariants = new ArrayList<>();
                body.path("invariants").forEach(i -> invariants.add(i.asText()));
                EvidenceRecord.Direction direction = switch (body.path("direction").asText("inbound")) {
                    case "inbound" -> EvidenceRecord.Direction.INBOUND;
                    case "outbound" -> EvidenceRecord.Direction.OUTBOUND;
                    default -> throw new ScenarioDefinitionException(source + ": direction must be inbound or outbound");
                };
                yield new Step.Expect(description, direction, required(source, body, "msgType").asText(),
                        body.path("timeoutMs").asLong(DEFAULT_TIMEOUT_MS), strings(body.path("match")),
                        assertions(source, body.path("assert")), invariants, strings(body.path("capture")));
            }
            case "expectNone" -> {
                allowOnly(source, body, "description", "msgType", "windowMs", "match");
                yield new Step.ExpectNone(description, required(source, body, "msgType").asText(),
                        body.path("windowMs").asLong(1_000), strings(body.path("match")));
            }
            case "skipOutboundSequence" -> {
                allowOnly(source, body, "description", "count", "captureAs");
                int count = required(source, body, "count").asInt();
                if (count < 1 || count > 1000) {
                    throw new ScenarioDefinitionException(source + ": count must be 1..1000");
                }
                yield new Step.SkipOutboundSequence(description, count, body.path("captureAs").asText(null));
            }
            case "logout" -> {
                allowOnly(source, body, "description", "timeoutMs");
                yield new Step.Logout(description, body.path("timeoutMs").asLong(DEFAULT_TIMEOUT_MS));
            }
            case "disconnect" -> {
                allowOnly(source, body, "description");
                yield new Step.Disconnect(description);
            }
            case "reconnect" -> {
                allowOnly(source, body, "description", "timeoutMs");
                yield new Step.Reconnect(description, body.path("timeoutMs").asLong(15_000));
            }
            case "pause" -> {
                allowOnly(source, body, "description", "ms");
                long ms = required(source, body, "ms").asLong();
                if (ms < 0 || ms > 60_000) {
                    throw new ScenarioDefinitionException(source + ": pause must be 0..60000 ms");
                }
                yield new Step.Pause(description, ms);
            }
            default -> throw new ScenarioDefinitionException(source + ": unknown step type '" + type + "'");
        };
    }

    private Map<String, FieldExpectation> assertions(String source, JsonNode node) {
        Map<String, FieldExpectation> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.put(entry.getKey(), expectation(source + " assert " + entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private FieldExpectation expectation(String source, JsonNode node) {
        if (!node.isObject() || node.size() != 1) {
            throw new ScenarioDefinitionException(source + ": expectation must have exactly one check");
        }
        String kind = node.fieldNames().next();
        JsonNode value = node.get(kind);
        return switch (kind) {
            case "equals" -> {
                if (value.isObject()) {
                    Map<FixVersion, String> byVersion = new EnumMap<>(FixVersion.class);
                    String fallback = null;
                    Iterator<Map.Entry<String, JsonNode>> it = value.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        if ("default".equals(e.getKey())) {
                            fallback = e.getValue().asText();
                        } else {
                            byVersion.put(FixVersion.parse(e.getKey()), e.getValue().asText());
                        }
                    }
                    if (fallback != null) {
                        for (FixVersion version : FixVersion.values()) {
                            byVersion.putIfAbsent(version, fallback);
                        }
                    }
                    yield FieldExpectation.equalsByVersion(byVersion);
                }
                yield FieldExpectation.equalsValue(value.asText());
            }
            case "notEquals" -> FieldExpectation.notEquals(value.asText());
            case "present" -> value.asBoolean() ? FieldExpectation.present() : FieldExpectation.absent();
            case "oneOf" -> {
                List<String> values = new ArrayList<>();
                value.forEach(v -> values.add(v.asText()));
                yield FieldExpectation.oneOf(values);
            }
            case "matches" -> FieldExpectation.matches(value.asText());
            case "gt" -> FieldExpectation.numeric(FieldExpectation.Comparison.GT, new BigDecimal(value.asText()));
            case "gte" -> FieldExpectation.numeric(FieldExpectation.Comparison.GTE, new BigDecimal(value.asText()));
            case "lt" -> FieldExpectation.numeric(FieldExpectation.Comparison.LT, new BigDecimal(value.asText()));
            case "lte" -> FieldExpectation.numeric(FieldExpectation.Comparison.LTE, new BigDecimal(value.asText()));
            case "numericEquals" -> FieldExpectation.numeric(FieldExpectation.Comparison.EQ, new BigDecimal(value.asText()));
            default -> throw new ScenarioDefinitionException(source + ": unknown check '" + kind + "'");
        };
    }

    private Map<String, Object> fields(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isArray()) {
                List<Map<String, Object>> group = new ArrayList<>();
                e.getValue().forEach(entry -> group.add(fields(entry)));
                result.put(e.getKey(), group);
            } else {
                result.put(e.getKey(), e.getValue().asText());
            }
        }
        return result;
    }

    private static Map<String, String> strings(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        return result;
    }

    private static JsonNode read(String source, String content) {
        try {
            JsonNode node = YAML.readTree(content);
            if (node == null || !node.isObject()) {
                throw new ScenarioDefinitionException(source + ": document must be a mapping");
            }
            return node;
        } catch (IOException exception) {
            throw new ScenarioDefinitionException(source + ": invalid YAML - " + exception.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static JsonNode required(String source, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw new ScenarioDefinitionException(source + ": missing required key '" + key + "'");
        }
        return value;
    }

    private static void allowOnly(String source, JsonNode node, String... keys) {
        Set<String> allowed = Set.of(keys);
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new ScenarioDefinitionException(source + ": unknown key '" + name + "'");
            }
        });
    }

    /** Raised for any invalid scenario or suite definition. */
    public static final class ScenarioDefinitionException extends RuntimeException {
        public ScenarioDefinitionException(String message) {
            super(message);
        }
    }
}
