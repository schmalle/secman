# Multi-stage build for Micronaut Kotlin application
FROM gradle:8.14.3-jdk17 AS build

# Set working directory
WORKDIR /app

# Copy Gradle files first for better layer caching
COPY src/backendng/gradle/ ./gradle/
COPY src/backendng/gradlew ./
COPY src/backendng/gradle.properties ./
COPY src/backendng/settings.gradle.kts ./
COPY src/backendng/build.gradle.kts ./

# Download dependencies
RUN gradle dependencies --no-daemon

# Copy source code
COPY src/backendng/src/ ./src/

# Build the application
RUN gradle build --no-daemon -x test

# Production stage
FROM openjdk:17-jre-slim

# Install necessary packages
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create application user
RUN groupadd -r secman && useradd -r -g secman secman

# Set working directory
WORKDIR /app

# Copy built application
COPY --from=build /app/build/libs/*-all.jar app.jar

# Change ownership
RUN chown -R secman:secman /app

# Switch to non-root user
USER secman

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]