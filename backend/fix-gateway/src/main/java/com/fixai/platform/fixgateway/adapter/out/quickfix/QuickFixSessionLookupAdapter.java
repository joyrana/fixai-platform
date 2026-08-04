package com.fixai.platform.fixgateway.adapter.out.quickfix;

import com.fixai.platform.fixgateway.application.port.outbound.FixSessionLookupPort;
import java.util.Optional;
import org.springframework.stereotype.Component;
import quickfix.Session;
import quickfix.SessionID;

/**
 * QuickFIX/J-backed session lookup adapter.
 */
@Component
public class QuickFixSessionLookupAdapter implements FixSessionLookupPort {

    @Override
    public Optional<Session> find(SessionID sessionId) {
        return Optional.ofNullable(Session.lookupSession(sessionId));
    }
}
