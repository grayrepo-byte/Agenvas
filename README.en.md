# Agenvas

Agenvas is a self-hostable AI creation canvas. A Creator Agent lives on the canvas as an editable card and works through explicit input bindings, human approvals, immutable content versions, and persistent tasks. The target MVP turns one instruction into a three-shot silent video, with image and video approvals, local redo, and sequential export.

**This repository is still under development.** The default installation uses a deterministic Mock model and clearly marked demonstration media. The LLM, ComfyUI, GPT Image 2, and Ark Seedance adapters have been tested against fake HTTP servers and PostgreSQL; no real cloud image or video generation call has been made. A successful Mock export does not establish real-provider support or production readiness. See [MVP-SPEC.md](docs/MVP-SPEC.md), [DEVELOPMENT-CHECKLIST.md](docs/DEVELOPMENT-CHECKLIST.md), and [dependency-baseline.md](docs/dependency-baseline.md) for scope and evidence.

## Quick start: local Mock mode

Install Docker Engine/Desktop with Compose. From the repository root:

```sh
cp .env.example .env
# Set random AGENVAS_DB_PASSWORD and AGENVAS_BOOTSTRAP_SECRET values in .env.
./deploy/update-local.sh
```

For later local rebuilds and updates, run `./deploy/update-local.sh` from the repository root. It pulls the pinned base images, rebuilds local images, updates the Compose containers, and waits for health checks. The script uses the repository's `.env` and retains the database and asset volumes. A failed build leaves running containers in place. Base image digests are pinned, so this command does not upgrade them to newer versions.

Both example values are intentionally blank; Compose refuses to start until they are set. For an older installation that used the former public defaults, changing only the database password in `.env` will break the connection: rotate the PostgreSQL account password and server configuration together during maintenance, and replace any known example bootstrap secret. Do not put real credentials in Git or logs.

Open <http://127.0.0.1:8088/setup>, enter the bootstrap secret from `.env`, create the administrator, and sign in at `/login`. The setup secret must not be exposed to untrusted visitors. The Compose defaults bind both web and API ports to loopback; do not publish this HTTP-only configuration directly on the internet. Production deployment requires HTTPS, secure cookies, and an explicit security review.

Stop without deleting the database or asset volumes:

```sh
docker compose --env-file .env -f deploy/compose.yaml down
```

Do not add `--volumes` unless you intentionally want to delete that Compose project's data. For an isolated acceptance instance, set a distinct `COMPOSE_PROJECT_NAME`, `AGENVAS_API_PORT`, and `AGENVAS_WEB_PORT`, plus separate passwords and bootstrap secret.

PostgreSQL is also published on loopback only, defaulting to 5432 and overridable with `AGENVAS_DB_PORT` (raise it if 5432 is already taken on the host, otherwise Compose fails with a port conflict). It exists solely so a local client can inspect the database while debugging; the server reaches the database over the Compose network and does not use this mapping.

Compose defaults cap PostgreSQL/server/web at 768 MiB/1 CPU, 1536 MiB/2 CPUs, and 256 MiB/0.5 CPU respectively, with three 10 MiB JSON log files per service. Adjust `AGENVAS_*_MEMORY_LIMIT` and `AGENVAS_*_CPUS` in `.env` after measuring your host. The server has a 45-second container stop grace period and a 30-second Spring shutdown phase; neither makes an uncertain external submission safe to retry without checking its Provider attempt.

## What the current build can do

- Create projects, versioned text/image/video/character/scene/shot artifacts, and persistent canvas cards. A card can bind exact historical artifact versions; moving it does not alter approved content.
- Run the default Mock three-shot path through an image plan, explicit approval, generated demonstration images, manual keyframe selection, a video plan, explicit approval, demonstration MP4 clips, an Agent export proposal that requires separate human approval, local single-shot redo, and a silent ordered MP4 export.
- Keep Run, plan, and task state in PostgreSQL. The Agent card shows paginated Run history and safe plan/task summaries after refresh. Project events are replayable over SSE.
- Upload PNG, JPEG, or WebP reference images with actual decoding and limits. Private original reads require project authorization and support a single byte range. Every image keeps a bounded 480px preview beside the original; image cards load the original, while video cards load the extracted cover frame.
- Display UNKNOWN external submissions and their task identifiers without automatically resubmitting. An operator can expand the persisted attempt ledger to see a pre-network correlation key and any saved Provider request ID; the key is not proof of acceptance. For new ComfyUI attempts, an explicit lookup checks the original prompt and resumes polling only after its ID, client ID, endpoint fingerprint, and configuration match. No evidence leaves the task UNKNOWN. Cancellation stops further local orchestration; it does not promise to stop or refund external work.
- Configure a candidate OpenAI-compatible chat model in the administrator settings page. Keys are encrypted server-side when `AGENVAS_CREDENTIAL_MASTER_KEY` is configured. The diagnostic must verify a complete tool-call round trip before new configured-model Runs are enabled.
- Configure multiple media connections and fixed capabilities in the administrator media settings page. Set image and video defaults, then choose and confirm a capability for each plan step before approval. Fixed Java adapters cover Mock, ComfyUI, GPT Image 2 images, and Beijing Ark Seedance first-frame video. Cloud API keys remain encrypted on the server and masked in the UI; both cloud capabilities are marked untested until real calls are verified.

The current ComfyUI image and image-to-video adapters use fixed candidate templates. A fixed Google Nano Banana 2 (`gemini-3.1-flash-image`) image adapter supports text generation and one approved reference image through the Gemini API. To configure it, set the server credential master key, then add a Google Gemini connection and publish its image capability in the administrator media settings. Google has only been tested against a local fake HTTP server and PostgreSQL; no real Google call has been made. Real model/template compatibility, provider recovery, full usage accounting, and release gates remain open. Do not label Mock images or videos as real AI generation.

Compose remains in Mock mode by default. Candidate-provider testing can set `AGENVAS_LLM_MODE=configured` and `AGENVAS_CREDENTIAL_MASTER_KEY`, then save and diagnose the LLM through the administrator page. Media connections, capabilities, and defaults are managed in the UI; the legacy `AGENVAS_PROVIDER_MODE` and ComfyUI environment settings are imported only once during the V40 upgrade. A local ComfyUI origin must be explicitly reachable from the server container; container `127.0.0.1` is not the host. Existing/UNKNOWN submissions may still need reconciliation on the original instance. These settings enable only candidate paths, not verified real generation.

## Local development

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

`contracts/openapi.yaml` is the authoritative API contract. Regenerate `frontend/src/shared/api/schema.ts` after changing it; do not edit generated types manually. Database migrations are append-only. Read [AGENTS.md](AGENTS.md) before changing behavior. Tests that use a fake model or fake ComfyUI server are not evidence of real generation.

When restoring an older backup, start with `AGENVAS_RECOVERY_MODE=true`. It keeps project and settings APIs read-only, disables scheduled workers, and skips Flyway migrations until external attempts and assets are audited. Follow the [backup and restore runbook](docs/operations/backup-restore.md); do not simply restart normal workers against an old database snapshot.

## Current limits

This is a single-administrator, single-application development build, not a multi-tenant hosted service. There has been no verified real LLM + real image + real video golden path, no full backup-and-restore exercise, and no completed security/performance/release audit. The outstanding checks remain unchecked in [DEVELOPMENT-CHECKLIST.md](docs/DEVELOPMENT-CHECKLIST.md). The planned project license is Apache-2.0; the formal license and third-party notices are still pending the release gate. See [SECURITY.md](SECURITY.md) for the current reporting boundary and the [0.1.0 release-notes draft](docs/release-notes/0.1.0-mvp-draft.md) for verified scope and upgrade cautions.

Video assets archived before migration V35 have no verified duration metadata. They cannot be used for new manual or Agent-proposed exports until re-archived; new video archives include a probed duration, and export segments must fit within it.
