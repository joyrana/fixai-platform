---
doc_id: KB-SES-HEARTBEAT
title: Heartbeats and TestRequest
version: 1
tags: [session, heartbeat, testrequest]
acl_roles: [public]
---
# Heartbeat interval

Each side sends Heartbeat (MsgType 0) when it has sent no other message for HeartBtInt seconds. A receiver that has
heard nothing for HeartBtInt plus a reasonable transmission allowance sends TestRequest (MsgType 1) with a TestReqID(112).

# Answering TestRequest

The answer to TestRequest is a Heartbeat that echoes TestReqID(112) exactly. Unsolicited heartbeats carry no
TestReqID. A Heartbeat that answers a TestRequest but omits TestReqID cannot be correlated with the request, so the
requesting side may conclude the link is dead and disconnect. This is a session protocol violation.

# Remediation guidance

Ensure the session layer copies TestReqID from the inbound TestRequest into the outbound Heartbeat. Most FIX engines do
this automatically; the defect usually comes from custom code that rebuilds or filters administrative messages.
