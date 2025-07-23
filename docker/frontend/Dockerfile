# Multi-stage build for Astro/React frontend
# Stage 1: Build stage
FROM node:18-alpine AS builder

WORKDIR /app

# Copy package files first for better caching
COPY src/frontend/package*.json ./

# Install dependencies
RUN npm ci --only=production

# Copy source code
COPY src/frontend/ ./

# Build the application
RUN npm run build

# Stage 2: Production stage
FROM node:18-alpine AS production

WORKDIR /app

# Install only production dependencies
COPY src/frontend/package*.json ./
RUN npm ci --only=production && npm cache clean --force

# Copy built application from builder stage
COPY --from=builder /app/dist ./dist

# Create non-root user
RUN addgroup -g 1001 -S nodejs && \
    adduser -S astro -u 1001

# Set ownership
RUN chown -R astro:nodejs /app

# Switch to non-root user
USER astro

# Expose port
EXPOSE 4321

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:4321/health.json || exit 1

# Run the application
CMD ["node", "./dist/server/entry.mjs"]

# Stage 3: Development stage
FROM node:18-alpine AS development

WORKDIR /app

# Install dependencies including dev dependencies
COPY src/frontend/package*.json ./
RUN npm install

# Copy source code
COPY src/frontend/ ./

# Expose port for dev server
EXPOSE 4321

# Run in development mode with hot reload
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]