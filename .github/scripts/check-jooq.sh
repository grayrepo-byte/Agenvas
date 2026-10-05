#!/usr/bin/env bash
set -euo pipefail

# Run from backend; this database is disposable and never reads deployment credentials.
# Testcontainers can occupy any ephemeral host port, including 55432.
# Let Docker reserve a free port and keep this one-off database isolated.
codegen_container="agenvas-jooq-codegen-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
trap 'docker rm -f -v "$codegen_container" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$codegen_container" \
  -e POSTGRES_DB=agenvas -e POSTGRES_USER=agenvas \
  -e POSTGRES_PASSWORD=codegen-only -p 127.0.0.1::5432 postgres:17.11-alpine
codegen_endpoint=$(docker port "$codegen_container" 5432/tcp)
codegen_port=${codegen_endpoint##*:}
# The initialization server accepts Unix sockets only; wait for final TCP startup.
for _ in $(seq 1 30); do
  if docker exec "$codegen_container" pg_isready -h 127.0.0.1 -U agenvas -d agenvas >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$codegen_container" pg_isready -h 127.0.0.1 -U agenvas -d agenvas
./mvnw --batch-mode --no-transfer-progress -Pjooq-codegen \
  "-Djooq.codegen.jdbcUrl=jdbc:postgresql://127.0.0.1:$codegen_port/agenvas" generate-sources
# 用 porcelain 而非 git diff：新增表会产生未跟踪文件，git diff 看不到。
if [ -n "$(git status --porcelain -- src/jooq/java)" ]; then
  echo 'src/jooq/java 与迁移不一致，请重新生成并提交：' >&2
  git status --porcelain -- src/jooq/java >&2
  exit 1
fi
