# Agenvas Web

[Agenvas](https://github.com/grayrepo-byte/Agenvas) is a self-hosted AI creative canvas for text, images, video, audio, and agents. The interface supports English, Simplified Chinese, Japanese, and Russian.

`grayrepo/agenvas-web` contains the built React application and an unprivileged Nginx reverse proxy. It is the browser entry point for Agenvas, not a standalone application server. Run it with [agenvas-server](https://hub.docker.com/r/grayrepo/agenvas-server) and PostgreSQL using the official Compose file.

## Quick start

Install and start Docker Engine or Docker Desktop with Docker Compose v2. No local build tools, GPU, `.env` file, or model key is needed to start the application and create an administrator.

For a **new installation on macOS or Linux**, run:

```sh
mkdir agenvas && cd agenvas && \
  curl -fL https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml \
    -o docker-compose.yml && \
  docker compose up -d --wait
```

Open [http://127.0.0.1:8088/setup](http://127.0.0.1:8088/setup), create your administrator account, and sign in. Keep the `agenvas` directory for maintenance. See the [installation guide](https://github.com/grayrepo-byte/Agenvas#quick-start) for Windows commands.

Configure text models and media providers in settings before generating content. The default installation uses real providers, not Mock mode; external services may charge for generation. See [supported integrations](https://github.com/grayrepo-byte/Agenvas#supported-integrations).

## Architecture and networking

- Nginx serves the built Vite/React frontend on container port **8080**.
- API, event streams, media requests, and `/mcp` are proxied to the backend at **`server:8080`** on the Compose network. The web image expects that backend service name.
- `agenvas-server` provides authentication, task execution, provider integrations, and storage; it connects to the official `postgres:17.11-alpine` service.
- Both application images support **Linux amd64 and arm64**. Docker selects the correct architecture automatically.

The default Compose file publishes **`0.0.0.0:8088:8080`** for web. Use `http://<server-ip>:8088` from another device. Backend and database ports are not published to the host. Change the host port in Compose if 8088 is already occupied.

Complete administrator setup on a controlled network before exposing the service. For internet access, configure HTTPS and a reverse proxy, and set `AGENVAS_SECURE_COOKIES: "true"` in the **server** environment. TLS certificates are not bundled into this image.

## Persistence and maintenance

The web container runs read-only with a temporary writable `/tmp`; it does not store your projects or media. The complete Compose stack persists data in:

- `postgres-data`: application database.
- `asset-data`: uploaded and generated media.
- `credentials-data`: the automatically generated database password and credential encryption key.

Back up all three volumes and the Compose configuration before upgrading. Preserve the original encryption secrets when restoring an installation. **Do not run `docker compose down -v`**: it deletes the data and secrets.

From the installation directory:

```sh
# Start, or update to the configured image versions after taking a backup.
docker compose up -d --wait

# Check the services.
docker compose ps

# Stop without deleting data.
docker compose down
```

## Tags and versions

- `latest` follows stable version-tag releases, not every source commit.
- `<version>` is the release version without the leading `v`, for example `0.0.11` for `v0.0.11`.
- Prerelease tags retain their suffix, such as `<version>-rc.1`, and do not update `latest`.
- `sha-<full-commit-sha>` identifies the source commit used by a published release.

Keep **server and web on the same release version**. To pin a release, check [published web tags](https://hub.docker.com/r/grayrepo/agenvas-web/tags) and [release notes](https://github.com/grayrepo-byte/Agenvas/releases), then replace `latest` on **both** application images in Compose. For reproducible configuration, start with the Compose file from that release's Git tag, then set both application image tags to the chosen version. The default Compose file pulls the configured application images on each `up`.

Agenvas is a development build intended for a single self-hosting administrator. Data compatibility with future versions is not guaranteed; review release notes before upgrading.

## Project links

- [Source code and full documentation](https://github.com/grayrepo-byte/Agenvas)
- [Deployment and maintenance](https://github.com/grayrepo-byte/Agenvas#deployment-and-maintenance)
- [Issues](https://github.com/grayrepo-byte/Agenvas/issues)
- [MIT license](https://github.com/grayrepo-byte/Agenvas/blob/main/LICENSE)

This Overview is maintained in [docs/dockerhub/web.md](https://github.com/grayrepo-byte/Agenvas/blob/main/docs/dockerhub/web.md) as a reference for manually editing the Docker Hub Overview. GitHub Actions does not synchronize this text.
