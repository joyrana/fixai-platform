package com.fixai.platform.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Shared security for platform services.
 *
 * <ul>
 *   <li>{@code fixai.security.enabled=true}: stateless OIDC resource server; issuer and audience are validated;
 *       roles come from the {@code roles} claim or Keycloak's {@code realm_access.roles}.</li>
 *   <li>{@code fixai.security.enabled=false} (default, local only): {@link DevIdentityFilter} authenticates requests;
 *       a warning is logged at startup.</li>
 * </ul>
 * Health/info/prometheus and OpenAPI documentation are public; everything else requires authentication. Fine-grained
 * authorisation is declared per endpoint with {@code @PreAuthorize}.
 */
@AutoConfiguration
@EnableMethodSecurity
@Import(CommonExceptionHandler.class)
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class PlatformWebAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformWebAutoConfiguration.class);
    private static final String[] PUBLIC = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public CurrentActor currentActor() {
        return new CurrentActor();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain platformSecurityFilterChain(HttpSecurity http, PlatformSecurityProperties properties) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentTypeOptions(c -> { }).frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated());
        if (!properties.corsAllowedOrigins().isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(properties.corsAllowedOrigins());
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", CorrelationIdFilter.HEADER,
                    DevIdentityFilter.USER_HEADER, DevIdentityFilter.ROLES_HEADER));
            cors.setExposedHeaders(List.of(CorrelationIdFilter.HEADER, "Location"));
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/api/**", cors);
            http.cors(c -> c.configurationSource(source));
        }
        if (properties.enabled()) {
            http.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt
                    .decoder(jwtDecoder(properties))
                    .jwtAuthenticationConverter(PlatformWebAutoConfiguration::authentication)));
        } else {
            LOGGER.warn("fixai.security.enabled=false: requests are authenticated with LOCAL DEVELOPMENT identities "
                    + "(X-Dev-User/X-Dev-Roles). Never run a shared environment in this mode.");
            http.addFilterBefore(new DevIdentityFilter(properties.devIdentity()), AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    private static JwtDecoder jwtDecoder(PlatformSecurityProperties properties) {
        if (properties.issuerUri() == null || properties.issuerUri().isBlank()) {
            throw new IllegalStateException("fixai.security.issuer-uri is required when security is enabled");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(properties.issuerUri()).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required audience missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(properties.issuerUri()), audience));
        return decoder;
    }

    /** Maps {@code roles} or {@code realm_access.roles} claims to {@code ROLE_*} authorities. */
    static AbstractAuthenticationToken authentication(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        Object roles = jwt.getClaims().get("roles");
        if (roles == null && jwt.getClaims().get("realm_access") instanceof Map<?, ?> realm) {
            roles = realm.get("roles");
        }
        if (roles instanceof Collection<?> values) {
            values.forEach(v -> authorities.add(new SimpleGrantedAuthority("ROLE_" + v)));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
