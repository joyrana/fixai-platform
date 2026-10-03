package com.fixai.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fixai.workflow.expiry-sweep-interval=PT1H")
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("unchecked")
class WorkflowApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-03T10:00:00Z"));
        }
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void fullLifecycleEnforcesRolesFourEyesHashBindingAndSingleUse() {
        Map<String, Object> created = create("alice", "BROKER_MANAGER", payload("cfg-1", "UAT", 9880), null).getBody();
        String id = (String) created.get("id");
        assertThat(created).containsEntry("status", "PENDING").containsEntry("riskLevel", "MEDIUM");
        assertThat((String) created.get("payloadHash")).hasSize(64);

        assertThat(decide(id, "alice", "BROKER_MANAGER", "APPROVE").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map<String, Object>> self = decide(id, "alice", "REVIEWER", "APPROVE");
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((List<Object>) self.getBody().get("codes")).containsExactly("FOUR_EYES");

        assertThat(decide(id, "bob", "REVIEWER", "APPROVE").getBody()).containsEntry("status", "APPROVED")
                .containsEntry("decidedBy", "bob");

        Map<String, Object> tampered = payload("cfg-1", "UAT", 9999);
        ResponseEntity<Map<String, Object>> mismatch = consume(id, "broker-service", "SERVICE", tampered);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((List<Object>) mismatch.getBody().get("codes")).containsExactly("PAYLOAD_MISMATCH");

        assertThat(consume(id, "carol", "REVIEWER", payload("cfg-1", "UAT", 9880)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(consume(id, "broker-service", "SERVICE", payload("cfg-1", "UAT", 9880)).getBody())
                .containsEntry("status", "CONSUMED").containsEntry("consumedBy", "broker-service");
        ResponseEntity<Map<String, Object>> again = consume(id, "broker-service", "SERVICE", payload("cfg-1", "UAT", 9880));
        assertThat((List<Object>) again.getBody().get("codes")).containsExactly("ALREADY_CONSUMED");

        Map<String, Object> detail = get("/api/v1/approvals/" + id, "auditor1", "AUDITOR").getBody();
        assertThat((List<Object>) detail.get("history")).hasSize(2);
    }

    @Test
    void policyBlocksProductionUnknownActionsAndWeakJustification() {
        ResponseEntity<Map<String, Object>> production = create("alice", "BROKER_MANAGER", payload("cfg-2", "PRODUCTION", 1), null);
        assertThat(production.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((List<Object>) production.getBody().get("codes")).contains("PRODUCTION_BLOCKED");

        Map<String, Object> unknown = payload("cfg-2", "TEST", 1);
        unknown.put("action", "DROP_DATABASE");
        assertThat((List<Object>) create("alice", "BROKER_MANAGER", unknown, null).getBody().get("codes")).contains("ACTION_NOT_ALLOWED");

        Map<String, Object> weak = payload("cfg-2", "TEST", 1);
        weak.put("justification", "because");
        assertThat(create("alice", "BROKER_MANAGER", weak, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(create("eve", "AUDITOR", payload("cfg-2", "TEST", 1), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectRequestChangesCancelAndVisibility() {
        String rejected = (String) create("alice", "BROKER_MANAGER", payload("cfg-3", "TEST", 1), null).getBody().get("id");
        assertThat(decide(rejected, "bob", "REVIEWER", "REJECT").getBody()).containsEntry("status", "REJECTED");
        assertThat((List<Object>) consume(rejected, "svc", "SERVICE", payload("cfg-3", "TEST", 1)).getBody().get("codes"))
                .containsExactly("NOT_APPROVED");

        String changes = (String) create("alice", "BROKER_MANAGER", payload("cfg-4", "TEST", 1), null).getBody().get("id");
        assertThat(decide(changes, "bob", "REVIEWER", "REQUEST_CHANGES").getBody()).containsEntry("status", "CHANGES_REQUESTED");
        assertThat((List<Object>) decide(changes, "carol", "REVIEWER", "APPROVE").getBody().get("codes")).containsExactly("NOT_PENDING");

        String cancelled = (String) create("alice", "BROKER_MANAGER", payload("cfg-5", "TEST", 1), null).getBody().get("id");
        assertThat((List<Object>) post("/api/v1/approvals/" + cancelled + "/cancel", "mallory", "BROKER_MANAGER", Map.of("reason", "x"), null)
                .getBody().get("codes")).containsExactly("NOT_REQUESTER");
        assertThat(post("/api/v1/approvals/" + cancelled + "/cancel", "alice", "BROKER_MANAGER", Map.of("reason", "no longer needed"), null)
                .getBody()).containsEntry("status", "CANCELLED");

        assertThat(get("/api/v1/approvals/" + cancelled, "mallory", "BROKER_MANAGER").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void expiredApprovalsCanNeverBeApprovedOrConsumed() {
        Map<String, Object> body = payload("cfg-6", "TEST", 1);
        body.put("ttlSeconds", 120);
        String pending = (String) create("alice", "BROKER_MANAGER", body, null).getBody().get("id");
        Map<String, Object> body2 = payload("cfg-7", "TEST", 1);
        body2.put("ttlSeconds", 120);
        String approved = (String) create("alice", "BROKER_MANAGER", body2, null).getBody().get("id");
        decide(approved, "bob", "REVIEWER", "APPROVE");

        clock.advance(Duration.ofMinutes(3));

        assertThat(get("/api/v1/approvals/" + pending, "alice", "BROKER_MANAGER").getBody().get("approval"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("status", "EXPIRED");
        assertThat((List<Object>) decide(pending, "bob", "REVIEWER", "APPROVE").getBody().get("codes")).containsExactly("EXPIRED");
        assertThat((List<Object>) consume(approved, "svc", "SERVICE", payload("cfg-7", "TEST", 1)).getBody().get("codes"))
                .containsExactly("EXPIRED");
    }

    @Test
    void concurrentDecisionsHaveExactlyOneWinner() throws Exception {
        String id = (String) create("alice", "BROKER_MANAGER", payload("cfg-8", "TEST", 1), null).getBody().get("id");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<HttpStatus>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String reviewer = "reviewer" + i;
            calls.add(() -> HttpStatus.valueOf(decide(id, reviewer, "REVIEWER", i(reviewer) % 2 == 0 ? "APPROVE" : "REJECT")
                    .getStatusCode().value()));
        }
        List<HttpStatus> results = new ArrayList<>();
        for (Future<HttpStatus> future : pool.invokeAll(calls)) {
            results.add(future.get());
        }
        pool.shutdown();
        assertThat(results).filteredOn(s -> s == HttpStatus.OK).hasSize(1);
        assertThat(results).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(5);
    }

    @Test
    void idempotentCreationReturnsOriginalAndRejectsDifferentPayload() {
        ResponseEntity<Map<String, Object>> first = create("alice", "BROKER_MANAGER", payload("cfg-9", "TEST", 1), "idem-key-0001");
        ResponseEntity<Map<String, Object>> retry = create("alice", "BROKER_MANAGER", payload("cfg-9", "TEST", 1), "idem-key-0001");
        ResponseEntity<Map<String, Object>> changed = create("alice", "BROKER_MANAGER", payload("cfg-9", "TEST", 2), "idem-key-0001");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void auditLogIsHashChainedAppendOnlyAndRoleProtected() {
        create("alice", "BROKER_MANAGER", payload("cfg-10", "TEST", 1), null);
        Map<String, Object> event = new HashMap<>(Map.of("actor", "alice", "actorType", "USER", "action", "CERTIFICATION_RUN_REQUESTED",
                "resourceType", "certification-run", "resourceId", "run-1", "outcome", "ACCEPTED", "details", Map.of("fixVersion", "FIX44")));
        assertThat(post("/api/v1/audit-events", "certification-service", "SERVICE", event, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/v1/audit-events", "alice", "BROKER_MANAGER", event, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> verification = get("/api/v1/audit-events/verify", "auditor1", "AUDITOR").getBody();
        assertThat(verification).containsEntry("valid", true);
        assertThat(get("/api/v1/audit-events/verify", "alice", "BROKER_MANAGER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThatThrownBy(() -> jdbc.update("UPDATE workflow.audit_event SET outcome = 'FORGED' WHERE sequence = 1"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM workflow.audit_event WHERE sequence = 1"))
                .hasMessageContaining("append-only");

        Map<String, Object> page = get("/api/v1/audit-events?resourceType=certification-run&resourceId=run-1", "auditor1", "AUDITOR").getBody();
        assertThat((List<Object>) page.get("items")).hasSize(1);
    }

    @Test
    void tamperingThatBypassesTriggersIsDetectedByVerification() {
        create("alice", "BROKER_MANAGER", payload("cfg-11", "TEST", 1), null);
        jdbc.execute("ALTER TABLE workflow.audit_event DISABLE TRIGGER audit_event_append_only");
        try {
            jdbc.update("UPDATE workflow.audit_event SET outcome = 'FORGED' WHERE sequence = (SELECT MAX(sequence) FROM workflow.audit_event)");
            Map<String, Object> verification = get("/api/v1/audit-events/verify", "auditor1", "AUDITOR").getBody();
            assertThat(verification).containsEntry("valid", false);
            assertThat(verification.get("firstInvalidSequence")).isNotNull();
        } finally {
            jdbc.update("DELETE FROM workflow.audit_event WHERE outcome = 'FORGED'");
            jdbc.execute("ALTER TABLE workflow.audit_event ENABLE TRIGGER audit_event_append_only");
        }
    }

    private static int i(String reviewer) {
        return reviewer.charAt(reviewer.length() - 1) - '0';
    }

    private static Map<String, Object> payload(String targetId, String environment, int port) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "ACTIVATE_SESSION_CONFIG");
        body.put("targetType", "session-config");
        body.put("targetId", targetId);
        body.put("environment", environment);
        body.put("arguments", Map.of("host", "uat.broker.example", "port", port, "senderCompId", "FIXAI", "targetCompId", "BRK",
                "fixVersion", "FIX44", "heartbeatIntervalSeconds", 30));
        body.put("justification", "Onboarding UAT connectivity for certification");
        return body;
    }

    private ResponseEntity<Map<String, Object>> create(String user, String roles, Map<String, Object> body, String key) {
        return post("/api/v1/approvals", user, roles, body, key);
    }

    private ResponseEntity<Map<String, Object>> decide(String id, String user, String roles, String decision) {
        return post("/api/v1/approvals/" + id + "/decision", user, roles,
                Map.of("decision", decision, "rationale", "Reviewed configuration against onboarding checklist"), null);
    }

    private ResponseEntity<Map<String, Object>> consume(String id, String user, String roles, Map<String, Object> payload) {
        Map<String, Object> body = new HashMap<>(payload);
        body.remove("justification");
        body.remove("ttlSeconds");
        return post("/api/v1/approvals/" + id + "/consume", user, roles, body, null);
    }

    private ResponseEntity<Map<String, Object>> post(String path, String user, String roles, Object body, String key) {
        HttpHeaders headers = headers(user, roles);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String user, String roles) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(user, roles)), MAP);
    }

    private static HttpHeaders headers(String user, String roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Dev-User", user);
        headers.set("X-Dev-Roles", roles);
        return headers;
    }

    /** Test clock that can be advanced to exercise expiry. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
