package com.fixai.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP defaults for every {@link RestClient} built from the auto-configured builder: service credentials,
 * correlation-ID propagation and bounded timeouts. Also wires the audit outbox when a workflow-service URL is set.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration",
    "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
    "org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration",
    "com.fixai.platform.web.PlatformWebAutoConfiguration"})
@EnableConfigurationProperties(PlatformClientsAutoConfiguration.ClientProperties.class)
public class PlatformClientsAutoConfiguration {

    /**
     * @param tokenUri OAuth token endpoint for client credentials (required when security is enabled)
     * @param clientId this service's client ID
     * @param clientSecret this service's client secret (inject from a secret store)
     * @param connectTimeout outbound connect timeout
     * @param readTimeout outbound read timeout
     */
    @ConfigurationProperties(prefix = "fixai.security.client")
    public record ClientProperties(String tokenUri, String clientId, String clientSecret, Duration connectTimeout,
                                   Duration readTimeout) {
        public ClientProperties {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundAuthorization outboundAuthorization(PlatformSecurityProperties security, ClientProperties client,
                                                       @Value("${spring.application.name:platform-service}") String name) {
        if (!security.enabled()) {
            return new DevServiceIdentity(name);
        }
        return new ClientCredentialsAuthorization(client.tokenUri(), client.clientId(), client.clientSecret(),
                security.audience(), Clock.systemUTC());
    }

    @Bean
    public RestClientCustomizer platformRestClientCustomizer(OutboundAuthorization authorization, ClientProperties client) {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(client.connectTimeout());
            factory.setReadTimeout(client.readTimeout());
            builder.requestFactory(factory).requestInterceptor((request, body, execution) -> {
                authorization.apply(request.getHeaders());
                request.getHeaders().set(CorrelationIdFilter.HEADER, CorrelationIdFilter.currentOrNew());
                return execution.execute(request, body);
            });
        };
    }

    /** Audit outbox; requires an {@code audit_outbox} table in the service schema and {@code fixai.audit.workflow-url}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcClient.class)
    @ConditionalOnProperty(prefix = "fixai.audit", name = "workflow-url")
    @EnableScheduling
    static class OutboxConfiguration {

        @Bean
        @ConditionalOnBean(JdbcClient.class)
        AuditOutbox auditOutbox(JdbcClient jdbc, RestClient.Builder builder, ObjectMapper json,
                                @Value("${fixai.audit.workflow-url}") String workflowUrl) {
            return new AuditOutbox(jdbc, builder.baseUrl(workflowUrl).build(), json, Clock.systemUTC());
        }

        @Bean
        @ConditionalOnBean(AuditOutbox.class)
        OutboxRelay auditOutboxRelay(AuditOutbox outbox) {
            return new OutboxRelay(outbox);
        }
    }

    /** Scheduled delivery of pending audit events. */
    public static class OutboxRelay {
        private final AuditOutbox outbox;

        OutboxRelay(AuditOutbox outbox) {
            this.outbox = outbox;
        }

        @Scheduled(fixedDelayString = "${fixai.audit.relay-interval:PT2S}")
        public void relay() {
            outbox.relay(100);
        }
    }
}
