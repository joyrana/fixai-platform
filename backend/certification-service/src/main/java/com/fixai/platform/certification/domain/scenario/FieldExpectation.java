package com.fixai.platform.certification.domain.scenario;

import com.fixai.platform.fixcore.FixVersion;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Expectation on a single field. Exactly one kind of check is populated. Expected values may be version-specific
 * ({@code byVersion}) and may contain templates resolved at evaluation time.
 */
public record FieldExpectation(
        Kind kind,
        String value,
        Map<FixVersion, String> byVersion,
        List<String> oneOf,
        String pattern,
        Comparison comparison,
        BigDecimal number) {

    public enum Kind {
        EQUALS,
        NOT_EQUALS,
        PRESENT,
        ABSENT,
        ONE_OF,
        MATCHES,
        NUMERIC
    }

    public enum Comparison {
        GT,
        GTE,
        LT,
        LTE,
        EQ
    }

    public static FieldExpectation equalsValue(String value) {
        return new FieldExpectation(Kind.EQUALS, value, Map.of(), List.of(), null, null, null);
    }

    public static FieldExpectation equalsByVersion(Map<FixVersion, String> byVersion) {
        return new FieldExpectation(Kind.EQUALS, null, Map.copyOf(byVersion), List.of(), null, null, null);
    }

    public static FieldExpectation notEquals(String value) {
        return new FieldExpectation(Kind.NOT_EQUALS, value, Map.of(), List.of(), null, null, null);
    }

    public static FieldExpectation present() {
        return new FieldExpectation(Kind.PRESENT, null, Map.of(), List.of(), null, null, null);
    }

    public static FieldExpectation absent() {
        return new FieldExpectation(Kind.ABSENT, null, Map.of(), List.of(), null, null, null);
    }

    public static FieldExpectation oneOf(List<String> values) {
        return new FieldExpectation(Kind.ONE_OF, null, Map.of(), List.copyOf(values), null, null, null);
    }

    public static FieldExpectation matches(String regex) {
        return new FieldExpectation(Kind.MATCHES, null, Map.of(), List.of(), regex, null, null);
    }

    public static FieldExpectation numeric(Comparison comparison, BigDecimal number) {
        return new FieldExpectation(Kind.NUMERIC, null, Map.of(), List.of(), null, comparison, number);
    }

    /** Expected scalar for the given version, falling back to {@link #value()}. */
    public String expectedFor(FixVersion version) {
        if (byVersion != null && byVersion.containsKey(version)) {
            return byVersion.get(version);
        }
        return value;
    }

    /** Human-readable expectation used in reports. */
    public String describe(FixVersion version) {
        return switch (kind) {
            case EQUALS -> "== " + expectedFor(version);
            case NOT_EQUALS -> "!= " + value;
            case PRESENT -> "present";
            case ABSENT -> "absent";
            case ONE_OF -> "one of " + oneOf;
            case MATCHES -> "matches /" + pattern + "/";
            case NUMERIC -> comparison.name().toLowerCase() + " " + number.toPlainString();
        };
    }
}
