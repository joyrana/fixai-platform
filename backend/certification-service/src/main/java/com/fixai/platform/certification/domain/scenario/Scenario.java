package com.fixai.platform.certification.domain.scenario;

import com.fixai.platform.fixcore.FixVersion;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, versioned certification scenario. Identity is {@code id@version}; any change to steps requires a new
 * version so historical runs stay reproducible.
 *
 * @param session per-scenario session overrides
 * @param allowInboundSessionRejects whether counterparty session-level Rejects (35=3) are expected; when false any
 *        inbound Reject fails the scenario through an automatic protocol check
 * @param mandatory whether failure of this scenario fails the certification verdict
 */
public record Scenario(
        String id,
        int version,
        String title,
        String description,
        ScenarioCategory category,
        Set<FixVersion> fixVersions,
        Set<String> tags,
        boolean mandatory,
        SessionOverrides session,
        boolean allowInboundSessionRejects,
        List<Step> steps) {

    public Scenario {
        Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("Scenario version must be >= 1: " + id);
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Scenario has no steps: " + id);
        }
        fixVersions = Set.copyOf(fixVersions);
        tags = Set.copyOf(tags);
        steps = List.copyOf(steps);
        session = session == null ? SessionOverrides.NONE : session;
    }

    public String reference() {
        return id + "@" + version;
    }

    public boolean supports(FixVersion fixVersion) {
        return fixVersions.contains(fixVersion);
    }

    /**
     * @param heartbeatIntervalSeconds HeartBtInt to request, or {@code null} for the engine default
     * @param resetOnLogon whether to send ResetSeqNumFlag=Y; {@code null} for default (true)
     * @param targetCompIdSuffix appended to the target CompID, used by negative logon scenarios
     */
    public record SessionOverrides(Integer heartbeatIntervalSeconds, Boolean resetOnLogon, String targetCompIdSuffix) {
        public static final SessionOverrides NONE = new SessionOverrides(null, null, null);
    }
}
