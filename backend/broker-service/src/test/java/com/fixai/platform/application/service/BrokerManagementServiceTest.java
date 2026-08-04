package com.fixai.platform.application.service;

import com.fixai.platform.application.port.inbound.BrokerManagementUseCase.CreateBrokerCommand;
import com.fixai.platform.application.port.inbound.BrokerManagementUseCase.UpdateBrokerCommand;
import com.fixai.platform.application.port.outbound.BrokerRepositoryPort;
import com.fixai.platform.domain.broker.Broker;
import com.fixai.platform.domain.broker.BrokerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrokerManagementServiceTest {

    @Mock
    private BrokerRepositoryPort brokerRepositoryPort;

    private BrokerManagementService service;

    @BeforeEach
    void setUp() {
        service = new BrokerManagementService(brokerRepositoryPort);
    }

    @Test
    void createBrokerShouldPersistNewBroker() {
        when(brokerRepositoryPort.findByBrokerCode("BRK-001")).thenReturn(Optional.empty());
        when(brokerRepositoryPort.save(org.mockito.ArgumentMatchers.any(Broker.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Broker created = service.createBroker(new CreateBrokerCommand("BRK-001", "Prime Broker", "fix://localhost:9876", BrokerStatus.ACTIVE));

        assertThat(created.id()).isNotNull();
        assertThat(created.brokerCode()).isEqualTo("BRK-001");
        assertThat(created.status()).isEqualTo(BrokerStatus.ACTIVE);
        verify(brokerRepositoryPort).save(org.mockito.ArgumentMatchers.any(Broker.class));
    }

    @Test
    void createBrokerShouldRejectDuplicateBrokerCode() {
        when(brokerRepositoryPort.findByBrokerCode("BRK-001"))
                .thenReturn(Optional.of(existingBroker()));

        assertThatThrownBy(() -> service.createBroker(new CreateBrokerCommand("BRK-001", "Prime Broker", "fix://localhost:9876", BrokerStatus.ACTIVE)))
                .isInstanceOf(BrokerAlreadyExistsException.class)
                .hasMessageContaining("Broker code already exists");
    }

    @Test
    void updateBrokerShouldPreserveImmutableFields() {
        Broker existing = existingBroker();
        when(brokerRepositoryPort.findById(existing.id())).thenReturn(Optional.of(existing));
        when(brokerRepositoryPort.save(org.mockito.ArgumentMatchers.any(Broker.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Broker updated = service.updateBroker(existing.id(), new UpdateBrokerCommand("Updated Broker", "fix://updated:9876", BrokerStatus.SUSPENDED));

        assertThat(updated.id()).isEqualTo(existing.id());
        assertThat(updated.brokerCode()).isEqualTo(existing.brokerCode());
        assertThat(updated.createdAt()).isEqualTo(existing.createdAt());
        assertThat(updated.name()).isEqualTo("Updated Broker");
        assertThat(updated.status()).isEqualTo(BrokerStatus.SUSPENDED);
    }

    @Test
    void listBrokersShouldDelegateToRepository() {
        when(brokerRepositoryPort.findAll()).thenReturn(List.of(existingBroker()));

        assertThat(service.listBrokers()).hasSize(1);
    }

    @Test
    void deleteBrokerShouldDeleteExistingBroker() {
        Broker existing = existingBroker();
        when(brokerRepositoryPort.findById(existing.id())).thenReturn(Optional.of(existing));

        service.deleteBroker(existing.id());

        verify(brokerRepositoryPort).deleteById(existing.id());
    }

    private Broker existingBroker() {
        return new Broker(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "BRK-001", "Prime Broker", "fix://localhost:9876", BrokerStatus.ACTIVE, Instant.parse("2026-08-01T10:15:30Z"), Instant.parse("2026-08-01T10:15:30Z"));
    }
}
