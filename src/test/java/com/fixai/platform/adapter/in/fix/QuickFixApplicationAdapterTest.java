package com.fixai.platform.adapter.in.fix;

import com.fixai.platform.adapter.in.fix.port.FixSessionEventPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import quickfix.SessionID;
import quickfix.field.BeginString;
import quickfix.field.MsgType;
import quickfix.fix44.Heartbeat;
import quickfix.fix44.ResendRequest;
import quickfix.fix44.SequenceReset;
import quickfix.fix44.TestRequest;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QuickFixApplicationAdapterTest {

    @Mock
    private FixSessionEventPort fixSessionEventPort;

    private QuickFixApplicationAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new QuickFixApplicationAdapter(fixSessionEventPort);
    }

    @Test
    void shouldForwardLogonAndLogout() {
        SessionID sessionID = new SessionID("FIX.4.4", "SENDER", "TARGET");

        adapter.onLogon(sessionID);
        adapter.onLogout(sessionID);

        verify(fixSessionEventPort).onLogon(sessionID);
        verify(fixSessionEventPort).onLogout(sessionID);
    }

    @Test
    void shouldHandleAdminHeartbeatMessage() throws Exception {
        SessionID sessionID = new SessionID("FIX.4.4", "SENDER", "TARGET");
        Heartbeat heartbeat = new Heartbeat();
        heartbeat.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        heartbeat.getHeader().setString(MsgType.FIELD, Heartbeat.MSGTYPE);

        adapter.fromAdmin(heartbeat, sessionID);

        verify(fixSessionEventPort).onHeartbeat(sessionID, heartbeat);
    }

    @Test
    void shouldHandleAdminTestRequestMessage() throws Exception {
        SessionID sessionID = new SessionID("FIX.4.4", "SENDER", "TARGET");
        TestRequest testRequest = new TestRequest("PING-1");
        testRequest.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        testRequest.getHeader().setString(MsgType.FIELD, TestRequest.MSGTYPE);

        adapter.fromAdmin(testRequest, sessionID);

        verify(fixSessionEventPort).onTestRequest(sessionID, testRequest);
    }

    @Test
    void shouldHandleSequenceResetAndResendRequestMessages() throws Exception {
        SessionID sessionID = new SessionID("FIX.4.4", "SENDER", "TARGET");

        SequenceReset sequenceReset = new SequenceReset();
        sequenceReset.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        sequenceReset.getHeader().setString(MsgType.FIELD, SequenceReset.MSGTYPE);

        ResendRequest resendRequest = new ResendRequest(1, 10);
        resendRequest.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        resendRequest.getHeader().setString(MsgType.FIELD, ResendRequest.MSGTYPE);

        adapter.fromAdmin(sequenceReset, sessionID);
        adapter.fromAdmin(resendRequest, sessionID);

        verify(fixSessionEventPort).onSequenceReset(sessionID, sequenceReset);
        verify(fixSessionEventPort).onResendRequest(sessionID, resendRequest);
    }

    @Test
    void shouldRejectUnknownAdminMessage() throws Exception {
        SessionID sessionID = new SessionID("FIX.4.4", "SENDER", "TARGET");
        Heartbeat heartbeat = new Heartbeat();
        heartbeat.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        heartbeat.getHeader().setString(MsgType.FIELD, "ZZZ");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.fromAdmin(heartbeat, sessionID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported admin message type");
    }
}
