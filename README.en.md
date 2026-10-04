<p align="center">
  <img src="frontend/src/assets/brand/agenvas-square.png" alt="Agenvas Logo" width="120" />
</p>

<h1 align="center">Agenvas</h1>

<p align="center"><strong>A self-hosted AI creative canvas</strong><br />Text, images, video, audio, and agents in one workspace.</p>

<p align="center">
  <a href="README.md">中文</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#local-development">Development</a> ·
  <a href="#documentation-and-contributing">Docs &amp; contributing</a> ·
  <a href="LICENSE">MIT</a>
</p>

> Development build for a single self-hosting administrator. See the [development checklist](docs/DEVELOPMENT-CHECKLIST.md) for real model compatibility and release verification status.

## Features

| Feature | Description |
| --- | --- |
| Creative canvas | Drag, select, connect, arrange, and upload local media |
| Multimodal generation | Run text, image, video, and audio cards with queues, cancellation, regeneration, and version history |
| Agent collaboration | Read bound context, edit text, and arrange cards; media proposals require user approval |
| Reusable materials | Project resources, personal assets, prompt templates, visual styles, and mixed references |
| Image tools | Annotate, crop, rotate, mirror, resize, extract depth, and use configured AI editing capabilities |
| Self-hosting | Model settings, local and OSS/COS/S3 storage, task and call records, and project manifest exports |

The interface supports Chinese, English, Russian, and Japanese. Media adapters include GPT Image, Google Nano Banana, Volcano Ark Seedance, Seed Audio, fixed ComfyUI templates, and RunningHub V2 workflows / AI apps. Available operations depend on administrator-published capabilities.

## Quick start

### 1. Configure the environment

Install Docker Engine / Docker Desktop and Docker Compose. From the repository root:

```sh
cp .env.example .env
```

Edit `.env` (never commit real credentials):

| Setting | Purpose |
| --- | --- |
| `AGENVAS_DB_PASSWORD` | Required: a unique random database password |
| `AGENVAS_BOOTSTRAP_SECRET` | Required: a one-time administrator setup secret of at least 24 characters |
| `AGENVAS_CREDENTIAL_MASTER_KEY` | A Base64-encoded random 32-byte key; required before saving model or storage credentials, or full ComfyUI addresses |

### 2. Start and sign in

```sh
./deploy/update-local.sh
```

The script builds images and starts the services, retaining database and media volumes. Starting and signing in require neither a GPU nor a model key.

Open <http://127.0.0.1:8088/setup>, enter the setup secret, create an administrator, and sign in.

### 3. Configure models and create

- **Text and Agent**: Add an OpenAI-compatible endpoint, model ID, and API key under settings → model settings. An administrator must run the tool-calling diagnostic before using an Agent.
- **Images, video, and audio**: Create connections, publish capabilities, and choose default models in media settings. The default deployment requires real model configuration before generating media.

Create a project and use the canvas context menu to add cards or upload media. Select a model, enter a prompt and references, check the estimated cost, and run. Preview, select, or regenerate results. Agent cards can bind context and use Skills; approve or reject media proposals as a batch in the conversation.

Seedance video references need a publicly accessible media relay. See the [relay guide](docs/media-relay-design.md).

> UNKNOWN results require an explicit retry, which may incur duplicate costs. Cancellation does not guarantee that an external service stops or refunds charges. Project manifests contain data and media metadata; they do not replace database and media file backups.

### Update and stop

After updating the code, run `./deploy/update-local.sh` again. To stop services and retain data volumes:

```sh
docker compose --env-file .env -f deploy/compose.yaml down
```

Default ports are Web `8088`, API `8080`, and PostgreSQL `5432`, all bound to loopback. Override them in `.env` with `AGENVAS_WEB_PORT`, `AGENVAS_API_PORT`, and `AGENVAS_DB_PORT`.

Public deployment requires HTTPS, a reverse proxy, and `AGENVAS_SECURE_COOKIES=true`. Back up databases, media, configuration, and encryption keys before upgrading. For old V1–V77 development database migration limits, see [backup and recovery](docs/operations/backup-restore.md).

## Local development

| Layer | Stack |
| --- | --- |
| Frontend | Vite · React · TypeScript · React Flow · TanStack Query · Zustand |
| Backend | Java 21 · Spring Boot · Spring AI · jOOQ |
| Data and deployment | PostgreSQL · Flyway · Docker Compose · REST / SSE |

Toolchain: JDK 21, Node 24 LTS (24.12+), and pnpm 12.5.1. Exact versions are in the [dependency baseline](docs/dependency-baseline.md).

### Mock environment

Fill the database password and setup secret in `.env`, then start without external model accounts:

```sh
docker compose --env-file .env -f deploy/compose.dev.yaml up -d --build
```

Text and media use Mock. Images, video, and audio are synthetic demo materials, not real model output. Use the same setup URL and process as above; stop with `down` using the same Compose file.

Default deployment and Mock use separate data volumes but share default ports. Set distinct ports to run both simultaneously.

<details>
<summary><strong>Run from source</strong></summary>

Frontend (separate terminal):

```sh
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm dev
```

Backend (separate terminal; configure PostgreSQL and environment variables first):

```sh
cd backend
./mvnw spring-boot:run
```

Provide `AGENVAS_DB_URL`, `AGENVAS_DB_USER`, `AGENVAS_DB_PASSWORD`, and `AGENVAS_BOOTSTRAP_SECRET` to the backend; it does not automatically load the root `.env`. Source execution defaults to Mock. Media processing requires FFmpeg / FFprobe; see [local image processing](docs/local-image-processing.md) for depth extraction.

Vite runs on port `5173` and proxies `/api` to `localhost:8080`.

</details>

## Documentation and contributing

| Document | Contents |
| --- | --- |
| [Product specification](docs/MVP-SPEC.md) | Feature scope and behavior |
| [Development checklist](docs/DEVELOPMENT-CHECKLIST.md) | Implementation and verification status |
| [Development guidelines](AGENTS.md) | Architecture, coding, and testing requirements |
| [Backup and recovery](docs/operations/backup-restore.md) | Upgrades, backups, and recovery |
| [Security policy](SECURITY.md) | Security reporting and support scope |

Issues and pull requests are welcome. Read the specification and development guidelines before contributing, and run the relevant tests for feature changes.

```text
frontend/       Pages and canvas interaction
backend/        Business APIs, Agent Runtime, and task execution
contracts/      Authoritative OpenAPI and content schemas
configs/        Agent, Skill, and trusted media workflow configuration
deploy/         Compose, Nginx, and container builds
docs/           Specifications, designs, and development documentation
```

## License

Licensed under the [MIT License](LICENSE). Third-party dependencies, model weights, workflows, and components such as FFmpeg retain their own licenses.
