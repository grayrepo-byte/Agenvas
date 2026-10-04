<p align="center">
  <img src="frontend/src/assets/brand/agenvas-favicon.png" alt="Agenvas Logo" width="120" />
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

### 1. Start

Install Docker Engine / Docker Desktop and Docker Compose. Put `docker-compose.yml` in a directory and run:

```sh
docker compose up -d --wait
```

No `.env` file or manual secret generation is required. The first start generates a database password and credential encryption key, then stores them in the persistent `credentials-data` volume. Restarts, updates, and ordinary `down` retain the same values.

The default file pulls `docker.io/grayrepo/agenvas-server:latest`, `docker.io/grayrepo/agenvas-web:latest`, and the official `postgres:17.11-alpine` image without local compilation. The publishing workflow produces amd64 / arm64 application images; first publish a `latest` image containing the automatic secret entrypoint. Before that publication, use [container source builds](#container-source-builds). Starting and signing in require neither a GPU nor a model key.

Each Compose file contains all environment settings, ports, volumes, health checks, and resource limits. Edit the chosen file directly. To pin an application version, replace `latest` with a published version, commit tag, or digest. PostgreSQL uses an [official fixed-version image](https://hub.docker.com/_/postgres) and is never built or published by this project.

### 2. Create an administrator

Open <http://127.0.0.1:8088/setup>, choose an administrator username and password, and sign in after account creation. No setup secret is required.

The database permanently records successful initialization: only one concurrent request succeeds; later requests return `409 SETUP_ALREADY_COMPLETED`. Restarts, disabling, or deleting the account never reopen setup. Failed initialization rolls back and can be retried. Complete setup locally before exposing a public reverse proxy.

The database password and credential encryption key are configured automatically. Their files are under `/run/agenvas/credentials/installation/` in the server container; ordinary use requires neither reading nor filling them in:

| Variable | Generated value | File |
| --- | --- | --- |
| `AGENVAS_DB_PASSWORD` | 32 random bytes encoded as a 64-character hexadecimal password | `database-password` |
| `AGENVAS_CREDENTIAL_MASTER_KEY` | 32 random bytes encoded as Base64 | `credential-master-key` |

Values are never written into Compose, Git, or startup logs. Encrypt and escrow `credentials-data` separately when backing up. Avoid `down -v`, which deletes data and secrets. Existing databases must restore this volume or import the original database password and encryption key through a private Compose configuration outside the repository. See [backup and restore](docs/operations/backup-restore.md).

### 3. Configure models and create

- **Text and Agent**: Add an OpenAI-compatible endpoint, model ID, and API key under settings → model settings. An administrator must run the tool-calling diagnostic before using an Agent.
- **Images, video, and audio**: Create connections, publish capabilities, and choose default models in media settings. The default deployment requires real model configuration before generating media.

Create a project and use the canvas context menu to add cards or upload media. Select a model, enter a prompt and references, check the estimated cost, and run. Preview, select, or regenerate results. Agent cards can bind context and use Skills; approve or reject media proposals as a batch in the conversation.

Seedance video references need a publicly accessible media relay. See the [relay guide](docs/media-relay-design.md).

> UNKNOWN results require an explicit retry, which may incur duplicate costs. Cancellation does not guarantee that an external service stops or refunds charges. Project manifests contain data and media metadata; they do not replace database and media file backups.

### Update and stop

Run `docker compose up -d --wait` to pull and apply the current `latest` application images. If you edited the image tags or digests directly in Compose, it uses those versions instead. To stop services and retain data volumes:

```sh
docker compose down
```

For source builds, update the checkout and run `./deploy/update-local.sh`; stop with `docker compose -f docker-compose.local.yml down`.

Default ports are Web `8088`, API `8080`, and PostgreSQL `5432`, all bound to loopback. Edit `ports` directly in the chosen Compose file.

Public deployment requires HTTPS, a reverse proxy, and `AGENVAS_SECURE_COOKIES: "true"` in Compose. Back up databases, media, configuration, and encryption keys before upgrading. For old V1–V77 development database migration limits, see [backup and recovery](docs/operations/backup-restore.md).

## Local development

| Layer | Stack |
| --- | --- |
| Frontend | Vite · React · TypeScript · React Flow · TanStack Query · Zustand |
| Backend | Java 21 · Spring Boot · Spring AI · jOOQ |
| Data and deployment | PostgreSQL · Flyway · Docker Compose · REST / SSE |

Toolchain: JDK 21, Node 24 LTS (24.12+), and pnpm 12.5.1. Exact versions are in the [dependency baseline](docs/dependency-baseline.md).

### Container source builds

From the repository root, compile server and web from the current checkout and start all three services; PostgreSQL uses the official fixed-version image:

```sh
docker compose -f docker-compose.local.yml up -d --build --wait
```

Alternatively, `./deploy/update-local.sh` builds all images before updating containers and waiting for health checks. Source builds need no Docker Hub login. Text and media still default to `configured`. The existing `deploy/compose.yaml` uses the same source build configuration.

Image deployment and source builds both default to the `agenvas` project and retain the same database, media, and credential volumes. Back up data and check version compatibility before switching. To run independent environments simultaneously, use distinct project names with `-p` and override the ports.

### Mock environment

Start directly; the database password and encryption key are generated automatically, with no external model accounts:

```sh
docker compose -f deploy/compose.dev.yaml up -d --build --wait
```

Text and media use Mock. Images, video, and audio are synthetic demo materials, not real model output. Use the same setup URL to create your account directly. Stop with `down` using the same Compose file.

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

For a standalone JVM, provide `AGENVAS_DB_URL`, `AGENVAS_DB_USER`, and `AGENVAS_DB_PASSWORD`; `.env.example` lists these variables but is not automatically loaded. Automatic installation secrets apply to container entrypoints. Source execution defaults to Mock. Media processing requires FFmpeg / FFprobe; see [local image processing](docs/local-image-processing.md) for depth extraction.

Vite runs on port `5173` and proxies `/api` to `localhost:8080`.

</details>

## Automatic Docker Hub publication

Configure these under **Settings → Secrets and variables → Actions** in the GitHub repository:

| Type | Name | Value |
| --- | --- | --- |
| Secret | `DOCKERHUB_USERNAME` | A Docker Hub user authorized to push the images |
| Secret | `DOCKERHUB_TOKEN` | A Docker Hub access token with read/write permissions for the target repositories |

Prepare two public Docker Hub repositories, `grayrepo/agenvas-server` and `grayrepo/agenvas-web`, allowing anonymous deployment pulls. CI publishes directly under `grayrepo`; to change the publisher, edit the workflow and Compose addresses. PostgreSQL is pulled from its official repository and scanned only.

`.github/workflows/ci.yml` runs on pushes to `main`, `v*.*.*` version tags, and manual dispatches. After frontend/backend tests, Compose checks, source scanning, and application/official PostgreSQL image scans on both architectures succeed, it publishes multi-platform manifests from the exact scanned application images:

- Every publication: `sha-<full 40-character commit SHA>`.
- `main` branch: also updates `latest` for the default Compose file; manual runs on main do the same.
- `v0.1.0` version tag: also publishes `0.1.0`; prereleases retain their suffix, and version-tag runs do not overwrite main's `latest`.

PRs run checks without Docker Hub credentials or pushes. Manual runs on other branches do not publish. Each service and architecture retains SBOM and license artifacts; published tags appear in the Actions summary. `ci-<run ID>-<attempt>-<architecture>` tags are intermediate images; deploy `latest` or choose a final version, commit tag, or digest directly in Compose.

The workflow follows [Docker's multi-platform build documentation](https://docs.docker.com/build/ci/github-actions/multi-platform/) and [Docker image tagging rules](https://github.com/docker/metadata-action).

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
