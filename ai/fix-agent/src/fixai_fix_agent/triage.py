"""Deterministic triage: maps failed assertions, protocol checks and evidence onto the failure taxonomy.

Rules only use what the certification engine persisted (assertion subject/expected/actual, protocol-check outcome,
redacted evidence). They never consult simulator profile names, so they work identically for real counterparties.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from fixai_common.contracts import FailureCategory

SEQUENCE_FIELDS = {"GapFillFlag", "NewSeqNo", "BeginSeqNo", "EndSeqNo", "MsgSeqNum", "PossDupFlag", "OrigSendingTime"}
QUANTITY_FIELDS = {"CumQty", "LeavesQty", "OrderQty", "32", "LastQty"}
REJECT_RE = re.compile(r"RefTagID=(\d+|\?) SessionRejectReason=(\d+|\?)")
NO_INBOUND_RE = re.compile(r"No inbound (\w+)(?: matching \{([^}]*)\})? within (\d+) ms")
TAG_NAMES = {"17": "ExecID", "41": "OrigClOrdID", "112": "TestReqID", "36": "NewSeqNo", "123": "GapFillFlag",
             "150": "ExecType", "14": "CumQty", "151": "LeavesQty", "6": "AvgPx", "54": "Side", "11": "ClOrdID"}


@dataclass
class Candidate:
    category: FailureCategory
    likelihood: str
    title: str
    detail: str
    evidence: list[str]
    knowledge_query: str
    remediation: list[str] = field(default_factory=list)


def _field_value(raw: str | None, tag: str) -> str | None:
    if not raw:
        return None
    match = re.search(rf"(?:^|\|){tag}=([^|]*)", raw)
    return match.group(1) if match else None


def triage(scenario: dict[str, Any]) -> list[Candidate]:
    """Returns ranked candidates (most likely root cause first) for one non-passing scenario."""
    candidates: list[Candidate] = []
    assertions = scenario.get("failed_assertions", [])
    evidence = scenario.get("evidence", [])
    summary = scenario.get("failure_summary") or ""
    fallback_ref = next((e["ref"] for e in evidence if e.get("ref")), None)

    for assertion in assertions:
        refs = [assertion["evidence_ref"]] if assertion.get("evidence_ref") else ([fallback_ref] if fallback_ref else [])
        subject, expected, actual = assertion["subject"], assertion["expected"], assertion["actual"]
        if not refs:
            continue
        if subject.startswith("Counterparty messages pass platform"):
            match = REJECT_RE.search(actual)
            tag, reason = (match.group(1), match.group(2)) if match else ("?", "?")
            name = TAG_NAMES.get(tag, f"tag {tag}")
            if tag in ("36", "123"):
                candidates.append(Candidate(FailureCategory.SEQUENCE_RECOVERY_FAILURE, "high",
                                            f"Sequence reset rejected ({name})",
                                            f"The platform rejected a counterparty SequenceReset because {name} was invalid: "
                                            f"{actual}.",
                                            refs, "SequenceReset GapFillFlag NewSeqNo resend"))
            elif reason == "1":
                candidates.append(Candidate(FailureCategory.MISSING_REQUIRED_FIELD, "high", f"Required field {name} missing",
                                            f"The platform's dictionary validation rejected a counterparty message: {actual}.",
                                            refs, f"{name} required field"))
            else:
                candidates.append(Candidate(FailureCategory.INCORRECT_FIELD_VALUE, "high", f"Invalid value for {name}",
                                            f"The platform's dictionary validation rejected a counterparty message: {actual}.",
                                            refs, f"{name} valid values session reject"))
        elif subject.startswith("ExecID(17) is unique") or (subject == "ExecID" and expected.startswith("!=")):
            candidates.append(Candidate(FailureCategory.DUPLICATE_IDENTIFIER, "high", "ExecID reused across reports",
                                        f"ExecID values must be unique per ExecutionReport; observed: {actual}.",
                                        refs, "ExecID unique every report"))
        elif subject in SEQUENCE_FIELDS:
            candidates.append(Candidate(FailureCategory.SEQUENCE_RECOVERY_FAILURE, "high", f"Recovery field {subject} incorrect",
                                        f"{subject} expected {expected} but was {actual} during sequence recovery.",
                                        refs, f"{subject} ResendRequest SequenceReset gap fill"))
        elif subject in QUANTITY_FIELDS or "CumQty" in subject or "LeavesQty" in subject:
            candidates.append(Candidate(FailureCategory.QUANTITY_INCONSISTENCY, "high", "Execution quantities inconsistent",
                                        f"{subject}: expected {expected}, actual {actual}.", refs,
                                        "CumQty LeavesQty OrderQty invariant"))
        elif actual == "<absent>":
            candidates.append(Candidate(FailureCategory.MISSING_REQUIRED_FIELD, "high", f"{subject} missing",
                                        f"{subject} was expected ({expected}) but absent from the counterparty message.",
                                        refs, f"{subject} required"))
        elif subject in ("ExecType", "OrdStatus") and expected.endswith("8") and actual != "8":
            candidates.append(Candidate(FailureCategory.UNEXPECTED_ACCEPTANCE, "high", "Request accepted instead of rejected",
                                        f"{subject} expected {expected} (rejected) but the counterparty reported {actual}.",
                                        refs, "order must be rejected duplicate ClOrdID unknown order"))
        elif subject in ("ExecType", "OrdStatus") and actual == "8" and not expected.endswith("8"):
            candidates.append(Candidate(FailureCategory.UNEXPECTED_REJECTION, "high", "Valid request rejected",
                                        f"{subject} expected {expected} but the counterparty rejected the request ({actual}).",
                                        refs, "rejecting every order trading permissions"))
        else:
            candidates.append(Candidate(FailureCategory.INCORRECT_FIELD_VALUE, "high", f"Incorrect {subject}",
                                        f"{subject} expected {expected} but was {actual}.", refs,
                                        f"{subject} {'ExecType fill trade' if subject == 'ExecType' else ''}".strip()))

    if "Unresolved scenario variable" in summary and fallback_ref and not candidates:
        candidates.append(Candidate(FailureCategory.MISSING_REQUIRED_FIELD, "medium", "Field needed for correlation missing",
                                    summary, [fallback_ref], "required field ExecutionReport"))

    missing = NO_INBOUND_RE.search(summary)
    if missing and fallback_ref:
        msg_type, match_text = missing.group(1), missing.group(2) or ""
        clordid = re.search(r"ClOrdID=([^,}]+)", match_text)
        inbound = [e for e in evidence if e.get("direction") == "INBOUND" and e.get("kind") == "MESSAGE"]
        if msg_type == "0" and "TestReqID" in match_text and any(e.get("msg_type") == "0" for e in inbound):
            hb = next(e for e in inbound if e.get("msg_type") == "0")
            candidates.append(Candidate(FailureCategory.SESSION_PROTOCOL_VIOLATION, "high",
                                        "Heartbeat does not echo TestReqID",
                                        "Heartbeats were received, but none carried the TestReqID from the TestRequest.",
                                        [hb["ref"]], "Heartbeat echoes TestReqID TestRequest"))
        elif clordid and any(_field_value(e.get("raw_redacted"), "11") == clordid.group(1).strip()
                             and _field_value(e.get("raw_redacted"), "150") in ("0", "4", "5") for e in inbound):
            hit = next(e for e in inbound if _field_value(e.get("raw_redacted"), "11") == clordid.group(1).strip())
            candidates.append(Candidate(FailureCategory.UNEXPECTED_ACCEPTANCE, "high", "Request acknowledged instead of rejected",
                                        f"No {msg_type} was received; instead an ExecutionReport acknowledged "
                                        f"{clordid.group(1)}.",
                                        [hit["ref"]], "cancel unknown order must be rejected OrderCancelReject"))
        else:
            # A late response must correlate with the request (same ClOrdID when the expectation had one).
            later = [e for e in inbound if e.get("msg_type") == msg_type
                     and (clordid is None or _field_value(e.get("raw_redacted"), "11") == clordid.group(1).strip())]
            category = FailureCategory.LATENCY_SLA_BREACH if later else FailureCategory.MISSING_RESPONSE
            candidates.append(Candidate(category, "high" if not candidates else "medium",
                                        "Response arrived late" if later else f"No {msg_type} response",
                                        f"The expected {msg_type} did not arrive within {missing.group(3)} ms.",
                                        [later[-1]["ref"]] if later else [fallback_ref],
                                        "response time acknowledgement timeout" if later
                                        else "missing response timeout acknowledgement"))
    elif fallback_ref and ("Logon did not complete" in summary or "No response from counterparty" in summary):
        candidates.append(Candidate(FailureCategory.CONNECTIVITY, "medium", "Logon not completed", summary, [fallback_ref],
                                    "Logon never answered host port"))
    elif fallback_ref and "accepted a Logon that must be refused" in summary:
        candidates.append(Candidate(FailureCategory.UNEXPECTED_ACCEPTANCE, "high", "Unknown CompID accepted", summary,
                                    [fallback_ref], "refuse Logon unknown TargetCompID"))
    elif fallback_ref and "No Logout response" in summary:
        candidates.append(Candidate(FailureCategory.SESSION_PROTOCOL_VIOLATION, "medium", "Logout not acknowledged",
                                    summary, [fallback_ref], "Logout"))

    # Deduplicate by category, keeping first occurrence (rules are ordered root-cause first).
    seen: set[FailureCategory] = set()
    unique = []
    for candidate in candidates:
        if candidate.category not in seen:
            seen.add(candidate.category)
            unique.append(candidate)
    for rank, candidate in enumerate(unique):
        if rank > 0 and candidate.likelihood == "high":
            candidate.likelihood = "medium"
    return unique


REMEDIATION = {
    FailureCategory.MISSING_REQUIRED_FIELD: ["Populate the field on every message type where the dictionary requires it.",
                                             "Re-run the affected scenarios and confirm no platform Reject is generated."],
    FailureCategory.INCORRECT_FIELD_VALUE: ["Correct the value mapping for the field for this FIX version.",
                                            "Check version-specific enumerations (e.g. ExecType F vs 1/2)."],
    FailureCategory.QUANTITY_INCONSISTENCY: [
        "Update CumQty and LeavesQty atomically on every fill so CumQty + LeavesQty = OrderQty."],
    FailureCategory.DUPLICATE_IDENTIFIER: ["Generate a new unique identifier for every report or order; never reuse it."],
    FailureCategory.MISSING_RESPONSE: ["Ensure every request type in scope produces a response or an explicit reject.",
                                       "Check gateway logs for dropped or unrouted messages."],
    FailureCategory.UNEXPECTED_ACCEPTANCE: [
        "Validate the request against order state and identifiers before acknowledging; reject when invalid."],
    FailureCategory.UNEXPECTED_REJECTION: ["Check trading permissions, account set-up and kill switches for the session.",
                                           "Read the reject Text for the stated reason."],
    FailureCategory.SESSION_PROTOCOL_VIOLATION: [
        "Use the FIX engine's standard session handling; do not rebuild administrative messages."],
    FailureCategory.SEQUENCE_RECOVERY_FAILURE: [
        "Answer ResendRequest with SequenceReset GapFillFlag=Y for administrative messages and PossDupFlag=Y resends."],
    FailureCategory.LATENCY_SLA_BREACH: ["Profile the order path; look for queuing or throttling before the acknowledgement."],
    FailureCategory.CONNECTIVITY: ["Verify host, port, TLS expectations and CompIDs against the onboarding record."],
    FailureCategory.UNKNOWN: ["Review the cited evidence manually."],
}
