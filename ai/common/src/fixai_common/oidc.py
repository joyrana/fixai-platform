"""OIDC bearer validation for Python services (enabled with FIXAI_SECURITY_ENABLED=true)."""

from __future__ import annotations

import functools
import os

import jwt

from fixai_common.identity import KNOWN_ROLES, Principal


@functools.cache
def _jwks_client() -> jwt.PyJWKClient:
    issuer = os.environ["FIXAI_OIDC_ISSUER_URI"].rstrip("/")
    return jwt.PyJWKClient(os.environ.get("FIXAI_OIDC_JWKS_URI", f"{issuer}/protocol/openid-connect/certs"))


def verify_bearer(authorization: str) -> Principal | None:
    token = authorization.split(" ", 1)[1].strip()
    try:
        key = _jwks_client().get_signing_key_from_jwt(token).key
        claims = jwt.decode(token, key, algorithms=["RS256", "ES256"],
                            audience=os.environ.get("FIXAI_OIDC_AUDIENCE", "fixai-platform"),
                            issuer=os.environ["FIXAI_OIDC_ISSUER_URI"])
    except (jwt.PyJWTError, KeyError):
        return None
    roles = claims.get("roles") or claims.get("realm_access", {}).get("roles", [])
    return Principal(subject=str(claims["sub"]), roles=frozenset(roles) & KNOWN_ROLES, agent=claims.get("fixai_agent"))
