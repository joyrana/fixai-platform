package com.fixai.platform.web;

import java.util.Set;

/**
 * The authenticated caller.
 *
 * @param id stable subject identifier (JWT {@code sub} or the dev identity)
 * @param type USER, SERVICE or AGENT, derived from roles
 */
public record Actor(String id, Type type, Set<String> roles) {

    public enum Type {
        USER,
        SERVICE,
        AGENT
    }

    public Actor {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    static Type typeOf(Set<String> roles) {
        if (roles.contains(PlatformRoles.SERVICE)) {
            return Type.SERVICE;
        }
        if (roles.contains(PlatformRoles.AI_AGENT)) {
            return Type.AGENT;
        }
        return Type.USER;
    }
}
