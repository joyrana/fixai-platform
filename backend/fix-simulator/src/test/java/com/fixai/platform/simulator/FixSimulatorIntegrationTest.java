package com.fixai.platform.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixai.platform.fixcore.FixMessageBuilder;
import com.fixai.platform.fixcore.FixVersion;
import com.fixai.platform.simulator.engine.FixSimulator;
import com.fixai.platform.simulator.engine.SimulatorProfile;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import quickfix.Message;
import quickfix.Session;
import quickfix.field.TestReqID;

class FixSimulatorIntegrationTest {

    private static FixSimulator simulator;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        simulator = new FixSimulator(port);
        simulator.start();
    }

    @AfterAll
    static void stop() {
        simulator.stop();
    }

    @ParameterizedTest
    @EnumSource(FixVersion.class)
    void logsOnAndFillsMarketOrderForEveryVersion(FixVersion version) throws Exception {
        try (TestFixClient client = new TestFixClient(version, "IT-" + version.name(), "SIM", port)) {
            assertThat(client.awaitLogon(10)).isTrue();

            client.send(OrderBookTest.order(version, "IT-1", "AAPL", "1", "1", "100", null));

            Message ack = client.next("8", 5_000);
            Message fill = client.next("8", 5_000);
            assertThat(ack.getString(39)).isEqualTo("0");
            assertThat(fill.getString(39)).isEqualTo("2");
            assertThat(fill.getString(14)).isEqualTo("100");
            assertThat(simulator.sessions())
                    .anySatisfy(s -> assertThat(s.clientCompId()).isEqualTo("IT-" + version.name()));
        }
    }

    @Test
    void rejectsLogonToUnknownSimulatorCompId() throws Exception {
        try (TestFixClient client = new TestFixClient(FixVersion.FIX44, "IT-UNKNOWN", "NOT-A-SIM", port)) {
            assertThat(client.awaitLogon(3)).isFalse();
            Message logout = client.next("5", 5_000);
            assertThat(logout).isNotNull();
        }
    }

    @Test
    void compliantProfileEchoesTestReqIdButDefectProfileDoesNot() throws Exception {
        assertThat(heartbeatTestReqId(SimulatorProfile.COMPLIANT, "IT-HB-OK")).isEqualTo("PING-1");
        assertThat(heartbeatTestReqId(SimulatorProfile.HEARTBEAT_WITHOUT_TEST_REQ_ID, "IT-HB-BAD")).isNull();
    }

    private String heartbeatTestReqId(SimulatorProfile profile, String clientCompId) throws Exception {
        try (TestFixClient client = new TestFixClient(FixVersion.FIX44, clientCompId, profile.compId(), port)) {
            assertThat(client.awaitLogon(10)).isTrue();
            Message testRequest = new FixMessageBuilder(FixVersion.FIX44).build("1", Map.of("TestReqID", "PING-1"));
            Session.sendToTarget(testRequest, client.sessionId);
            Message heartbeat = client.next("0", 5_000);
            assertThat(heartbeat).isNotNull();
            return heartbeat.isSetField(TestReqID.FIELD) ? heartbeat.getString(TestReqID.FIELD) : null;
        }
    }
}
