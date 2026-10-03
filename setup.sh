#!/usr/bin/env bash
# One-time (and after-pull) setup: checks prerequisites, installs every package and builds every container image.
#
#   ./setup.sh               install dependencies and build images
#   ./setup.sh --with-tests  also run the Java, Python and frontend test suites
#
# Behind a TLS-intercepting corporate proxy, point CORP_CA_FILE at the proxy's CA bundle; it is passed to image
# builds as a build secret and never stored in an image:
#   CORP_CA_FILE=/path/to/ca.pem ./setup.sh
# DOCKER_BUILD_NETWORK=host can be set when image builds must use the host's network (e.g. a local proxy).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f infra/docker/docker-compose.yml)
WITH_TESTS=false
for arg in "$@"; do
  case "$arg" in
    --with-tests) WITH_TESTS=true ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

step "Checking prerequisites"
command -v java >/dev/null || fail "Java 21 is required (https://adoptium.net)"
java_major="$(java -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]; exit}')"
[ "${java_major:-0}" -ge 21 ] || fail "Java 21+ is required (found ${java_major:-none})"
command -v mvn >/dev/null || fail "Maven 3.9+ is required (https://maven.apache.org)"
command -v docker >/dev/null || fail "Docker is required (https://docs.docker.com/get-docker/)"
docker info >/dev/null 2>&1 || fail "Docker is installed but the daemon is not running"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"
command -v node >/dev/null || fail "Node.js 22 is required (https://nodejs.org)"
node_major="$(node -p 'process.versions.node.split(".")[0]')"
[ "$node_major" -ge 20 ] || fail "Node.js 20+ is required (found $node_major)"
if ! command -v uv >/dev/null; then
  echo "uv not found; installing it for this user"
  curl -LsSf https://astral.sh/uv/install.sh | sh
  export PATH="$HOME/.local/bin:$PATH"
fi
echo "java $java_major, node $node_major, $(uv --version), $(docker compose version --short 2>/dev/null || echo compose)"

[ -f .env ] || { cp .env.example .env; echo "Created .env from .env.example (local, synthetic defaults)"; }

step "Building Java services (Maven)"
if $WITH_TESTS; then mvn -B -ntp verify; else mvn -B -ntp -q package -DskipTests; fi

step "Installing Python workspace (uv, locked)"
uv sync --all-packages --frozen
if $WITH_TESTS; then
  uv run ruff check ai mcp evals e2e
  uv run pytest
fi

step "Installing frontend packages (npm, locked)"
(cd frontend && npm ci --no-audit --no-fund)
if $WITH_TESTS; then (cd frontend && npm run typecheck && npm test && npm run build); fi

step "Building container images"
build_opts=()
if [ -n "${CORP_CA_FILE:-}" ]; then
  [ -f "$CORP_CA_FILE" ] || fail "CORP_CA_FILE=$CORP_CA_FILE does not exist"
  build_opts=(--secret "id=corp_ca,src=$CORP_CA_FILE")
fi
[ -n "${DOCKER_BUILD_NETWORK:-}" ] && build_opts+=(--network "$DOCKER_BUILD_NETWORK")
export DOCKER_BUILDKIT=1
docker build ${build_opts[@]+"${build_opts[@]}"} -t fixai/python:local -f infra/docker/python.Dockerfile .
docker build ${build_opts[@]+"${build_opts[@]}"} -t fixai/frontend:local frontend
"${COMPOSE[@]}" --profile platform build fix-simulator workflow-service broker-service certification-service

step "Pulling infrastructure images"
"${COMPOSE[@]}" --profile platform --profile observability pull --ignore-buildable --quiet postgres prometheus grafana

printf '\n\033[1;32mSetup complete.\033[0m Start the platform with ./start.sh\n'
