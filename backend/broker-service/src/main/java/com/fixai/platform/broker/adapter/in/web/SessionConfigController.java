package com.fixai.platform.broker.adapter.in.web;

import com.fixai.platform.broker.adapter.in.web.dto.SessionConfigDtos.SessionConfigRequest;
import com.fixai.platform.broker.adapter.in.web.dto.SessionConfigDtos.SessionConfigResponse;
import com.fixai.platform.broker.adapter.in.web.dto.SessionConfigDtos.SubmitRequest;
import com.fixai.platform.broker.adapter.in.web.dto.SessionConfigDtos.ValidationResponse;
import com.fixai.platform.broker.application.service.SessionConfigService;
import com.fixai.platform.broker.application.service.SessionConfigService.ConfigFields;
import com.fixai.platform.web.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "FIX session configurations", description = "Configure, validate, approve and activate broker FIX sessions")
public class SessionConfigController {

    private final SessionConfigService service;
    private final CurrentActor actors;

    public SessionConfigController(SessionConfigService service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/api/v1/brokers/{brokerId}/session-configs")
    @PreAuthorize("hasAnyRole('ADMIN','BROKER_MANAGER')")
    @Operation(summary = "Create a draft FIX session configuration (TEST or UAT only)")
    public ResponseEntity<SessionConfigResponse> create(@PathVariable UUID brokerId, @Valid @RequestBody SessionConfigRequest request) {
        SessionConfigResponse body = SessionConfigResponse.of(service.create(brokerId, fields(request), actors.get().id()));
        return ResponseEntity.created(URI.create("/api/v1/session-configs/" + body.id())).body(body);
    }

    @GetMapping("/api/v1/brokers/{brokerId}/session-configs")
    @Operation(summary = "List a broker's session configurations")
    public List<SessionConfigResponse> list(@PathVariable UUID brokerId) {
        return service.forBroker(brokerId).stream().map(SessionConfigResponse::of).toList();
    }

    @GetMapping("/api/v1/session-configs/{id}")
    @Operation(summary = "Get a session configuration (credential references only, never secrets)")
    public SessionConfigResponse get(@PathVariable UUID id) {
        return SessionConfigResponse.of(service.get(id));
    }

    @PutMapping("/api/v1/session-configs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','BROKER_MANAGER')")
    @Operation(summary = "Update; returns the configuration to DRAFT and invalidates any approval. Requires expectedVersion.")
    public SessionConfigResponse update(@PathVariable UUID id, @Valid @RequestBody SessionConfigRequest request) {
        long expected = request.expectedVersion() == null ? service.get(id).version() : request.expectedVersion();
        return SessionConfigResponse.of(service.update(id, fields(request), expected, actors.get().id()));
    }

    @PostMapping("/api/v1/session-configs/{id}/validate")
    @Operation(summary = "Validate protocol and platform rules without changing state")
    public ValidationResponse validate(@PathVariable UUID id) {
        var violations = service.validate(id);
        return new ValidationResponse(violations.isEmpty(), violations);
    }

    @PostMapping("/api/v1/session-configs/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','BROKER_MANAGER')")
    @Operation(summary = "Submit for approval; the workflow service binds the approval to this exact configuration")
    public SessionConfigResponse submit(@PathVariable UUID id, @Valid @RequestBody SubmitRequest request) {
        return SessionConfigResponse.of(service.submit(id, request.justification(), actors.get().id()));
    }

    @PostMapping("/api/v1/session-configs/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','BROKER_MANAGER')")
    @Operation(summary = "Activate after approval: re-validates and consumes the approval (single use, hash-checked)")
    public SessionConfigResponse activate(@PathVariable UUID id) {
        return SessionConfigResponse.of(service.activate(id, actors.get().id()));
    }

    @PostMapping("/api/v1/session-configs/{id}/retire")
    @PreAuthorize("hasAnyRole('ADMIN','BROKER_MANAGER')")
    @Operation(summary = "Retire a configuration permanently")
    public SessionConfigResponse retire(@PathVariable UUID id) {
        return SessionConfigResponse.of(service.retire(id, actors.get().id()));
    }

    private static ConfigFields fields(SessionConfigRequest r) {
        return new ConfigFields(r.name(), r.environment(), r.fixVersion(), r.role(), r.senderCompId(), r.targetCompId(),
                r.host(), r.port(), r.heartbeatIntervalSeconds(), r.reconnectIntervalSeconds(), r.resetOnLogon(),
                r.resetOnLogout(), r.resetOnDisconnect(), r.credentialRef());
    }
}
