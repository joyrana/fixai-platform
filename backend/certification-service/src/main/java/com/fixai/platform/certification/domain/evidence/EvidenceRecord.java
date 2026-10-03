package com.fixai.platform.certification.domain.evidence;

import com.fixai.platform.fixcore.FixMessageView;
import java.time.Instant;

/**
 * One item of message-level evidence. Ordinals are strictly increasing within a scenario execution and are the anchor
 * for both live evaluation and offline replay.
 *
 * @param message redacted message for {@link Kind#MESSAGE}; {@code null} for events
 * @param eventText redacted session event text for {@link Kind#EVENT}; {@code null} for messages
 */
public record EvidenceRecord(
        long ordinal, Kind kind, Direction direction, Instant occurredAt, FixMessageView message, String eventText) {

    public enum Kind {
        MESSAGE,
        EVENT
    }

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    public boolean isInboundMessage() {
        return kind == Kind.MESSAGE && direction == Direction.INBOUND;
    }

    public boolean isOutboundMessage() {
        return kind == Kind.MESSAGE && direction == Direction.OUTBOUND;
    }

    public String msgType() {
        return message == null ? null : message.msgType();
    }
}
