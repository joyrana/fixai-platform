package com.fixai.platform.simulator;

import com.fixai.platform.fixcore.FixSessionSpec;
import com.fixai.platform.fixcore.FixVersion;
import com.fixai.platform.fixcore.SessionSettingsBuilder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

/** Minimal QuickFIX/J initiator for simulator tests. */
final class TestFixClient implements AutoCloseable {

    final BlockingQueue<Message> inbound = new LinkedBlockingQueue<>();
    final CountDownLatch loggedOn = new CountDownLatch(1);
    final CountDownLatch loggedOut = new CountDownLatch(1);
    final SessionID sessionId;
    private final SocketInitiator initiator;

    TestFixClient(FixVersion version, String clientCompId, String simulatorCompId, int port) throws Exception {
        FixSessionSpec spec = FixSessionSpec.initiator(version, clientCompId, simulatorCompId, "localhost", port)
                .withHeartbeat(5);
        SessionSettings settings = new SessionSettingsBuilder().add(spec).build();
        settings.setString(SessionSettingsBuilder.sessionId(spec), "SocketConnectHost", "127.0.0.1");
        this.sessionId = SessionSettingsBuilder.sessionId(spec);
        Application app = new Application() {
            public void onCreate(SessionID id) { }
            public void onLogon(SessionID id) { loggedOn.countDown(); }
            public void onLogout(SessionID id) { loggedOut.countDown(); }
            public void toAdmin(Message m, SessionID id) { }
            public void fromAdmin(Message m, SessionID id) { inbound.add(m); }
            public void toApp(Message m, SessionID id) { }
            public void fromApp(Message m, SessionID id) { inbound.add(m); }
        };
        settings.setBool(sessionId, "ScreenLogShowIncoming", false);
        settings.setBool(sessionId, "ScreenLogShowOutgoing", false);
        settings.setBool(sessionId, "ScreenLogShowEvents", false);
        initiator = new SocketInitiator(app, new MemoryStoreFactory(), settings, new ScreenLogFactory(settings),
                new DefaultMessageFactory());
        initiator.start();
    }

    boolean awaitLogon(long seconds) throws InterruptedException {
        return loggedOn.await(seconds, TimeUnit.SECONDS);
    }

    void send(Message message) throws Exception {
        Session.sendToTarget(message, sessionId);
    }

    Message next(String msgType, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            Message m = inbound.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (m != null && msgType.equals(m.getHeader().getString(35))) {
                return m;
            }
        }
        return null;
    }

    @Override
    public void close() {
        initiator.stop(true);
    }
}
