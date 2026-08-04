package com.fixai.platform.adapter.in.fix.port;

import quickfix.Message;
import quickfix.SessionID;

public interface FixSessionEventPort {

    void onLogon(SessionID sessionId);

    void onLogout(SessionID sessionId);

    void onHeartbeat(SessionID sessionId, Message message);

    void onTestRequest(SessionID sessionId, Message message);

    void onSequenceReset(SessionID sessionId, Message message);

    void onResendRequest(SessionID sessionId, Message message);
}
