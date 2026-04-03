# Secman Docker Deployment

Three standalone Docker containers (no docker-compose) for running the full Secman stack.

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   Host Machine                   │
│                                                  │
│  ┌──────────────┐   ┌──────────────┐            │
│  │  secman-db    │   │secman-backend│            │
│  │  MariaDB 11.4 │◄──│ Micronaut    │            │
│  │  :3307→3306   │   │ :8080        │            │
│  └──────────────┘   └──────▲───────┘            │
│                            │                     │
│                    ┌───────┴──────┐              │
│                    │secman-frontend│              │
│                    │ Nginx + SSL  │              │
│                    │ :8443        │              │
│                    └──────────────┘              │
│                                                  │
│          Docker Network: secman-net              │
└─────────────────────────────────────────────────┘
```

| Container | Image | Port | Description |
|-----------|-------|------|-------------|
| `secman-db` | `secman-db` | 3307 → 3306 | MariaDB 11.4, persistent volume |
| `secman-backend` | `secman-backend` | 8080 | Kotlin/Micronaut REST API |
| `secman-frontend` | `secman-frontend` | **8443** (HTTPS) | Nginx reverse proxy + static assets |

## Prerequisites

- Docker 20.10+ installed and running
- ~2GB free disk space for images
- Ports 8443, 8080, 3307 available

## Quick Start

```bash
# 1. Build all images
./docker/build-all.sh

# 2. Start everything
./docker/start-all.sh

# 3. Open https://localhost:8443
#    (Accept self-signed certificate warning)

# 4. Get the auto-generated admin password
docker logs secman-backend 2>&1 | grep "Password:"

# 5. Login with username: admin
```

## Scripts Reference

| Script | Description |
|--------|-------------|
| `build-all.sh` | Build all three Docker images |
| `start-all.sh` | Start all containers in order (DB → backend → frontend) |
| `start-database.sh` | Start only the database container |
| `start-backend.sh` | Start only the backend container |
| `start-frontend.sh` | Start only the frontend container |
| `stop-all.sh` | Stop all containers (preserves data) |
| `stop-all.sh --purge` | Stop all containers and delete data volume + network |
| `test-docker.sh` | Full integration test (build, start, login, verify, cleanup) |
| `test-docker.sh --keep` | Same as above but leave containers running after test |

## Individual Container Management

### Database

```bash
# Start
./docker/start-database.sh

# Connect to MariaDB
docker exec -it secman-db mariadb -usecman -psecman-docker-pw secman

# View logs
docker logs -f secman-db

# Reset (delete all data)
./docker/stop-all.sh --purge
```

### Backend

```bash
# Start (requires database)
./docker/start-backend.sh

# View logs (includes admin password on first run)
docker logs -f secman-backend

# Get admin password
docker logs secman-backend 2>&1 | grep "Password:"

# Check health
curl -s http://localhost:8080/health
```

### Frontend

```bash
# Start (requires backend)
./docker/start-frontend.sh

# Access
open https://localhost:8443

# View Nginx logs
docker logs -f secman-frontend
```

## Configuration

Environment variables can be set before running the start scripts:

### Database

| Variable | Default | Description |
|----------|---------|-------------|
| `SECMAN_DB_ROOT_PASSWORD` | `secman-root-pw` | MariaDB root password |
| `SECMAN_DB_PASSWORD` | `secman-docker-pw` | Application DB password |

### Backend

| Variable | Default | Description |
|----------|---------|-------------|
| `SECMAN_DB_PASSWORD` | `secman-docker-pw` | Must match database password |
| `SECMAN_JWT_SECRET` | (built-in default) | JWT signing secret (min 256 bits) |

### Example: Custom passwords

```bash
export SECMAN_DB_PASSWORD="my-strong-password"
export SECMAN_JWT_SECRET="my-custom-jwt-secret-that-is-at-least-256-bits-long-for-hs256-algo"
./docker/start-all.sh
```

## Integration Test

The test script validates the entire Docker stack end-to-end:

```bash
# Run full test (builds, starts, tests, cleans up)
./docker/test-docker.sh

# Run test and keep containers running
./docker/test-docker.sh --keep
```

**What it tests:**
1. All three images build successfully
2. Containers start and pass health checks
3. Default admin user is created automatically
4. Login via REST API returns a valid JWT token
5. Authenticated API calls work through the Nginx proxy
6. Database has tables (Flyway/Hibernate migrations ran)

## Data Persistence

- Database data is stored in Docker volume `secman-db-data`
- Stopping containers preserves data; next start resumes with existing data
- Use `./docker/stop-all.sh --purge` to delete all data and start fresh

## Networking

All containers communicate via the `secman-net` Docker bridge network:
- Frontend resolves `secman-backend` by container name
- Backend resolves `secman-db` by container name
- No host networking required

## SSL Certificate

The frontend container generates a **self-signed certificate** during image build.
Browsers will show a security warning - this is expected for local/development use.

For production, mount your own certificate:
```bash
docker run -d \
  --name secman-frontend \
  --network secman-net \
  -v /path/to/cert.pem:/etc/nginx/ssl/selfsigned.crt \
  -v /path/to/key.pem:/etc/nginx/ssl/selfsigned.key \
  -p 8443:8443 \
  secman-frontend
```

## Troubleshooting

### Backend won't start
```bash
# Check if database is ready
docker logs secman-db
# Check backend logs for connection errors
docker logs secman-backend
```

### Can't login
```bash
# Admin password is generated on FIRST start only
# If you purged and restarted, check for the new password
docker logs secman-backend 2>&1 | grep -A5 "ADMIN"
```

### Port conflicts
If ports 8443, 8080, or 3307 are in use, stop the conflicting services or edit the port mappings in the respective start scripts.

### Reset everything
```bash
./docker/stop-all.sh --purge
./docker/start-all.sh
```
