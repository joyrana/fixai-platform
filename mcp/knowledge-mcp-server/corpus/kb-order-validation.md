---
doc_id: KB-ORD-VALIDATION
title: Order validation and reject reasons
version: 1
tags: [order-lifecycle, reject, limits, reference-data]
acl_roles: [public]
---
# What must be rejected

A broker rejects a NewOrderSingle (MsgType D) with an ExecutionReport ExecType=8 and OrdStatus=8 when the instrument
is unknown (OrdRejReason 1), the market is closed or the instrument halted (2), the order exceeds a limit (3), the
ClOrdID duplicates an open order (6), the quantity is invalid (13 where defined) or the price has too many decimals
(18 where defined). Text(58) should explain the reason. Older FIX versions define fewer reason codes; 0 (broker
option) is the fallback.

# Duplicate ClOrdID

ClOrdID must be unique among a client's open orders. Accepting a second order with the same ClOrdID breaks the
client's ability to correlate reports and can double the intended position.

# Rejecting everything

A counterparty that rejects every order regardless of content usually has a configuration problem: trading not
enabled for the session, the account not permissioned, or a kill switch engaged. Check the rejection Text and the
session's trading permissions before investigating message content.
