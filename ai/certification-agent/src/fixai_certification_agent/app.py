from __future__ import annotations

import os

import uvicorn

from fixai_certification_agent.agent import SPEC
from fixai_common.agent_service import create_agent_app

app = create_agent_app(SPEC)


def main() -> None:
    uvicorn.run(app, host=os.environ.get("HOST", "0.0.0.0"), port=int(os.environ.get("PORT", "8102")))  # noqa: S104
