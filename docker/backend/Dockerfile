# Secman Backend Container
# Expects the fat JAR to be pre-built on the host (build-all.sh handles this).
# This avoids needing 4GB+ RAM inside Docker for the Kotlin/Gradle compilation.
FROM eclipse-temurin:21-jre

LABEL maintainer="secman"
LABEL description="Secman backend (Micronaut/Kotlin)"

WORKDIR /app

# Copy the pre-built fat JAR from the host
COPY docker/backend/app.jar app.jar

# Backend port
EXPOSE 8080

# Environment defaults for Docker networking
ENV DB_CONNECT=jdbc:mariadb://secman-db:3306/secman
ENV DB_USERNAME=secman
ENV DB_PASSWORD=secman-docker-pw
ENV JWT_SECRET=docker-secman-jwt-secret-must-be-at-least-256-bits-long-for-hs256
ENV SECMAN_AUTH_COOKIE_SECURE=false
ENV FRONTEND_URL=https://localhost:8443
ENV SECMAN_BACKEND_URL=https://localhost:8443

# Disable Flyway on fresh Docker databases — Hibernate auto-DDL creates the full schema.
# Flyway migrations assume pre-existing tables (created by Hibernate on existing deployments).
ENV FLYWAY_DATASOURCES_DEFAULT_ENABLED=false

# JVM tuning for container
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport"

HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=60s \
  CMD curl -sf http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
