package com.fixai.platform.simulator.engine;

import com.fixai.platform.fixcore.FixMessageRedactor;
import com.fixai.platform.fixcore.FixVersion;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.ThreadedSocketAcceptor;
import quickfix.field.GapFillFlag;
import quickfix.field.MsgType;
import quickfix.field.TestReqID;
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider;
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider.TemplateMapping;

/**
 * Synthetic FIX counterparty. One TCP port serves FIX 4.2, FIX 4.4 and FIXT.1.1 (FIX 5.0 SP2) clients; a session is
 * created dynamically for every client CompID, and the simulator's own CompID selects the {@link SimulatorProfile}.
 *
 * <p>Session-level behaviour (Logon, Heartbeat, TestRequest, ResendRequest, SequenceReset, Logout, sequence numbers)
 * is QuickFIX/J's own; only application behaviour and the explicitly documented defects are simulated.
 */
public final class FixSimulator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(FixSimulator.class);
    static final long SLOW_ACK_DELAY_MILLIS = 6_000;
    private static final FixMessageRedactor REDACTOR = new FixMessageRedactor();

    private final int port;
    private final Clock clock;
    private final Map<SessionID, OrderBook> books = new ConcurrentHashMap<>();
    private ThreadedSocketAcceptor acceptor;
    private ScheduledExecutorService delayedSender;

    public FixSimulator(int port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    public FixSimulator(int port) {
        this(port, Clock.systemUTC());
    }

    public int port() {
        return port;
    }

    public synchronized boolean isRunning() {
        return acceptor != null;
    }

    public synchronized void start() {
        if (acceptor != null) {
            return;
        }
        try {
            SessionSettings settings = settings();
            SimulatorApplication application = new SimulatorApplication();
            MemoryStoreFactory storeFactory = new MemoryStoreFactory();
            LogFactory logFactory = new EventOnlyLogFactory();
            DefaultMessageFactory messageFactory = new DefaultMessageFactory();
            ThreadedSocketAcceptor created =
                    new ThreadedSocketAcceptor(application, storeFactory, settings, logFactory, messageFactory);
            List<TemplateMapping> mappings = new ArrayList<>();
            for (FixVersion version : FixVersion.values()) {
                mappings.add(new TemplateMapping(
                        new SessionID(version.beginString(), DynamicAcceptorSessionProvider.WILDCARD,
                                DynamicAcceptorSessionProvider.WILDCARD),
                        templateId(version)));
            }
            created.setSessionProvider(new InetSocketAddress("0.0.0.0", port), new DynamicAcceptorSessionProvider(
                    settings, mappings, application, storeFactory, logFactory, messageFactory));
            delayedSender = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "fix-simulator-delayed-sender");
                thread.setDaemon(true);
                return thread;
            });
            created.start();
            acceptor = created;
            LOGGER.info("FIX simulator listening on port {} for {}", port, List.of(FixVersion.values()));
        } catch (ConfigError exception) {
            throw new IllegalStateException("Unable to start FIX simulator on port " + port, exception);
        }
    }

    public synchronized void stop() {
        if (acceptor == null) {
            return;
        }
        acceptor.stop(true);
        delayedSender.shutdownNow();
        acceptor = null;
        books.clear();
        LOGGER.info("FIX simulator stopped");
    }

    @Override
    public void close() {
        stop();
    }

    /** Snapshot of active sessions for diagnostics; contains no message content. */
    public List<SessionSnapshot> sessions() {
        List<SessionSnapshot> snapshots = new ArrayList<>();
        books.forEach((id, book) -> {
            Session session = Session.lookupSession(id);
            snapshots.add(new SessionSnapshot(
                    id.getBeginString(), id.getSenderCompID(), id.getTargetCompID(), book.profile(),
                    session != null && session.isLoggedOn()));
        });
        return snapshots;
    }

    public record SessionSnapshot(
            String beginString, String simulatorCompId, String clientCompId, SimulatorProfile profile, boolean loggedOn) {
    }

    private SessionSettings settings() {
        SessionSettings settings = new SessionSettings();
        for (FixVersion version : FixVersion.values()) {
            SessionID template = templateId(version);
            settings.setString(template, "ConnectionType", "acceptor");
            settings.setBool(template, "AcceptorTemplate", true);
            settings.setLong(template, "SocketAcceptPort", port);
            settings.setString(template, "SocketAcceptAddress", "0.0.0.0");
            settings.setString(template, "StartTime", "00:00:00");
            settings.setString(template, "EndTime", "00:00:00");
            settings.setBool(template, "NonStopSession", true);
            settings.setBool(template, "UseDataDictionary", true);
            settings.setBool(template, "ValidateIncomingMessage", true);
            settings.setBool(template, "ResetOnDisconnect", false);
            settings.setBool(template, "ResetOnLogout", false);
            if (version.isFixt()) {
                settings.setString(template, "TransportDataDictionary", version.transportDictionary());
                settings.setString(template, "AppDataDictionary", version.applicationDictionary());
                settings.setString(template, "DefaultApplVerID", version.defaultApplVerId().orElseThrow());
            } else {
                settings.setString(template, "DataDictionary", version.applicationDictionary());
            }
        }
        return settings;
    }

    private static SessionID templateId(FixVersion version) {
        return new SessionID(version.beginString(), SimulatorProfile.COMP_ID_PREFIX, "TEMPLATE");
    }

    private final class SimulatorApplication implements Application {

        @Override
        public void onCreate(SessionID sessionId) {
            Optional<SimulatorProfile> profile = SimulatorProfile.fromCompId(sessionId.getSenderCompID());
            FixVersion version = FixVersion.fromBeginString(sessionId.getBeginString()).orElseThrow();
            profile.ifPresent(p -> books.put(sessionId, new OrderBook(version, p, clock)));
        }

        @Override
        public void onLogon(SessionID sessionId) {
            LOGGER.info("Simulator logon {}", sessionId);
        }

        @Override
        public void onLogout(SessionID sessionId) {
            LOGGER.info("Simulator logout {}", sessionId);
        }

        @Override
        public void toAdmin(Message message, SessionID sessionId) {
            OrderBook book = books.get(sessionId);
            if (book == null) {
                return;
            }
            String msgType = msgType(message);
            if (book.profile() == SimulatorProfile.HEARTBEAT_WITHOUT_TEST_REQ_ID && MsgType.HEARTBEAT.equals(msgType)) {
                message.removeField(TestReqID.FIELD);
            }
            if (book.profile() == SimulatorProfile.GAP_FILL_WITHOUT_FLAG && MsgType.SEQUENCE_RESET.equals(msgType)) {
                message.removeField(GapFillFlag.FIELD);
            }
        }

        @Override
        public void fromAdmin(Message message, SessionID sessionId) throws RejectLogon {
            if (MsgType.LOGON.equals(msgType(message)) && !books.containsKey(sessionId)) {
                throw new RejectLogon("Unknown simulator CompID " + sessionId.getSenderCompID());
            }
        }

        @Override
        public void toApp(Message message, SessionID sessionId) {
            // no enrichment required
        }

        @Override
        public void fromApp(Message message, SessionID sessionId) throws FieldNotFound {
            OrderBook book = books.get(sessionId);
            if (book == null) {
                return;
            }
            List<Message> responses = book.handle(message);
            if (book.profile() == SimulatorProfile.SLOW_ACK) {
                for (Message response : responses) {
                    delayedSender.schedule(() -> send(response, sessionId), SLOW_ACK_DELAY_MILLIS, TimeUnit.MILLISECONDS);
                }
            } else {
                responses.forEach(response -> send(response, sessionId));
            }
        }

        private void send(Message message, SessionID sessionId) {
            try {
                Session.sendToTarget(message, sessionId);
            } catch (SessionNotFound exception) {
                LOGGER.warn("Simulator session {} disappeared before response could be sent", sessionId);
            }
        }

        private String msgType(Message message) {
            try {
                return message.getHeader().getString(MsgType.FIELD);
            } catch (FieldNotFound exception) {
                return "";
            }
        }
    }

    /** Logs QuickFIX/J session events only; message content is never logged. */
    private static final class EventOnlyLogFactory implements LogFactory {
        @Override
        public Log create(SessionID sessionId) {
            return new Log() {
                @Override
                public void clear() {
                }

                @Override
                public void onIncoming(String message) {
                }

                @Override
                public void onOutgoing(String message) {
                }

                @Override
                public void onEvent(String text) {
                    LOGGER.debug("{} {}", sessionId, text);
                }

                @Override
                public void onErrorEvent(String text) {
                    LOGGER.warn("{} {}", sessionId, REDACTOR.redactRaw(text));
                }
            };
        }
    }
}
