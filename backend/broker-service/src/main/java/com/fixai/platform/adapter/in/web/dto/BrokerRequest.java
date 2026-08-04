package com.fixai.platform.adapter.in.web.dto;

import com.fixai.platform.domain.broker.BrokerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "BrokerRequest")
public record BrokerRequest(
        @NotBlank @Size(max = 40)
        String brokerCode,
        @NotBlank @Size(max = 120)
        String name,
        @NotBlank @Size(max = 255)
        String endpoint,
        @NotNull
        BrokerStatus status) {
}
