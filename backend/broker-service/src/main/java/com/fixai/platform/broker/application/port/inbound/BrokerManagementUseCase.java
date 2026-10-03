package com.fixai.platform.broker.application.port.inbound;

import com.fixai.platform.broker.domain.broker.Broker;
import com.fixai.platform.broker.domain.broker.BrokerStatus;

import java.util.List;
import java.util.UUID;

public interface BrokerManagementUseCase {

    Broker createBroker(CreateBrokerCommand command);

    Broker updateBroker(UUID brokerId, UpdateBrokerCommand command);

    Broker getBroker(UUID brokerId);

    List<Broker> listBrokers();

    Broker changeStatus(UUID brokerId, BrokerStatus status);

    void deleteBroker(UUID brokerId);

    record CreateBrokerCommand(String brokerCode, String name, String endpoint, BrokerStatus status) {
    }

    record UpdateBrokerCommand(String name, String endpoint, BrokerStatus status) {
    }
}
