package com.fixai.platform.fixgateway.adapter.in.fix;

import com.fixai.platform.fixgateway.application.port.inbound.FixGatewayEventPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.BeginString;
import quickfix.field.MsgType;
import quickfix.fix44.Heartbeat;
import quickfix.fix44.Logon;
import quickfix.fix44.Reject;
import quickfix.fix44.ResendRequest;
import quickfix.fix44.SequenceReset;
import quickfix.fix44.TestRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QuickFixApplicationAdapterTest {

    @Mock
    private FixGatewayEventPort eventPort;

    private QuickFixApplicationAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new QuickFixApplicationAdapter(eventPort);
    }

    @Test
    void shouldForwardSessionLifecycleEvents() {
        SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");

        adapter.onCreate(sessionId);
        adapter.onLogon(sessionId);
        adapter.onLogout(sessionId);

        verify(eventPort).onSessionCreated(sessionId);
        verify(eventPort).onLogon(sessionId);
        verify(eventPort).onLogout(sessionId);
    }

    @Test
    void shouldRouteSupportedAdminMessages() throws Exception {
        SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");

        Heartbeat heartbeat = heartbeat();
        Reject reject = adminMessage(new Reject(), Reject.MSGTYPE);
        SequenceReset sequenceReset = adminMessage(new SequenceReset(), SequenceReset.MSGTYPE);
        ResendRequest resendRequest = adminMessage(new ResendRequest(), ResendRequest.MSGTYPE);
        TestRequest testRequest = adminMessage(new TestRequest(), TestRequest.MSGTYPE);
        Logon logon = adminMessage(new Logon(), Logon.MSGTYPE);

        adapter.fromAdmin(heartbeat, sessionId);
        adapter.fromAdmin(reject, sessionId);
        adapter.fromAdmin(sequenceReset, sessionId);
        adapter.fromAdmin(resendRequest, sessionId);
        adapter.toAdmin(testRequest, sessionId);
        adapter.toAdmin(logon, sessionId);

        verify(eventPort).onHeartbeat(sessionId, heartbeat, FixGatewayEventPort.Direction.INBOUND);
        verify(eventPort).onReject(sessionId, reject, FixGatewayEventPort.Direction.INBOUND);
        verify(eventPort).onSequenceReset(sessionId, sequenceReset, FixGatewayEventPort.Direction.INBOUND);
        verify(eventPort).onResendRequest(sessionId, resendRequest, FixGatewayEventPort.Direction.INBOUND);
        verify(eventPort).onAdministrativeMessage(sessionId, testRequest, FixGatewayEventPort.Direction.OUTBOUND);
        verify(eventPort).onAdministrativeMessage(sessionId, logon, FixGatewayEventPort.Direction.OUTBOUND);
    }

    @Test
    void shouldGracefullyLogUnsupportedMessages() {
        SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
        Message unsupportedAdmin = genericMessage("U1");
        Message unsupportedApp = genericMessage("D");

        assertDoesNotThrow(() -> adapter.fromAdmin(unsupportedAdmin, sessionId));
        assertDoesNotThrow(() -> adapter.fromApp(unsupportedApp, sessionId));

        verify(eventPort)
                .onUnsupportedAdminMessage(sessionId, unsupportedAdmin, "U1", FixGatewayEventPort.Direction.INBOUND);
        verify(eventPort).onUnsupportedApplicationMessage(
                sessionId,
                unsupportedApp,
                "D",
                FixGatewayEventPort.Direction.INBOUND);
    }

    @Test
    void shouldPublishApplicationFlowAfterSuccessfulCrack() throws Exception {
        SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
        Heartbeat heartbeat = heartbeat();

        assertDoesNotThrow(() -> adapter.toApp(heartbeat, sessionId));

        verify(eventPort).onHeartbeat(sessionId, heartbeat, FixGatewayEventPort.Direction.OUTBOUND);
        verify(eventPort).onApplicationMessage(sessionId, heartbeat, FixGatewayEventPort.Direction.OUTBOUND);
    }

    @Test
    void shouldReportProcessingErrorsWithoutThrowing() {
        SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
        Heartbeat heartbeat = heartbeat();

        doThrow(new IllegalStateException("boom"))
                .when(eventPort)
                .onHeartbeat(sessionId, heartbeat, FixGatewayEventPort.Direction.INBOUND);

        assertDoesNotThrow(() -> adapter.fromAdmin(heartbeat, sessionId));

        verify(eventPort).onProcessingError(eq(sessionId), eq(heartbeat), eq("fromAdmin"), any());
    }

    private Heartbeat heartbeat() {
        Heartbeat heartbeat = new Heartbeat();
        heartbeat.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        heartbeat.getHeader().setString(MsgType.FIELD, Heartbeat.MSGTYPE);
        return heartbeat;
    }

    private <T extends Message> T adminMessage(T message, String msgType) throws Exception {
        message.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        message.getHeader().setString(MsgType.FIELD, msgType);
        return message;
    }

    private Message genericMessage(String msgType) {
        Message message = new Message();
        message.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        message.getHeader().setString(MsgType.FIELD, msgType);
        return message;
    }
}
