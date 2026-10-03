package com.fixai.platform.workflow.adapter.web;

import com.fixai.platform.web.CorrelationIdFilter;
import com.fixai.platform.web.CurrentActor;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.ApprovalDetail;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.ApprovalResponse;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.CancelRequest;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.ConsumeRequest;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.CreateApprovalRequest;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.DecisionRequest;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.PageResponse;
import com.fixai.platform.workflow.application.service.ApprovalService;
import com.fixai.platform.workflow.domain.approval.ApprovalPayload;
import com.fixai.platform.workflow.domain.approval.ApprovalStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals", description = "Human approval of privileged actions, bound to the exact action payload")
public class ApprovalController {

    private final ApprovalService approvals;
    private final CurrentActor actors;

    public ApprovalController(ApprovalService approvals, CurrentActor actors) {
        this.approvals = approvals;
        this.actors = actors;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','BROKER_MANAGER','CERTIFICATION_ENGINEER','SERVICE','AI_AGENT')")
    @Operation(summary = "Request approval for an action. The server computes and binds the payload hash.")
    public ResponseEntity<ApprovalResponse> create(
            @Valid @RequestBody CreateApprovalRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}") String key,
            HttpServletRequest http) {
        ApprovalService.CreateResult result = approvals.create(new ApprovalService.CreateCommand(
                new ApprovalPayload(request.action(), request.targetType(), request.targetId(), request.environment(),
                        request.arguments()),
                request.justification(), request.risk(), request.evidenceRefs(), request.traceIds(),
                request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds()), request.onBehalfOf()),
                actors.get(), CorrelationIdFilter.current(http), key);
        ApprovalResponse body = ApprovalResponse.of(result.request());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/api/v1/approvals/" + body.id())).body(body);
    }

    @GetMapping
    @Operation(summary = "List approval requests (reviewers, auditors and services see all; others see their own)")
    public PageResponse<ApprovalResponse> list(
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var actor = actors.get();
        return new PageResponse<>(approvals.list(status, actor, page, size).stream().map(ApprovalResponse::of).toList(),
                page, size, approvals.count(status, actor));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Approval request with its decision history")
    public ApprovalDetail get(@PathVariable UUID id) {
        var actor = actors.get();
        return new ApprovalDetail(ApprovalResponse.of(approvals.get(id, actor)), approvals.history(id, actor));
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Approve, reject or request changes. Four-eyes: requesters cannot decide their own requests.")
    public ApprovalResponse decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request, HttpServletRequest http) {
        return ApprovalResponse.of(approvals.decide(id, request.decision(), request.rationale(), actors.get(),
                CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending or unused approval (requester or administrator)")
    public ApprovalResponse cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelRequest request,
                                   HttpServletRequest http) {
        return ApprovalResponse.of(approvals.cancel(id, request == null ? null : request.reason(), actors.get(),
                CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{id}/consume")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Single-use consumption at execution time; the presented action must hash to the approved payload")
    public ApprovalResponse consume(@PathVariable UUID id, @Valid @RequestBody ConsumeRequest request, HttpServletRequest http) {
        return ApprovalResponse.of(approvals.consume(id, new ApprovalPayload(request.action(), request.targetType(),
                request.targetId(), request.environment(), request.arguments()), actors.get(), CorrelationIdFilter.current(http)));
    }
}
