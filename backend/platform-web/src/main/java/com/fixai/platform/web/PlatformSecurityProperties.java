package com.fixai.platform.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled when true, every API call requires an OIDC JWT issued by {@code issuerUri} for {@code audience}
 * @param issuerUri OIDC issuer (resource-server validation)
 * @param audience required {@code aud} value
 * @param devIdentity local development only (security disabled): identity is taken from {@code X-Dev-User} /
 *        {@code X-Dev-Roles} headers, defaulting to {@code defaultUser} with {@code defaultRoles}
 * @param corsAllowedOrigins explicit origins allowed for browser calls; empty disables CORS
 */
@ConfigurationProperties(prefix = "fixai.security")
public record PlatformSecurityProperties(
        boolean enabled,
        String issuerUri,
        String audience,
        DevIdentity devIdentity,
        List<String> corsAllowedOrigins) {

    public PlatformSecurityProperties {
        devIdentity = devIdentity == null ? new DevIdentity(null, null) : devIdentity;
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
    }

    public record DevIdentity(String defaultUser, List<String> defaultRoles) {
        public DevIdentity {
            defaultUser = defaultUser == null ? "local-developer" : defaultUser;
            defaultRoles = defaultRoles == null
                    ? List.of(PlatformRoles.ADMIN, PlatformRoles.BROKER_MANAGER, PlatformRoles.CERTIFICATION_ENGINEER,
                            PlatformRoles.AUDITOR)
                    : List.copyOf(defaultRoles);
        }
    }
}
