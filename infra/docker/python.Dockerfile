# One image for every Python component (MCP servers, agents, orchestrator); the container command selects which.
# Dependencies are installed exactly as locked in uv.lock.
#
# Behind a TLS-intercepting corporate proxy, pass its CA as a build secret (never baked into the image):
#   docker build --secret id=corp_ca,src=/path/to/ca.pem ...
FROM python:3.12-slim AS build
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /src
COPY pyproject.toml uv.lock ./
COPY ai ai
COPY mcp mcp
COPY evals evals
RUN --mount=type=secret,id=corp_ca,required=false \
    if [ -f /run/secrets/corp_ca ]; then export SSL_CERT_FILE=/run/secrets/corp_ca PIP_CERT=/run/secrets/corp_ca; fi \
    && pip install --no-cache-dir uv==0.8.17 \
    && uv sync --frozen --no-dev --all-packages --no-editable

FROM python:3.12-slim
RUN groupadd --system fixai && useradd --system --gid fixai --no-create-home --shell /usr/sbin/nologin fixai \
    && mkdir -p /data && chown fixai:fixai /data
WORKDIR /app
COPY --from=build --chown=fixai:fixai /app/.venv /app/.venv
COPY --chown=fixai:fixai mcp/knowledge-mcp-server/corpus /app/corpus
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 KNOWLEDGE_CORPUS=/app/corpus MCP_HOST=0.0.0.0 HOST=0.0.0.0
USER fixai
CMD ["fixai-agent-orchestrator"]
