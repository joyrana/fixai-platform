package com.fixai.platform.fixcore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import quickfix.DataDictionary;

/**
 * Immutable, redacted, framework-neutral representation of one FIX message in wire order.
 *
 * <p>This is the only shape in which FIX content leaves the transport adapters: it is what gets persisted as
 * evidence, rendered in reports and passed to AI tools. Repeating-group members appear in wire order.
 */
public record FixMessageView(
        String beginString,
        String msgType,
        Integer msgSeqNum,
        String senderCompId,
        String targetCompId,
        List<FixField> fields,
        String rawRedacted,
        String sha256) {

    public FixMessageView {
        fields = List.copyOf(fields);
    }

    /** First value of a tag anywhere in the message. */
    public Optional<String> value(int tag) {
        return fields.stream().filter(f -> f.tag() == tag).map(FixField::value).findFirst();
    }

    /** First value of a field by dictionary name (e.g. {@code ClOrdID}) or numeric tag text. */
    public Optional<String> value(String nameOrTag) {
        return fields.stream()
                .filter(f -> f.name().equals(nameOrTag) || Integer.toString(f.tag()).equals(nameOrTag))
                .map(FixField::value)
                .findFirst();
    }

    public boolean has(int tag) {
        return fields.stream().anyMatch(f -> f.tag() == tag);
    }

    /**
     * Builds a view from raw wire text (SOH or {@code |} delimited), labelling fields with dictionary names.
     *
     * @param raw original, unredacted message
     * @param transport session-level dictionary (header/trailer membership)
     * @param application application-level dictionary (field names); may equal {@code transport}
     */
    public static FixMessageView fromRaw(
            String raw, DataDictionary transport, DataDictionary application, FixMessageRedactor redactor) {
        char delimiter = raw.indexOf(FixMessageRedactor.SOH) >= 0 ? FixMessageRedactor.SOH : '|';
        List<FixField> fields = new ArrayList<>();
        String beginString = null;
        String msgType = null;
        Integer msgSeqNum = null;
        String sender = null;
        String target = null;

        int start = 0;
        while (start < raw.length()) {
            int end = raw.indexOf(delimiter, start);
            if (end < 0) {
                end = raw.length();
            }
            String token = raw.substring(start, end);
            start = end + 1;
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            int tag;
            try {
                tag = Integer.parseInt(token.substring(0, eq));
            } catch (NumberFormatException exception) {
                continue;
            }
            String value = token.substring(eq + 1);
            switch (tag) {
                case 8 -> beginString = value;
                case 35 -> msgType = value;
                case 34 -> msgSeqNum = parseIntOrNull(value);
                case 49 -> sender = value;
                case 56 -> target = value;
                default -> {
                    // body field
                }
            }
            FixField.Section section = transport.isHeaderField(tag)
                    ? FixField.Section.HEADER
                    : transport.isTrailerField(tag) ? FixField.Section.TRAILER : FixField.Section.BODY;
            fields.add(new FixField(tag, fieldName(tag, transport, application), value, section));
        }

        return new FixMessageView(
                beginString,
                msgType,
                msgSeqNum,
                sender,
                target,
                redactor.redactFields(fields),
                redactor.redactRaw(raw),
                FixMessageRedactor.sha256(raw));
    }

    private static String fieldName(int tag, DataDictionary transport, DataDictionary application) {
        String name = application.getFieldName(tag);
        if (name == null) {
            name = transport.getFieldName(tag);
        }
        return name == null ? Integer.toString(tag) : name;
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
