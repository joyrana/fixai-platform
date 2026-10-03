package com.fixai.platform.certification.domain.evaluation;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scenario variables and deterministic template resolution.
 *
 * <ul>
 *   <li>{@code ${id:name}} - a deterministic identifier {@code <idPrefix>-<name>}, stable for the scenario execution</li>
 *   <li>{@code ${var:name}} - a value captured by an earlier step; unresolved variables fail fast</li>
 *   <li>{@code ${now}} - current UTC timestamp in FIX format (excluded from deterministic comparisons)</li>
 * </ul>
 */
public final class Variables {

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{(id|var|now)(?::([A-Za-z0-9_.-]+))?}");

    private final String idPrefix;
    private final Map<String, String> captured = new HashMap<>();
    private final java.util.function.Supplier<String> now;

    public Variables(String idPrefix, java.util.function.Supplier<String> now) {
        this.idPrefix = idPrefix;
        this.now = now;
    }

    public void put(String name, String value) {
        captured.put(name, value);
    }

    public Map<String, String> captured() {
        return Map.copyOf(captured);
    }

    public String resolve(String text) {
        if (text == null || !text.contains("${")) {
            return text;
        }
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String kind = matcher.group(1);
            String name = matcher.group(2);
            String replacement = switch (kind) {
                case "id" -> idPrefix + "-" + require(name, text);
                case "var" -> {
                    String value = captured.get(require(name, text));
                    if (value == null) {
                        throw new UnresolvedVariableException(name);
                    }
                    yield value;
                }
                default -> now.get();
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String require(String name, String text) {
        if (name == null) {
            throw new IllegalArgumentException("Template requires a name: " + text);
        }
        return name;
    }

    /** A step referenced a variable that no earlier step captured. */
    public static final class UnresolvedVariableException extends RuntimeException {
        public UnresolvedVariableException(String name) {
            super("Unresolved scenario variable: " + name);
        }
    }
}
