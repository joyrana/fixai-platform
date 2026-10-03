package com.fixai.platform.certification.adapter.out.broker;

import com.fixai.platform.certification.application.port.out.SessionConfigPort;
import com.fixai.platform.certification.application.service.CertificationExceptions;
import com.fixai.platform.fixcore.FixVersion;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads session configurations from broker-service with this service's own credentials. Only the fields needed to
 * connect are used; credential references are resolved by the transport from the secret store, never passed here.
 */
public class BrokerServiceSessionConfigAdapter implements SessionConfigPort {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private final RestClient broker;

    public BrokerServiceSessionConfigAdapter(RestClient broker) {
        this.broker = broker;
    }

    @Override
    public Optional<ResolvedSessionConfig> resolve(UUID sessionConfigId, String correlationId) {
        try {
            Map<String, Object> c = broker.get().uri("/api/v1/session-configs/{id}", sessionConfigId).retrieve().body(MAP);
            if (c == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedSessionConfig(UUID.fromString((String) c.get("id")),
                    FixVersion.valueOf((String) c.get("fixVersion")), (String) c.get("host"), ((Number) c.get("port")).intValue(),
                    (String) c.get("senderCompId"), (String) c.get("targetCompId"), (String) c.get("environment"),
                    (String) c.get("status")));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new CertificationExceptions.InvalidState("Broker service unavailable; cannot resolve session configuration");
        }
    }
}
