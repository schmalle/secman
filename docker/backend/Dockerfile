# Multi-stage build for Micronaut/Kotlin backend
# Stage 1: Build stage
FROM gradle:8.14-jdk17 AS builder

WORKDIR /app

# Copy gradle files first for better caching
COPY src/backendng/gradle/ gradle/
COPY src/backendng/gradle.properties .
COPY src/backendng/settings.gradle.kts .
COPY src/backendng/build.gradle.kts .

# Copy source code
COPY src/backendng/src/ src/

# Build the application
RUN gradle shadowJar --no-daemon

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Create non-root user
RUN addgroup -g 1001 -S micronaut && \
    adduser -S secman -u 1001 -G micronaut

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*-all.jar app.jar

# Set ownership
RUN chown -R secman:micronaut /app

# Switch to non-root user
USER secman

# JVM optimization for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+UnlockExperimentalVMOptions -XX:+UseZGC"

# Application configuration
ENV MICRONAUT_ENVIRONMENTS=docker

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Expose port
EXPOSE 8080

# Run the application
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]