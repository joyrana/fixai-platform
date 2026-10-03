---
doc_id: KB-SES-REJECT
title: Session-level Reject versus business rejects
version: 1
tags: [session, reject, validation]
acl_roles: [public]
---
# Session-level Reject

Reject (MsgType 3) reports a message that violates session rules: a required tag is missing, a value is not valid for
its tag, a tag appears twice, or the data format is wrong. RefSeqNum(45) identifies the rejected message,
RefTagID(371) the offending tag, and SessionRejectReason(373) the reason (for example 1 = required tag missing,
5 = value is incorrect, 6 = incorrect data format).

When the certification platform itself sends a Reject, it means a counterparty message failed dictionary validation.
The RefSeqNum points at the counterparty message and RefTagID names the field to fix.

# Business rejects

A message that is well formed but cannot be processed is rejected at the application level: an ExecutionReport with
ExecType=8 for orders, OrderCancelReject (MsgType 9) for cancel or replace requests, or BusinessMessageReject
(MsgType j) for unsupported message types, with BusinessRejectReason(380)=3 meaning "unsupported message type".
