package com.fixai.platform.broker.application.service;

import com.fixai.platform.broker.application.port.inbound.BrokerManagementUseCase;
import com.fixai.platform.broker.application.port.outbound.AuditPort;
import com.fixai.platform.broker.application.port.outbound.BrokerRepositoryPort;
import com.fixai.platform.broker.domain.broker.Broker;
import com.fixai.platform.broker.domain.broker.BrokerStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@org.springframework.transaction.annotation.Transactional
public class BrokerManagementService implements BrokerManagementUseCase {

    private final BrokerRepositoryPort brokerRepositoryPort;
    private final AuditPort audit;

    public BrokerManagementService(BrokerRepositoryPort brokerRepositoryPort, AuditPort audit) {
        this.brokerRepositoryPort = brokerRepositoryPort;
        this.audit = audit;
    }

    @Override
    public Broker createBroker(CreateBrokerCommand command) {
        brokerRepositoryPort.findByBrokerCode(command.brokerCode())
                .ifPresent(existing -> {
                    throw new BrokerAlreadyExistsException("Broker code already exists: " + command.brokerCode());
                });

        Instant now = Instant.now();
        Broker broker = new Broker(
                UUID.randomUUID(),
                command.brokerCode(),
                command.name(),
                command.endpoint(),
                command.status(),
                now,
                now);
        Broker saved = brokerRepositoryPort.save(broker);
        audit.record("BROKER_CREATED", "broker", saved.id().toString(), saved.status().name(),
                Map.of("brokerCode", saved.brokerCode()));
        return saved;
    }

    @Override
    public Broker updateBroker(UUID brokerId, UpdateBrokerCommand command) {
        Broker existing = getBroker(brokerId);
        Broker updated = new Broker(
                existing.id(),
                existing.brokerCode(),
                command.name(),
                command.endpoint(),
                command.status(),
                existing.createdAt(),
                Instant.now());
        Broker saved = brokerRepositoryPort.save(updated);
        audit.record("BROKER_UPDATED", "broker", brokerId.toString(), saved.status().name(), Map.of());
        return saved;
    }

    @Override
    public Broker getBroker(UUID brokerId) {
        return brokerRepositoryPort.findById(brokerId)
                .orElseThrow(() -> new BrokerNotFoundException("Broker not found: " + brokerId));
    }

    @Override
    public List<Broker> listBrokers() {
        return brokerRepositoryPort.findAll();
    }

    @Override
    public Broker changeStatus(UUID brokerId, BrokerStatus status) {
        Broker existing = getBroker(brokerId);
        Broker updated = new Broker(
                existing.id(),
                existing.brokerCode(),
                existing.name(),
                existing.endpoint(),
                status,
                existing.createdAt(),
                Instant.now());
        Broker saved = brokerRepositoryPort.save(updated);
        audit.record("BROKER_STATUS_CHANGED", "broker", brokerId.toString(), status.name(),
                Map.of("previousStatus", existing.status().name()));
        return saved;
    }

    @Override
    public void deleteBroker(UUID brokerId) {
        getBroker(brokerId);
        brokerRepositoryPort.deleteById(brokerId);
        audit.record("BROKER_DELETED", "broker", brokerId.toString(), "DELETED", Map.of());
    }
}
