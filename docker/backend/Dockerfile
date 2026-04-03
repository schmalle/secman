# Secman Backend Container
# Multi-stage build: Gradle build → minimal JRE runtime
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Copy Gradle wrapper and config first (cache dependencies)
COPY gradlew gradlew
COPY gradle/ gradle/
COPY settings.gradle.kts settings.gradle.kts
COPY build.gradle.kts build.gradle.kts
COPY gradle.properties gradle.properties

# Copy subproject build files
COPY src/backendng/build.gradle.kts src/backendng/build.gradle.kts
COPY src/shared/build.gradle.kts src/shared/build.gradle.kts
COPY src/cli/build.gradle.kts src/cli/build.gradle.kts

# Download dependencies (cached layer)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src/shared/ src/shared/
COPY src/backendng/ src/backendng/

# Build the fat JAR (skip tests per project conventions)
RUN ./gradlew :backendng:shadowJar -x test --no-daemon

# --- Runtime stage ---
FROM eclipse-temurin:21-jre

LABEL maintainer="secman"
LABEL description="Secman backend (Micronaut/Kotlin)"

WORKDIR /app

# Copy the built JAR
COPY --from=builder /build/src/backendng/build/libs/*-all.jar app.jar

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

# JVM tuning for container
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport"

HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=60s \
  CMD curl -sf http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
