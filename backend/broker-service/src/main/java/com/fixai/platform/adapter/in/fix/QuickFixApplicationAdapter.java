package com.fixai.platform.adapter.in.fix;

import com.fixai.platform.adapter.in.fix.port.FixSessionEventPort;
import org.springframework.stereotype.Component;
import quickfix.ApplicationAdapter;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.MsgType;
import quickfix.field.TestReqID;
import quickfix.fix44.Heartbeat;
import quickfix.fix44.ResendRequest;
import quickfix.fix44.SequenceReset;
import quickfix.fix44.TestRequest;

@Component
public class QuickFixApplicationAdapter extends ApplicationAdapter {

    private final FixSessionEventPort fixSessionEventPort;

    public QuickFixApplicationAdapter(FixSessionEventPort fixSessionEventPort) {
        this.fixSessionEventPort = fixSessionEventPort;
    }

    @Override
    public void onLogon(SessionID sessionId) {
        fixSessionEventPort.onLogon(sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        fixSessionEventPort.onLogout(sessionId);
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        switch (msgType) {
            case Heartbeat.MSGTYPE -> fixSessionEventPort.onHeartbeat(sessionId, message);
            case TestRequest.MSGTYPE -> {
                message.getString(TestReqID.FIELD);
                fixSessionEventPort.onTestRequest(sessionId, message);
            }
            case SequenceReset.MSGTYPE -> fixSessionEventPort.onSequenceReset(sessionId, message);
            case ResendRequest.MSGTYPE -> fixSessionEventPort.onResendRequest(sessionId, message);
            default -> throw new IllegalArgumentException("Unsupported admin message type: " + msgType);
        }
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // no-op: applications can enrich admin messages here if needed
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        // Application messages can be routed through a dedicated port in a future adapter.
    }
}
