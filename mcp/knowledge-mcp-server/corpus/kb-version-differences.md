---
doc_id: KB-VERSIONS
title: FIX 4.2, 4.4 and 5.0 SP2 differences relevant to certification
version: 1
tags: [versions, fix42, fix44, fix50]
acl_roles: [public]
---
# Transport

FIX 4.2 and 4.4 use BeginString FIX.4.2 / FIX.4.4 for both session and application messages. FIX 5.0 SP2 runs over
the FIXT.1.1 session protocol: BeginString is FIXT.1.1 and the application version is negotiated with
DefaultApplVerID(1137)=9 on Logon, or set per message with ApplVerID(1128).

# Field differences

Tag 32 is called LastShares in FIX 4.2 and LastQty from 4.4. ExecTransType(20) exists only up to 4.2. ExecType values
1 and 2 for fills belong to 4.2; 4.4 and later use F. HandlInst(21) is required on NewOrderSingle in 4.2 and optional
later. Reject reason code lists grew over versions, so a reason valid in 5.0 SP2 may not exist in 4.2.
