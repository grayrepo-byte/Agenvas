FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build

# Allow up to 4 GiB of Node heap for TypeScript and Vite during image builds.
# This setting belongs only to the build stage; the runtime serves static files.
ARG FRONTEND_BUILD_HEAP_MB=4096
ENV NODE_OPTIONS="--max-old-space-size=${FRONTEND_BUILD_HEAP_MB}"

RUN corepack enable && corepack prepare pnpm@12.5.1 --activate
WORKDIR /workspace/frontend

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml frontend/.npmrc ./
RUN pnpm install --frozen-lockfile

COPY contracts /workspace/contracts
COPY frontend .
RUN pnpm api:generate && pnpm build

FROM nginx:1.28.0-alpine@sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284 AS runtime

RUN apk upgrade --no-cache \
    && rm /etc/nginx/conf.d/default.conf \
    && mkdir -p /tmp/nginx/client_temp /tmp/nginx/proxy_temp /tmp/nginx/fastcgi_temp \
    && chown -R nginx:nginx /tmp/nginx /usr/share/nginx/html

COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY --from=build --chown=nginx:nginx /workspace/frontend/dist /usr/share/nginx/html

USER nginx
EXPOSE 8080
