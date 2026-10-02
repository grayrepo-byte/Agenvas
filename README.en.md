# Agenvas

Agenvas is a self-hostable AI creation canvas with text, image, video, audio, and Agent cards. Users generate media directly on their cards; an Agent reads exact context, edits text, and arranges the canvas. Agent media proposals require approval before they enter the same persistent media task pipeline.

**This repository is still under development.** The default Compose file is for deployment and uses configured LLM and media providers. The separate development Compose file enables a deterministic Mock model and clearly marked demonstration media. The LLM, ComfyUI, GPT Image 2, and Ark Seedance adapters have been tested against fake HTTP servers and PostgreSQL; real LLM/ComfyUI/Seedance and official OpenAI/Google endpoints remain unverified. Selected intermediary image calls have separate evidence in the development checklist. Successful Mock generation does not establish real-provider support or production readiness. See [MVP-SPEC.md](docs/MVP-SPEC.md), [DEVELOPMENT-CHECKLIST.md](docs/DEVELOPMENT-CHECKLIST.md), and [dependency-baseline.md](docs/dependency-baseline.md) for scope and evidence.

## Quick start: default deployment

Install Docker Engine/Desktop with Compose. From the repository root:

```sh
cp .env.example .env
# Set random AGENVAS_DB_PASSWORD and AGENVAS_BOOTSTRAP_SECRET values in .env.
# Set AGENVAS_CREDENTIAL_MASTER_KEY before saving cloud credentials.
./deploy/update-local.sh
```

`deploy/compose.yaml` defaults both text and media modes to `configured` and does not enable Mock. After setup, use the administrator settings to configure a real LLM, media connections, and published capabilities. With no real media configuration, the capability catalog is empty and preloaded Mock capabilities cannot start new generation. Saving cloud credentials requires `AGENVAS_CREDENTIAL_MASTER_KEY`, a Base64-encoded random 32-byte key kept separately from database backups. Changing modes preserves existing configuration, results, and fixed tasks; accepted tasks still reconcile against their original configuration. These defaults do not establish production readiness.

For later local rebuilds and updates, run `./deploy/update-local.sh` from the repository root. It pulls the pinned base images, rebuilds local images, updates the Compose containers, and waits for health checks. The script uses the repository's `.env` and retains the database and asset volumes. A failed build leaves running containers in place. Base image digests are pinned, so this command does not upgrade them to newer versions.

Both example values are intentionally blank; Compose refuses to start until they are set. For an older installation that used the former public defaults, changing only the database password in `.env` will break the connection: rotate the PostgreSQL account password and server configuration together during maintenance, and replace any known example bootstrap secret. Do not put real credentials in Git or logs.

Open <http://127.0.0.1:8088/setup>, enter the bootstrap secret from `.env`, create the administrator, and sign in at `/login`. The setup secret must not be exposed to untrusted visitors. The Compose defaults bind both web and API ports to loopback; do not publish this HTTP-only configuration directly on the internet. Production deployment requires HTTPS, secure cookies, and an explicit security review.

Stop without deleting the database or asset volumes:

```sh
docker compose --env-file .env -f deploy/compose.yaml down
```

Do not add `--volumes` unless you intentionally want to delete that Compose project's data. The deployment project defaults to `agenvas`. For an isolated acceptance instance, set a distinct `COMPOSE_PROJECT_NAME`, `AGENVAS_API_PORT`, and `AGENVAS_WEB_PORT`, plus separate passwords and bootstrap secret.

PostgreSQL is also published on loopback only, defaulting to 5432 and overridable with `AGENVAS_DB_PORT` (raise it if 5432 is already taken on the host, otherwise Compose fails with a port conflict). It exists solely so a local client can inspect the database while debugging; the server reaches the database over the Compose network and does not use this mapping.

Compose defaults cap PostgreSQL/server/web at 768 MiB/1 CPU, 1536 MiB/2 CPUs, and 256 MiB/0.5 CPU respectively, with three 10 MiB JSON log files per service. Adjust `AGENVAS_*_MEMORY_LIMIT` and `AGENVAS_*_CPUS` in `.env` after measuring your host. The server has a 45-second container stop grace period and a 30-second Spring shutdown phase; neither makes an uncertain external submission safe to retry without checking its Provider attempt.

## What the current build can do

- Create projects, versioned text/image/video/audio artifacts, and persistent canvas cards. Cards bind exact historical artifact versions; moving a card does not change its content.
- Save media drafts, select a published capability, run individual cards, and select results. Regeneration appends a node version; edits and post-processing create derived nodes. Mock outputs are clearly marked synthetic images, video clips, or audio.
- Keep Run, task, approvals, and execution records in PostgreSQL. Agent media proposals enter the media pipeline only after batch approval. Project events are replayable over SSE.
- Export a project manifest for backup inventory and migration planning; the manifest does not contain media bytes or a concatenated video.
- Upload PNG, JPEG, or WebP reference images with actual decoding and limits. Private original reads require project authorization and support a single byte range. Every image keeps a bounded 480px preview beside the original; image cards load the original, while video cards load the extracted cover frame.
- Display UNKNOWN submissions with their durable request key and any confirmed Provider request ID. A request key is not proof of acceptance. Only an explicit user retry creates a new attempt; accepted requests are polled using the saved ID and pinned connection version. Cancellation stops local orchestration and does not promise external cancellation or refunds.
- Configure a candidate OpenAI-compatible chat model in the administrator settings page. Keys are encrypted server-side when `AGENVAS_CREDENTIAL_MASTER_KEY` is configured. The diagnostic must verify a complete tool-call round trip before new configured-model Runs are enabled.
- Configure multiple media connections and fixed capabilities in the administrator media settings page. Set image, video, and audio defaults, then choose a capability on each card before running it. Fixed Java adapters cover Mock, ComfyUI, GPT Image 2 images, and Beijing Ark Seedance video with approved image/audio references. Cloud API keys remain encrypted on the server and masked in the UI; both cloud capabilities are marked untested until real calls are verified.

ComfyUI image and image-to-video adapters use fixed templates and published model filenames. GPT Image 2 and Google Nano Banana image capabilities, Ark Seedance video, and Seed Audio speech capabilities share the same version-pinned task pipeline. Real-call evidence and its limitations are recorded per provider in the development checklist; Mock media and fake HTTP tests do not prove real generation.

Media connections, capabilities, and defaults are managed exclusively in administrator settings. The old ComfyUI environment wiring, one-time legacy import, configuration-version registry, and separate schedulers have been removed. `AGENVAS_PROVIDER_MODE` now selects `mock` or `configured`; ComfyUI remains available through the published capability catalog. A local origin must be reachable from the server container; container `127.0.0.1` is not the host. Accepted requests retain their original connection versions after configuration changes.

## Local development

The container development file extends the deployment services and explicitly enables text and media Mock modes:

```sh
# .env still requires the database password and bootstrap secret.
docker compose --env-file .env -f deploy/compose.dev.yaml up -d --build
# Stop development services without deleting their volumes.
docker compose --env-file .env -f deploy/compose.dev.yaml down
```

Unless `COMPOSE_PROJECT_NAME` is set, development uses project `agenvas-dev`, so database and asset volumes are separate from deployment. Both files use the same default ports. To run them together, use separate env files or environment variables for `AGENVAS_API_PORT`, `AGENVAS_WEB_PORT`, and `AGENVAS_DB_PORT`, and keep project names distinct.

The frontend requires Node 24 and pnpm 12.5.1; the backend requires JDK 21, PostgreSQL 17, and FFmpeg/FFprobe for video operations.

The frontend is a Vite-built, client-only page bundle. It has no SSR data access, server actions, or production Node server; Spring Boot remains the only business backend.

```sh
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm test
corepack pnpm build
corepack pnpm dev
```

```sh
cd backend
./mvnw verify
```

`contracts/openapi.yaml` is the authoritative API contract. Regenerate `frontend/src/shared/api/schema.ts` after changing it; do not edit generated types manually. The unreleased V1–V77 migrations have been consolidated once into [V1__initial_schema.sql](backend/src/main/resources/db/migration/V1__initial_schema.sql), with 67 business tables, 28 required seed rows, and comments for all business database objects, without user data or credentials. Retired reset/import markers, the old ComfyUI registry, task dependencies, and redundant fields were removed. This V1 is for empty databases; future migrations are append-only starting at V2. See the [ADR 0012 supplement](docs/adr/0012-jooq-persistence.md#2026-10-02-未发布基线重建) and read [AGENTS.md](AGENTS.md) before changing behavior. Tests that use a fake model or fake ComfyUI server are not evidence of real generation.

Restore a backup with a compatible image and start with `AGENVAS_RECOVERY_MODE=true`. It keeps project and settings APIs read-only, disables scheduled workers, and skips Flyway migrations until external attempts and assets are audited. A database with the old V1–V77 history must use its matching old version; auditing it does not make it compatible with the new V1. Use a separate empty database and asset volume for the consolidated baseline. Follow the [backup and restore runbook](docs/operations/backup-restore.md).

## Current limits

This is a single-administrator, single-application development build, not a multi-tenant hosted service. There has been no verified real LLM + real image + real video golden path, no full backup-and-restore exercise, and no completed security/performance/release audit. The outstanding checks remain unchecked in [DEVELOPMENT-CHECKLIST.md](docs/DEVELOPMENT-CHECKLIST.md). The planned project license is Apache-2.0; the formal license and third-party notices are still pending the release gate. See [SECURITY.md](SECURITY.md) for the current reporting boundary and the [0.1.0 release-notes draft](docs/release-notes/0.1.0-mvp-draft.md) for verified scope and upgrade cautions.

No automatic data import is provided across the consolidation boundary. The project export is a manifest; it cannot restore a database or archived media. Preserve old databases, media, keys, and their matching application versions separately.
