package com.fixai.platform.certification.domain.scenario;

import java.util.List;
import java.util.Map;

/**
 * One scenario step. Action steps move the anchor used by subsequent expectations; expectation steps are evaluated by
 * the pure {@code ExpectationEvaluator} both live and during offline replay.
 */
public sealed interface Step {

    String description();

    /** Wait for the Logon handshake to complete. */
    record Logon(String description, long timeoutMillis) implements Step {
    }

    /** Expect the counterparty to refuse the Logon (Logout or disconnect without a completed Logon). */
    record LogonRejected(String description, long timeoutMillis) implements Step {
    }

    /** Send an application or admin message built from dictionary field names; values may contain templates. */
    record Send(String description, String msgType, Map<String, Object> fields) implements Step {
    }

    /**
     * Expect one inbound message.
     *
     * @param match fields that select the message (correlation), e.g. ClOrdID
     * @param assertions field expectations verified on the selected message
     * @param invariants arithmetic relations such as {@code CumQty + LeavesQty == OrderQty}
     * @param capture variables to capture: variable name to field name
     * @param direction INBOUND (counterparty messages, the default) or OUTBOUND (wait for the platform's own engine,
     *        e.g. a gap fill, to be on the wire before continuing)
     */
    record Expect(
            String description,
            com.fixai.platform.certification.domain.evidence.EvidenceRecord.Direction direction,
            String msgType,
            long timeoutMillis,
            Map<String, String> match,
            Map<String, FieldExpectation> assertions,
            List<String> invariants,
            Map<String, String> capture) implements Step {
    }

    /** Expect that no matching inbound message arrives within the window. */
    record ExpectNone(String description, String msgType, long windowMillis, Map<String, String> match) implements Step {
    }

    /** Advance our outbound MsgSeqNum to create a gap; captures the first skipped number into {@code captureAs}. */
    record SkipOutboundSequence(String description, int count, String captureAs) implements Step {
    }

    /** Initiate Logout and wait for the counterparty's Logout. */
    record Logout(String description, long timeoutMillis) implements Step {
    }

    /** Drop the TCP connection without Logout. */
    record Disconnect(String description) implements Step {
    }

    /** Reconnect and wait for Logon, keeping sequence numbers (no reset). */
    record Reconnect(String description, long timeoutMillis) implements Step {
    }

    /** Pause without traffic, e.g. to observe heartbeats. */
    record Pause(String description, long millis) implements Step {
    }
}
