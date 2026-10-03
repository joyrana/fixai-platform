package com.fixai.platform.fixcore;

/**
 * A single tag/value pair in wire order.
 *
 * @param tag FIX tag number
 * @param name dictionary field name, or the tag number as text when the dictionary does not define it
 * @param value field value, already redacted when produced by {@link FixMessageRedactor}
 * @param section header, body, or trailer
 */
public record FixField(int tag, String name, String value, Section section) {

    public enum Section {
        HEADER,
        BODY,
        TRAILER
    }
}
