# Secman Docker Setup

This directory contains Docker configuration for the complete Secman application stack, including the Micronaut/Kotlin backend, Astro/React frontend, and MariaDB database.

## Architecture

- **Backend**: Micronaut 4.4.3 with Kotlin 2.0.21, built with Gradle
- **Frontend**: Astro with React components  
- **Database**: MariaDB 10.11
- **Build System**: Gradle (not sbt)
- **Container Runtime**: Docker with multi-architecture support

## Directory Structure

```
docker/
├── backend/           # Micronaut/Kotlin backend containerization
│   └── Dockerfile
├── frontend/          # Astro/React frontend containerization
│   └── Dockerfile
├── database/          # MariaDB setup with initialization
│   ├── Dockerfile
│   ├── init/          # Database initialization scripts
│   └── config/        # MariaDB configuration
└── compose/           # Docker Compose configurations
    ├── docker-compose.yml        # Production setup
    ├── docker-compose.dev.yml    # Development setup
    └── .env.example              # Environment variables template
```

## Quick Start

### Prerequisites

- Docker 20.10+ with Buildx support
- Docker Compose 2.0+
- 4GB+ available RAM
- 10GB+ available disk space

### Production Setup

1. **Clone and navigate to the repository:**
   ```bash
   git clone https://github.com/schmalle/secman.git
   cd secman
   ```

2. **Create environment configuration:**
   ```bash
   cp docker/compose/.env.example docker/compose/.env
   # Edit .env file with your configurations
   ```

3. **Build and start the application:**
   ```bash
   # Build images locally
   ./docker/build-local.sh
   
   # Start the application
   cd docker/compose
   docker compose up -d
   ```

4. **Access the application:**
   - Frontend: http://localhost:4321
   - Backend API: http://localhost:8080
   - Database: localhost:3306

### Development Setup

For development with hot reload:

```bash
# Start development environment
cd docker/compose
docker compose -f docker-compose.dev.yml up -d

# View logs
docker compose -f docker-compose.dev.yml logs -f
```

Development ports:
- Frontend: http://localhost:4322
- Backend API: http://localhost:8081  
- Database: localhost:3307

## Multi-Architecture Support

Build for multiple architectures (ARM64 and x64):

```bash
# Build and push to registry
export REGISTRY=your-registry
export TAG=v1.0.0
./docker/build-multi-arch.sh
```

## Environment Variables

Key environment variables (see `.env.example` for complete list):

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_PASSWORD` | Database password | `CHANGEME` |
| `JWT_SECRET` | JWT signing secret | *generated* |
| `BACKEND_PORT` | Backend port | `8080` |
| `FRONTEND_PORT` | Frontend port | `4321` |
| `DB_PORT` | Database port | `3306` |

## Database Management

### Initial Setup

The database is automatically initialized with:
- `secman` database creation
- `secman` user with proper permissions
- Optimized MariaDB configuration

### Data Persistence

Production data is persisted in Docker volumes:
- `secman_db_data` - Production database data
- `secman_db_dev_data` - Development database data

### Database Access

Connect to the database:
```bash
# Production
docker exec -it secman-db mysql -u secman -p secman

# Development  
docker exec -it secman-db-dev mysql -u secman -p secman
```

## Application Configuration

### Backend Configuration

The Micronaut backend uses layered configuration:
- `application.yml` - Base configuration
- `application-docker.yml` - Docker-specific overrides
- Environment variables - Runtime configuration

### Frontend Configuration

The Astro frontend is built for:
- Production: Optimized static build with SSR
- Development: Hot reload with file watching

## Health Checks

All services include health checks:
- **Database**: MySQL ping
- **Backend**: HTTP health endpoint at `/health`
- **Frontend**: HTTP availability check

Monitor health:
```bash
docker compose ps
docker compose logs -f
```

## Troubleshooting

### Common Issues

1. **Port conflicts**:
   ```bash
   # Check what's using the port
   lsof -i :8080
   # Or use different ports in .env file
   ```

2. **Database connection issues**:
   ```bash
   # Check database logs
   docker compose logs database
   
   # Verify database is ready
   docker compose exec database mysqladmin ping -u secman -pCHANGEME
   ```

3. **Build failures**:
   ```bash
   # Clean build
   docker system prune -f
   ./docker/build-local.sh
   ```

### Logs

View application logs:
```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f database
```

## Security Considerations

- Change default passwords in production
- Use proper JWT secrets
- Configure proper firewall rules
- Keep images updated
- Use non-root users in containers
- Enable TLS/SSL for production deployments

## Performance Tuning

### Backend (JVM)

The backend uses optimized JVM settings:
- Container-aware memory management
- ZGC garbage collector
- 70% RAM utilization limit

### Database

MariaDB is tuned for:
- UTF8MB4 character set
- Optimized buffer pools
- Connection pooling
- Performance schema disabled

### Frontend

The frontend build is optimized for:
- Tree shaking
- Asset compression
- Static asset caching
- CDN compatibility

## CI/CD Integration

Example GitHub Actions workflow:

```yaml
name: Build and Deploy
on:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Build multi-arch images
        run: ./docker/build-multi-arch.sh
```

## Migration from Legacy Stack

This Docker setup replaces the previous Play Framework/Java/sbt stack with:
- ✅ Micronaut/Kotlin instead of Play/Java
- ✅ Gradle instead of sbt
- ✅ Container-first development
- ✅ Multi-architecture support
- ✅ Production-ready configuration

## Support

For issues or questions:
- Check logs with `docker-compose logs`
- Review health checks with `docker-compose ps`
- See main project README for contact information