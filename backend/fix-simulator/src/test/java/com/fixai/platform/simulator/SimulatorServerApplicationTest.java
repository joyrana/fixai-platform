package com.fixai.platform.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorServerApplicationTest {

    @Autowired
    TestRestTemplate rest;

    @DynamicPropertySource
    static void fixPort(DynamicPropertyRegistry registry) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            registry.add("fixai.simulator.fix-port", () -> port);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesProfilesWithoutDefectGroundTruthAndReportsHealthy() {
        List<Map<String, Object>> profiles = rest.getForObject("/api/v1/simulator/profiles", List.class);
        Map<String, Object> health = rest.getForObject("/actuator/health", Map.class);

        assertThat(profiles).anySatisfy(p -> {
            assertThat(p).containsEntry("name", "COMPLIANT").containsEntry("compId", "SIM").containsEntry("compliant", true);
        });
        assertThat(profiles).allSatisfy(p -> assertThat(p).doesNotContainKey("defect"));
        assertThat(health).containsEntry("status", "UP");
    }
}
