package com.fixai.platform.certification.application.port.out;

import com.fixai.platform.certification.domain.evidence.EvidenceLog;
import com.fixai.platform.fixcore.FixSessionSpec;
import java.time.Duration;
import java.util.Map;

/**
 * Outbound port for one short-lived FIX session used by a single scenario execution. Implementations must record every
 * inbound and outbound message (including messages the engine rejects) into the supplied {@link EvidenceLog} in
 * redacted form, and must leave sequence-number management to the FIX engine.
 */
public interface FixTransport extends AutoCloseable {

    void open(FixSessionSpec spec, EvidenceLog evidence);

    boolean awaitLogon(Duration timeout) throws InterruptedException;

    /** True once the counterparty ended a logon attempt (Logout or disconnect) without a completed Logon. */
    boolean awaitLogonRefused(Duration timeout) throws InterruptedException;

    /** Sends a message built from dictionary field names; returns false if the session is not logged on. */
    boolean send(String msgType, Map<String, Object> fields);

    void logout(String reason);

    boolean awaitLogout(Duration timeout) throws InterruptedException;

    void disconnect(String reason);

    /** Advances the next outbound MsgSeqNum by {@code count}; returns the first skipped number. */
    int skipOutboundSequence(int count);

    @Override
    void close();

    /** Creates a fresh transport per scenario execution. */
    interface Factory {
        FixTransport create();
    }
}
