#!/usr/bin/env bash
set -euo pipefail

# Rebuild the local Compose images before replacing any running containers.
deploy_repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
deploy_compose_file="$deploy_repo/docker-compose.local.yml"
deploy_health_wait_seconds=300
deploy_status_format=$'table {{.Name}}\t{{.Service}}\t{{.Status}}\t{{.Ports}}'
deploy_build_flags=(build)
deploy_services=()
deploy_selected_services=' '

deploy_usage() {
  printf 'Usage: %s [--pull] [server|web ...]\n' "$0"
  printf 'Reuse cached base images by default. --pull explicitly refreshes base images.\n'
  printf 'Build both applications by default, or just the selected services.\n'
}

for deploy_argument in "$@"; do
  case "$deploy_argument" in
    --help|-h) deploy_usage; exit 0 ;;
    --pull) deploy_build_flags=(build --pull) ;;
    server|web)
      if [[ $deploy_selected_services != *" $deploy_argument "* ]]; then
        deploy_services+=("$deploy_argument")
        deploy_selected_services+="$deploy_argument "
      fi
      ;;
    *) deploy_usage >&2; exit 2 ;;
  esac
done
if [[ ${#deploy_services[@]} -eq 0 ]]; then
  deploy_services=(server web)
fi
if ! command -v docker >/dev/null 2>&1; then
  printf 'Docker is required.\n' >&2
  exit 2
fi
if ! docker compose version >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  printf 'Docker Compose and a running Docker daemon are required.\n' >&2
  exit 2
fi

deploy_compose=(docker compose -f "$deploy_compose_file")
"${deploy_compose[@]}" config --quiet

printf 'Building local Compose images: %s (backend test compilation/execution skipped)...\n' "${deploy_services[*]}"
deploy_phase_started=$SECONDS
# Package caches live in BuildKit mounts, independently of source and pom.xml layer hashes.
"${deploy_compose[@]}" "${deploy_build_flags[@]}" "${deploy_services[@]}"
printf 'Build completed in %ss.\n' "$((SECONDS - deploy_phase_started))"

printf 'Updating containers and waiting for health checks...\n'
deploy_phase_started=$SECONDS
# All builds must finish before replacing containers. Only missing images (e.g. a first-run
# PostgreSQL image) may be pulled; the application images just built remain selected.
"${deploy_compose[@]}" up -d --no-build --pull missing --wait --wait-timeout "$deploy_health_wait_seconds"
printf 'Container update completed in %ss.\n' "$((SECONDS - deploy_phase_started))"

# Older Compose versions print full inline entrypoints in the default COMMAND column.
"${deploy_compose[@]}" ps --format "$deploy_status_format"
printf 'Local deployment is healthy. Database and asset volumes were retained.\n'
