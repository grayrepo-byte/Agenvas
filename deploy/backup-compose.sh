#!/usr/bin/env bash
set -euo pipefail

# Produce one stopped-writer database, asset and repository-template backup.
# Credentials remain outside this archive and must be escrowed separately.
if [[ $# -ne 3 ]]; then
  printf 'Usage: %s <exact-compose-project> <env-file> <new-absolute-backup-directory>\n' "$0" >&2
  exit 2
fi

backup_project=$1
backup_env_file=$2
backup_output=$3
backup_repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
backup_compose_file="$backup_repo/deploy/compose.yaml"

if [[ ! $backup_project =~ ^[a-z][a-z0-9_-]{2,63}$ ]]; then
  printf 'Backup project must be an explicit lowercase Compose project name.\n' >&2
  exit 2
fi
if [[ ! -r $backup_env_file || $backup_output != /* || -e $backup_output ]]; then
  printf 'Provide a readable env file and a new absolute output directory.\n' >&2
  exit 2
fi
backup_env_file=$(cd "$(dirname "$backup_env_file")" && pwd -P)/$(basename "$backup_env_file")
backup_parent=$(cd "$(dirname "$backup_output")" && pwd -P)
if [[ $backup_parent == / || $backup_parent == "$backup_repo" ||
      $backup_parent == "$backup_repo/"* ]]; then
  printf 'Backup output must be outside the repository and below a specific parent directory.\n' >&2
  exit 2
fi
for backup_command in docker jq sha256sum tar git; do
  if ! command -v "$backup_command" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$backup_command" >&2
    exit 2
  fi
done

backup_compose=(docker compose -p "$backup_project" --env-file "$backup_env_file"
  -f "$backup_compose_file")
backup_config=$("${backup_compose[@]}" config --format json)
backup_config_project=$(jq -r '.name // empty' <<< "$backup_config")
backup_asset_volume=$(jq -r '.volumes["asset-data"].name // empty' <<< "$backup_config")
if [[ $backup_config_project != "$backup_project" ||
      ! $backup_asset_volume =~ ^[a-z][a-z0-9_-]{2,127}$ ]]; then
  printf 'Compose project or asset volume did not resolve exactly.\n' >&2
  exit 2
fi
docker volume inspect "$backup_asset_volume" >/dev/null
backup_running=$("${backup_compose[@]}" ps --status running --services)
if [[ $'\n'"$backup_running"$'\n' != *$'\npostgres\n'* ]]; then
  printf 'The selected project PostgreSQL service is not running.\n' >&2
  exit 2
fi
backup_restart_server=false
backup_restart_web=false
if [[ $'\n'"$backup_running"$'\n' == *$'\nserver\n'* ]]; then
  backup_restart_server=true
fi
if [[ $'\n'"$backup_running"$'\n' == *$'\nweb\n'* ]]; then
  backup_restart_web=true
fi

mkdir -m 700 -- "$backup_output"
backup_stopped=false
backup_restore_services() {
  local backup_exit=$?
  trap - EXIT
  if [[ $backup_stopped == true ]]; then
    if [[ $backup_restart_server == true ]]; then
      "${backup_compose[@]}" up -d --no-build server || backup_exit=1
    fi
    if [[ $backup_restart_web == true ]]; then
      "${backup_compose[@]}" up -d --no-build web || backup_exit=1
    fi
  fi
  if [[ $backup_exit -ne 0 ]]; then
    printf 'Backup incomplete; do not restore from %s without investigation.\n' "$backup_output" >&2
  fi
  exit "$backup_exit"
}
trap backup_restore_services EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

backup_stopped=true
"${backup_compose[@]}" stop web server
"${backup_compose[@]}" exec -T postgres pg_dump -U agenvas -d agenvas -Fc \
  > "$backup_output/database.dump"
docker run --rm \
  --mount "type=volume,source=$backup_asset_volume,target=/source,readonly" \
  --mount "type=bind,source=$backup_output,target=/backup" \
  postgres:17.11-alpine tar -C /source -czf /backup/assets.tgz .
tar -C "$backup_repo" -czf "$backup_output/repository-templates.tgz" configs deploy

[[ -s $backup_output/database.dump && -s $backup_output/assets.tgz &&
   -s $backup_output/repository-templates.tgz ]]
"${backup_compose[@]}" exec -T postgres pg_restore -l \
  < "$backup_output/database.dump" >/dev/null
tar -tzf "$backup_output/assets.tgz" >/dev/null
tar -tzf "$backup_output/repository-templates.tgz" >/dev/null

backup_database_sha=$(sha256sum "$backup_output/database.dump" | awk '{print $1}')
backup_assets_sha=$(sha256sum "$backup_output/assets.tgz" | awk '{print $1}')
backup_templates_sha=$(sha256sum "$backup_output/repository-templates.tgz" | awk '{print $1}')
backup_revision=$(git -C "$backup_repo" rev-parse HEAD)
jq -n --arg project "$backup_project" --arg revision "$backup_revision" \
  --arg database "$backup_database_sha" --arg assets "$backup_assets_sha" \
  --arg templates "$backup_templates_sha" \
  '{schemaVersion:1, project:$project, createdAt:(now | todateiso8601),
    gitRevision:$revision, files:{"database.dump":$database,
    "assets.tgz":$assets, "repository-templates.tgz":$templates},
    credentialEscrowRequired:true}' > "$backup_output/manifest.json"
chmod 600 "$backup_output/database.dump" "$backup_output/assets.tgz" \
  "$backup_output/repository-templates.tgz" "$backup_output/manifest.json"
printf 'Verified backup written to %s. Escrow matching credentials separately.\n' "$backup_output"
