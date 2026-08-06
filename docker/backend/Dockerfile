# Secman Backend Container
# Expects the fat JAR to be pre-built on the host (build-all.sh handles this).
# This avoids needing 4GB+ RAM inside Docker for the Kotlin/Gradle compilation.
FROM eclipse-temurin:25-jre

LABEL maintainer="secman"
LABEL description="Secman backend (Micronaut/Kotlin)"

WORKDIR /app

# Copy the pre-built fat JAR from the host
COPY docker/backend/app.jar app.jar

# Backend port
EXPOSE 8080

# Environment defaults for Docker networking (non-secret only — see below).
ENV DB_CONNECT=jdbc:mariadb://secman-db:3306/secman
ENV DB_USERNAME=secman
ENV SECMAN_AUTH_COOKIE_SECURE=false
ENV FRONTEND_URL=https://localhost:8443
ENV SECMAN_BACKEND_URL=https://localhost:8443

# Security-sensitive vars (DB_PASSWORD, JWT_SECRET, SECMAN_ENCRYPTION_PASSWORD,
# SECMAN_ENCRYPTION_SALT, ...) intentionally have NO baked default — a dev
# fallback here would eventually leak into a real deployment. Inject them at
# `docker run -e` / compose `env_file` time. See docker/README.md.

# Disable Flyway on fresh Docker databases — Hibernate auto-DDL creates the full schema.
# Flyway migrations assume pre-existing tables (created by Hibernate on existing deployments).
ENV FLYWAY_DATASOURCES_DEFAULT_ENABLED=false

# JVM tuning for container.
#
# Percentage, not -Xmx: the explicit -Xmx512m this replaces overrode the cgroup limit, so
# raising the container's memory did nothing and the backend OOM'd on any full-table read
# (2026-07-30 import incident). 75% is safe here because — unlike docker/aws/Dockerfile —
# the JVM is the only process in this image.
#
# On OOM: dump, then exit, so the HEALTHCHECK/orchestrator restarts a clean JVM instead of
# leaving a poisoned one serving traffic. /app is writable; mount it to keep dumps.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/secman-backend-oom.hprof -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=60s \
  CMD curl -sf http://localhost:8080/health || exit 1

# `exec` so the JVM replaces the shell as PID 1: signals reach it directly and its exit
# code (notably from -XX:+ExitOnOutOfMemoryError) propagates to the orchestrator.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
