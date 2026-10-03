package com.fixai.platform.certification.application.engine;

import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.fixcore.FixMessageView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Automatic checks applied to every scenario's evidence, independent of the scenario's own steps. They catch protocol
 * defects a scenario author did not explicitly assert, and they are deterministic over persisted evidence.
 */
public final class ProtocolChecks {

    public static final String PLATFORM_REJECTS = "Counterparty messages pass platform session-level validation";
    public static final String COUNTERPARTY_REJECTS = "Counterparty sends no session-level Reject";
    public static final String UNIQUE_EXEC_ID = "ExecID(17) is unique per ExecutionReport";
    public static final String EVIDENCE_CAP = "Evidence captured within limit";

    public List<AssertionResult> check(Scenario scenario, List<EvidenceRecord> evidence, boolean overflowed) {
        List<AssertionResult> results = new ArrayList<>();
        results.add(firstReject(PLATFORM_REJECTS, evidence, EvidenceRecord.Direction.OUTBOUND));
        if (!scenario.allowInboundSessionRejects()) {
            results.add(firstReject(COUNTERPARTY_REJECTS, evidence, EvidenceRecord.Direction.INBOUND));
        }
        results.add(uniqueExecIds(evidence));
        results.add(new AssertionResult(EVIDENCE_CAP, "not exceeded", overflowed ? "exceeded" : "not exceeded", !overflowed, null));
        return results;
    }

    private static AssertionResult firstReject(String subject, List<EvidenceRecord> evidence, EvidenceRecord.Direction direction) {
        for (EvidenceRecord record : evidence) {
            if (record.kind() == EvidenceRecord.Kind.MESSAGE && record.direction() == direction && "3".equals(record.msgType())) {
                FixMessageView reject = record.message();
                String actual = "Reject RefSeqNum=" + reject.value(45).orElse("?")
                        + " RefTagID=" + reject.value(371).orElse("?")
                        + " SessionRejectReason=" + reject.value(373).orElse("?")
                        + reject.value(58).map(t -> " Text=" + t).orElse("");
                return new AssertionResult(subject, "no Reject(3)", actual, false, record.ordinal());
            }
        }
        return new AssertionResult(subject, "no Reject(3)", "none", true, null);
    }

    private static AssertionResult uniqueExecIds(List<EvidenceRecord> evidence) {
        Map<String, Long> seen = new HashMap<>();
        for (EvidenceRecord record : evidence) {
            if (!record.isInboundMessage() || !"8".equals(record.msgType())) {
                continue;
            }
            FixMessageView report = record.message();
            boolean possDup = "Y".equals(report.value(43).orElse("N"));
            boolean statusReply = "I".equals(report.value(150).orElse("")) || "3".equals(report.value(20).orElse(""));
            if (possDup || statusReply) {
                continue;
            }
            String execId = report.value(17).orElse(null);
            if (execId == null) {
                continue;
            }
            Long previous = seen.putIfAbsent(execId, record.ordinal());
            if (previous != null) {
                return new AssertionResult(UNIQUE_EXEC_ID, "unique",
                        "ExecID " + execId + " reused (evidence #" + previous + " and #" + record.ordinal() + ")", false,
                        record.ordinal());
            }
        }
        return new AssertionResult(UNIQUE_EXEC_ID, "unique", "unique", true, null);
    }
}
