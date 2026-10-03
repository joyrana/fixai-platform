package com.fixai.platform.simulator.engine;

import java.util.Arrays;
import java.util.Optional;

/**
 * Deterministic behaviour profiles. A client selects a profile through the TargetCompID it logs on to:
 * {@code SIM} selects {@link #COMPLIANT}; {@code SIM-<PROFILE_NAME>} selects any other profile.
 *
 * <p>Each defect profile injects exactly one known deviation from FIX expectations. The {@code defect} text is the
 * ground-truth label used by evaluation datasets; it must stay factual and must not be shown to agents under test.
 */
public enum SimulatorProfile {
    COMPLIANT(null),
    REJECT_ALL_ORDERS("Rejects every NewOrderSingle with OrdRejReason=0 (broker option) regardless of content"),
    MISSING_EXEC_ID("Omits required ExecID(17) from every ExecutionReport"),
    WRONG_EXEC_TYPE_ON_FILL("Reports fills with ExecType(150)=0 (New) instead of the trade ExecType"),
    DUPLICATE_EXEC_ID("Reuses the same ExecID(17) for every ExecutionReport of an order"),
    INCORRECT_CUM_QTY("Does not accumulate CumQty(14) on fills, so CumQty+LeavesQty differs from OrderQty"),
    WRONG_AVG_PX("Reports AvgPx(6)=0 on fills"),
    NO_CANCEL_RESPONSE("Silently ignores OrderCancelRequest(F)"),
    ACCEPT_UNKNOWN_CANCEL("Acknowledges cancels for unknown OrigClOrdID instead of sending OrderCancelReject(9)"),
    MISSING_ORIG_CLORDID("Omits OrigClOrdID(41) on cancel and replace acknowledgements"),
    ACCEPT_DUPLICATE_CLORDID("Accepts a NewOrderSingle whose ClOrdID duplicates an open order"),
    SLOW_ACK("Delays every ExecutionReport by 6 seconds, beyond the 5 second response expectation"),
    HEARTBEAT_WITHOUT_TEST_REQ_ID("Answers TestRequest(1) with a Heartbeat(0) that omits TestReqID(112)"),
    GAP_FILL_WITHOUT_FLAG("Sends SequenceReset(4) without GapFillFlag(123)=Y when answering ResendRequest");

    public static final String COMP_ID_PREFIX = "SIM";

    private final String defect;

    SimulatorProfile(String defect) {
        this.defect = defect;
    }

    public Optional<String> defect() {
        return Optional.ofNullable(defect);
    }

    /** The CompID a client must target to exercise this profile. */
    public String compId() {
        return this == COMPLIANT ? COMP_ID_PREFIX : COMP_ID_PREFIX + "-" + name();
    }

    public static Optional<SimulatorProfile> fromCompId(String compId) {
        return Arrays.stream(values()).filter(p -> p.compId().equals(compId)).findFirst();
    }
}
