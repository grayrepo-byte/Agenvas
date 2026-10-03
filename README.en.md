<p align="center">
  <img src="frontend/src/assets/brand/agenvas-square.png" alt="Agenvas Logo" width="160" />
</p>

<h1 align="center">Agenvas</h1>

<p align="center">A self-hosted AI creative canvas for text, images, video, audio, and agents in one workspace.</p>

<p align="center"><a href="README.md">中文</a> · <a href="#usage">Usage</a> · <a href="LICENSE">ELv2</a></p>

## Features

- **Creative canvas**: Text, image, video, audio, and Agent cards with dragging, selection, connections, zoom, locking, and automatic arrangement. Upload local media directly from the canvas context menu.
- **Direct generation**: Edit prompts, select models, add references, and run individual cards. Queue, cancel, regenerate, and switch historical versions; image edits and post-processing create separate derived nodes.
- **Agent collaboration**: Read explicitly bound context, create and edit text, and arrange cards. Persistent conversations, creative Skills, streamed public answers, and execution records are available. Agent media proposals require user approval before generation.
- **Reusable materials**: Project resources, a personal asset library shared across your own projects, image/video prompt templates, visual styles, and image/video/audio references.
- **Image tools**: Brush annotations, crop, rotate, mirror, resize, and depth extraction. AI edits, outpainting, relighting, and related operations use configured media capabilities.
- **Model and storage settings**: Administrator-managed LLMs, media connections, published capabilities, and default models. Local files and OSS/COS/S3 storage, with a separate relay for video references.
- **Execution and recovery**: Persistent tasks, call logs, usage records, event replay after disconnects, and project manifests. UNKNOWN generation requests require an explicit retry; the system does not automatically resubmit them.
- **Languages**: Chinese, English, Russian, and Japanese use the same interface.

Media adapters include GPT Image, Google Nano Banana, Volcano Ark Seedance, Seed Audio, fixed ComfyUI templates, and RunningHub workflows/AI apps using the fixed V2 protocol. Available inputs, parameters, and operations depend on administrator-published capabilities. An implemented adapter does not establish verified compatibility with every model or official endpoint.

This is a development build for a single self-hosting administrator. Some image proxy endpoints have real generation records; the complete real LLM/image/video path, other Provider compatibility, and production release gates remain incomplete. See the [development checklist](docs/DEVELOPMENT-CHECKLIST.md) for actual verification scope.

## Usage

### 1. Start the application

Install Docker Engine / Docker Desktop and Docker Compose, then run from the repository root:

```sh
cp .env.example .env
```

Edit `.env`:

| Setting | Purpose |
| --- | --- |
| `AGENVAS_DB_PASSWORD` | Unique random database password; required |
| `AGENVAS_BOOTSTRAP_SECRET` | One-time administrator setup secret, at least 24 characters; required |
| `AGENVAS_CREDENTIAL_MASTER_KEY` | Base64-encoded random 32-byte encryption key; required before saving model API keys, cloud credentials, or full ComfyUI addresses |

Keep real credentials in your local `.env` or server configuration. Store encryption keys separately from database backups.

```sh
./deploy/update-local.sh
```

The script builds images, updates containers, and waits for health checks while retaining database and media volumes. Default deployment uses configured providers. Starting the application and signing in require neither a GPU nor a model key.

Open [administrator setup](http://127.0.0.1:8088/setup), enter the setup secret, create an administrator, and [sign in](http://127.0.0.1:8088/login).

### 2. Configure models

- **Text and Agent**: Add an OpenAI-compatible endpoint, model ID, and API key in model settings. An administrator must run the tool-calling diagnostic before Agent use; the interface explains potential call costs.
- **Images, video, and audio**: Create connections, publish capabilities, and select default models in media settings. The default deployment has no available media capabilities until configured.
- **Video reference relay**: For Seedance video references from a local deployment, select a publicly accessible OSS/COS/S3 connection under storage settings → media relay. Default storage can remain local. See the [relay guide](docs/media-relay-design.md).

ComfyUI uses trusted fixed templates; RunningHub uses administrator-published targets and parameter contracts. Credentials are encrypted server-side, and configuration changes do not replace connection versions pinned by accepted tasks.

### 3. Create on the canvas

1. Create a project and open its canvas. Add cards or upload images, video, and audio through the context menu.
2. Edit or generate text in a text card. For media, select a model, enter a prompt, and add the references required by its capability.
3. Check inputs and estimated cost (unknown when pricing is unavailable), then run. Preview and select results, regenerate within the node, or inspect version history.
4. Add an Agent card for assistance, explicitly bind context, choose a Skill, and enter a task. Send the task to start; the server checks the model, inputs, and pinned versions before accepting it. Approve or reject a media proposal as a batch in the conversation.
5. Save results to your personal asset library for reuse, or export a project manifest. The manifest contains data and media metadata; a full backup also requires the database and media files.

When a request becomes UNKNOWN, inspect its task and call records first. Explicit retries create separate attempts and may incur duplicate costs. Cancellation stops subsequent local orchestration; it does not guarantee external cancellation or refunds.

### Update, stop, and back up

After updating repository code, run `./deploy/update-local.sh` again. Base images are pinned by digest; the script does not automatically upgrade their versions.

```sh
# Stop default deployment and retain database/media volumes
docker compose --env-file .env -f deploy/compose.yaml down
```

- Default Web/API/database ports are `8088` / `8080` / `5432`, bound to loopback only. Override with `AGENVAS_WEB_PORT`, `AGENVAS_API_PORT`, and `AGENVAS_DB_PORT`; use a separate `COMPOSE_PROJECT_NAME` for isolated installations.
- Public deployment requires HTTPS, a reverse proxy, and secure cookies (`AGENVAS_SECURE_COOKIES=true`). Resource limits are documented in `.env.example`. Frontend build heap defaults to 1536 MiB and can be adjusted with the Docker build argument `FRONTEND_BUILD_HEAP_MB`.
- Back up databases, media, configuration, and current/historical encryption keys before upgrading. Old V1–V77 development databases cannot upgrade directly to the rebuilt V1 baseline. Restore with a matching version and recovery mode first. See [backup and recovery](docs/operations/backup-restore.md).
- Administrator-only system logs are available in settings. They retain bounded output from the current backend process and clear on restart.

## Local development

Frontend: Vite, React, TypeScript, React Flow, TanStack Query, and Zustand. Backend: Java 21, Spring Boot, Spring AI, jOOQ, PostgreSQL, and Flyway. The frontend is a static SPA; Spring Boot provides business APIs and SSE.

Use JDK 21, Node 24 LTS (24.12+), and pnpm 12.5.1. Exact versions are recorded in the [dependency baseline](docs/dependency-baseline.md).

### Container development environment (Mock)

After filling the database password and setup secret in `.env`, start the separate development environment:

```sh
docker compose --env-file .env -f deploy/compose.dev.yaml up -d --build
# Stop development and retain its volumes
docker compose --env-file .env -f deploy/compose.dev.yaml down
```

Development Compose explicitly enables text and media Mock without external model accounts. Images, video, and audio are synthetic demo materials; audio is a tone, not speech synthesis. The setup URL and login process are the same as above.

Default deployment and development project names are `agenvas` and `agenvas-dev`. Their volumes are separate, but default ports overlap. Use distinct project names and ports when running both simultaneously.

### Run from source

```sh
# Frontend, in one terminal
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm dev
```

```sh
# Backend, in another terminal; configure environment variables and PostgreSQL first
cd backend
./mvnw spring-boot:run
```

Provide `AGENVAS_DB_URL`, `AGENVAS_DB_USER`, `AGENVAS_DB_PASSWORD`, and `AGENVAS_BOOTSTRAP_SECRET` to the backend. Spring Boot does not automatically load the root `.env`. Source execution defaults to Mock; media processing needs local FFmpeg/FFprobe, optionally selected with `AGENVAS_MEDIA_TOOLS_FFMPEG` and `AGENVAS_MEDIA_TOOLS_FFPROBE`. Depth extraction has additional [local configuration](docs/local-image-processing.md). Vite runs on port `5173` and proxies `/api` to `localhost:8080`.

```text
frontend/       Pages and canvas interaction
backend/        Business APIs, Agent Runtime, and task execution
contracts/      Authoritative OpenAPI and content schemas
configs/        Agent, Skill, and trusted media workflow configuration
deploy/         Compose, Nginx, and container builds
docs/           Specifications, designs, dependencies, and verification
```

Before contributing, read [AGENTS.md](AGENTS.md), the [MVP specification](docs/MVP-SPEC.md), and the [development checklist](docs/DEVELOPMENT-CHECKLIST.md). See [SECURITY.md](SECURITY.md) for security reporting and current support boundaries.

## License

The Agenvas main project uses [Elastic License 2.0 (ELv2)](LICENSE). Subject to its terms, you may use, copy, modify, and distribute the source, including for self-hosted use.

ELv2 restricts hosted or managed services that give third parties access to substantial software functionality, prohibits circumventing license-key functionality, and requires preservation of licensing and copyright notices. It is a source-available license, not an OSI-approved open-source license. Refer to [LICENSE](LICENSE) and the [official Elastic terms](https://www.elastic.co/licensing/elastic-license) for the full conditions.

Third-party dependencies, model weights, media workflows, and components such as FFmpeg retain their own licenses. Model fees and hardware costs remain the user's responsibility. Complete third-party license and release-inventory reviews remain release gates.
