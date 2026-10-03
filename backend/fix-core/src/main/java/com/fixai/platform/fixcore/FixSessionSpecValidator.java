package com.fixai.platform.fixcore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates a {@link FixSessionSpec} before it is approved or started. Returns violations rather than throwing so
 * callers can present every problem at once.
 */
public final class FixSessionSpecValidator {

    /** Printable ASCII without SOH, '=' or '|', 1-64 chars: safe in FIX headers and filesystem store names. */
    private static final Pattern COMP_ID = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-:\\[\\]]{1,253}");

    private final DictionaryRegistry dictionaries;

    public FixSessionSpecValidator(DictionaryRegistry dictionaries) {
        this.dictionaries = dictionaries;
    }

    public FixSessionSpecValidator() {
        this(DictionaryRegistry.shared());
    }

    public record Violation(String field, String code, String message) {
    }

    public List<Violation> validate(FixSessionSpec spec) {
        List<Violation> violations = new ArrayList<>();
        compId(violations, "senderCompId", spec.senderCompId());
        compId(violations, "targetCompId", spec.targetCompId());
        if (spec.senderCompId() != null && spec.senderCompId().equals(spec.targetCompId())) {
            violations.add(new Violation("targetCompId", "SAME_COMP_IDS", "SenderCompID and TargetCompID must differ"));
        }
        if (spec.role() == FixSessionSpec.Role.INITIATOR) {
            if (spec.host() == null || !HOST.matcher(spec.host()).matches()) {
                violations.add(new Violation("host", "INVALID_HOST", "Initiator requires a valid host name or address"));
            }
        }
        if (spec.port() < 1 || spec.port() > 65535) {
            violations.add(new Violation("port", "INVALID_PORT", "Port must be between 1 and 65535"));
        }
        if (spec.heartbeatIntervalSeconds() < 1 || spec.heartbeatIntervalSeconds() > 300) {
            violations.add(new Violation("heartbeatIntervalSeconds", "INVALID_HEARTBEAT",
                    "HeartBtInt must be between 1 and 300 seconds"));
        }
        if (spec.reconnectIntervalSeconds() < 1 || spec.reconnectIntervalSeconds() > 3600) {
            violations.add(new Violation("reconnectIntervalSeconds", "INVALID_RECONNECT",
                    "Reconnect interval must be between 1 and 3600 seconds"));
        }
        if (spec.logonTimeoutSeconds() < 1 || spec.logonTimeoutSeconds() > 120) {
            violations.add(new Violation("logonTimeoutSeconds", "INVALID_LOGON_TIMEOUT",
                    "Logon timeout must be between 1 and 120 seconds"));
        }
        if (spec.storeType() == FixSessionSpec.StoreType.FILE && (spec.storePath() == null || spec.storePath().isBlank())) {
            violations.add(new Violation("storePath", "STORE_PATH_REQUIRED", "File store requires a store path"));
        }
        if (spec.customDictionaryPath() != null && !spec.customDictionaryPath().isBlank()) {
            Path path = Path.of(spec.customDictionaryPath());
            if (!Files.isRegularFile(path)) {
                violations.add(new Violation("customDictionaryPath", "DICTIONARY_NOT_FOUND", "Dictionary file does not exist"));
            } else {
                try {
                    String dictionaryVersion = dictionaries.file(path).getVersion();
                    String expected = spec.version().isFixt() ? "FIX.5.0SP2" : spec.version().beginString();
                    if (!expected.equals(dictionaryVersion)) {
                        violations.add(new Violation("customDictionaryPath", "DICTIONARY_VERSION_MISMATCH",
                                "Dictionary is " + dictionaryVersion + " but session uses " + expected));
                    }
                } catch (DictionaryRegistry.FixDictionaryException exception) {
                    violations.add(new Violation("customDictionaryPath", "DICTIONARY_INVALID", "Dictionary could not be parsed"));
                }
            }
        }
        return violations;
    }

    private static void compId(List<Violation> violations, String field, String value) {
        if (value == null || !COMP_ID.matcher(value).matches()) {
            violations.add(new Violation(field, "INVALID_COMP_ID",
                    field + " must be 1-64 characters of letters, digits, '.', '_' or '-'"));
        }
    }
}
