package com.fixai.platform.fixgateway.application.port.outbound;

import java.util.Optional;
import quickfix.Session;
import quickfix.SessionID;

/**
 * Outbound port for resolving a QuickFIX/J session from the runtime registry.
 */
public interface FixSessionLookupPort {

    Optional<Session> find(SessionID sessionId);
}
