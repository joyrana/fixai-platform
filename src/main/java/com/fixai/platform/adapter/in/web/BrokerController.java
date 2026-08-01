package com.fixai.platform.adapter.in.web;

import com.fixai.platform.adapter.in.web.dto.BrokerRequest;
import com.fixai.platform.adapter.in.web.dto.BrokerResponse;
import com.fixai.platform.application.port.inbound.BrokerManagementUseCase;
import com.fixai.platform.application.port.inbound.BrokerManagementUseCase.CreateBrokerCommand;
import com.fixai.platform.application.port.inbound.BrokerManagementUseCase.UpdateBrokerCommand;
import com.fixai.platform.domain.broker.Broker;
import com.fixai.platform.domain.broker.BrokerStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/brokers")
@Tag(name = "Broker Management", description = "REST API for managing broker onboarding and lifecycle")
public class BrokerController {

    private final BrokerManagementUseCase brokerManagementUseCase;

    public BrokerController(BrokerManagementUseCase brokerManagementUseCase) {
        this.brokerManagementUseCase = brokerManagementUseCase;
    }

    @PostMapping
    @Operation(summary = "Create a broker")
    public ResponseEntity<BrokerResponse> createBroker(@Valid @RequestBody BrokerRequest request) {
        Broker created = brokerManagementUseCase.createBroker(new CreateBrokerCommand(
                request.brokerCode(),
                request.name(),
                request.endpoint(),
                request.status()));
        return ResponseEntity.created(URI.create("/api/v1/brokers/" + created.id())).body(toResponse(created));
    }

    @GetMapping
    @Operation(summary = "List brokers")
    public List<BrokerResponse> listBrokers() {
        return brokerManagementUseCase.listBrokers().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{brokerId}")
    @Operation(summary = "Get broker by id")
    public BrokerResponse getBroker(@PathVariable UUID brokerId) {
        return toResponse(brokerManagementUseCase.getBroker(brokerId));
    }

    @PutMapping("/{brokerId}")
    @Operation(summary = "Update broker")
    public BrokerResponse updateBroker(@PathVariable UUID brokerId, @Valid @RequestBody BrokerRequest request) {
        Broker updated = brokerManagementUseCase.updateBroker(brokerId, new UpdateBrokerCommand(
                request.name(),
                request.endpoint(),
                request.status()));
        return toResponse(updated);
    }

    @PatchMapping("/{brokerId}/status/{status}")
    @Operation(summary = "Change broker status")
    public BrokerResponse changeStatus(
            @Parameter(description = "Broker id") @PathVariable UUID brokerId,
            @Parameter(description = "New broker status") @PathVariable BrokerStatus status) {
        return toResponse(brokerManagementUseCase.changeStatus(brokerId, status));
    }

    @DeleteMapping("/{brokerId}")
    @Operation(summary = "Delete broker")
    public ResponseEntity<Void> deleteBroker(@PathVariable UUID brokerId) {
        brokerManagementUseCase.deleteBroker(brokerId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private BrokerResponse toResponse(Broker broker) {
        return new BrokerResponse(
                broker.id(),
                broker.brokerCode(),
                broker.name(),
                broker.endpoint(),
                broker.status(),
                broker.createdAt(),
                broker.updatedAt());
    }
}
