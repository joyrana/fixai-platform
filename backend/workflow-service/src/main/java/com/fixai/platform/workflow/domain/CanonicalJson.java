package com.fixai.platform.workflow.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON serialisation for hashing: object keys sorted by Unicode code point, no insignificant whitespace,
 * numbers in plain decimal form without trailing zeros, strings escaped per RFC 8259. Accepts the generic object model
 * produced by Jackson ({@code Map}, {@code List}, {@code String}, {@code Number}, {@code Boolean}, {@code null}).
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b);
            case BigDecimal d -> out.append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Short s -> out.append(s);
            case Number n -> append(out, new BigDecimal(n.toString()));
            case Map<?, ?> map -> {
                TreeMap<String, Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(out, entry.getKey());
                    out.append(':');
                    append(out, entry.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    append(out, list.get(i));
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON value type " + value.getClass().getSimpleName());
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
