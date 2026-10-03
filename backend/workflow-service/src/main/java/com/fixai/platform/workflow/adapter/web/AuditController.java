package com.fixai.platform.workflow.adapter.web;

import com.fixai.platform.web.CurrentActor;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.AuditEventRequest;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.AuditEventResponse;
import com.fixai.platform.workflow.adapter.web.WorkflowDtos.PageResponse;
import com.fixai.platform.workflow.application.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit", description = "Append-only, hash-chained audit log")
public class AuditController {

    private final AuditService audit;
    private final CurrentActor actors;

    public AuditController(AuditService audit, CurrentActor actors) {
        this.audit = audit;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Append an audit event (platform services only). The authenticated caller is recorded as recordedBy.")
    public AuditEventResponse append(@Valid @RequestBody AuditEventRequest request) {
        return AuditEventResponse.of(audit.append(request.actor(), request.actorType(), actors.get().id(), request.action(),
                request.resourceType(), request.resourceId(), request.correlationId(), request.outcome(),
                request.details() == null ? Map.of() : request.details()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN','REVIEWER')")
    @Operation(summary = "Query the audit log, newest first")
    public PageResponse<AuditEventResponse> list(
            @RequestParam(required = false) @Pattern(regexp = "[a-z0-9-]{2,64}") String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{3,64}") String action,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return new PageResponse<>(audit.list(resourceType, resourceId, action, page, size).stream()
                .map(AuditEventResponse::of).toList(), page, size, audit.count(resourceType, resourceId, action));
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    @Operation(summary = "Recompute the hash chain and report the first inconsistent event, if any")
    public AuditService.Verification verify() {
        return audit.verify();
    }
}
