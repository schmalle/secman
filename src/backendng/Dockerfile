# Stage 1: Build
FROM gradle:8.12-jdk21 AS build
WORKDIR /app

# Copy Gradle configuration first for dependency caching
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/shared/ src/shared/
COPY src/backendng/ src/backendng/

# Build the shadow JAR (skip tests for Docker build)
RUN gradle :backendng:shadowJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S secman && adduser -S secman -G secman

COPY --from=build /app/src/backendng/build/libs/backendng-*-all.jar app.jar

RUN chown secman:secman app.jar
USER secman

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
