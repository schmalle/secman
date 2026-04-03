# Secman Frontend Container
# Multi-stage build: Node build → Nginx with SSL
FROM node:22-alpine AS builder

WORKDIR /build

# Copy package files first (cache dependencies)
COPY src/frontend/package.json src/frontend/package-lock.json* ./

# Install dependencies
RUN npm ci --production=false

# Copy frontend source
COPY src/frontend/ ./

# Build the Astro site
RUN npm run build

# --- Runtime stage: Nginx ---
FROM nginx:1.27-alpine

LABEL maintainer="secman"
LABEL description="Secman frontend with Nginx reverse proxy (HTTPS)"

# Install openssl for self-signed cert generation
RUN apk add --no-cache openssl curl

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

# Copy built frontend assets from builder
# Astro with Node adapter builds to dist/client (static) and dist/server (SSR)
# For Nginx we serve the client assets statically and proxy SSR to backend
COPY --from=builder /build/dist/client /usr/share/nginx/html

EXPOSE 8443

HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=10s \
  CMD curl -kf https://localhost:8443/ || exit 1
