package com.fixai.platform.fixgateway.application.service;

import com.fixai.platform.fixgateway.application.port.inbound.FixGatewayEventPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.BeginString;
import quickfix.field.MsgType;
import quickfix.fix44.Heartbeat;
import quickfix.fix44.Reject;
import quickfix.fix44.ResendRequest;
import quickfix.fix44.SequenceReset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LoggingFixGatewayEventServiceTest {

    private LoggingFixGatewayEventService service;

    @BeforeEach
    void setUp() {
        service = new LoggingFixGatewayEventService();
    }

    @Test
    void shouldHandleAllGatewayEvents() throws Exception {
        SessionID sessionId = new SessionID("FIX.4.4", "FIXAI", "BROKER");
        Heartbeat heartbeat = typedMessage(new Heartbeat(), Heartbeat.MSGTYPE);
        Reject reject = typedMessage(new Reject(), Reject.MSGTYPE);
        SequenceReset sequenceReset = typedMessage(new SequenceReset(), SequenceReset.MSGTYPE);
        ResendRequest resendRequest = typedMessage(new ResendRequest(), ResendRequest.MSGTYPE);
        Message unsupported = new Message();

        assertDoesNotThrow(() -> service.onSessionCreated(sessionId));
        assertDoesNotThrow(() -> service.onLogon(sessionId));
        assertDoesNotThrow(() -> service.onLogout(sessionId));
        assertDoesNotThrow(() -> service.onHeartbeat(sessionId, heartbeat, FixGatewayEventPort.Direction.INBOUND));
        assertDoesNotThrow(() -> service.onReject(sessionId, reject, FixGatewayEventPort.Direction.OUTBOUND));
        assertDoesNotThrow(() -> service.onSequenceReset(sessionId, sequenceReset, FixGatewayEventPort.Direction.INBOUND));
        assertDoesNotThrow(() -> service.onResendRequest(sessionId, resendRequest, FixGatewayEventPort.Direction.OUTBOUND));
        assertDoesNotThrow(() -> service.onAdministrativeMessage(sessionId, unsupported, FixGatewayEventPort.Direction.INBOUND));
        assertDoesNotThrow(() -> service.onApplicationMessage(sessionId, unsupported, FixGatewayEventPort.Direction.OUTBOUND));
        assertDoesNotThrow(
                () -> service.onUnsupportedAdminMessage(sessionId, unsupported, "U1", FixGatewayEventPort.Direction.INBOUND));
        assertDoesNotThrow(() -> service.onUnsupportedApplicationMessage(
                sessionId,
                unsupported,
                "D",
                FixGatewayEventPort.Direction.OUTBOUND));
        assertDoesNotThrow(
                () -> service.onProcessingError(sessionId, unsupported, "fromAdmin", new IllegalStateException("boom")));
    }

    private <T extends Message> T typedMessage(T message, String msgType) throws Exception {
        message.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        message.getHeader().setString(MsgType.FIELD, msgType);
        return message;
    }
}
