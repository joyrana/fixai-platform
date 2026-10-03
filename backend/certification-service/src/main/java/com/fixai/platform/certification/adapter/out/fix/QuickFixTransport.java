package com.fixai.platform.certification.adapter.out.fix;

import com.fixai.platform.certification.application.port.out.FixTransport;
import com.fixai.platform.certification.domain.evidence.EvidenceLog;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.fixcore.DictionaryRegistry;
import com.fixai.platform.fixcore.FixMessageBuilder;
import com.fixai.platform.fixcore.FixMessageRedactor;
import com.fixai.platform.fixcore.FixMessageView;
import com.fixai.platform.fixcore.FixSessionSpec;
import com.fixai.platform.fixcore.SessionSettingsBuilder;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import quickfix.Application;
import quickfix.ConfigError;
import quickfix.DataDictionary;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.MsgType;

/**
 * QuickFIX/J-backed transport for one scenario execution.
 *
 * <p>Evidence is captured at the QuickFIX/J {@link Log} layer, so it includes every message on the wire in both
 * directions - including counterparty messages that fail validation and never reach the application callbacks.
 * Only redacted views are stored. Sequence numbers, heartbeats, resend and gap-fill handling are QuickFIX/J's.
 */
public final class QuickFixTransport implements FixTransport {

    private final Clock clock;
    private final FixMessageRedactor redactor;
    private final Object monitor = new Object();

    private SocketInitiator initiator;
    private SessionID sessionId;
    private FixMessageBuilder builder;
    private DataDictionary transportDictionary;
    private DataDictionary applicationDictionary;
    private EvidenceLog evidence;

    private boolean loggedOn;
    private boolean everLoggedOn;
    private boolean logonSent;
    private boolean logonRefused;
    private boolean logoutReceived;

    public QuickFixTransport(Clock clock, FixMessageRedactor redactor) {
        this.clock = clock;
        this.redactor = redactor;
    }

    @Override
    public void open(FixSessionSpec spec, EvidenceLog evidenceLog) {
        this.evidence = evidenceLog;
        this.sessionId = SessionSettingsBuilder.sessionId(spec);
        this.builder = new FixMessageBuilder(spec.version());
        this.transportDictionary = DictionaryRegistry.shared().transport(spec.version());
        this.applicationDictionary = DictionaryRegistry.shared().application(spec.version());
        SessionSettings settings = new SessionSettingsBuilder().add(spec).build();
        try {
            initiator = new SocketInitiator(new CapturingApplication(), new MemoryStoreFactory(), settings,
                    new EvidenceLogFactory(), new DefaultMessageFactory());
            initiator.start();
        } catch (ConfigError exception) {
            throw new IllegalStateException("Invalid FIX session configuration", exception);
        }
    }

    @Override
    public boolean awaitLogon(Duration timeout) throws InterruptedException {
        return await(timeout, () -> loggedOn);
    }

    @Override
    public boolean awaitLogonRefused(Duration timeout) throws InterruptedException {
        return await(timeout, () -> logonRefused);
    }

    @Override
    public boolean send(String msgType, Map<String, Object> fields) {
        synchronized (monitor) {
            if (!loggedOn) {
                return false;
            }
        }
        Message message = builder.build(msgType, fields);
        try {
            return Session.sendToTarget(message, sessionId);
        } catch (SessionNotFound exception) {
            return false;
        }
    }

    @Override
    public void logout(String reason) {
        synchronized (monitor) {
            logoutReceived = false;
        }
        session().ifPresent(s -> s.logout(reason));
    }

    @Override
    public boolean awaitLogout(Duration timeout) throws InterruptedException {
        return await(timeout, () -> logoutReceived);
    }

    @Override
    public void disconnect(String reason) {
        session().ifPresent(s -> {
            try {
                s.disconnect(reason, false);
            } catch (IOException exception) {
                evidence.event(clock.instant(), "Disconnect failed: " + exception.getClass().getSimpleName());
            }
        });
    }

    @Override
    public int skipOutboundSequence(int count) {
        Session session = session().orElseThrow(() -> new IllegalStateException("No FIX session"));
        try {
            int next = session.getStore().getNextSenderMsgSeqNum();
            session.setNextSenderMsgSeqNum(next + count);
            return next;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to adjust outbound sequence number", exception);
        }
    }

    @Override
    public void close() {
        if (initiator == null) {
            return;
        }
        try {
            boolean wasLoggedOn;
            synchronized (monitor) {
                wasLoggedOn = loggedOn;
            }
            if (wasLoggedOn) {
                logout("Certification scenario complete");
                awaitLogout(Duration.ofSeconds(2));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            initiator.stop(true);
            initiator = null;
        }
    }

    private java.util.Optional<Session> session() {
        return java.util.Optional.ofNullable(Session.lookupSession(sessionId));
    }

    private boolean await(Duration timeout, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (!condition.getAsBoolean()) {
                long remainingMillis = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
                if (remainingMillis <= 0) {
                    return false;
                }
                monitor.wait(remainingMillis);
            }
            return true;
        }
    }

    private void update(Runnable change) {
        synchronized (monitor) {
            change.run();
            monitor.notifyAll();
        }
    }

    private static String msgType(Message message) {
        try {
            return message.getHeader().getString(MsgType.FIELD);
        } catch (FieldNotFound exception) {
            return "";
        }
    }

    private final class CapturingApplication implements Application {

        @Override
        public void onCreate(SessionID id) {
        }

        @Override
        public void onLogon(SessionID id) {
            update(() -> {
                loggedOn = true;
                everLoggedOn = true;
            });
        }

        @Override
        public void onLogout(SessionID id) {
            update(() -> loggedOn = false);
        }

        @Override
        public void toAdmin(Message message, SessionID id) {
            if (MsgType.LOGON.equals(msgType(message))) {
                update(() -> logonSent = true);
            }
        }

        @Override
        public void fromAdmin(Message message, SessionID id) {
            if (MsgType.LOGOUT.equals(msgType(message))) {
                update(() -> {
                    logoutReceived = true;
                    if (!everLoggedOn) {
                        logonRefused = true;
                    }
                });
            }
        }

        @Override
        public void toApp(Message message, SessionID id) {
        }

        @Override
        public void fromApp(Message message, SessionID id) {
        }
    }

    /** Routes QuickFIX/J's wire-level log into the evidence log in redacted form. */
    private final class EvidenceLogFactory implements LogFactory {
        @Override
        public Log create(SessionID id) {
            return new Log() {
                @Override
                public void clear() {
                }

                @Override
                public void onIncoming(String raw) {
                    record(EvidenceRecord.Direction.INBOUND, raw);
                }

                @Override
                public void onOutgoing(String raw) {
                    record(EvidenceRecord.Direction.OUTBOUND, raw);
                }

                @Override
                public void onEvent(String text) {
                    evidence.event(clock.instant(), redactor.redactRaw(text));
                    if (text.startsWith("Disconnecting")) {
                        update(() -> {
                            if (logonSent && !everLoggedOn) {
                                logonRefused = true;
                            }
                        });
                    }
                }

                @Override
                public void onErrorEvent(String text) {
                    evidence.event(clock.instant(), "ERROR: " + redactor.redactRaw(text));
                }
            };
        }

        private void record(EvidenceRecord.Direction direction, String raw) {
            FixMessageView view = FixMessageView.fromRaw(raw, transportDictionary, applicationDictionary, redactor);
            evidence.message(direction, clock.instant(), view);
        }
    }
}
