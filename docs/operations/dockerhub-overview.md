# Maintaining Docker Hub Overviews manually

Docker Hub descriptions are edited manually. GitHub Actions does not synchronize Overviews or short descriptions; updating these files will not overwrite content on Docker Hub.

The English documents remain available as editing references:

- [Server Overview](../dockerhub/server.md) for [grayrepo/agenvas-server](https://hub.docker.com/r/grayrepo/agenvas-server).
- [Web Overview](../dockerhub/web.md) for [grayrepo/agenvas-web](https://hub.docker.com/r/grayrepo/agenvas-web).

## Update a description

Open the appropriate repository on Docker Hub, edit its Overview, and copy or adapt the corresponding Markdown document. Review and save the change on Docker Hub. Keep installation instructions aligned with the root `docker-compose.yml`, runtime images, and tag rules in `.github/workflows/ci.yml`.

Suggested short descriptions:

- Server: `Agenvas backend: a self-hosted AI creative canvas for text, images, video, audio, and agents.`
- Web: `Agenvas web UI and reverse proxy for the self-hosted AI creative canvas.`

Use absolute links because Docker Hub does not resolve repository-relative Markdown links the same way GitHub does. Docker Hub limits Overview content to 25,000 bytes and short descriptions to 100 bytes.

The image build and version-tag publishing workflow is unchanged. Editing descriptions does not require publishing a new image or changing CI credentials.
