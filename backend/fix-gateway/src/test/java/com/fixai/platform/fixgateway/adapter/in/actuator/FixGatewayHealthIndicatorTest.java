package com.fixai.platform.fixgateway.adapter.in.actuator;

import com.fixai.platform.fixgateway.application.service.SessionManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import quickfix.Initiator;
import quickfix.SessionID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FixGatewayHealthIndicatorTest {

    @Mock
    private Initiator initiator;

    @Mock
    private SessionManager sessionManager;

    private SessionID sessionId;
    private FixGatewayHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        sessionId = new SessionID("FIX.4.4", "FIXAI", "BROKER");
        healthIndicator = new FixGatewayHealthIndicator(initiator, sessionManager, sessionId);
    }

    @Test
    void shouldReportUpWhenSessionIsLoggedOn() {
        when(sessionManager.isLoggedOn()).thenReturn(true);
        when(initiator.getSessions()).thenReturn(new ArrayList<>(List.of(sessionId)));

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("sessionCount", 1);
        assertThat(health.getDetails()).containsEntry("loggedOn", true);
        assertThat(health.getDetails()).containsEntry("primarySession", "FIX.4.4:FIXAI->BROKER");
    }

    @Test
    void shouldReportDownWhenSessionIsNotLoggedOn() {
        when(sessionManager.isLoggedOn()).thenReturn(false);
        when(initiator.getSessions()).thenReturn(new ArrayList<>());

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("sessionCount", 0);
        assertThat(health.getDetails()).containsEntry("loggedOn", false);
        assertThat(health.getDetails()).containsKey("compIds");
    }
}
