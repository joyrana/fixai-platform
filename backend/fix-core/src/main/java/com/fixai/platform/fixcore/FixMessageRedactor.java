package com.fixai.platform.fixcore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Masks sensitive FIX fields before anything is logged, persisted as evidence or shown to an AI model.
 *
 * <p>The default set covers credentials and opaque data that can carry them. Integrity of the original message is
 * preserved separately through {@link #sha256(String)} of the unredacted wire bytes.
 */
public final class FixMessageRedactor {

    public static final String MASK = "***";
    public static final char SOH = '\u0001';

    /** RawData(96), RawDataLength(95), Username(553), Password(554), NewPassword(925), EncryptedPassword(1402), EncryptedNewPassword(1404), SecureData(91). */
    public static final Set<Integer> DEFAULT_SENSITIVE_TAGS = Set.of(91, 95, 96, 553, 554, 925, 1402, 1404);

    private final Set<Integer> sensitiveTags;

    public FixMessageRedactor() {
        this(Set.of());
    }

    public FixMessageRedactor(Set<Integer> additionalSensitiveTags) {
        Set<Integer> tags = new HashSet<>(DEFAULT_SENSITIVE_TAGS);
        tags.addAll(additionalSensitiveTags);
        this.sensitiveTags = Set.copyOf(tags);
    }

    public boolean isSensitive(int tag) {
        return sensitiveTags.contains(tag);
    }

    public Set<Integer> sensitiveTags() {
        return sensitiveTags;
    }

    /**
     * Returns the message with sensitive values masked and SOH delimiters replaced by {@code |} for display.
     * Accepts SOH or {@code |} delimited input.
     */
    public String redactRaw(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        char delimiter = raw.indexOf(SOH) >= 0 ? SOH : '|';
        StringBuilder out = new StringBuilder(raw.length());
        int start = 0;
        while (start < raw.length()) {
            int end = raw.indexOf(delimiter, start);
            if (end < 0) {
                end = raw.length();
            }
            String token = raw.substring(start, end);
            int eq = token.indexOf('=');
            if (eq > 0 && isSensitive(parseTag(token.substring(0, eq)))) {
                out.append(token, 0, eq + 1).append(MASK);
            } else {
                out.append(token);
            }
            if (end < raw.length()) {
                out.append('|');
            }
            start = end + 1;
        }
        return out.toString();
    }

    public List<FixField> redactFields(List<FixField> fields) {
        return fields.stream()
                .map(f -> isSensitive(f.tag()) ? new FixField(f.tag(), f.name(), MASK, f.section()) : f)
                .toList();
    }

    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static int parseTag(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
