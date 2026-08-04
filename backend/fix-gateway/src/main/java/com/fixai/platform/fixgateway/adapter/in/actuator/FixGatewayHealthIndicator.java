package com.fixai.platform.fixgateway.adapter.in.actuator;

import com.fixai.platform.fixgateway.application.service.SessionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import quickfix.Initiator;
import quickfix.SessionID;

/**
 * Actuator health indicator for the FIX gateway connection state.
 */
@Component
public class FixGatewayHealthIndicator implements HealthIndicator {

    private final Initiator initiator;
    private final SessionManager sessionManager;
    private final SessionID sessionId;

    public FixGatewayHealthIndicator(Initiator initiator, SessionManager sessionManager, SessionID sessionId) {
        this.initiator = initiator;
        this.sessionManager = sessionManager;
        this.sessionId = sessionId;
    }

    @Override
    public Health health() {
        boolean loggedOn = sessionManager.isLoggedOn();
        List<SessionID> sessions = new ArrayList<>(initiator.getSessions());

        return Health.status(loggedOn ? "UP" : "DOWN")
                .withDetail("sessionCount", sessions.size())
                .withDetail("loggedOn", loggedOn)
                .withDetail("compIds", compIds(sessions))
                .withDetail("primarySession", sessionId.toString())
                .build();
    }

    private List<Map<String, String>> compIds(List<SessionID> sessions) {
        if (sessions.isEmpty()) {
            return List.of(compIdDetails(sessionId));
        }
        return sessions.stream().map(this::compIdDetails).toList();
    }

    private Map<String, String> compIdDetails(SessionID currentSessionId) {
        return Map.of(
                "senderCompId", currentSessionId.getSenderCompID(),
                "targetCompId", currentSessionId.getTargetCompID());
    }
}
