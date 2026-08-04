package com.fixai.platform.fixgateway.application.service;

import com.fixai.platform.fixgateway.application.port.outbound.FixSessionLookupPort;
import java.util.Optional;
import org.springframework.stereotype.Service;
import quickfix.ConfigError;
import quickfix.Initiator;
import quickfix.RuntimeError;
import quickfix.Session;
import quickfix.SessionID;

/**
 * Application service that manages the lifecycle of the FIX initiator session.
 */
@Service
public class SessionManager {

    private final Initiator initiator;
    private final SessionID sessionId;
    private final FixSessionLookupPort fixSessionLookupPort;

    public SessionManager(Initiator initiator, SessionID sessionId, FixSessionLookupPort fixSessionLookupPort) {
        this.initiator = initiator;
        this.sessionId = sessionId;
        this.fixSessionLookupPort = fixSessionLookupPort;
    }

    public synchronized void start() {
        try {
            initiator.start();
        } catch (ConfigError | RuntimeError exception) {
            throw new IllegalStateException("Failed to start FIX initiator for session " + sessionId, exception);
        }
    }

    public synchronized void stop() {
        initiator.stop();
    }

    public boolean isLoggedOn() {
        return initiator.isLoggedOn() && getSession().map(Session::isLoggedOn).orElse(false);
    }

    public Optional<Session> getSession() {
        return fixSessionLookupPort.find(sessionId);
    }
}
