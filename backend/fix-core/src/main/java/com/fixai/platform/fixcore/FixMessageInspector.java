package com.fixai.platform.fixcore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import quickfix.DataDictionary;
import quickfix.FieldException;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.InvalidMessage;
import quickfix.Message;
import quickfix.MessageUtils;
import quickfix.field.SessionRejectReason;

/**
 * Structural, dictionary and checksum validation for a single raw FIX message.
 *
 * <p>This is the gate every externally supplied or AI-generated message must pass before it is interpreted as FIX.
 * It never throws for bad input; all problems are returned as {@link Issue}s with stable codes.
 */
public final class FixMessageInspector {

    private final DictionaryRegistry dictionaries;
    private final FixMessageRedactor redactor;

    public FixMessageInspector(DictionaryRegistry dictionaries, FixMessageRedactor redactor) {
        this.dictionaries = dictionaries;
        this.redactor = redactor;
    }

    public FixMessageInspector() {
        this(DictionaryRegistry.shared(), new FixMessageRedactor());
    }

    /** Stable validation issue codes, safe to use in API contracts and evaluation datasets. */
    public enum Code {
        EMPTY_MESSAGE,
        MALFORMED_FIELD,
        BEGIN_STRING_NOT_FIRST,
        UNSUPPORTED_VERSION,
        BODY_LENGTH_NOT_SECOND,
        MSG_TYPE_NOT_THIRD,
        BODY_LENGTH_MISMATCH,
        CHECKSUM_NOT_LAST,
        CHECKSUM_MISMATCH,
        UNKNOWN_MSG_TYPE,
        INVALID_MESSAGE,
        REQUIRED_TAG_MISSING,
        TAG_NOT_DEFINED_FOR_MESSAGE,
        UNDEFINED_TAG,
        TAG_WITHOUT_VALUE,
        VALUE_IS_INCORRECT,
        INCORRECT_DATA_FORMAT,
        TAG_APPEARS_MORE_THAN_ONCE,
        TAG_OUT_OF_ORDER,
        REPEATING_GROUP_ORDER,
        INCORRECT_NUM_IN_GROUP,
        OTHER
    }

    public record Issue(Code code, Integer tag, String detail) {
    }

    public record Result(boolean valid, FixVersion version, String msgType, List<Issue> issues, FixMessageView view) {
        public Result {
            issues = List.copyOf(issues);
        }
    }

    /**
     * Inspects a message. Delimiters may be SOH or {@code |} (normalised to SOH before checksum validation).
     *
     * @param raw message text
     * @param expectedVersion optional version the message must use
     */
    public Result inspect(String raw, Optional<FixVersion> expectedVersion) {
        List<Issue> issues = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            issues.add(new Issue(Code.EMPTY_MESSAGE, null, "Message is empty"));
            return new Result(false, null, null, issues, null);
        }
        String wire = normalise(raw.strip());
        List<String[]> tokens = tokenize(wire, issues);
        if (!issues.isEmpty()) {
            return new Result(false, null, null, issues, null);
        }

        if (tokens.isEmpty() || !"8".equals(tokens.get(0)[0])) {
            issues.add(new Issue(Code.BEGIN_STRING_NOT_FIRST, 8, "BeginString(8) must be the first field"));
            return new Result(false, null, null, issues, null);
        }
        Optional<FixVersion> version = FixVersion.fromBeginString(tokens.get(0)[1]);
        if (version.isEmpty()) {
            issues.add(new Issue(Code.UNSUPPORTED_VERSION, 8, "Unsupported BeginString " + tokens.get(0)[1]));
            return new Result(false, null, null, issues, null);
        }
        if (expectedVersion.isPresent() && expectedVersion.get() != version.get()) {
            issues.add(new Issue(Code.UNSUPPORTED_VERSION, 8,
                    "Expected " + expectedVersion.get().beginString() + " but was " + version.get().beginString()));
        }
        if (tokens.size() < 2 || !"9".equals(tokens.get(1)[0])) {
            issues.add(new Issue(Code.BODY_LENGTH_NOT_SECOND, 9, "BodyLength(9) must be the second field"));
        }
        if (tokens.size() < 3 || !"35".equals(tokens.get(2)[0])) {
            issues.add(new Issue(Code.MSG_TYPE_NOT_THIRD, 35, "MsgType(35) must be the third field"));
        }
        String[] last = tokens.get(tokens.size() - 1);
        if (!"10".equals(last[0])) {
            issues.add(new Issue(Code.CHECKSUM_NOT_LAST, 10, "CheckSum(10) must be the last field"));
        }
        if (issues.stream().anyMatch(i -> i.code() != Code.UNSUPPORTED_VERSION)) {
            return new Result(false, version.get(), null, issues, null);
        }

        checkBodyLengthAndChecksum(wire, tokens, issues);

        FixVersion fixVersion = version.get();
        DataDictionary transport = dictionaries.transport(fixVersion);
        DataDictionary application = dictionaries.application(fixVersion);
        String msgType = tokens.get(2)[1];
        boolean admin = MessageUtils.isAdminMessage(msgType);
        DataDictionary bodyDictionary = admin ? transport : application;
        if (!bodyDictionary.isMsgType(msgType)) {
            issues.add(new Issue(Code.UNKNOWN_MSG_TYPE, 35, "MsgType " + msgType + " is not defined in " + bodyDictionary.getVersion()));
        }

        FixMessageView view = FixMessageView.fromRaw(wire, transport, application, redactor);
        if (issues.stream().anyMatch(i -> i.code() == Code.UNKNOWN_MSG_TYPE)) {
            return new Result(false, fixVersion, msgType, issues, view);
        }

        try {
            Message message = fixVersion.isFixt()
                    ? new Message(wire, transport, application, false)
                    : new Message(wire, transport, false);
            FieldException parseException = message.getException();
            if (parseException != null) {
                issues.add(fromReason(parseException.getSessionRejectReason(), fieldOf(parseException), parseException.getMessage()));
            } else if (fixVersion.isFixt()) {
                if (admin) {
                    transport.validate(message);
                } else {
                    application.validate(message, true);
                }
            } else {
                transport.validate(message);
            }
        } catch (InvalidMessage exception) {
            issues.add(new Issue(Code.INVALID_MESSAGE, null, safeDetail(exception.getMessage())));
        } catch (FieldException exception) {
            issues.add(fromReason(exception.getSessionRejectReason(), fieldOf(exception), exception.getMessage()));
        } catch (IncorrectTagValue exception) {
            issues.add(fromReason(exception.getSessionRejectReason(), exception.getField(), "Value is incorrect (out of range) for this tag"));
        } catch (IncorrectDataFormat exception) {
            issues.add(fromReason(exception.getSessionRejectReason(), exception.getField(), "Incorrect data format for value"));
        } catch (FieldNotFound exception) {
            issues.add(new Issue(Code.REQUIRED_TAG_MISSING, exception.field, "Required tag missing"));
        }

        return new Result(issues.isEmpty(), fixVersion, msgType, issues, view);
    }

    /** Recomputes BodyLength and CheckSum, returning a well-formed wire message. Intended for building fixtures. */
    public static String withComputedLengthAndChecksum(String raw) {
        String wire = normalise(raw.strip());
        int bodyStart = wire.indexOf(FixMessageRedactor.SOH, wire.indexOf("9=")) + 1;
        String head = wire.substring(0, wire.indexOf("9="));
        int checksumIndex = wire.lastIndexOf(FixMessageRedactor.SOH + "10=");
        String body = checksumIndex >= 0 ? wire.substring(bodyStart, checksumIndex + 1) : wire.substring(bodyStart);
        if (!body.endsWith(String.valueOf(FixMessageRedactor.SOH))) {
            body = body + FixMessageRedactor.SOH;
        }
        int length = body.getBytes(StandardCharsets.ISO_8859_1).length;
        String withoutChecksum = head + "9=" + length + FixMessageRedactor.SOH + body;
        return withoutChecksum + "10=" + String.format("%03d", checksum(withoutChecksum)) + FixMessageRedactor.SOH;
    }

    static String normalise(String raw) {
        String wire = raw.indexOf(FixMessageRedactor.SOH) >= 0 ? raw : raw.replace('|', FixMessageRedactor.SOH);
        wire = wire.replace("^A", String.valueOf(FixMessageRedactor.SOH));
        return wire.endsWith(String.valueOf(FixMessageRedactor.SOH)) ? wire : wire + FixMessageRedactor.SOH;
    }

    private static List<String[]> tokenize(String wire, List<Issue> issues) {
        List<String[]> tokens = new ArrayList<>();
        for (String token : wire.split(String.valueOf(FixMessageRedactor.SOH))) {
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq <= 0 || !token.substring(0, eq).chars().allMatch(Character::isDigit)) {
                issues.add(new Issue(Code.MALFORMED_FIELD, null, "Field is not in tag=value form at position " + tokens.size()));
                continue;
            }
            tokens.add(new String[] {token.substring(0, eq), token.substring(eq + 1)});
        }
        return tokens;
    }

    private static void checkBodyLengthAndChecksum(String wire, List<String[]> tokens, List<Issue> issues) {
        int bodyStart = wire.indexOf(FixMessageRedactor.SOH, wire.indexOf(FixMessageRedactor.SOH) + 1) + 1;
        int checksumStart = wire.lastIndexOf(FixMessageRedactor.SOH + "10=") + 1;
        int actualLength = wire.substring(bodyStart, checksumStart).getBytes(StandardCharsets.ISO_8859_1).length;
        String declaredLength = tokens.get(1)[1];
        if (!declaredLength.equals(Integer.toString(actualLength))) {
            issues.add(new Issue(Code.BODY_LENGTH_MISMATCH, 9,
                    "BodyLength declared " + declaredLength + " but computed " + actualLength));
        }
        int computed = checksum(wire.substring(0, checksumStart));
        String declared = tokens.get(tokens.size() - 1)[1];
        if (!declared.equals(String.format("%03d", computed))) {
            issues.add(new Issue(Code.CHECKSUM_MISMATCH, 10,
                    "CheckSum declared " + declared + " but computed " + String.format("%03d", computed)));
        }
    }

    private static int checksum(String text) {
        int sum = 0;
        for (byte b : text.getBytes(StandardCharsets.ISO_8859_1)) {
            sum += b & 0xFF;
        }
        return sum % 256;
    }

    private static Integer fieldOf(FieldException exception) {
        return exception.isFieldSpecified() ? exception.getField() : null;
    }

    private Issue fromReason(int reason, Integer tag, String detail) {
        Code code = switch (reason) {
            case SessionRejectReason.REQUIRED_TAG_MISSING -> Code.REQUIRED_TAG_MISSING;
            case SessionRejectReason.TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE -> Code.TAG_NOT_DEFINED_FOR_MESSAGE;
            case SessionRejectReason.INVALID_TAG_NUMBER, SessionRejectReason.UNDEFINED_TAG -> Code.UNDEFINED_TAG;
            case SessionRejectReason.TAG_SPECIFIED_WITHOUT_A_VALUE -> Code.TAG_WITHOUT_VALUE;
            case SessionRejectReason.VALUE_IS_INCORRECT -> Code.VALUE_IS_INCORRECT;
            case SessionRejectReason.INCORRECT_DATA_FORMAT_FOR_VALUE -> Code.INCORRECT_DATA_FORMAT;
            case SessionRejectReason.TAG_APPEARS_MORE_THAN_ONCE -> Code.TAG_APPEARS_MORE_THAN_ONCE;
            case SessionRejectReason.TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER -> Code.TAG_OUT_OF_ORDER;
            case SessionRejectReason.REPEATING_GROUP_FIELDS_OUT_OF_ORDER -> Code.REPEATING_GROUP_ORDER;
            case SessionRejectReason.INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP -> Code.INCORRECT_NUM_IN_GROUP;
            case SessionRejectReason.INVALID_MSGTYPE -> Code.UNKNOWN_MSG_TYPE;
            default -> Code.OTHER;
        };
        return new Issue(code, tag, safeDetail(detail));
    }

    /** Exception text from QuickFIX/J can echo field values; keep only a bounded, single-line description. */
    private String safeDetail(String detail) {
        if (detail == null) {
            return "";
        }
        String line = redactor.redactRaw(detail).lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
