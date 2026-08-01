package com.fixai.platform.adapter.out.persistence;

import com.fixai.platform.application.port.outbound.BrokerRepositoryPort;
import com.fixai.platform.domain.broker.Broker;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryBrokerRepositoryAdapter implements BrokerRepositoryPort {

    private final Map<UUID, Broker> storage = new ConcurrentHashMap<>();

    @Override
    public Broker save(Broker broker) {
        storage.put(broker.id(), broker);
        return broker;
    }

    @Override
    public Optional<Broker> findById(UUID brokerId) {
        return Optional.ofNullable(storage.get(brokerId));
    }

    @Override
    public Optional<Broker> findByBrokerCode(String brokerCode) {
        return storage.values().stream()
                .filter(broker -> broker.brokerCode().equalsIgnoreCase(brokerCode))
                .findFirst();
    }

    @Override
    public List<Broker> findAll() {
        return storage.values().stream()
                .sorted(Comparator.comparing(Broker::createdAt).reversed())
                .toList();
    }

    @Override
    public void deleteById(UUID brokerId) {
        storage.remove(brokerId);
    }
}
