---
doc_id: KB-RUNBOOK-TRIAGE
title: Certification failure triage runbook
version: 2
tags: [runbook, triage, certification]
acl_roles: [CERTIFICATION_ENGINEER, ADMIN, AI_AGENT]
---
# Triage order

1. Read the failed assertion: subject, expected value, actual value and the evidence ordinal it was evaluated on.
2. Open the cited evidence message. Confirm the actual value is present in the message itself.
3. Check protocol checks: a Reject sent by the platform means the counterparty message failed validation; RefTagID
   names the field.
4. Classify the failure: missing required field, incorrect value, quantity inconsistency, duplicate identifier,
   missing response, unexpected acceptance, unexpected rejection, session protocol violation, sequence recovery
   failure, latency, or connectivity.
5. Only then propose remediation, and cite the evidence that supports it.

# What not to conclude

Never mark a scenario as passed because an explanation seems plausible. The verdict is produced by the engine's
assertions; analysis explains failures, it does not change them.
