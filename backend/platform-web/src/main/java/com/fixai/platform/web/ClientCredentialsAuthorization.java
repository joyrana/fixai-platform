package com.fixai.platform.web;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * OAuth 2.0 client-credentials grant for service-to-service calls. Tokens are cached until 30 seconds before expiry.
 * The client secret is read from configuration (environment or secret manager), never from code.
 */
public class ClientCredentialsAuthorization implements OutboundAuthorization {

    private final RestClient tokenClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String audience;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public ClientCredentialsAuthorization(String tokenUri, String clientId, String clientSecret, String audience, Clock clock) {
        this.tokenClient = RestClient.create();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.audience = audience;
        this.clock = clock;
    }

    @Override
    public synchronized void apply(HttpHeaders headers) {
        if (token == null || clock.instant().isAfter(expiresAt.minusSeconds(30))) {
            refresh();
        }
        headers.setBearerAuth(token);
    }

    @SuppressWarnings("unchecked")
    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        if (audience != null && !audience.isBlank()) {
            form.add("audience", audience);
        }
        Map<String, Object> response = tokenClient.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String accessToken)) {
            throw new IllegalStateException("Token endpoint returned no access_token");
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
        token = accessToken;
        expiresAt = clock.instant().plusSeconds(expiresIn);
    }
}
