package com.fixai.platform.certification.adapter.out.workflow;

import com.fixai.platform.certification.application.port.out.ApprovalPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Consumes approvals in workflow-service with this service's own credentials (role SERVICE). */
public class WorkflowApprovalAdapter implements ApprovalPort {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private final RestClient workflow;

    public WorkflowApprovalAdapter(RestClient workflow) {
        this.workflow = workflow;
    }

    @Override
    public ConsumeOutcome consume(UUID approvalId, ApprovalPayload payload, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", payload.action());
        body.put("targetType", payload.targetType());
        body.put("targetId", payload.targetId());
        body.put("environment", payload.environment());
        body.put("arguments", payload.arguments());
        try {
            Map<String, Object> response = workflow.post().uri("/api/v1/approvals/{id}/consume", approvalId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .body(body).retrieve().body(MAP);
            return new ConsumeOutcome(true, response == null ? null : (String) response.get("payloadHash"), List.of(), null);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 404) {
                return ConsumeOutcome.refused(List.of("APPROVAL_NOT_FOUND"), "Approval not found");
            }
            if (status == 409 || status == 422 || status == 403) {
                Map<String, Object> problem = parse(exception);
                return ConsumeOutcome.refused(codes(problem, status),
                        problem.get("detail") == null ? "Approval refused" : String.valueOf(problem.get("detail")));
            }
            return ConsumeOutcome.refused(List.of("APPROVALS_UNAVAILABLE"), "Workflow service returned " + status);
        } catch (RestClientException exception) {
            return ConsumeOutcome.refused(List.of("APPROVALS_UNAVAILABLE"), "Workflow service unavailable");
        }
    }

    private static Map<String, Object> parse(RestClientResponseException exception) {
        try {
            Map<String, Object> problem = exception.getResponseBodyAs(MAP);
            return problem == null ? Map.of() : problem;
        } catch (RuntimeException parseFailure) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Map<String, Object> problem, int status) {
        Object codes = problem.get("codes");
        return codes instanceof List<?> list && !list.isEmpty() ? (List<String>) list : List.of("HTTP_" + status);
    }
}
