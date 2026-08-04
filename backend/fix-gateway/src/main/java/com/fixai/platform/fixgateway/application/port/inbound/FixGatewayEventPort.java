package com.fixai.platform.fixgateway.application.port.inbound;

import quickfix.Message;
import quickfix.SessionID;

/**
 * Application port for FIX session and message lifecycle events.
 */
public interface FixGatewayEventPort {

    enum Direction {
        INBOUND,
        OUTBOUND
    }

    void onSessionCreated(SessionID sessionId);

    void onLogon(SessionID sessionId);

    void onLogout(SessionID sessionId);

    void onHeartbeat(SessionID sessionId, Message message, Direction direction);

    void onReject(SessionID sessionId, Message message, Direction direction);

    void onSequenceReset(SessionID sessionId, Message message, Direction direction);

    void onResendRequest(SessionID sessionId, Message message, Direction direction);

    void onAdministrativeMessage(SessionID sessionId, Message message, Direction direction);

    void onApplicationMessage(SessionID sessionId, Message message, Direction direction);

    void onUnsupportedAdminMessage(SessionID sessionId, Message message, String msgType, Direction direction);

    void onUnsupportedApplicationMessage(SessionID sessionId, Message message, String msgType, Direction direction);

    void onProcessingError(SessionID sessionId, Message message, String stage, Exception exception);
}
