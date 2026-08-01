package com.fixai.platform.adapter.in.web.dto;

import com.fixai.platform.domain.broker.BrokerStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "BrokerResponse")
public record BrokerResponse(
        UUID id,
        String brokerCode,
        String name,
        String endpoint,
        BrokerStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
