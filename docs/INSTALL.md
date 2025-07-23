# Secman Installation Guide

## Overview

This guide covers installation options for Secman, a security requirements and risk assessment management tool built with Micronaut/Kotlin backend and Astro/React frontend.

## Prerequisites

- Docker 20.10+ with Docker Compose (recommended)
- **OR** for manual setup:
  - Java 17+
  - Node.js 18+
  - MariaDB 10.11+
  - Gradle 8.5+

## Option 1: Docker Installation (Recommended)

### Quick Start
```bash
# Clone repository
git clone https://github.com/schmalle/secman.git
cd secman

# Copy environment template
cp docker/compose/.env.example docker/compose/.env

# Build and start all services
./docker/build-local.sh
cd docker/compose
docker compose up -d
```

### Access Points
- **Frontend**: http://localhost:4321
- **Backend API**: http://localhost:8080
- **Database**: localhost:3306

### Default Users
After initialization, log in with:
- **Admin**: adminuser / password
- **User**: normaluser / password

## Option 2: Manual Installation

### Database Setup
```bash
# Create database
cd scripts/install
./install.sh
```

### Backend Setup (Micronaut/Kotlin)
```bash
cd src/backendng
gradle run
```

### Frontend Setup (Astro/React)
```bash
cd src/frontend
npm install
npm run dev
```

### Create Default Users
```sql
INSERT INTO secman.users (id, username, email, password_hash, created_at, updated_at) 
VALUES 
  (1, 'adminuser', 'admin@example.com', '$2a$12$qjfBO6Mr65SiujkTbgg3YudRvsDRE7A.RBvSyLJe89PKbkWhC6sgK', NOW(), NOW()),
  (2, 'normaluser', 'user@example.com', '$2a$12$qjfBO6Mr65SiujkTbgg3YudRvsDRE7A.RBvSyLJe89PKbkWhC6sgK', NOW(), NOW());
```

## Roles and Permissions

- **adminuser**: Full administrative rights, including user management and configuration
- **normaluser**: Basic access for submitting and tracking requirements/risks

Additional roles are planned for future releases.

## Database Configuration

- **Database**: secman
- **User**: secman / CHANGEME (configurable via environment variables)
- **Host**: localhost (manual) or database container (Docker)
- **Port**: 3306 (production) / 3307 (development)

## Environment Configuration

Key environment variables (see `docker/compose/.env.example` for complete list):

```bash
# Database
DB_PASSWORD=CHANGEME
DB_USERNAME=secman

# Security
JWT_SECRET=your_256_bit_secret

# Optional integrations
OPENROUTER_API_KEY=your_api_key
GITHUB_CLIENT_ID=your_client_id
GITHUB_CLIENT_SECRET=your_client_secret
```

## Verification

### Health Checks
```bash
# Backend health
curl http://localhost:8080/health

# Frontend health
curl http://localhost:4321/health.json

# Database connection
docker compose exec database mysql -u secman -pCHANGEME -e "SELECT 1"
```

### Test Login
1. Navigate to http://localhost:4321
2. Log in with adminuser / password
3. Verify dashboard loads correctly

## Troubleshooting

### Port Conflicts
If ports are in use, modify `.env` file:
```bash
BACKEND_PORT=8081
FRONTEND_PORT=4322
DB_PORT=3307
```

### Database Issues
```bash
# Check database logs
docker compose logs database

# Reset database (destroys data)
docker compose down -v
docker compose up -d
```

### Build Issues
```bash
# Clean Docker build
docker system prune -f
./docker/build-local.sh
```

## Next Steps

1. Configure external services (email, translation)
2. Set up user accounts and roles
3. Import initial requirements data
4. Configure system settings

For detailed usage, see the main [README](../README.md) and [Docker documentation](../docker/README.md).
