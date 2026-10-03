package com.fixai.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixai.platform.workflow.domain.CanonicalJson;
import com.fixai.platform.workflow.domain.approval.ApprovalPayload;
import com.fixai.platform.workflow.domain.approval.ApprovalPolicy;
import com.fixai.platform.workflow.domain.approval.RiskLevel;
import com.fixai.platform.workflow.domain.audit.AuditEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalJsonAndPolicyTest {

    @Test
    void canonicalJsonIsIndependentOfKeyOrderAndNumberFormatting() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", new BigDecimal("1.50"));
        a.put("a", List.of("x", Map.of("z", true, "y", "q\"\n")));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Arrays.asList("x", Map.of("y", "q\"\n", "z", true)));
        b.put("b", 1.5);

        assertThat(CanonicalJson.write(a)).isEqualTo(CanonicalJson.write(b))
                .isEqualTo("{\"a\":[\"x\",{\"y\":\"q\\\"\\n\",\"z\":true}],\"b\":1.5}");
    }

    @Test
    void payloadHashChangesWithAnyArgument() {
        ApprovalPayload base = new ApprovalPayload("ACTIVATE_SESSION_CONFIG", "session-config", "c1", "UAT", Map.of("port", 9880));
        ApprovalPayload same = new ApprovalPayload("ACTIVATE_SESSION_CONFIG", "session-config", "c1", "UAT", Map.of("port", 9880L));
        ApprovalPayload other = new ApprovalPayload("ACTIVATE_SESSION_CONFIG", "session-config", "c1", "UAT", Map.of("port", 9881));

        assertThat(base.hash()).isEqualTo(same.hash()).isNotEqualTo(other.hash());
    }

    @Test
    void policyDerivesRiskAndValidatesTtl() {
        ApprovalPolicy policy = new ApprovalPolicy();
        ApprovalPayload remediation = new ApprovalPayload("APPLY_REMEDIATION", "session-config", "c1", "TEST", Map.of());

        assertThat(policy.riskFor(remediation, RiskLevel.LOW)).isEqualTo(RiskLevel.HIGH);
        assertThat(policy.riskFor(remediation, RiskLevel.CRITICAL)).isEqualTo(RiskLevel.CRITICAL);
        assertThat(policy.validateCreation(remediation, "Valid justification text", Duration.ofDays(8)))
                .extracting(ApprovalPolicy.Rejection::code).containsExactly("INVALID_TTL");
    }

    @Test
    void auditHashCoversEveryFieldAndThePredecessor() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-03T10:00:00Z");
        String hash = AuditEvent.computeHash(1, id, at, "a", "USER", "svc", "X", "r", "1", "c", "OK", Map.of("k", "v"), AuditEvent.GENESIS);
        AuditEvent event = new AuditEvent(1, id, at, "a", "USER", "svc", "X", "r", "1", "c", "OK", Map.of("k", "v"), AuditEvent.GENESIS, hash);
        AuditEvent forged = new AuditEvent(1, id, at, "a", "USER", "svc", "X", "r", "1", "c", "FORGED", Map.of("k", "v"), AuditEvent.GENESIS, hash);

        assertThat(event.verifies(AuditEvent.GENESIS)).isTrue();
        assertThat(forged.verifies(AuditEvent.GENESIS)).isFalse();
        assertThat(event.verifies("1".repeat(64))).isFalse();
    }
}
