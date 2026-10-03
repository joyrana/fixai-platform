package com.fixai.platform.certification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * End-to-end through the public API: PostgreSQL (Testcontainers), Flyway, embedded simulator, real FIX sessions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CertificationApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() { };

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void simulator(DynamicPropertyRegistry registry) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        int fixPort = port;
        registry.add("fixai.certification.simulator.embedded", () -> "true");
        registry.add("fixai.certification.simulator.host", () -> "127.0.0.1");
        registry.add("fixai.certification.simulator.port", () -> fixPort);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void listsCatalogueAndValidatesTestPlans() {
        List<Map<String, Object>> scenarios = rest.exchange("/api/v1/scenarios?fixVersion=FIX44", HttpMethod.GET, null, LIST).getBody();
        assertThat(scenarios).hasSize(24);
        assertThat(rest.exchange("/api/v1/suites", HttpMethod.GET, null, LIST).getBody())
                .extracting(s -> s.get("id")).contains("full-certification", "smoke");

        Map<String, Object> valid = post("/api/v1/test-plans/validate", Map.of("suiteId", "smoke", "fixVersion", "FIX44"), null).getBody();
        Map<String, Object> invalid = post("/api/v1/test-plans/validate",
                Map.of("scenarioIds", List.of("ORD-001", "NOPE-999"), "fixVersion", "FIX44"), null).getBody();
        assertThat(valid).containsEntry("valid", true);
        assertThat(invalid).containsEntry("valid", false);
        assertThat((List<?>) invalid.get("problems")).anySatisfy(p -> assertThat(p.toString()).contains("NOPE-999"));
    }

    @Test
    void compliantSmokeRunPassesWithReportReplayAndEvidence() throws Exception {
        ResponseEntity<Map<String, Object>> started = post("/api/v1/certification-runs",
                Map.of("suiteId", "smoke", "fixVersion", "FIX44"), "smoke-key-00000001");
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(started.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        String runId = (String) started.getBody().get("id");

        Map<String, Object> run = awaitTerminal(runId);
        assertThat(run).containsEntry("status", "COMPLETED").containsEntry("verdict", "PASSED");
        assertThat(run.get("scenariosPassed")).isEqualTo(4);
        assertThat((String) run.get("evidenceDigest")).hasSize(64);

        // Idempotent retry returns the same run with 200.
        ResponseEntity<Map<String, Object>> retry = post("/api/v1/certification-runs",
                Map.of("suiteId", "smoke", "fixVersion", "FIX44"), "smoke-key-00000001");
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("id")).isEqualTo(runId);

        List<Map<String, Object>> executions = rest.exchange(
                "/api/v1/certification-runs/" + runId + "/scenarios", HttpMethod.GET, null, LIST).getBody();
        assertThat(executions).hasSize(4).allSatisfy(e -> assertThat(e.get("status")).isEqualTo("PASSED"));
        String executionId = (String) executions.get(0).get("id");

        Map<String, Object> evidence = rest.exchange("/api/v1/certification-runs/" + runId + "/scenarios/" + executionId
                + "/evidence?msgType=A", HttpMethod.GET, null, MAP).getBody();
        assertThat((List<?>) evidence.get("items")).isNotEmpty();

        Map<String, Object> replay = post("/api/v1/certification-runs/" + runId + "/replay-verification", Map.of(), null).getBody();
        assertThat(replay).containsEntry("consistent", true).containsEntry("evidenceDigestMatches", true);

        Map<String, Object> report = rest.exchange("/api/v1/certification-runs/" + runId + "/report", HttpMethod.GET, null, MAP).getBody();
        assertThat(report).containsEntry("schemaVersion", 1).containsKey("disclaimer");
        assertThat(report.get("run")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("verdict", "PASSED");

        ResponseEntity<String> html = rest.getForEntity("/api/v1/certification-runs/" + runId + "/report.html", String.class);
        assertThat(html.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html.getBody()).contains("FIX Certification Report").contains("PASSED");
        assertThat(html.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
    }

    @Test
    void defectiveCounterpartyFailsWithEvidenceBackedFindings() throws Exception {
        String runId = (String) post("/api/v1/certification-runs", Map.of(
                "scenarioIds", List.of("ORD-001", "ORD-002"),
                "fixVersion", "FIX44",
                "target", Map.of("type", "SIMULATOR", "simulatorProfile", "WRONG_EXEC_TYPE_ON_FILL")), null).getBody().get("id");

        Map<String, Object> run = awaitTerminal(runId);
        assertThat(run).containsEntry("verdict", "FAILED");

        Map<String, Object> report = rest.exchange("/api/v1/certification-runs/" + runId + "/report", HttpMethod.GET, null, MAP).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) report.get("scenarios");
        Map<String, Object> failed = scenarios.stream().filter(s -> "ORD-002".equals(s.get("scenarioId"))).findFirst().orElseThrow();
        assertThat(failed.get("status")).isEqualTo("FAILED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assertions = (List<Map<String, Object>>) failed.get("failedAssertions");
        assertThat(assertions).anySatisfy(a -> {
            assertThat(a.get("subject")).isEqualTo("ExecType");
            assertThat(a.get("actual")).isEqualTo("0");
            assertThat(a.get("evidenceOrdinal")).isNotNull();
        });
    }

    @Test
    void rejectsInvalidRequestsAndUnsafeTargets() {
        ResponseEntity<Map<String, Object>> unknown = post("/api/v1/certification-runs",
                Map.of("scenarioIds", List.of("NOPE-1"), "fixVersion", "FIX44"), null);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unknown.getBody()).containsKey("problems").containsKey("correlationId").doesNotContainKey("trace");

        ResponseEntity<Map<String, Object>> both = post("/api/v1/certification-runs",
                Map.of("suiteId", "smoke", "scenarioIds", List.of("ORD-001"), "fixVersion", "FIX44"), null);
        assertThat(both.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        ResponseEntity<Map<String, Object>> external = post("/api/v1/certification-runs", Map.of("suiteId", "smoke",
                "fixVersion", "FIX44", "target", Map.of("type", "SESSION_CONFIG",
                        "sessionConfigId", "6f1c3c1e-0000-4000-8000-000000000001")), null);
        assertThat(external.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map<String, Object>> malformed = post("/api/v1/certification-runs", Map.of("fixVersion", "FIX99"), null);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> conflict1 = post("/api/v1/certification-runs",
                Map.of("suiteId", "smoke", "fixVersion", "FIX44"), "conflict-key-0001");
        ResponseEntity<Map<String, Object>> conflict2 = post("/api/v1/certification-runs",
                Map.of("suiteId", "smoke", "fixVersion", "FIX42"), "conflict-key-0001");
        assertThat(conflict1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(conflict2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map<String, Object>> missing = rest.exchange(
                "/api/v1/certification-runs/6f1c3c1e-0000-4000-8000-000000000002", HttpMethod.GET, null, MAP);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rolesRestrictWhoCanStartRunsAndAgentsAreLimitedToSimulatedTargets() {
        HttpHeaders auditor = devHeaders("auditor1", "AUDITOR");
        ResponseEntity<Map<String, Object>> forbidden = rest.exchange("/api/v1/certification-runs", HttpMethod.POST,
                new HttpEntity<>(Map.of("suiteId", "smoke", "fixVersion", "FIX44"), auditor), MAP);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders agent = devHeaders("certification-agent", "AI_AGENT");
        ResponseEntity<Map<String, Object>> external = rest.exchange("/api/v1/certification-runs", HttpMethod.POST,
                new HttpEntity<>(Map.of("suiteId", "smoke", "fixVersion", "FIX44", "target",
                        Map.of("type", "SESSION_CONFIG", "sessionConfigId", "6f1c3c1e-0000-4000-8000-000000000003")), agent), MAP);
        assertThat(external.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((String) external.getBody().get("detail")).contains("AI agents may only start simulated certifications");

        ResponseEntity<Map<String, Object>> simulated = rest.exchange("/api/v1/certification-runs", HttpMethod.POST,
                new HttpEntity<>(Map.of("scenarioIds", List.of("SES-002"), "fixVersion", "FIX44"), agent), MAP);
        assertThat(simulated.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(simulated.getBody()).containsEntry("requestedBy", "certification-agent");

        assertThat(rest.exchange("/api/v1/certification-runs/" + simulated.getBody().get("id"), HttpMethod.GET,
                new HttpEntity<>(auditor), MAP).getStatusCode()).isEqualTo(HttpStatus.OK);
        Integer outbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM certification.audit_outbox WHERE payload->>'resourceId' = ?", Integer.class,
                simulated.getBody().get("id"));
        assertThat(outbox).isPositive();
    }

    private static HttpHeaders devHeaders(String user, String roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Dev-User", user);
        headers.set("X-Dev-Roles", roles);
        return headers;
    }

    private Map<String, Object> awaitTerminal(String runId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> run = rest.exchange("/api/v1/certification-runs/" + runId, HttpMethod.GET, null, MAP).getBody();
            if (List.of("COMPLETED", "CANCELLED", "ERROR").contains(run.get("status"))) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Run " + runId + " did not finish");
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }
}
