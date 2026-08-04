package com.fixai.platform.adapter.in.web;

import com.fixai.platform.application.port.inbound.BrokerManagementUseCase;
import com.fixai.platform.application.port.inbound.BrokerManagementUseCase.CreateBrokerCommand;
import com.fixai.platform.domain.broker.Broker;
import com.fixai.platform.domain.broker.BrokerStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BrokerController.class)
class BrokerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BrokerManagementUseCase brokerManagementUseCase;

    @Test
    void createBrokerShouldReturnCreatedResponse() throws Exception {
        Broker broker = broker();
        when(brokerManagementUseCase.createBroker(any(CreateBrokerCommand.class))).thenReturn(broker);

        mockMvc.perform(post("/api/v1/brokers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "brokerCode": "BRK-001",
                                  "name": "Prime Broker",
                                  "endpoint": "fix://localhost:9876",
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/brokers/123e4567-e89b-12d3-a456-426614174000"))
                .andExpect(jsonPath("$.brokerCode").value("BRK-001"));
    }

    @Test
    void createBrokerShouldValidateInput() throws Exception {
        mockMvc.perform(post("/api/v1/brokers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "brokerCode": "",
                                  "name": "",
                                  "endpoint": "",
                                  "status": null
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listBrokersShouldReturnArray() throws Exception {
        when(brokerManagementUseCase.listBrokers()).thenReturn(List.of(broker()));

        mockMvc.perform(get("/api/v1/brokers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void updateBrokerShouldReturnUpdatedBroker() throws Exception {
        when(brokerManagementUseCase.updateBroker(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(broker());

        mockMvc.perform(put("/api/v1/brokers/{brokerId}", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "brokerCode": "BRK-001",
                                  "name": "Prime Broker",
                                  "endpoint": "fix://localhost:9876",
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Prime Broker"));
    }

    @Test
    void changeStatusShouldReturnUpdatedBroker() throws Exception {
        when(brokerManagementUseCase.changeStatus(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(BrokerStatus.SUSPENDED)))
                .thenReturn(broker());

        mockMvc.perform(patch("/api/v1/brokers/{brokerId}/status/{status}", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "SUSPENDED"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteBrokerShouldReturnNoContent() throws Exception {
        doNothing().when(brokerManagementUseCase).deleteBroker(org.mockito.ArgumentMatchers.any());

        mockMvc.perform(delete("/api/v1/brokers/{brokerId}", UUID.fromString("123e4567-e89b-12d3-a456-426614174000")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private Broker broker() {
        return new Broker(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "BRK-001", "Prime Broker", "fix://localhost:9876", BrokerStatus.ACTIVE, Instant.parse("2026-08-01T10:15:30Z"), Instant.parse("2026-08-01T10:15:30Z"));
    }
}
