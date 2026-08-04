package com.fixai.platform.fixgateway.adapter.in.fix;

import com.fixai.platform.fixgateway.application.port.inbound.FixGatewayEventPort;
import org.springframework.stereotype.Component;
import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.MsgType;
import quickfix.fix44.Heartbeat;
import quickfix.fix44.Logon;
import quickfix.fix44.Logout;
import quickfix.fix44.Reject;
import quickfix.fix44.ResendRequest;
import quickfix.fix44.SequenceReset;
import quickfix.fix44.TestRequest;

/**
 * QuickFIX/J application adapter for session lifecycle and message processing.
 */
@Component
public class QuickFixApplicationAdapter extends MessageCracker implements Application {

    private final FixGatewayEventPort eventPort;
    private final ThreadLocal<FixGatewayEventPort.Direction> currentDirection = new ThreadLocal<>();
    private final ThreadLocal<MessageChannel> currentChannel = new ThreadLocal<>();

    public QuickFixApplicationAdapter(FixGatewayEventPort eventPort) {
        this.eventPort = eventPort;
    }

    @Override
    public void onCreate(SessionID sessionId) {
        eventPort.onSessionCreated(sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        eventPort.onLogon(sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        eventPort.onLogout(sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        process(message, sessionId, FixGatewayEventPort.Direction.OUTBOUND, MessageChannel.ADMIN);
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        process(message, sessionId, FixGatewayEventPort.Direction.INBOUND, MessageChannel.ADMIN);
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        process(message, sessionId, FixGatewayEventPort.Direction.OUTBOUND, MessageChannel.APPLICATION);
    }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        process(message, sessionId, FixGatewayEventPort.Direction.INBOUND, MessageChannel.APPLICATION);
    }

    public void onMessage(Heartbeat message, SessionID sessionId) {
        eventPort.onHeartbeat(sessionId, message, direction());
    }

    public void onMessage(Reject message, SessionID sessionId) {
        eventPort.onReject(sessionId, message, direction());
    }

    public void onMessage(SequenceReset message, SessionID sessionId) {
        eventPort.onSequenceReset(sessionId, message, direction());
    }

    public void onMessage(ResendRequest message, SessionID sessionId) {
        eventPort.onResendRequest(sessionId, message, direction());
    }

    public void onMessage(Logon message, SessionID sessionId) {
        eventPort.onAdministrativeMessage(sessionId, message, direction());
    }

    public void onMessage(Logout message, SessionID sessionId) {
        eventPort.onAdministrativeMessage(sessionId, message, direction());
    }

    public void onMessage(TestRequest message, SessionID sessionId) {
        eventPort.onAdministrativeMessage(sessionId, message, direction());
    }

    private void process(Message message, SessionID sessionId, FixGatewayEventPort.Direction direction, MessageChannel channel) {
        currentDirection.set(direction);
        currentChannel.set(channel);

        try {
            crack(message, sessionId);
            if (channel == MessageChannel.APPLICATION) {
                eventPort.onApplicationMessage(sessionId, message, direction);
            }
        } catch (UnsupportedMessageType exception) {
            handleUnsupported(sessionId, message, direction, channel);
        } catch (FieldNotFound | IncorrectTagValue | RuntimeException exception) {
            eventPort.onProcessingError(sessionId, message, stage(channel, direction), exception);
        } finally {
            currentDirection.remove();
            currentChannel.remove();
        }
    }

    private void handleUnsupported(
            SessionID sessionId,
            Message message,
            FixGatewayEventPort.Direction direction,
            MessageChannel channel) {
        String msgType = messageType(message);
        if (channel == MessageChannel.ADMIN) {
            eventPort.onUnsupportedAdminMessage(sessionId, message, msgType, direction);
            return;
        }
        eventPort.onUnsupportedApplicationMessage(sessionId, message, msgType, direction);
    }

    private FixGatewayEventPort.Direction direction() {
        FixGatewayEventPort.Direction direction = currentDirection.get();
        return direction == null ? FixGatewayEventPort.Direction.INBOUND : direction;
    }

    private String stage(MessageChannel channel, FixGatewayEventPort.Direction direction) {
        String prefix = direction == FixGatewayEventPort.Direction.INBOUND ? "from" : "to";
        return prefix + (channel == MessageChannel.ADMIN ? "Admin" : "App");
    }

    private String messageType(Message message) {
        try {
            return message.getHeader().getString(MsgType.FIELD);
        } catch (FieldNotFound exception) {
            return "UNKNOWN";
        }
    }

    private enum MessageChannel {
        ADMIN,
        APPLICATION
    }
}
