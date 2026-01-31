# Stage 1: Build
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# Stage 2: Runtime
FROM node:22-alpine
WORKDIR /app

RUN addgroup -S secman && adduser -S secman -G secman

# Astro standalone output includes everything needed
COPY --from=build /app/dist/ ./dist/

# Astro standalone needs the host entry point
ENV HOST=0.0.0.0
ENV PORT=4321

RUN chown -R secman:secman /app
USER secman

EXPOSE 4321

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:4321/ || exit 1

ENTRYPOINT ["node", "dist/server/entry.mjs"]
