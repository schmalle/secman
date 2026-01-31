# Stage 1: Build
FROM gradle:8.12-jdk21 AS build
WORKDIR /app

COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/shared/ src/shared/
COPY src/backendng/ src/backendng/
COPY src/cli/ src/cli/

RUN gradle :cli:shadowJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S secman && adduser -S secman -G secman

COPY --from=build /app/src/cli/build/libs/cli-*-all.jar secman-cli.jar

RUN chown secman:secman secman-cli.jar
USER secman

ENTRYPOINT ["java", "-jar", "secman-cli.jar"]
