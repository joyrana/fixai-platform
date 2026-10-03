package com.fixai.platform.broker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fixai.platform.broker.adapter.out.workflow.WorkflowApprovalGateway;
import com.fixai.platform.broker.application.port.outbound.ApprovalGateway;
import com.fixai.platform.broker.domain.session.SessionConfig;
import com.fixai.platform.broker.domain.session.SessionConfigStatus;
import com.fixai.platform.broker.domain.session.SessionEnvironment;
import com.fixai.platform.fixcore.FixSessionSpec;
import com.fixai.platform.fixcore.FixVersion;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WorkflowApprovalGatewayTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://workflow");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final WorkflowApprovalGateway gateway = new WorkflowApprovalGateway(builder.build());
    private final SessionConfig config = new SessionConfig(UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.randomUUID(), "UAT", SessionEnvironment.UAT, FixVersion.FIX44, FixSessionSpec.Role.INITIATOR, "FIXAI", "BRK",
            "uat.example", 9876, 30, 5, true, false, false, "vault:x", SessionConfigStatus.DRAFT, null, null, null, null,
            "alice", Instant.EPOCH, Instant.EPOCH, 3);

    @Test
    void requestsApprovalWithExactArgumentsOnBehalfOfUser() {
        server.expect(requestTo("http://workflow/api/v1/approvals")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.action").value("ACTIVATE_SESSION_CONFIG"))
                .andExpect(jsonPath("$.environment").value("UAT"))
                .andExpect(jsonPath("$.onBehalfOf").value("alice"))
                .andExpect(jsonPath("$.arguments.port").value(9876))
                .andExpect(jsonPath("$.arguments.configVersion").value(3))
                .andRespond(withSuccess("{\"id\":\"22222222-2222-2222-2222-222222222222\",\"payloadHash\":\"h\",\"status\":\"PENDING\"}",
                        MediaType.APPLICATION_JSON));

        ApprovalGateway.ApprovalTicket ticket = gateway.requestActivation(config, "justification text", "alice");

        assertThat(ticket.status()).isEqualTo("PENDING");
        server.verify();
    }

    @Test
    void mapsConsumeRefusalCodesAndOutages() {
        UUID approvalId = UUID.randomUUID();
        server.expect(requestTo("http://workflow/api/v1/approvals/" + approvalId + "/consume"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"detail\":\"mismatch\",\"codes\":[\"PAYLOAD_MISMATCH\"]}"));
        server.expect(requestTo("http://workflow/api/v1/approvals/" + approvalId + "/consume"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        ApprovalGateway.ConsumeResult refused = gateway.consume(approvalId, config);
        assertThat(refused.consumed()).isFalse();
        assertThat(refused.codes()).containsExactly("PAYLOAD_MISMATCH");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.consume(approvalId, config))
                .isInstanceOf(ApprovalGateway.Unavailable.class);
    }
}
