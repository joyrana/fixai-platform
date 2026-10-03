package com.fixai.platform.web;

/** Platform roles. Granted authorities are {@code ROLE_<name>}. */
public final class PlatformRoles {

    public static final String ADMIN = "ADMIN";
    public static final String BROKER_MANAGER = "BROKER_MANAGER";
    public static final String CERTIFICATION_ENGINEER = "CERTIFICATION_ENGINEER";
    public static final String REVIEWER = "REVIEWER";
    public static final String AUDITOR = "AUDITOR";
    /** Platform services calling each other. */
    public static final String SERVICE = "SERVICE";
    /** AI agents acting through MCP tools: read and simulated-write only. */
    public static final String AI_AGENT = "AI_AGENT";

    private PlatformRoles() {
    }
}
