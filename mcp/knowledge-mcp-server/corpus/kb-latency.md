---
doc_id: KB-OPS-LATENCY
title: Response-time expectations during certification
version: 1
tags: [operations, latency, timeout]
acl_roles: [public]
---
# Expectations

Certification scenarios expect an acknowledgement within five seconds of sending an order, cancel or replace.
An expectation that times out while the session stays logged on, with the response arriving later, indicates slow
processing at the counterparty rather than a lost message.

# Diagnosis

Compare the time the request left the platform with the time of the late response in the evidence timeline. A
constant delay on every response points at queuing or throttling in the counterparty's order gateway; a delay that
grows during a run points at resource exhaustion. If no response ever arrives, treat it as a missing response
instead.
