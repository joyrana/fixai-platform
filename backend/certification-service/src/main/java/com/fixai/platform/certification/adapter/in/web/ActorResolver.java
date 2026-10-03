package com.fixai.platform.certification.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.stereotype.Component;

/**
 * Resolves the acting identity for audit records. With security enabled this is the authenticated principal; with
 * security disabled (local development only) every caller is recorded as {@code local-developer}. Client-supplied
 * identity headers are deliberately ignored.
 */
@Component
public class ActorResolver {

    public String current(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        return principal == null ? "local-developer" : principal.getName();
    }
}
