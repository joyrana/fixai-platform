package com.fixai.platform.broker.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixai.platform.broker.application.port.outbound.ApprovalGateway;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        properties = {"fixai.audit.workflow-url=http://127.0.0.1:1", "fixai.audit.relay-interval=PT1H"})
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("unchecked")
class BrokerServiceIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class Gateways {
        @Bean
        @Primary
        FakeApprovalGateway fakeApprovalGateway() {
            return new FakeApprovalGateway();
        }
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    FakeApprovalGateway approvals;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sessionConfigLifecycleFromDraftToApprovedWithHashBinding() {
        String brokerId = createBroker("BRK-LIFE");
        ResponseEntity<Map<String, Object>> created = send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs",
                config("FIXAI-LIFE", 9876), "BROKER_MANAGER");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        assertThat(created.getBody()).containsEntry("status", "DRAFT").containsEntry("beginString", "FIX.4.4");

        assertThat(send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/activate", null, "BROKER_MANAGER").getBody().get("codes"))
                .asList().containsExactly("NOT_SUBMITTED");

        Map<String, Object> submitted = send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/submit",
                Map.of("justification", "UAT onboarding for certification"), "BROKER_MANAGER").getBody();
        assertThat(submitted).containsEntry("status", "PENDING_APPROVAL");
        UUID approvalId = UUID.fromString((String) submitted.get("approvalRequestId"));

        ResponseEntity<Map<String, Object>> early = send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/activate", null, "BROKER_MANAGER");
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody().get("codes")).asList().containsExactly("NOT_APPROVED");

        // Editing after submission returns the config to DRAFT: the pending approval no longer matches it.
        Map<String, Object> edited = config("FIXAI-LIFE", 9877);
        edited.put("expectedVersion", 1);
        assertThat(send(HttpMethod.PUT, "/api/v1/session-configs/" + id, edited, "BROKER_MANAGER").getBody())
                .containsEntry("status", "DRAFT").containsEntry("version", 2);
        approvals.decide(approvalId, "APPROVED");
        assertThat(send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/activate", null, "BROKER_MANAGER").getBody().get("codes"))
                .asList().containsExactly("NOT_SUBMITTED");

        UUID second = UUID.fromString((String) send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/submit",
                Map.of("justification", "UAT onboarding, port corrected"), "BROKER_MANAGER").getBody().get("approvalRequestId"));
        approvals.decide(second, "APPROVED");
        Map<String, Object> active = send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/activate", null, "BROKER_MANAGER").getBody();
        assertThat(active).containsEntry("status", "APPROVED").containsEntry("activatedBy", "dev-BROKER_MANAGER");
        assertThat((String) active.get("approvedPayloadHash")).hasSize(64);

        List<String> actions = jdbc.queryForList("SELECT payload->>'action' FROM broker.audit_outbox ORDER BY created_at", String.class);
        assertThat(actions).contains("BROKER_CREATED", "SESSION_CONFIG_CREATED", "SESSION_CONFIG_SUBMITTED",
                "SESSION_CONFIG_UPDATED", "SESSION_CONFIG_ACTIVATION_REFUSED", "SESSION_CONFIG_ACTIVATED");
    }

    @Test
    void rejectedApprovalIsReflectedAndConfigCanBeResubmitted() {
        String brokerId = createBroker("BRK-REJ");
        String id = (String) send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs",
                config("FIXAI-REJ", 9876), "BROKER_MANAGER").getBody().get("id");
        UUID approval = UUID.fromString((String) send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/submit",
                Map.of("justification", "UAT onboarding for certification"), "BROKER_MANAGER").getBody().get("approvalRequestId"));
        approvals.decide(approval, "REJECTED");

        assertThat(send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/activate", null, "BROKER_MANAGER").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(HttpMethod.GET, "/api/v1/session-configs/" + id, null, "AUDITOR").getBody()).containsEntry("status", "REJECTED");
        assertThat(send(HttpMethod.POST, "/api/v1/session-configs/" + id + "/submit",
                Map.of("justification", "Addressed reviewer comments"), "BROKER_MANAGER").getBody())
                .containsEntry("status", "PENDING_APPROVAL");
    }

    @Test
    void validationRejectsUnsafeOrInvalidConfigurations() {
        String brokerId = createBroker("BRK-VAL");
        Map<String, Object> bad = config("BAD ID", 70000);
        bad.put("credentialRef", "password=hunter2");
        bad.put("host", "169.254.169.254");
        bad.put("port", 9876);
        ResponseEntity<Map<String, Object>> invalid = send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs", bad, "BROKER_MANAGER");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((List<Map<String, Object>>) invalid.getBody().get("violations")).extracting(v -> v.get("code"))
                .contains("INVALID_COMP_ID", "INVALID_CREDENTIAL_REF", "FORBIDDEN_HOST");
        assertThat(invalid.getBody().toString()).doesNotContain("hunter2");

        Map<String, Object> production = config("FIXAI-PROD", 9876);
        production.put("environment", "PRODUCTION");
        assertThat(send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs", production, "BROKER_MANAGER")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs", config("FIXAI-DUP", 9876), "BROKER_MANAGER");
        ResponseEntity<Map<String, Object>> duplicate = send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs",
                config("FIXAI-DUP", 9999), "BROKER_MANAGER");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("codes")).asList().containsExactly("DUPLICATE_SESSION");
    }

    @Test
    void rolesBrokerStateAndReferentialIntegrityAreEnforced() {
        String brokerId = createBroker("BRK-ROLE");
        assertThat(send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs", config("FIXAI-ROLE", 9876), "AUDITOR")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs", config("FIXAI-ROLE", 9876), "BROKER_MANAGER")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(send(HttpMethod.DELETE, "/api/v1/brokers/" + brokerId, null, "ADMIN").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        send(HttpMethod.PATCH, "/api/v1/brokers/" + brokerId + "/status/SUSPENDED", null, "ADMIN");
        ResponseEntity<Map<String, Object>> suspended = send(HttpMethod.POST, "/api/v1/brokers/" + brokerId + "/session-configs",
                config("FIXAI-ROLE2", 9876), "BROKER_MANAGER");
        assertThat(suspended.getBody().get("codes")).asList().containsExactly("BROKER_NOT_ACTIVE");

        ResponseEntity<Map<String, Object>> duplicateBroker = send(HttpMethod.POST, "/api/v1/brokers",
                Map.of("brokerCode", "brk-role", "name", "Dup", "endpoint", "fix://x", "status", "ACTIVE"), "ADMIN");
        assertThat(duplicateBroker.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private String createBroker(String code) {
        return (String) send(HttpMethod.POST, "/api/v1/brokers",
                Map.of("brokerCode", code, "name", "Test Broker " + code, "endpoint", "fix://uat.example:9876", "status", "ACTIVE"),
                "ADMIN").getBody().get("id");
    }

    private static Map<String, Object> config(String sender, int port) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "UAT order routing");
        body.put("environment", "UAT");
        body.put("fixVersion", "FIX44");
        body.put("senderCompId", sender);
        body.put("targetCompId", "BROKER-UAT");
        body.put("host", "uat.broker.example");
        body.put("port", port);
        body.put("heartbeatIntervalSeconds", 30);
        body.put("reconnectIntervalSeconds", 5);
        body.put("resetOnLogon", true);
        body.put("credentialRef", "vault:fix/brk/uat");
        return body;
    }

    private ResponseEntity<Map<String, Object>> send(HttpMethod method, String path, Object body, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Dev-User", "dev-" + role);
        headers.set("X-Dev-Roles", role);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }
}
