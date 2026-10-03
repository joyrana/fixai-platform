package com.fixai.platform.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixai.platform.certification.application.port.out.ApprovalPort;
import com.fixai.platform.certification.application.port.out.SessionConfigPort;
import com.fixai.platform.fixcore.FixVersion;
import com.fixai.platform.simulator.engine.SimulatorProfile;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * External (SESSION_CONFIG) runs need a human approval of exactly that run, consumed once. broker-service and
 * workflow-service are replaced by fakes with the same contract; the "broker endpoint" is the embedded simulator.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(ExternalCertificationApprovalIntegrationTest.Fakes.class)
class ExternalCertificationApprovalIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    static final UUID CONFIG = UUID.fromString("7d4a8f40-0000-4000-8000-00000000c0f1");
    static final UUID APPROVED = UUID.fromString("7d4a8f40-0000-4000-8000-0000000000a1");
    static final UUID OTHER_PLAN = UUID.fromString("7d4a8f40-0000-4000-8000-0000000000a2");
    static int fixPort;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void simulator(DynamicPropertyRegistry registry) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            fixPort = socket.getLocalPort();
        }
        registry.add("fixai.certification.simulator.embedded", () -> "true");
        registry.add("fixai.certification.simulator.host", () -> "127.0.0.1");
        registry.add("fixai.certification.simulator.port", () -> fixPort);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    FakeWorkflow workflow;

    @Test
    void externalRunRequiresAMatchingApprovalAndConsumesItOnce() throws Exception {
        Map<String, Object> plan = Map.of("scenarioIds", List.of("SES-002", "SES-001"), "fixVersion", "FIX44");

        ResponseEntity<Map<String, Object>> noApproval = start(plan, null);
        assertThat(noApproval.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(noApproval.getBody().get("codes")).asList().containsExactly("APPROVAL_REQUIRED");

        ResponseEntity<Map<String, Object>> wrongPlan = start(plan, OTHER_PLAN);
        assertThat(wrongPlan.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(wrongPlan.getBody().get("codes")).asList().containsExactly("PAYLOAD_MISMATCH");

        ResponseEntity<Map<String, Object>> approved = start(plan, APPROVED);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(approved.getBody()).containsEntry("targetType", "SESSION_CONFIG").containsEntry("environment", "TEST");
        // The executor presented exactly the approved action: sorted scenarios, the configuration's environment.
        assertThat(workflow.presented.getFirst().arguments())
                .containsEntry("fixVersion", "FIX44").containsEntry("scenarioIds", List.of("SES-001", "SES-002"));

        ResponseEntity<Map<String, Object>> reused = start(plan, APPROVED);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reused.getBody().get("codes")).asList().containsExactly("ALREADY_CONSUMED");

        String runId = (String) approved.getBody().get("id");
        Map<String, Object> finished = awaitFinished(runId);
        String scenarios = String.valueOf(rest.exchange("/api/v1/certification-runs/" + runId + "/scenarios", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() { }).getBody().stream()
                .map(e -> e.get("scenarioId") + ":" + e.get("status") + ":" + e.get("failureSummary")).toList());
        assertThat(finished).as(scenarios).containsEntry("status", "COMPLETED").containsEntry("verdict", "PASSED");
    }

    private ResponseEntity<Map<String, Object>> start(Map<String, Object> plan, UUID approvalId) {
        Map<String, Object> target = new java.util.HashMap<>(Map.of("type", "SESSION_CONFIG", "sessionConfigId", CONFIG.toString()));
        if (approvalId != null) {
            target.put("approvalId", approvalId.toString());
        }
        Map<String, Object> body = new java.util.HashMap<>(plan);
        body.put("target", target);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Dev-User", "alice.engineer");
        headers.set("X-Dev-Roles", "CERTIFICATION_ENGINEER");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/certification-runs", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private Map<String, Object> awaitFinished(String runId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> run = rest.exchange("/api/v1/certification-runs/" + runId, HttpMethod.GET, null, MAP).getBody();
            if (!"QUEUED".equals(run.get("status")) && !"RUNNING".equals(run.get("status"))) {
                return run;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("run did not finish");
    }

    /** Workflow-service contract: consume succeeds once, only for the payload that was approved. */
    static class FakeWorkflow implements ApprovalPort {
        final List<ApprovalPayload> presented = new ArrayList<>();
        final Set<UUID> consumed = new HashSet<>();

        @Override
        public synchronized ConsumeOutcome consume(UUID approvalId, ApprovalPayload payload, String correlationId) {
            ApprovalPayload approved = new ApprovalPayload(START_EXTERNAL_CERTIFICATION, "session-config", CONFIG.toString(), "TEST",
                    Map.of("fixVersion", "FIX44", "scenarioIds", List.of("SES-001", "SES-002")));
            if (approvalId.equals(OTHER_PLAN)) {
                return ConsumeOutcome.refused(List.of("PAYLOAD_MISMATCH"), "Presented action does not match the approved payload");
            }
            if (!approvalId.equals(APPROVED)) {
                return ConsumeOutcome.refused(List.of("APPROVAL_NOT_FOUND"), "Approval not found");
            }
            if (consumed.contains(approvalId)) {
                return ConsumeOutcome.refused(List.of("ALREADY_CONSUMED"), "Approval already used");
            }
            if (!approved.equals(payload)) {
                return ConsumeOutcome.refused(List.of("PAYLOAD_MISMATCH"), "Presented action does not match the approved payload");
            }
            presented.add(payload);
            consumed.add(approvalId);
            return new ConsumeOutcome(true, "h", List.of(), null);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeWorkflow fakeWorkflow() {
            return new FakeWorkflow();
        }

        @Bean
        @Primary
        SessionConfigPort fakeSessionConfigs() {
            return (id, correlationId) -> id.equals(CONFIG)
                    ? Optional.of(new SessionConfigPort.ResolvedSessionConfig(CONFIG, FixVersion.FIX44, "127.0.0.1", fixPort,
                            "FIXAI-UAT", SimulatorProfile.COMPLIANT.compId(), "TEST", "APPROVED"))
                    : Optional.empty();
        }
    }
}
