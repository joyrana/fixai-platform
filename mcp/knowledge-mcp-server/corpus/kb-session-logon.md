---
doc_id: KB-SES-LOGON
title: Session establishment and the Logon handshake
version: 1
tags: [session, logon, heartbeat]
acl_roles: [public]
---
# Logon handshake

A FIX session begins when the initiator sends Logon (MsgType A) carrying EncryptMethod(98), HeartBtInt(108) and,
when it wants to start a fresh sequence, ResetSeqNumFlag(141)=Y. The acceptor validates SenderCompID(49) and
TargetCompID(56) against a configured session and replies with its own Logon.

A compliant acceptor echoes HeartBtInt and EncryptMethod=0. When the initiator sent ResetSeqNumFlag=Y the reply must
also carry ResetSeqNumFlag=Y and both sides restart at MsgSeqNum 1.

# Refusing a Logon

A counterparty must refuse a Logon addressed to a CompID it does not own. Refusal is expressed by a Logout (MsgType 5),
optionally with Text(58), followed by a disconnect. Silently accepting an unknown TargetCompID is a security defect:
it means orders could be routed to a session that was never onboarded.

# Remediation guidance

If a Logon is never answered, check network reachability, the configured host and port, and whether the
counterparty expects TLS. If the Logon is answered with Logout, compare SenderCompID/TargetCompID with the onboarding
record and confirm the counterparty has activated the session on its side.
