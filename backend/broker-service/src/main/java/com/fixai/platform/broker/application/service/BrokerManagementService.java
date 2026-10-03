package com.fixai.platform.broker.application.service;

import com.fixai.platform.broker.application.port.inbound.BrokerManagementUseCase;
import com.fixai.platform.broker.application.port.outbound.BrokerRepositoryPort;
import com.fixai.platform.broker.domain.broker.Broker;
import com.fixai.platform.broker.domain.broker.BrokerStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BrokerManagementService implements BrokerManagementUseCase {

    private final BrokerRepositoryPort brokerRepositoryPort;

    public BrokerManagementService(BrokerRepositoryPort brokerRepositoryPort) {
        this.brokerRepositoryPort = brokerRepositoryPort;
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
        return brokerRepositoryPort.save(broker);
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
        return brokerRepositoryPort.save(updated);
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
        return brokerRepositoryPort.save(updated);
    }

    @Override
    public void deleteBroker(UUID brokerId) {
        getBroker(brokerId);
        brokerRepositoryPort.deleteById(brokerId);
    }
}
