package com.fixai.platform.fixgateway.application.service;

import com.fixai.platform.fixgateway.application.port.inbound.FixGatewayEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;

/**
 * SLF4J-backed implementation of the FIX gateway event port.
 */
@Service
public class LoggingFixGatewayEventService implements FixGatewayEventPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingFixGatewayEventService.class);

    @Override
    public void onSessionCreated(SessionID sessionId) {
        LOGGER.info("FIX session created for {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        LOGGER.info("FIX logon completed for {}", sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        LOGGER.info("FIX logout completed for {}", sessionId);
    }

    @Override
    public void onHeartbeat(SessionID sessionId, Message message, Direction direction) {
        LOGGER.debug("FIX {} heartbeat for session {} {}", directionLabel(direction), sessionId, summary(message));
    }

    @Override
    public void onReject(SessionID sessionId, Message message, Direction direction) {
        LOGGER.warn("FIX {} reject for session {} {}", directionLabel(direction), sessionId, summary(message));
    }

    @Override
    public void onSequenceReset(SessionID sessionId, Message message, Direction direction) {
        LOGGER.info("FIX {} sequence reset for session {} {}", directionLabel(direction), sessionId, summary(message));
    }

    @Override
    public void onResendRequest(SessionID sessionId, Message message, Direction direction) {
        LOGGER.warn("FIX {} resend request for session {} {}", directionLabel(direction), sessionId, summary(message));
    }

    @Override
    public void onAdministrativeMessage(SessionID sessionId, Message message, Direction direction) {
        LOGGER.debug("FIX {} admin message for session {} {}", directionLabel(direction), sessionId, summary(message));
    }

    @Override
    public void onApplicationMessage(SessionID sessionId, Message message, Direction direction) {
        LOGGER.debug(
                "FIX {} application message for session {} {}", directionLabel(direction), sessionId, summary(message));
    }

    @Override
    public void onUnsupportedAdminMessage(SessionID sessionId, Message message, String msgType, Direction direction) {
        LOGGER.warn(
                "Unsupported FIX {} admin message {} for session {} {}",
                directionLabel(direction),
                msgType,
                sessionId,
                summary(message));
    }

    @Override
    public void onUnsupportedApplicationMessage(
            SessionID sessionId,
            Message message,
            String msgType,
            Direction direction) {
        LOGGER.warn(
                "Unsupported FIX {} application message {} for session {} {}",
                directionLabel(direction),
                msgType,
                sessionId,
                summary(message));
    }

    @Override
    public void onProcessingError(SessionID sessionId, Message message, String stage, Exception exception) {
        LOGGER.error(
                "FIX message processing failed during {} for session {} {}: {}",
                stage,
                sessionId,
                summary(message),
                exception.getClass().getSimpleName(),
                exception);
    }

    /**
     * Returns non-sensitive message metadata. Message bodies are never logged because they can carry
     * credentials (Logon 553/554/925/96) and client order data; message-level evidence is persisted
     * separately in redacted form.
     */
    static String summary(Message message) {
        return "[msgType=" + headerValue(message, MsgType.FIELD) + ", msgSeqNum=" + headerValue(message, MsgSeqNum.FIELD) + "]";
    }

    private static String headerValue(Message message, int tag) {
        try {
            return message.getHeader().getString(tag);
        } catch (FieldNotFound exception) {
            return "UNKNOWN";
        }
    }

    private String directionLabel(Direction direction) {
        return direction == Direction.INBOUND ? "inbound" : "outbound";
    }
}
