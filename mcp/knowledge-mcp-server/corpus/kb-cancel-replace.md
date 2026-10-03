---
doc_id: KB-ORD-CANCEL
title: Cancel and cancel/replace handling
version: 1
tags: [order-lifecycle, cancel, replace]
acl_roles: [public]
---
# Cancel

OrderCancelRequest (MsgType F) carries a new ClOrdID(11) and OrigClOrdID(41) identifying the order. A successful cancel
is acknowledged with an ExecutionReport with ExecType=4 and OrdStatus=4 that echoes both ClOrdID and OrigClOrdID.
A cancel for an order that is unknown or already closed must be refused with OrderCancelReject (MsgType 9) carrying
CxlRejResponseTo(434)=1 and a CxlRejReason(102) such as 1 (unknown order) or 0 (too late to cancel).

Acknowledging a cancel for an unknown order is dangerous: the client believes an order is gone while the broker may
still hold a live order under another identifier. Ignoring a cancel request entirely leaves the client unable to
tell whether its risk is reduced.

# Cancel/replace

OrderCancelReplaceRequest (MsgType G) changes quantity or price. The confirmation is an ExecutionReport with ExecType=5
that echoes OrigClOrdID, keeps the same OrderID and reports the new OrderQty, with CumQty + LeavesQty = OrderQty.
Omitting OrigClOrdID prevents the client from linking the confirmation to the original order.
