# Docker Directory Structure

This directory contains all Docker-related configurations for the Secman application.

## Directory Structure

```
docker/
├── backend/          # Micronaut/Kotlin backend containers
│   ├── Dockerfile    # Production build (multi-stage)
│   └── Dockerfile.dev # Development build (hot reload)
├── frontend/         # Astro/React frontend containers
│   ├── Dockerfile    # Production build (nginx)
│   ├── Dockerfile.dev # Development build (hot reload)
│   └── nginx.conf    # Nginx configuration for production
├── database/         # MariaDB database containers
│   ├── Dockerfile    # Database container
│   ├── my.cnf        # MariaDB configuration
│   └── init/         # Database initialization scripts
│       ├── 01-init-database.sh
│       └── 02-sample-data.sql
└── scripts/          # Build and deployment automation
    ├── build-local.sh       # Build images locally
    ├── build-multiarch.sh   # Multi-platform builds
    ├── dev.sh              # Development environment
    ├── deploy.sh           # Production deployment
    └── test-setup.sh       # Configuration testing
```

## Quick Start

### Development Environment
```bash
# Start all services
./scripts/dev.sh up

# View logs
./scripts/dev.sh logs

# Stop services
./scripts/dev.sh down
```

### Production Deployment
```bash
# Configure environment
cp ../.env.example ../.env
# Edit .env with production values

# Deploy
./scripts/deploy.sh deploy
```

### Building Images
```bash
# Local development images
./scripts/build-local.sh

# Multi-architecture images
./scripts/build-multiarch.sh latest
```

## Container Details

### Backend Container
- **Base**: gradle:8.14.3-jdk17 (dev) / openjdk:17-jre-slim (prod)
- **Port**: 8080 (app), 5005 (debug, dev only)
- **Features**: Hot reload, health checks, non-root user

### Frontend Container  
- **Base**: node:20-alpine (dev) / nginx:alpine (prod)
- **Port**: 4321
- **Features**: Hot module replacement, API proxy, gzip compression

### Database Container
- **Base**: mariadb:11.4
- **Port**: 3306
- **Features**: UTF8MB4, performance tuning, auto-initialization

## Environment Configuration

Copy `.env.example` to `.env` and configure:

```bash
# Required for production
MYSQL_ROOT_PASSWORD=your_secure_password
MYSQL_PASSWORD=your_secure_password
JWT_SECRET=your_256_bit_secret_key
```

## Documentation

See the main docs directory for comprehensive guides:
- `../docs/DOCKER.md` - Complete Docker setup guide
- `../docs/DEVELOPMENT.md` - Development environment setup
- `../docs/TESTING.md` - Testing with containers

## Support

For issues with Docker setup:
1. Check `../docs/DOCKER.md` troubleshooting section
2. Run `./scripts/test-setup.sh` to validate configuration
3. Ensure Docker and Docker Compose are properly installed