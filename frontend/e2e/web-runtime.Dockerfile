FROM agenvas-canvas-perf-e2e-web:latest
COPY --chown=nginx:nginx . /usr/share/nginx/html/
