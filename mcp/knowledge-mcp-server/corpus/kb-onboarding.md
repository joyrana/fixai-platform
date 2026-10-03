---
doc_id: KB-ONBOARD-GUIDE
title: Synthetic broker onboarding guide
version: 1
tags: [onboarding, configuration, approval]
acl_roles: [BROKER_MANAGER, ADMIN, CERTIFICATION_ENGINEER, AI_AGENT, REVIEWER]
---
# Onboarding steps

1. Create the broker profile.
2. Create a FIX session configuration for TEST or UAT. Credentials are provided as secret-store references only.
3. Validate the configuration. Fix every violation before submitting.
4. Submit for approval with a business justification. A reviewer other than the submitter decides.
5. Activate after approval. Activation re-validates the configuration and consumes the approval; any later change
   requires a new approval.
6. Run the smoke suite against the simulator, then the full certification suite against the approved test endpoint.

Production connectivity is never configured through the certification platform.
