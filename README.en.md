<p align="center">
  <img src="frontend/src/assets/brand/agenvas-favicon.png" alt="Agenvas Logo" width="120" />
</p>

<h1 align="center">Agenvas</h1>

<p align="center"><strong>A self-hosted AI creative canvas</strong><br />Text, images, video, audio, and agents in one workspace.</p>

<p align="center">
  <a href="README.md">中文</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#deployment-and-maintenance">Deployment</a> ·
  <a href="#local-development">Development</a> ·
  <a href="#documentation-and-contributing">Docs &amp; contributing</a> ·
  <a href="LICENSE">MIT</a>
</p>

<p align="center">Author: <a href="https://x.com/Grayrepo">X / Twitter @Grayrepo</a> · Email: <a href="mailto:yoshioka8084806@gmail.com">yoshioka8084806@gmail.com</a></p>

<p align="center">
  <img src="docs/assets/wechat-official-account.jpg" alt="WeChat official account QR code" width="180" /><br />
  Scan to follow on WeChat
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

### 1. Start with one command

Before using the command below, install and start **Docker Engine / Docker Desktop**, with **Docker Compose v2**. No Git, local build tools, GPU, `.env` file, or model key is needed to start and sign in.

For a new installation, paste this entire block into a **macOS / Linux** terminal:

```sh
mkdir agenvas && cd agenvas && \
  curl -fL https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml \
    -o docker-compose.yml && \
  docker compose up -d --wait
```

**Windows: one-command installation**

For a new installation, open CMD or PowerShell and paste the corresponding block. Choose one of the two options.

<details>
<summary><strong>CMD (Command Prompt)</strong></summary>

```cmd
mkdir agenvas && cd agenvas && curl.exe -fL https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml -o docker-compose.yml && docker compose up -d --wait
```

</details>

<details>
<summary><strong>PowerShell (Windows PowerShell 5.1 / PowerShell 7)</strong></summary>

```powershell
& {
  $ErrorActionPreference = 'Stop'
  New-Item -ItemType Directory -Path agenvas | Out-Null
  Set-Location agenvas
  Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml' -OutFile docker-compose.yml -UseBasicParsing
  docker compose up -d --wait
}
```

</details>

**Windows: install with an AI Agent**

Send the [installation prompt (Chinese)](docs/operations/windows-ai-install-prompt.md) to WorkBuddy or another AI Agent that can run tasks on your computer. It will check the environment, install Agenvas, and start it. Open the address it provides to create your account.

For manual installation, see the [Windows guide (Chinese)](docs/operations/windows-install.md).

These steps prepare an `agenvas` directory and Compose file, pull the images, and wait for all three services to become healthy. Keep this directory for future management. If you already have the Compose file, run `docker compose up -d --wait` in its directory.

Database and encryption secrets are generated and saved automatically on first start. First-time image downloads may take a few minutes.

### 2. Create an administrator

Open **<http://127.0.0.1:8088/setup>** locally, or `http://<server-ip>:8088/setup` from another device. Choose an administrator username and password, then sign in. Complete setup on a controlled network before exposing the service to the internet.

### 3. Configure models and create

- **Text and Agent**: Add an OpenAI-compatible endpoint, model ID, and API key under settings → model settings. An administrator must run the tool-calling diagnostic before using an Agent.
- **Images, video, and audio**: Create connections, publish capabilities, and choose default models in media settings. The default deployment requires real model configuration before generating media.

Create a project and use the canvas context menu to add cards or upload media. Select a model, enter a prompt and references, check the estimated cost, and run. Preview, select, or regenerate results. Agent cards can bind context and use Skills; approve or reject media proposals as a batch in the conversation.

Seedance video references need a publicly accessible media relay. See the [relay guide](docs/media-relay-design.md).

> UNKNOWN results require an explicit retry, which may incur duplicate costs. Cancellation does not guarantee that an external service stops or refunds charges. Project manifests contain data and media metadata; they do not replace database and media file backups.

## Deployment and maintenance

Run the following commands from the directory containing `docker-compose.yml` (created as `agenvas` above):

| Action | Command |
| --- | --- |
| Start / update to the configured image version | `docker compose up -d --wait` |
| Stop while keeping data | `docker compose down` |
| View service status | `docker compose ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"` |
| View recent startup logs | `docker compose logs --tail=100 server postgres` |

> Back up the database, media, configuration, and encryption secrets before updating. **Do not run `docker compose down -v`**: it deletes data and secrets. See [backup and recovery](docs/operations/backup-restore.md).

<details>
<summary><strong>Ports, image versions, and public deployment</strong></summary>

- The default `docker-compose.yml` publishes only Web `8088` on `0.0.0.0`, allowing access through the server IP. API `8080` and PostgreSQL `5432` use the internal container network and are not published to the host. Source and Mock Compose files retain all three ports on loopback for development. If the Web port is in use, change the host port in `ports`.
- The default file pulls `docker.io/grayrepo/agenvas-server:latest`, `docker.io/grayrepo/agenvas-web:latest`, and the official `postgres:17.11-alpine` image. The publishing workflow targets amd64 / arm64 application images; no local compilation is required.
- Application images are pulled on every `up`. To pin a version, replace `latest` with a published version, commit tag, or digest.
- Each Compose file includes environment settings, ports, volumes, health checks, and resource limits; edit it directly without additional configuration files.
- Public deployment requires HTTPS, a reverse proxy, and `AGENVAS_SECURE_COOKIES: "true"` in Compose. For old V1–V77 database migration limits, see [backup and recovery](docs/operations/backup-restore.md).
- For source builds, update the checkout and run `./deploy/update-local.sh`; stop with `docker compose -f docker-compose.local.yml down`.

</details>

<details>
<summary><strong>Automatic secrets, administrator initialization, and recovery</strong></summary>

The database permanently records successful initialization: only one concurrent request succeeds; later requests return `409 SETUP_ALREADY_COMPLETED`. Restarts, disabling, or deleting the account never reopen setup. Failed initialization rolls back and can be retried. Complete setup on a controlled network before exposing a public reverse proxy.

The database password and credential encryption key are configured automatically. Their files are under `/run/agenvas/credentials/installation/` in the server container; ordinary use requires neither reading nor filling them in:

| Variable | Generated value | File |
| --- | --- | --- |
| `AGENVAS_DB_PASSWORD` | 32 random bytes encoded as a 64-character hexadecimal password | `database-password` |
| `AGENVAS_CREDENTIAL_MASTER_KEY` | 32 random bytes encoded as Base64 | `credential-master-key` |

To view the database password, run this command from the directory containing the Compose file while the PostgreSQL container is running:

```sh
docker compose exec -u 0 postgres cat /run/agenvas/credentials/installation/database-password
```

The service name is `postgres`. If you started with `-f` or `-p`, use the same options when reading the password. For example, for a source build:

```sh
docker compose -f docker-compose.local.yml exec -u 0 postgres cat /run/agenvas/credentials/installation/database-password
```

If you see `no configuration file provided: not found`, switch to the directory containing the Compose file or use `-f` to specify its correct path.

Values are never written into Compose, Git, or startup logs. Encrypt and escrow `credentials-data` separately when backing up. Avoid `down -v`, which deletes data and secrets. Existing databases must restore this volume or import the original database password and encryption key through a private Compose configuration outside the repository. See [backup and restore](docs/operations/backup-restore.md).

</details>

<details>
<summary><strong>Startup troubleshooting</strong></summary>

| Symptom | Next step |
| --- | --- |
| Docker cannot connect / Compose command unavailable | Start Docker Desktop or Docker Engine and check `docker compose version` |
| The `agenvas` directory already exists | For an existing installation, enter it and run `docker compose up -d --wait`; for a new download, choose an unused directory name |
| Compose download fails | Check access to `raw.githubusercontent.com`; you can also manually download [docker-compose.yml](docker-compose.yml) |
| `manifest unknown` / image unavailable | Check Docker Hub access and published tags; if no release is available, use [container source builds](#container-source-builds) |
| Port already in use | Edit the host port in `ports`, then start again; if you change `8088`, use the new port in the browser |
| Health checks fail | Inspect startup logs with the command above, fix the reported issue, and run the start command again |

</details>

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

Alternatively, `./deploy/update-local.sh` builds all images before updating containers and waiting for health checks. The backend runs only `package -DskipTests`, without automatically running `mvn verify`; run the full test suites manually as needed or through CI. Its final status table shows only container names, services, status, and ports so full startup commands cannot stretch the table. Source builds need no Docker Hub login. Text and media still default to `configured`. The existing `deploy/compose.yaml` uses the same source build configuration.

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

<details>
<summary><strong>Image publication configuration (maintainers)</strong></summary>

Configure these under **Settings → Secrets and variables → Actions** in the GitHub repository:

| Type | Name | Value |
| --- | --- | --- |
| Secret | `DOCKERHUB_USERNAME` | A Docker Hub user authorized to push the images |
| Secret | `DOCKERHUB_TOKEN` | A Docker Hub access token with read/write permissions for the target repositories |

Prepare two public Docker Hub repositories, `grayrepo/agenvas-server` and `grayrepo/agenvas-web`, allowing anonymous deployment pulls. CI publishes directly under `grayrepo`; to change the publisher, edit the workflow and Compose addresses. PostgreSQL is pulled from its official repository; CI retains its SBOM and license inventory.

`.github/workflows/ci.yml` runs on PRs, pushes to `main`, `v*.*.*` version tags, and manual dispatches. Tests and image builds run in parallel:

| Trigger | Validation | Images and inventories |
| --- | --- | --- |
| Code PR | Frontend, backend unit tests, all four PostgreSQL IT shards, jOOQ, Compose, secret scan | server/web amd64 builds |
| Documentation-only PR | Planning checks, secret scan, CI Gate | Heavy jobs skipped |
| main / manual | Full validation | server/web amd64 + arm64 builds |
| Version tag push | Full validation | Application builds and official PostgreSQL inventories on both architectures; publication after CI Gate |

`CI Gate` always appears and rejects failed, cancelled, or unexpectedly skipped checks. Select it when configuring a required branch check. Buildx caches are scoped by service and architecture; PRs only read them. Server packaging skips the full test suite, while CI retains one depth-model/JNI smoke test per native architecture.

Only version-tag pushes upload to Docker Hub. Release images are inventoried once, then the JSON is converted to CycloneDX. The same images are loaded from short-lived artifacts and pushed after all checks pass. Failure reports are retained for 7 days, publication images for 1 day, and SBOM/license evidence for 90 days.

- Every publication: `sha-<full 40-character commit SHA>`.
- `v0.1.0` version tag: also publishes `0.1.0` with the leading `v` removed.
- Stable version tag (no prerelease suffix, e.g. `v0.1.0`): also points `latest` at that version for the default Compose file; a prerelease (e.g. `v0.1.0-rc.1`) retains its suffix and never updates `latest`.

PRs, `main` pushes, and manual runs never access Docker Hub credentials or push images. Release service/architecture jobs retain SBOM and license artifacts; published tags appear in the Actions summary. `ci-<run ID>-<attempt>-<architecture>` tags are intermediate images; deploy `latest` or choose a final version, commit tag, or digest directly in Compose.

After both server/web multi-platform images are published, the workflow creates a GitHub Release for the version tag. Notes include versioned image addresses, the source commit, commits since the previous version, and GitHub-generated PR, contributor, and changelog links. Stable versions compare against the previous stable tag; prereleases may compare against an earlier prerelease. Prereleases and backfills older than an existing stable release never become Latest. Retries preserve existing notes and only fill empty notes; existing drafts are preserved and require manual publication. Only this tag-only job has `contents: write`; it waits for CI Gate and successful Docker Hub publication and does not rebuild images.

Existing tags are not backfilled automatically. Once their CI and images on both architectures have been verified, preview the notes before explicitly publishing the Release:

```sh
python3 .github/scripts/release.py notes v0.0.2 --repo grayrepo-byte/Agenvas --output release-notes.md
python3 .github/scripts/release.py publish v0.0.2 --repo grayrepo-byte/Agenvas
```

Local commands use your authenticated GitHub CLI without placing tokens in command arguments. `notes` only generates a preview; `publish` writes to GitHub. The script checks the remote tag and source commit without creating or moving tags. Generated content uses the [GitHub Release Notes API](https://docs.github.com/en/rest/releases/releases#generate-release-notes-content-for-a-release).

The workflow follows [Docker's multi-platform build documentation](https://docs.docker.com/build/ci/github-actions/multi-platform/) and [Docker image tagging rules](https://github.com/docker/metadata-action).

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
