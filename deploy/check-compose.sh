#!/usr/bin/env bash
set -euo pipefail

# Configuration regression checks only: never read installation secrets or start containers.
compose_repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$compose_repo"
unset COMPOSE_FILE COMPOSE_PROJECT_NAME
# Host environment (or an old .env) must not silently override the visible configuration.
export AGENVAS_DB_PASSWORD=compose-synthetic-ignored-value
export AGENVAS_CREDENTIAL_MASTER_KEY=compose-synthetic-ignored-value
export AGENVAS_LLM_MODE=mock AGENVAS_PROVIDER_MODE=mock
export AGENVAS_SECURE_COOKIES=true
export AGENVAS_SERVER_MEMORY_LIMIT=2g AGENVAS_SERVER_CPUS=3.0
compose_files=(docker-compose.yml docker-compose.local.yml deploy/compose.yaml deploy/compose.dev.yaml)

compose_config() {
  docker compose --env-file /dev/null -f "$1" config --format json
}

for compose_file in "${compose_files[@]}"; do
  if grep -Eq '^[[:space:]]*(extends|include):' "$compose_file"; then
    printf '%s must contain its complete runtime configuration\n' "$compose_file" >&2
    exit 1
  fi
  if grep -Eq '\$\{AGENVAS_' "$compose_file"; then
    printf '%s must declare settings directly rather than require an env file\n' "$compose_file" >&2
    exit 1
  fi

  compose_bind_host=127.0.0.1
  compose_web_only=false
  if [[ $compose_file == docker-compose.yml || $compose_file == docker-compose.local.yml ]]; then
    compose_bind_host=0.0.0.0
  fi
  if [[ $compose_file == docker-compose.yml ]]; then
    compose_web_only=true
  fi
  compose_config "$compose_file" | jq -e --arg bind_host "$compose_bind_host" --argjson web_only "$compose_web_only" '
    (.services | keys) == ["postgres", "server", "web"] and
    .services.postgres.image == "postgres:17.11-alpine" and
    (.services.postgres | has("build") | not) and
    ([.services[]] | all(.[];
      (.mem_limit | tonumber) > 0 and .cpus > 0 and
      .logging.driver == "json-file" and
      .logging.options."max-size" == "10m" and .logging.options."max-file" == "3" and
      all((.ports // [])[]; .host_ip == $bind_host))) and
    # Require exactly the intended published ports, including the absence of API/DB mappings.
    .services.web.ports == [{mode: "ingress", host_ip: $bind_host,
      target: 8080, published: "8088", protocol: "tcp"}] and
    (if $web_only then
      (.services.postgres | has("ports") | not) and
      (.services.server | has("ports") | not)
    else
      .services.postgres.ports == [{mode: "ingress", host_ip: $bind_host,
        target: 5432, published: "5432", protocol: "tcp"}] and
      .services.server.ports == [{mode: "ingress", host_ip: $bind_host,
        target: 8080, published: "8080", protocol: "tcp"}]
    end) and
    .services.server.read_only and .services.web.read_only and
    .services.server.stop_grace_period == "45s" and
    .services.server.depends_on.postgres.condition == "service_healthy" and
    .services.web.depends_on.server.condition == "service_healthy" and
    (.services | all(.[]; .healthcheck.test | length > 0)) and
    .services.server.environment.AGENVAS_STORAGE_ROOT == "/opt/agenvas/data/assets" and
    .volumes."postgres-data".name == (.name + "_postgres-data") and
    .volumes."asset-data".name == (.name + "_asset-data") and
    .volumes."credentials-data".name == (.name + "_credentials-data") and
    .services.postgres.environment.AGENVAS_DB_PASSWORD == "" and
    .services.postgres.environment.AGENVAS_CREDENTIAL_MASTER_KEY == "" and
    .services.postgres.environment.POSTGRES_PASSWORD_FILE == "/run/agenvas/credentials/installation/database-password" and
    .services.server.environment.AGENVAS_DB_PASSWORD_FILE == .services.postgres.environment.POSTGRES_PASSWORD_FILE and
    .services.server.environment.AGENVAS_CREDENTIAL_MASTER_KEY_FILE == "/run/agenvas/credentials/installation/credential-master-key" and
    (.services | all(.[]; (.environment // {} | has("AGENVAS_BOOTSTRAP_SECRET") | not) and
      (.environment // {} | has("AGENVAS_BOOTSTRAP_SECRET_FILE") | not))) and
    any(.services.server.volumes[]; .source == "credentials-data" and .read_only)' >/dev/null
done

# Fixed image addresses work without registry or tag settings and without source files.
compose_config docker-compose.yml | jq -e '
  .name == "agenvas" and
  (.services | all(.[]; has("build") | not)) and
  .services.server.image == "docker.io/grayrepo/agenvas-server:latest" and
  .services.web.image == "docker.io/grayrepo/agenvas-web:latest" and
  .services.server.pull_policy == "always" and .services.web.pull_policy == "always"' >/dev/null
docker compose --env-file /dev/null config --format json | jq -e '
  .services.server.image == "docker.io/grayrepo/agenvas-server:latest" and
  (.services.server | has("build") | not)' >/dev/null

# Copy just the remote Compose file to prove there are no external Compose dependencies.
compose_standalone_dir=$(mktemp -d)
trap 'rm -rf -- "$compose_standalone_dir"' EXIT
cp docker-compose.yml "$compose_standalone_dir/docker-compose.yml"
chmod 600 "$compose_standalone_dir/docker-compose.yml"
docker compose --env-file /dev/null -f "$compose_standalone_dir/docker-compose.yml" config --quiet
env -u AGENVAS_DB_PASSWORD -u AGENVAS_CREDENTIAL_MASTER_KEY \
  docker compose -f "$compose_standalone_dir/docker-compose.yml" config --quiet

# Source and Mock environments build only the application, never PostgreSQL.
for compose_file in docker-compose.local.yml deploy/compose.yaml deploy/compose.dev.yaml; do
  compose_config "$compose_file" | jq -e --arg root "$compose_repo" '
    ([.services.server, .services.web] | all(.[];
      .build.context == $root and .pull_policy == "build")) and
    .services.server.build.dockerfile == "deploy/docker/server.Dockerfile" and
    .services.web.build.dockerfile == "deploy/docker/frontend.Dockerfile"' >/dev/null
done

# Port publication differs between image and source deployment and is checked above.
# Compare all other runtime settings and persistent volume identities here.
source_runtime=$(compose_config docker-compose.local.yml |
  jq -Sc 'del(.services[].build, .services[].image, .services[].pull_policy, .services[].ports)')
for compose_file in docker-compose.yml deploy/compose.yaml; do
  runtime=$(compose_config "$compose_file" |
    jq -Sc 'del(.services[].build, .services[].image, .services[].pull_policy, .services[].ports)')
  if [[ $runtime != "$source_runtime" ]]; then
    printf '%s differs from source deployment runtime settings\n' "$compose_file" >&2
    exit 1
  fi
done
compose_config docker-compose.local.yml | jq -e '
  .services.server.environment.AGENVAS_LLM_MODE == "configured" and
  .services.server.environment.AGENVAS_PROVIDER_MODE == "configured"' >/dev/null
compose_config deploy/compose.dev.yaml | jq -e '
  .name == "agenvas-dev" and
  .services.server.environment.AGENVAS_LLM_MODE == "mock" and
  .services.server.environment.AGENVAS_PROVIDER_MODE == "mock"' >/dev/null

for compose_file in docker-compose.yml docker-compose.local.yml; do
  compose_config "$compose_file" |
    jq -e '.services.server |
      (.mem_limit | tonumber) == 1610612736 and .cpus == 2 and
      .environment.AGENVAS_SECURE_COOKIES == "false" and
      .environment.AGENVAS_LLM_MODE == "configured" and
      .environment.AGENVAS_PROVIDER_MODE == "configured"' >/dev/null
done
printf 'Compose regression checks passed for all four entry points.\n'
