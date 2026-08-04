package com.fixai.platform.application.port.outbound;

import com.fixai.platform.domain.broker.Broker;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrokerRepositoryPort {

    Broker save(Broker broker);

    Optional<Broker> findById(UUID brokerId);

    Optional<Broker> findByBrokerCode(String brokerCode);

    List<Broker> findAll();

    void deleteById(UUID brokerId);
}
