package com.fixai.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = PlatformWebTest.TestApp.class)
@AutoConfigureMockMvc
class PlatformWebTest {

    @Autowired
    MockMvc mvc;

    @Test
    void devIdentityAuthenticatesWithDefaultsAndHeaders() throws Exception {
        mvc.perform(get("/whoami"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("local-developer"))
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
        mvc.perform(get("/whoami").header(DevIdentityFilter.USER_HEADER, "alice").header(DevIdentityFilter.ROLES_HEADER, "REVIEWER,NOT_A_ROLE"))
                .andExpect(jsonPath("$.id").value("alice"))
                .andExpect(jsonPath("$.roles[0]").value("REVIEWER"))
                .andExpect(jsonPath("$.roles.length()").value(1));
    }

    @Test
    void roleChecksReturnProblemDetail403() throws Exception {
        mvc.perform(get("/reviewers-only").header(DevIdentityFilter.ROLES_HEADER, "AUDITOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Insufficient permissions for this operation"))
                .andExpect(jsonPath("$.correlationId").exists());
        mvc.perform(get("/reviewers-only").header(DevIdentityFilter.ROLES_HEADER, "REVIEWER"))
                .andExpect(status().isOk());
    }

    @Test
    void correlationIdIsEchoedWhenWellFormedAndReplacedOtherwise() throws Exception {
        mvc.perform(get("/whoami").header(CorrelationIdFilter.HEADER, "abc-123"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "abc-123"));
        String generated = mvc.perform(get("/whoami").header(CorrelationIdFilter.HEADER, "bad value <script>"))
                .andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).doesNotContain("<").hasSize(36);
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal error"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void jwtRolesMapFromRolesOrRealmAccessClaims() {
        Jwt direct = jwt(Map.of("sub", "svc-a", "roles", List.of("SERVICE")));
        Jwt keycloak = jwt(Map.of("sub", "bob", "realm_access", Map.of("roles", List.of("REVIEWER", "AUDITOR"))));

        assertThat(PlatformWebAutoConfiguration.authentication(direct).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_SERVICE");
        assertThat(PlatformWebAutoConfiguration.authentication(keycloak).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder("ROLE_REVIEWER", "ROLE_AUDITOR");
        assertThat(PlatformWebAutoConfiguration.authentication(keycloak).getName()).isEqualTo("bob");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    @SpringBootApplication(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
    static class TestApp {
        @RestController
        static class Probe {
            private final CurrentActor actors;

            Probe(CurrentActor actors) {
                this.actors = actors;
            }

            @GetMapping("/whoami")
            Actor whoami() {
                return actors.get();
            }

            @GetMapping("/reviewers-only")
            @PreAuthorize("hasRole('REVIEWER')")
            String reviewers() {
                return "ok";
            }

            @GetMapping("/boom")
            String boom() {
                throw new IllegalStateException("secret internal detail");
            }
        }
    }
}
