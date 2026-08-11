# Secman all-in-one container (AWS single-container deployment)
#
# Runs BOTH the Astro SSR web server and the Micronaut backend in one image,
# fronted by an internal nginx on port 80:
#
#   ALB (TLS) ──> :80 nginx ──┬── /api/, /oauth/, /mcp, /health ──> 127.0.0.1:8080  (Micronaut)
#                             ├── /_astro/, static assets ───────> served from disk
#                             └── everything else (SSR pages) ───> 127.0.0.1:4321  (Astro/Node)
#
# Build (from the repository root — the Gradle stage needs ~4 GB RAM):
#   docker build -f docker/aws/Dockerfile -t secman-aio .
#
# All configuration is injected at runtime via environment variables — no
# secrets are baked into the image. See docs/DOCKER_AWS.md for the full
# reference and AWS (ECR/ECS/Secrets Manager) instructions.

# --- Stage 1: backend fat JAR (Gradle/Kotlin) ---
FROM eclipse-temurin:25-jdk AS backend-builder

WORKDIR /build

# Whole repo context: the Gradle settings include :backendng and :cli modules.
COPY . .

RUN ./gradlew :backendng:shadowJar -x test --no-daemon

# --- Stage 2: frontend build (Astro SSR, Node standalone adapter) ---
FROM node:22-alpine AS frontend-builder

WORKDIR /build

COPY src/frontend/package.json src/frontend/package-lock.json* ./
RUN npm ci

# Footer build stamp. The build context is src/frontend/ only — no .git, and no
# git binary in the image — so the commit stamps must come from the host
# (docker/build-all.sh passes them). Left unset, the footer still shows a
# correct build time, just without the commit. Never a hardcoded date.
ARG SECMAN_BUILD_TIME=""
ARG SECMAN_GIT_COMMIT=""
ARG SECMAN_GIT_COMMIT_TIME=""
ENV SECMAN_BUILD_TIME=${SECMAN_BUILD_TIME} \
    SECMAN_GIT_COMMIT=${SECMAN_GIT_COMMIT} \
    SECMAN_GIT_COMMIT_TIME=${SECMAN_GIT_COMMIT_TIME}

COPY src/frontend/ ./
RUN npm run build \
 && npm prune --omit=dev

# --- Stage 3: runtime (JRE + Node + nginx) ---
FROM eclipse-temurin:25-jre

LABEL maintainer="secman"
LABEL description="Secman all-in-one: Astro SSR web server + Micronaut backend behind nginx"

# Node 22 (NodeSource) + nginx + curl (healthcheck)
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg nginx \
 && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Backend fat JAR
COPY --from=backend-builder /build/src/backendng/build/libs/*-all.jar /app/app.jar

# Astro build output (server + client) and production node_modules.
# The standalone adapter bundles most dependencies, but production
# node_modules are kept so externalized packages resolve at runtime.
COPY --from=frontend-builder /build/dist /app/frontend
COPY --from=frontend-builder /build/node_modules /app/node_modules
COPY --from=frontend-builder /build/package.json /app/package.json

# nginx config + process supervisor
COPY docker/aws/nginx.conf /etc/nginx/nginx.conf
COPY docker/aws/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# --- Runtime defaults (override at runtime; never bake real secrets here) ---
# Database (point at RDS in AWS)
ENV DB_CONNECT=jdbc:mariadb://localhost:3306/secman
ENV DB_USERNAME=secman
# Security-sensitive vars (DB_PASSWORD, JWT_SECRET, SECMAN_ENCRYPTION_PASSWORD,
# SECMAN_ENCRYPTION_SALT, SMTP_*, OPENROUTER_API_KEY, FALCON_*) intentionally
# have NO baked default where the app ships a dev fallback that must not reach
# production — inject them via ECS "secrets" / docker run -e.
#
# URLs used for CORS, OAuth callbacks and email links — set to the public ALB/host URL.
ENV FRONTEND_URL=http://localhost
ENV SECMAN_BACKEND_URL=http://localhost
# TLS terminates at the ALB; the auth cookie must still be marked Secure.
ENV SECMAN_AUTH_COOKIE_SECURE=true
# Flyway assumes a schema that already exists (Hibernate auto-DDL creates it on
# first boot). Keep disabled for a FRESH database; set to true for an existing
# production schema that is migrated by Flyway.
ENV FLYWAY_DATASOURCES_DEFAULT_ENABLED=false
# JVM sizing — tune together with the ECS task size (docs/DOCKER_AWS.md).
#
# Percentage, not -Xmx: an explicit -Xmx silently ignores the cgroup limit, so it
# mis-sizes the heap every time the task memory changes and makes "give the container
# more RAM" a no-op. That is what turned the 2026-07-30 import into an OutOfMemoryError.
#
# 45% deliberately, NOT the usual 75%: entrypoint.sh runs THREE processes in this one
# cgroup. At the documented 2048 MiB task size that budgets ~920 MiB heap and leaves room
# for JVM non-heap (metaspace, code cache, thread stacks, direct buffers), the Astro SSR
# node process, nginx, and page cache. Raise this only together with the task memory.
#
# On OOM: dump for post-mortem, then EXIT. A JVM that has already thrown
# OutOfMemoryError is in an undefined state — on 2026-07-30 it kept running with a
# broken Hikari connection and a dead event-loop thread, which is strictly worse than a
# restart. entrypoint.sh's `wait -n` sees the exit and stops the container, so ECS (or
# compose's `restart: unless-stopped`) replaces it with a clean one.
#
# /tmp is ephemeral: the dump survives long enough for `docker cp` locally, but on
# Fargate it dies with the task unless a volume is mounted. Override JAVA_OPTS to point
# HeapDumpPath at a mount if you need dumps to outlive the container.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=45.0 -XX:InitialRAMPercentage=12.5 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/secman-backend-oom.hprof -XX:+ExitOnOutOfMemoryError"

# nginx front door (TLS terminates at the ALB)
EXPOSE 80

# /health is proxied to the Micronaut health endpoint — the container is
# healthy only when nginx AND the backend are up.
HEALTHCHECK --interval=15s --timeout=5s --retries=12 --start-period=90s \
  CMD curl -sf http://localhost:80/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
