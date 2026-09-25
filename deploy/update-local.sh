#!/usr/bin/env bash
set -euo pipefail

# Rebuild the local Compose images before replacing any running containers.
deploy_repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
deploy_env_file="$deploy_repo/.env"
deploy_compose_file="$deploy_repo/deploy/compose.yaml"
deploy_health_wait_seconds=300

if [[ $# -ne 0 ]]; then
  printf 'Usage: %s\n' "$0" >&2
  exit 2
fi
if [[ ! -r $deploy_env_file ]]; then
  printf 'Missing readable %s. Copy .env.example to .env and set the required secrets.\n' "$deploy_env_file" >&2
  exit 2
fi
if ! command -v docker >/dev/null 2>&1; then
  printf 'Docker is required.\n' >&2
  exit 2
fi
if ! docker compose version >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  printf 'Docker Compose and a running Docker daemon are required.\n' >&2
  exit 2
fi

deploy_compose=(docker compose --env-file "$deploy_env_file" -f "$deploy_compose_file")
"${deploy_compose[@]}" config --quiet

printf 'Building local Compose images...\n'
"${deploy_compose[@]}" build --pull

printf 'Updating containers and waiting for health checks...\n'
"${deploy_compose[@]}" up -d --no-build --wait --wait-timeout "$deploy_health_wait_seconds"

"${deploy_compose[@]}" ps
printf 'Local deployment is healthy. Database and asset volumes were retained.\n'
