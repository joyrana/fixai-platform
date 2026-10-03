---
doc_id: KB-SES-RECOVERY
title: Sequence numbers, gap detection and message recovery
version: 1
tags: [session, sequence, recovery, resend]
acl_roles: [public]
---
# Gap detection

Every message carries MsgSeqNum(34). When a receiver sees a sequence number higher than expected, it has detected a
gap and must send ResendRequest (MsgType 2) with BeginSeqNo(7) set to the first missing number and EndSeqNo(16) set to
0, meaning "everything after".

# Answering ResendRequest

The sender replays the requested range. Application messages are resent with PossDupFlag(43)=Y and
OrigSendingTime(122). Administrative messages (Logon, Heartbeat, TestRequest, ResendRequest, Reject, SequenceReset,
Logout) are not resent; the sender covers them with SequenceReset (MsgType 4) in gap-fill mode: GapFillFlag(123)=Y and
NewSeqNo(36) set to the next sequence number after the skipped range.

A SequenceReset without GapFillFlag=Y is in reset mode, which is reserved for disaster recovery. Using reset mode while
answering a ResendRequest is a defect: the receiver may reject it because it can lower the expected sequence number.

# Continuity after reconnect

Without ResetSeqNumFlag, sequence numbers continue across disconnects. After reconnecting, each side's Logon carries
the next sequence number and no ResendRequest is needed if nothing was lost.
