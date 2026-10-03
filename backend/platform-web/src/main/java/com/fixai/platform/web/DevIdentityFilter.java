package com.fixai.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * LOCAL DEVELOPMENT ONLY. Active only when {@code fixai.security.enabled=false}: authenticates every request as the
 * identity named in {@code X-Dev-User} / {@code X-Dev-Roles} so that role checks and four-eyes rules can be exercised
 * without an identity provider. Unknown roles are ignored.
 */
public class DevIdentityFilter extends OncePerRequestFilter {

    public static final String USER_HEADER = "X-Dev-User";
    public static final String ROLES_HEADER = "X-Dev-Roles";
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._@\\-]{1,64}");
    private static final Set<String> KNOWN_ROLES = Set.of(PlatformRoles.ADMIN, PlatformRoles.BROKER_MANAGER,
            PlatformRoles.CERTIFICATION_ENGINEER, PlatformRoles.REVIEWER, PlatformRoles.AUDITOR, PlatformRoles.SERVICE,
            PlatformRoles.AI_AGENT);

    private final PlatformSecurityProperties.DevIdentity defaults;

    public DevIdentityFilter(PlatformSecurityProperties.DevIdentity defaults) {
        this.defaults = defaults;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = request.getHeader(USER_HEADER);
        if (user == null || !USER.matcher(user).matches()) {
            user = defaults.defaultUser();
        }
        String rolesHeader = request.getHeader(ROLES_HEADER);
        List<String> roles = rolesHeader == null
                ? defaults.defaultRoles()
                : Arrays.stream(rolesHeader.split(",")).map(String::trim).filter(KNOWN_ROLES::contains).toList();
        var authorities = roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
