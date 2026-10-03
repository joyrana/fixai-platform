#!/usr/bin/env bash
# Stops every FIXAI process started by ./start.sh (all compose profiles). Data volumes are kept.
#
#   ./stop.sh           stop and remove the containers
#   ./stop.sh --clean   also delete data volumes (database, workflow checkpoints) for a fresh start
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f infra/docker/docker-compose.yml --profile platform --profile observability)
[ -f .env ] && COMPOSE=(docker compose --env-file .env -f infra/docker/docker-compose.yml --profile platform --profile observability)
DOWN=(down --remove-orphans)
for arg in "$@"; do
  case "$arg" in
    --clean) DOWN+=(--volumes) ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not running; nothing to stop."
  exit 0
fi
"${COMPOSE[@]}" "${DOWN[@]}"
echo "FIXAI platform stopped.$([[ " ${DOWN[*]} " == *" --volumes "* ]] && echo ' Data volumes removed.')"
