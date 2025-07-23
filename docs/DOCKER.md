# Docker Setup Guide for Secman

This document provides comprehensive instructions for setting up and running Secman using Docker containers.

## Architecture Overview

Secman uses a microservices architecture with the following components:

- **Frontend**: Astro/React application (port 4321)
- **Backend**: Micronaut/Kotlin application (port 8080)
- **Database**: MariaDB 11.4 (port 3306)

## Quick Start

### Prerequisites

- Docker 20.10 or higher
- Docker Compose 2.0 or higher
- Git

### 1. Clone and Setup

```bash
git clone https://github.com/schmalle/secman.git
cd secman

# Create environment configuration
cp .env.example .env
```

### 2. Configure Environment

Edit `.env` file with your settings:

```bash
# Database configuration
MYSQL_ROOT_PASSWORD=your_secure_root_password
MYSQL_PASSWORD=your_secure_database_password

# Backend configuration
JWT_SECRET=your_256_bit_secret_key_for_jwt_tokens
```

**⚠️ Important**: Change default passwords for production use!

### 3. Start Development Environment

```bash
# Start all services
./docker/scripts/dev.sh up

# Or start in detached mode
./docker/scripts/dev.sh up -d
```

### 4. Access Applications

- **Frontend**: http://localhost:4321
- **Backend API**: http://localhost:8080
- **Database**: localhost:3306

## Development Workflow

### Starting and Stopping Services

```bash
# Start services
./docker/scripts/dev.sh up

# Start in detached mode
./docker/scripts/dev.sh up -d

# Stop services
./docker/scripts/dev.sh down

# Restart services
./docker/scripts/dev.sh restart

# View logs
./docker/scripts/dev.sh logs

# View specific service logs
./docker/scripts/dev.sh logs backend
./docker/scripts/dev.sh logs frontend
./docker/scripts/dev.sh logs database
```

### Building Images

```bash
# Build all images locally
./docker/scripts/build-local.sh

# Build for multiple architectures (requires Docker Buildx)
./docker/scripts/build-multiarch.sh latest
```

### Development Features

- **Hot Reload**: Code changes are automatically reflected
- **Volume Mounts**: Source code is mounted for live editing
- **Debug Support**: Backend includes debug port 5005
- **Health Checks**: All services include health monitoring

## Production Deployment

### 1. Configure Production Environment

```bash
cp .env.example .env
# Edit .env with production-specific values
```

Ensure you set secure values for:
- `MYSQL_ROOT_PASSWORD`
- `MYSQL_PASSWORD`
- `JWT_SECRET`

### 2. Deploy to Production

```bash
# Deploy all services
./docker/scripts/deploy.sh deploy

# Check deployment status
./docker/scripts/deploy.sh status

# View production logs
./docker/scripts/deploy.sh logs

# Update deployment
./docker/scripts/deploy.sh update
```

### 3. Production Considerations

- Use a reverse proxy (nginx, Traefik) for SSL termination
- Configure proper firewall rules
- Set up log rotation and monitoring
- Regular database backups

## Container Configuration

### Backend Container

**Development** (`docker/backend/Dockerfile.dev`):
- Based on `gradle:8.14.3-jdk17`
- Includes development tools (vim, git)
- Gradle continuous build with hot reload
- Debug port exposed on 5005

**Production** (`docker/backend/Dockerfile`):
- Multi-stage build for optimized image size
- Uses `openjdk:17-jre-slim` for runtime
- Non-root user for security
- Health checks included

### Frontend Container

**Development** (`docker/frontend/Dockerfile.dev`):
- Based on `node:20-alpine`
- Development server with hot reload
- All dev dependencies included

**Production** (`docker/frontend/Dockerfile`):
- Multi-stage build with nginx
- Optimized static asset serving
- Proxy configuration for API calls
- Gzip compression enabled

### Database Container

**Configuration** (`docker/database/Dockerfile`):
- Based on `mariadb:11.4`
- Custom configuration in `my.cnf`
- Initialization scripts for database setup
- Optimized for performance

## Multi-Architecture Support

Secman supports both AMD64 and ARM64 architectures:

```bash
# Build for multiple architectures
./docker/scripts/build-multiarch.sh latest

# Supported platforms
- linux/amd64 (Intel/AMD x64)
- linux/arm64 (Apple Silicon, ARM servers)
```

### Docker Buildx Setup

```bash
# Create buildx builder
docker buildx create --name secman-builder --driver docker-container --bootstrap

# Use the builder
docker buildx use secman-builder

# Build and push
docker buildx build --platform linux/amd64,linux/arm64 -t secman/app:latest --push .
```

## Database Management

### Backup and Restore

```bash
# Create backup
./docker/scripts/deploy.sh backup

# Manual backup
docker-compose exec database mysqldump -u secman -pCHANGEME secman > backup.sql

# Restore from backup
docker-compose exec -T database mysql -u secman -pCHANGEME secman < backup.sql
```

### Database Access

```bash
# Access database shell
docker-compose exec database mysql -u secman -pCHANGEME secman

# Or as root
docker-compose exec database mysql -u root -p
```

## Troubleshooting

### Common Issues

1. **Port conflicts**: Ensure ports 3306, 4321, and 8080 are available
2. **Permission issues**: Check file permissions for scripts
3. **Memory issues**: Increase Docker memory allocation for large builds

### Logs and Debugging

```bash
# View all logs
./docker/scripts/dev.sh logs

# View specific service
./docker/scripts/dev.sh logs backend

# Follow logs in real-time
./docker/scripts/dev.sh logs -f

# Debug backend
# Connect to localhost:5005 with your IDE debugger
```

### Health Checks

```bash
# Check service health
curl http://localhost:8080/health  # Backend
curl http://localhost:4321/health  # Frontend

# Container health status
docker-compose ps
```

## Environment Variables

### Backend Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_USERNAME` | Database username | `secman` |
| `DB_PASSWORD` | Database password | `CHANGEME` |
| `DB_URL` | Database connection URL | `jdbc:mariadb://database:3306/secman` |
| `JWT_SECRET` | JWT signing secret | Default (change for production) |
| `MICRONAUT_ENVIRONMENTS` | Micronaut environment | `dev` |

### Database Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `MYSQL_ROOT_PASSWORD` | Root password | `rootpassword` |
| `MYSQL_DATABASE` | Database name | `secman` |
| `MYSQL_USER` | Application user | `secman` |
| `MYSQL_PASSWORD` | Application password | `CHANGEME` |

## Performance Optimization

### Development

- Use volume mounts for faster file access
- Allocate sufficient memory to Docker
- Use SSD storage for better I/O performance

### Production

- Use specific image tags instead of `latest`
- Enable resource limits in docker-compose
- Use external volumes for persistent data
- Configure proper logging levels

## Security Considerations

1. **Change default passwords** before production deployment
2. **Use environment variables** for sensitive data
3. **Regular updates** of base images and dependencies
4. **Network isolation** using Docker networks
5. **Non-root users** in containers
6. **Regular security scans** of images

## Maintenance

### Updates

```bash
# Update to latest images
docker-compose pull

# Rebuild with latest base images
./docker/scripts/build-local.sh

# Update dependencies
cd src/backendng && gradle dependencies
cd src/frontend && npm update
```

### Cleanup

```bash
# Remove development containers and volumes
./docker/scripts/dev.sh clean

# Remove unused Docker resources
docker system prune -a
```