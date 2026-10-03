package com.fixai.platform.broker.adapter.out.workflow;

import com.fixai.platform.broker.application.port.outbound.ApprovalGateway;
import com.fixai.platform.broker.domain.session.SessionConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** HTTP adapter to workflow-service. Uses this service's credentials and files requests on behalf of the user. */
public class WorkflowApprovalGateway implements ApprovalGateway {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private final RestClient workflow;

    public WorkflowApprovalGateway(RestClient workflow) {
        this.workflow = workflow;
    }

    @Override
    public ApprovalTicket requestActivation(SessionConfig config, String justification, String requestedBy) {
        Map<String, Object> body = payload(config);
        body.put("justification", justification);
        body.put("onBehalfOf", requestedBy);
        body.put("evidenceRefs", List.of("session-config:" + config.id() + "@v" + config.version()));
        try {
            Map<String, Object> response = workflow.post().uri("/api/v1/approvals").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "session-config-" + config.id() + "-v" + config.version() + "-" + requestedBy.hashCode())
                    .body(body).retrieve().body(MAP);
            return new ApprovalTicket(UUID.fromString((String) response.get("id")), (String) response.get("payloadHash"),
                    (String) response.get("status"));
        } catch (RestClientResponseException exception) {
            throw new ApprovalRefusedException(codes(exception), exception.getStatusCode());
        } catch (RestClientException exception) {
            throw new Unavailable("Workflow service unavailable", exception);
        }
    }

    @Override
    public ConsumeResult consume(UUID approvalId, SessionConfig config) {
        try {
            Map<String, Object> response = workflow.post().uri("/api/v1/approvals/{id}/consume", approvalId)
                    .contentType(MediaType.APPLICATION_JSON).body(payload(config)).retrieve().body(MAP);
            return new ConsumeResult(true, (String) response.get("payloadHash"), List.of(), null);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                Map<String, Object> problem = exception.getResponseBodyAs(MAP);
                return new ConsumeResult(false, null, codes(exception),
                        problem == null ? "Approval refused" : String.valueOf(problem.get("detail")));
            }
            throw new Unavailable("Workflow service returned " + exception.getStatusCode().value(), exception);
        } catch (RestClientException exception) {
            throw new Unavailable("Workflow service unavailable", exception);
        }
    }

    @Override
    public Optional<String> status(UUID approvalId) {
        try {
            Map<String, Object> response = workflow.get().uri("/api/v1/approvals/{id}", approvalId).retrieve().body(MAP);
            Object approval = response == null ? null : response.get("approval");
            return approval instanceof Map<?, ?> map ? Optional.ofNullable((String) map.get("status")) : Optional.empty();
        } catch (RestClientException exception) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> payload(SessionConfig config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", ACTION);
        body.put("targetType", TARGET_TYPE);
        body.put("targetId", config.id().toString());
        body.put("environment", config.environment().name());
        body.put("arguments", config.approvalArguments());
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(RestClientResponseException exception) {
        try {
            Map<String, Object> problem = exception.getResponseBodyAs(MAP);
            Object codes = problem == null ? null : problem.get("codes");
            return codes instanceof List<?> list ? (List<String>) list : List.of("HTTP_" + exception.getStatusCode().value());
        } catch (RuntimeException parse) {
            return List.of("HTTP_" + exception.getStatusCode().value());
        }
    }

    /** Workflow refused to create the approval request (policy or validation). */
    public static final class ApprovalRefusedException extends RuntimeException {
        private final List<String> codes;
        private final HttpStatusCode status;

        public ApprovalRefusedException(List<String> codes, HttpStatusCode status) {
            super("Approval request refused: " + String.join(",", codes));
            this.codes = List.copyOf(codes);
            this.status = status;
        }

        public List<String> codes() {
            return codes;
        }

        public HttpStatusCode status() {
            return status;
        }
    }
}
