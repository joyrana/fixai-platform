package com.fixai.platform.broker.domain.session;

import com.fixai.platform.fixcore.FixSessionSpecValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation of a session configuration before it can be submitted for approval: protocol rules from fix-core plus
 * platform rules (credential references only, no wildcard or metadata-service hosts).
 */
public final class SessionConfigPolicy {

    private static final Pattern CREDENTIAL_REF = Pattern.compile("(vault|env|aws-sm|gcp-sm|azure-kv):[A-Za-z0-9/_.\\-]{1,200}");
    private static final List<String> FORBIDDEN_HOSTS = List.of("0.0.0.0", "169.254.169.254", "metadata.google.internal");

    private final FixSessionSpecValidator fixValidator;

    public SessionConfigPolicy(FixSessionSpecValidator fixValidator) {
        this.fixValidator = fixValidator;
    }

    public List<FixSessionSpecValidator.Violation> validate(SessionConfig config) {
        List<FixSessionSpecValidator.Violation> violations = new ArrayList<>(fixValidator.validate(config.toSpec()));
        if (config.name() == null || config.name().isBlank() || config.name().length() > 120) {
            violations.add(new FixSessionSpecValidator.Violation("name", "INVALID_NAME", "Name must be 1-120 characters"));
        }
        if (config.credentialRef() != null && !config.credentialRef().isBlank()
                && !CREDENTIAL_REF.matcher(config.credentialRef()).matches()) {
            violations.add(new FixSessionSpecValidator.Violation("credentialRef", "INVALID_CREDENTIAL_REF",
                    "credentialRef must reference a secret store (vault:, env:, aws-sm:, gcp-sm:, azure-kv:); raw secrets are not accepted"));
        }
        if (config.host() != null && FORBIDDEN_HOSTS.contains(config.host().toLowerCase(Locale.ROOT))) {
            violations.add(new FixSessionSpecValidator.Violation("host", "FORBIDDEN_HOST", "Host is not permitted"));
        }
        return violations;
    }
}
