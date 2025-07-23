# Multi-stage build for Astro React application
FROM node:20-alpine AS build

# Set working directory
WORKDIR /app

# Copy package files first for better layer caching
COPY src/frontend/package*.json ./

# Install dependencies
RUN npm ci --only=production && npm cache clean --force

# Copy source code
COPY src/frontend/ ./

# Build the application
RUN npm run build

# Production stage with nginx
FROM nginx:alpine

# Copy built files
COPY --from=build /app/dist /usr/share/nginx/html

# Copy nginx configuration
COPY docker/frontend/nginx.conf /etc/nginx/nginx.conf

# Expose port
EXPOSE 4321

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:4321/health || exit 1

# Start nginx
CMD ["nginx", "-g", "daemon off;"]