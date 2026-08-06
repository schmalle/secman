# Secman Frontend Container
# Multi-stage build: Node build → Nginx + Node SSR server, both supervised
# by docker/frontend/entrypoint.sh.
#
# Astro is configured with `output: "server"` (astro.config.mjs) — it is NOT
# a static site. The build produces:
#   dist/client/  static assets (served directly by nginx)
#   dist/server/  the SSR entry point (must be run by Node — nginx alone
#                 cannot render these pages). This runtime stage runs both,
#                 same pattern as docker/aws/Dockerfile's entrypoint.
FROM node:22-alpine AS builder

WORKDIR /build

# Copy package files first (cache dependencies)
COPY src/frontend/package.json src/frontend/package-lock.json* ./

# Install dependencies
RUN npm ci --production=false

# Copy frontend source
COPY src/frontend/ ./

# Build the Astro site (dist/client + dist/server), then drop devDependencies —
# the standalone Node adapter externalizes some packages, so production
# node_modules must ship alongside dist/server for those to resolve at runtime.
RUN npm run build \
 && npm prune --omit=dev

# --- Runtime stage: Nginx + Node SSR, both supervised ---
# node:22-alpine (not nginx:1.27-alpine) so the SSR server runs the exact
# same Node version it was built/pruned against; nginx is installed via apk.
FROM node:22-alpine

LABEL maintainer="secman"
LABEL description="Secman frontend: Nginx (TLS + static assets) + Astro SSR Node server"

# nginx + openssl (self-signed cert) + curl (HEALTHCHECK) + bash (entrypoint
# uses `wait -n`, which Alpine's default busybox ash does not support)
RUN apk add --no-cache nginx openssl curl bash

# Create SSL directory
RUN mkdir -p /etc/nginx/ssl

# Generate self-signed certificate for development
RUN openssl req -x509 -nodes -days 3650 \
  -newkey rsa:2048 \
  -keyout /etc/nginx/ssl/selfsigned.key \
  -out /etc/nginx/ssl/selfsigned.crt \
  -subj "/C=DE/ST=Docker/L=Docker/O=Secman/CN=localhost"

# Copy Nginx configuration
COPY docker/frontend/nginx.conf /etc/nginx/nginx.conf

# Astro build output (server + client) and production node_modules — same
# layout as docker/aws/Dockerfile so entry.mjs resolves its dependencies via
# Node's directory walk-up to /app/node_modules.
WORKDIR /app
COPY --from=builder /build/dist /app/frontend
COPY --from=builder /build/node_modules /app/node_modules
COPY --from=builder /build/package.json /app/package.json

COPY docker/frontend/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8443

HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=15s \
  CMD curl -kf https://localhost:8443/ || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
