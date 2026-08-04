package com.fixai.platform.adapter.in.fix;

import com.fixai.platform.adapter.in.fix.port.FixSessionEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.SessionID;

@Component
public class LoggingFixSessionEventAdapter implements FixSessionEventPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingFixSessionEventAdapter.class);

    @Override
    public void onLogon(SessionID sessionId) {
        LOGGER.info("FIX logon received for session {}", sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        LOGGER.info("FIX logout received for session {}", sessionId);
    }

    @Override
    public void onHeartbeat(SessionID sessionId, Message message) {
        LOGGER.debug("FIX heartbeat received for session {}: {}", sessionId, message);
    }

    @Override
    public void onTestRequest(SessionID sessionId, Message message) {
        LOGGER.info("FIX test request received for session {}: {}", sessionId, message);
    }

    @Override
    public void onSequenceReset(SessionID sessionId, Message message) {
        LOGGER.info("FIX sequence reset received for session {}: {}", sessionId, message);
    }

    @Override
    public void onResendRequest(SessionID sessionId, Message message) {
        LOGGER.warn("FIX resend request received for session {}: {}", sessionId, message);
    }
}
