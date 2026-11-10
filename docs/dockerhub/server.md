# Agenvas Server

[Agenvas](https://github.com/grayrepo-byte/Agenvas) is a self-hosted AI creative canvas for text, images, video, audio, and agents.

`grayrepo/agenvas-server` is the Spring Boot backend. It provides the application API, authentication, task execution, media storage, model integrations, and the MCP endpoint. Use it with [agenvas-web](https://hub.docker.com/r/grayrepo/agenvas-web) and PostgreSQL through the official Compose file, rather than starting this image alone.

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

The default deployment uses real provider configuration, not Mock mode. Add your text model and media provider connections in settings before generating content. External providers may charge for their services. See [supported integrations](https://github.com/grayrepo-byte/Agenvas#supported-integrations).

## How the containers fit together

- `agenvas-web` serves the React application and proxies API, event streams, media, and MCP requests to `server:8080`.
- `agenvas-server` runs the backend and connects to the `postgres` service.
- PostgreSQL uses the official `postgres:17.11-alpine` image. It is not bundled into this image.
- The published application images support **Linux amd64 and arm64**. Docker selects the matching architecture automatically.

The official Compose file publishes only web port **8088** on all host IPv4 interfaces. Backend port 8080 and PostgreSQL port 5432 stay on the internal Compose network. Complete setup on a controlled network. For public access, use HTTPS with a reverse proxy and set `AGENVAS_SECURE_COOKIES: "true"` in the server environment.

## Data and configuration

The Compose file contains the runtime settings, health checks, resource limits, and three named volumes:

- `postgres-data`: application database.
- `asset-data`: uploaded and generated media; mounted at `/opt/agenvas/data` in the server.
- `credentials-data`: automatically generated database password and credential encryption key. The server mounts it read-only at `/run/agenvas/credentials`.

The backend reads `AGENVAS_DB_URL`, `AGENVAS_DB_USER`, `AGENVAS_DB_PASSWORD_FILE`, and `AGENVAS_CREDENTIAL_MASTER_KEY_FILE` from this configuration. Leave the automatic installation secret values empty for a new installation. Never generate replacement secrets for an existing database; restore the original secrets instead.

Back up the database, media, Compose configuration, and encryption secrets together before updating. **Do not run `docker compose down -v`**: it deletes the data and secrets. The image is not a substitute for a database or media backup.

## Tags and upgrades

- `latest`: updated by stable version-tag releases, not by every source commit.
- `<version>`: the published release version without the leading `v`, for example `0.0.11` for `v0.0.11`.
- `<version>-<prerelease>`: prerelease versions retain their suffix and do not update `latest`.
- `sha-<full-commit-sha>`: the source commit used by a published release.

Use the **same release version for server and web**. Check [published tags](https://hub.docker.com/r/grayrepo/agenvas-server/tags) and [release notes](https://github.com/grayrepo-byte/Agenvas/releases), then replace `latest` on both application images in your Compose file to pin a release. For reproducible configuration, start with the Compose file from that release's Git tag, then set both application image tags to the chosen version. PostgreSQL remains on the version specified by the selected Compose file.

From the installation directory, after taking a backup:

```sh
docker compose up -d --wait
docker compose ps
```

The official Compose file pulls the configured application images on each `up`. To stop without deleting data, use `docker compose down`.

Agenvas is a development build intended for a single self-hosting administrator. Data compatibility with future versions is not guaranteed; review release notes before upgrading.

## Project links

- [Source code and full documentation](https://github.com/grayrepo-byte/Agenvas)
- [Deployment and maintenance](https://github.com/grayrepo-byte/Agenvas#deployment-and-maintenance)
- [Issues](https://github.com/grayrepo-byte/Agenvas/issues)
- [MIT license](https://github.com/grayrepo-byte/Agenvas/blob/main/LICENSE)

This Overview is maintained in [docs/dockerhub/server.md](https://github.com/grayrepo-byte/Agenvas/blob/main/docs/dockerhub/server.md) as a reference for manually editing the Docker Hub Overview. GitHub Actions does not synchronize this text.
