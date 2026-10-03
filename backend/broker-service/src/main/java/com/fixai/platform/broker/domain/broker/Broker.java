package com.fixai.platform.broker.domain.broker;

import java.time.Instant;
import java.util.UUID;

public record Broker(
        UUID id,
        String brokerCode,
        String name,
        String endpoint,
        BrokerStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
