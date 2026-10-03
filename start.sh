#!/usr/bin/env bash
# Starts the entire platform (run ./setup.sh first): PostgreSQL, the FIX simulator, the Java services, the MCP servers,
# the agent orchestrator, the UI gateway, and Prometheus + Grafana. Waits until every service is healthy.
#
#   ./start.sh                   everything
#   ./start.sh --no-observability   without Prometheus and Grafana
#
# Local only: security is disabled (development identity headers) and every port is bound to 127.0.0.1.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
COMPOSE=(docker compose --env-file .env -f infra/docker/docker-compose.yml)
PROFILES=(--profile platform --profile observability)
for arg in "$@"; do
  case "$arg" in
    --no-observability) PROFILES=(--profile platform) ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

fail() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

docker info >/dev/null 2>&1 || fail "Docker daemon is not running"
for image in fixai/python:local fixai/frontend:local fixai/certification-service:local fixai/broker-service:local \
             fixai/workflow-service:local fixai/fix-simulator:local; do
  docker image inspect "$image" >/dev/null 2>&1 || fail "Image $image is missing; run ./setup.sh first"
done
[ -f .env ] || cp .env.example .env
set -a; . ./.env; set +a

echo "Starting FIXAI platform (this waits for every service to report healthy)..."
if ! "${COMPOSE[@]}" "${PROFILES[@]}" up -d --no-build --wait --wait-timeout 600; then
  "${COMPOSE[@]}" "${PROFILES[@]}" ps
  fail "Some services did not become healthy; inspect with: docker compose -f infra/docker/docker-compose.yml logs <service>"
fi

"${COMPOSE[@]}" "${PROFILES[@]}" ps --format 'table {{.Service}}\t{{.Status}}'
cat <<EOF

FIXAI platform is running.
  UI                      http://localhost:${UI_PORT:-3000}   (pick a role in the sidebar's dev identity panel)
  Agent orchestrator API  http://localhost:8100/docs
  Certification API       http://localhost:8083/swagger-ui.html
EOF
if [[ " ${PROFILES[*]} " == *" observability "* ]]; then
  cat <<EOF
  Grafana                 http://localhost:${GRAFANA_PORT:-3001}
  Prometheus              http://localhost:9090
EOF
fi
echo
echo "Stop everything with ./stop.sh"
