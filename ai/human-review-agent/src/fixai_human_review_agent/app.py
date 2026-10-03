from __future__ import annotations

import os

import uvicorn

from fixai_common.agent_service import create_agent_app
from fixai_human_review_agent.agent import SPEC

app = create_agent_app(SPEC)


def main() -> None:
    uvicorn.run(app, host=os.environ.get("HOST", "0.0.0.0"), port=int(os.environ.get("PORT", "8106")))  # noqa: S104
