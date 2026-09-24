FROM agenvas-canvas-perf-e2e-server:latest
COPY --chown=agenvas:agenvas agenvas-server-*.jar /opt/agenvas/app.jar
