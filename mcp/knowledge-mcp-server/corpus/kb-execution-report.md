---
doc_id: KB-ORD-EXECREPORT
title: ExecutionReport fields and invariants
version: 1
tags: [order-lifecycle, execution-report, fill]
acl_roles: [public]
---
# Required identity fields

Every ExecutionReport (MsgType 8) carries OrderID(37), assigned by the broker and stable for the life of the order,
and ExecID(17), which must be unique for every report. Reusing an ExecID makes downstream systems discard or
double-count executions. ExecID is mandatory; a report without it fails dictionary validation.

# ExecType by FIX version

In FIX 4.2 fills are reported with ExecType(150)=1 (partial fill) or 2 (fill) and ExecTransType(20) is required.
From FIX 4.4 onwards every execution is reported with ExecType=F (trade) and OrdStatus(39) distinguishes partially
filled (1) from filled (2). Reporting a fill with ExecType=0 (new) misclassifies the execution as an acknowledgement.

# Quantity invariants

For every report CumQty(14) + LeavesQty(151) = OrderQty(38) while the order is open, and LeavesQty = 0 once it is
filled, cancelled or rejected. CumQty never decreases. AvgPx(6) is the volume-weighted average of LastPx(31) over all
fills and is greater than zero once anything has executed. LastQty(32) is the quantity of this fill only.
