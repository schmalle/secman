# Secman Docker Deployment

This directory holds four ways to run Secman in Docker/Kubernetes. Pick the
one that fits:

| Setup | Files | Best for | Database |
|-------|-------|----------|----------|
| **Compose (all-in-one)** | `docker-compose.yml`, `compose-up.sh`, `.env.example` | Fastest local/demo start; mirrors the AWS image | **Bundled MariaDB container OR external RDS** (toggle) |
| **Compose (split, 3 containers)** | `docker-compose.split.yaml`, `compose-up-split.sh` | Real separate frontend/backend/db containers, e.g. **macOS local dev** | Bundled MariaDB container |
| **Single-container (AWS ECS/Fargate)** | `docker/aws/` | ECS/Fargate + Secrets Manager | External RDS / MariaDB |
| **Kubernetes (AWS EKS/Fargate)** | `docker/eks/` | EKS + Fargate + External Secrets Operator | External RDS |

Manual `start-*.sh` scripts (no compose) are also still available — see
[Option 3](#option-3--split-three-container-topology) — as a throwaway/demo
fallback for the split topology.

> Deploying to AWS ECS/Fargate? The full reference is
> [docs/DOCKER_AWS.md](../docs/DOCKER_AWS.md). Deploying to AWS EKS/Fargate
> (Kubernetes)? See [docs/DOCKER_EKS.md](../docs/DOCKER_EKS.md). Running
> somewhere other than AWS? See [Suggestions](#suggestions--other-deployment-targets)
> below.

---

## Option 1 — Docker Compose (recommended, DB is a container *or* RDS)

One compose file runs the all-in-one image (Astro SSR + Micronaut + nginx) and
lets you choose where the database lives. This is the direct answer to
"run in AWS with the DB as either a Docker container or an RDS backend":
develop locally against a bundled MariaDB, then point the same image at RDS in
AWS by changing one variable.

```bash
# A) Bundled MariaDB container (dev/demo) — generates docker/.env with secrets
./docker/compose-up.sh --local-db

# B) External database / Amazon RDS — set DB_CONNECT in docker/.env first
./docker/compose-up.sh --rds
```

The helper creates `docker/.env` from `.env.example` on first run, filling in
freshly generated `JWT_SECRET`, encryption password/salt, and DB passwords.
Review it before real use. Open `http://localhost:8080` (default host port).

**Database mode is a single switch:**

| Mode | Command | `DB_CONNECT` in `docker/.env` |
|------|---------|-------------------------------|
| Container | `compose-up.sh --local-db` | `jdbc:mariadb://db:3306/secman` |
| RDS / external | `compose-up.sh --rds` | `jdbc:mariadb://<rds-endpoint>:3306/secman` |

In `--rds` mode the bundled `db` service (guarded by the `local-db` compose
profile) never starts — only the app container runs, talking to your RDS.

Raw compose (no helper):

```bash
cp docker/.env.example docker/.env            # then edit secrets + DB_CONNECT
# bundled DB:
docker compose -f docker/docker-compose.yml --profile local-db up -d
# RDS:
docker compose -f docker/docker-compose.yml up -d
```

Tear down: `docker compose -f docker/docker-compose.yml --profile local-db down`
(add `-v` to also drop the `secman-db-data` volume).

---

## Option 2 — Single-container AWS image (ECS/Fargate)

The same all-in-one image, deployed to AWS ECS/Fargate behind an ALB with
secrets in AWS Secrets Manager and the database on RDS. Build, push, and task
definition instructions live in
[docs/DOCKER_AWS.md](../docs/DOCKER_AWS.md).

---

## Option 3 — Split, three-container topology

Three real, separate containers: database, backend (Micronaut), frontend
(nginx + Astro SSR). This is the option to reach for on **macOS** — closest
to a genuine local prod-like stack — or anywhere you specifically want the
three pieces isolated rather than bundled into one image.

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
│                    │ Nginx + Astro │              │
│                    │ SSR :8443    │              │
│                    └──────────────┘              │
│                                                  │
│          Docker Network: secman-net              │
└─────────────────────────────────────────────────┘
```

| Container | Image | Port | Description |
|-----------|-------|------|-------------|
| `secman-db` | `secman-db` | 3307 → 3306 | MariaDB 11.4, persistent volume |
| `secman-backend` | `secman-backend` | 8080 | Kotlin/Micronaut REST API |
| `secman-frontend` | `secman-frontend` | **8443** (HTTPS) | Nginx (TLS + static assets) + Astro SSR Node server |

### Recommended: `compose-up-split.sh` (real secrets)

```bash
# Builds the backend JAR, builds all three images, and brings up the stack.
# Generates docker/.env (same file the all-in-one flow uses) with fresh
# secrets on first run — nothing is hardcoded.
./docker/compose-up-split.sh

# Open https://localhost:8443 (accept the self-signed certificate warning)

# Get the auto-generated admin password
docker logs secman-backend 2>&1 | grep "Password:"
```

Raw compose (no wrapper):
```bash
cp docker/.env.example docker/.env            # then edit secrets
docker compose -f docker/docker-compose.split.yaml --env-file docker/.env up --build
```

Tear down: `docker compose -f docker/docker-compose.split.yaml --env-file docker/.env down` (add `-v` to also drop the `secman-split-db-data` volume).

### Fallback: manual scripts (throwaway/demo only)

No compose, no `.env` file — every secret must be exported by hand, every
time. Nothing is stored, so this is fine for a five-minute smoke test but
not for anything you'd come back to.

```bash
# 1. Build all images
./docker/build-all.sh

# 2. Export secrets (no defaults — the scripts refuse to start without these)
export SECMAN_DB_ROOT_PASSWORD="$(openssl rand -hex 12)"
export SECMAN_DB_PASSWORD="$(openssl rand -hex 12)"
export SECMAN_JWT_SECRET="$(openssl rand -base64 32)"
export SECMAN_ENCRYPTION_PASSWORD="$(openssl rand -hex 32)"
export SECMAN_ENCRYPTION_SALT="$(openssl rand -hex 8)"

# 3. Start everything
./docker/start-all.sh

# 4. Open https://localhost:8443, get the admin password as above
```

#### Scripts Reference

| Script | Description |
|--------|-------------|
| `build-all.sh` | Build all three Docker images |
| `start-all.sh` | Start all containers in order (DB → backend → frontend) — requires the exported secrets above |
| `start-database.sh` | Start only the database container — requires `SECMAN_DB_ROOT_PASSWORD`, `SECMAN_DB_PASSWORD` |
| `start-backend.sh` | Start only the backend container — requires `SECMAN_DB_PASSWORD`, `SECMAN_JWT_SECRET`, `SECMAN_ENCRYPTION_PASSWORD`, `SECMAN_ENCRYPTION_SALT` |
| `start-frontend.sh` | Start only the frontend container |
| `stop-all.sh` | Stop all containers (preserves data) |
| `stop-all.sh --purge` | Stop all containers and delete data volume + network |
| `test-docker.sh` | Full integration test (build, start, login, verify, cleanup) |
| `test-docker.sh --keep` | Same as above but leave containers running after test |

`SECMAN_DB_PASSWORD`/`SECMAN_JWT_SECRET`/`SECMAN_ENCRYPTION_PASSWORD`/`SECMAN_ENCRYPTION_SALT` must be **exported before** calling `start-backend.sh`, and `SECMAN_DB_PASSWORD` must match what `start-database.sh` was started with. There are intentionally no built-in defaults — a "just works" default password is exactly what ends up in a real deployment by accident. `SECMAN_ENCRYPTION_PASSWORD`/`SALT` are especially important to set for real use: unset, the backend falls back to a well-known constant in `application.yml` that would silently "encrypt" stored OAuth secrets/API keys with a public value.

## Data Persistence

- Compose flow: data lives in the `secman-split-db-data` volume (distinct from the all-in-one flow's `secman-db-data` — don't run both flows against the same volume concurrently).
- Manual-script flow: data lives in the `secman-db-data` volume.
- Stopping containers preserves data; next start resumes with existing data.
- Use `./docker/stop-all.sh --purge` (or `docker compose ... down -v`) to delete all data and start fresh.

## Networking

All containers communicate via the `secman-net` Docker bridge network (manual scripts) or the compose-managed network (compose flow):
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
If ports 8443, 8080, or 3307 are in use, stop the conflicting services or edit the port mappings in the respective start scripts / `docker-compose.split.yaml`.

### Reset everything
```bash
./docker/stop-all.sh --purge
./docker/start-all.sh
# or, compose flow:
docker compose -f docker/docker-compose.split.yaml --env-file docker/.env down -v
./docker/compose-up-split.sh
```

---

## Option 4 — Kubernetes (AWS EKS/Fargate)

Frontend + backend run as Kubernetes Deployments on EKS Fargate (serverless,
no nodes to manage), behind an ALB provisioned by the AWS Load Balancer
Controller; the database is Amazon RDS. Secrets come from AWS Secrets
Manager via the External Secrets Operator — nothing in plaintext in any
manifest. Manifests, the eksctl cluster config, and the build/deploy scripts
live in `docker/eks/`. Full step-by-step runbook:
[docs/DOCKER_EKS.md](../docs/DOCKER_EKS.md).

---

## macOS notes

- **Docker Desktop for Mac** is the default choice; **Colima** (`brew
  install colima docker` + `colima start`) is a lighter, free alternative if
  you don't need Docker Desktop's GUI/licensing.
- **Apple Silicon (M-series)**: images build natively as `arm64`. If you
  want parity with AWS Fargate (which defaults to `x86_64`), add
  `--platform linux/amd64` to any `docker build`/`docker compose build`
  invocation — this is slower (emulated) but confirms the image behaves
  correctly on the architecture that will actually run in AWS.
- **Memory**: the backend build stage compiles Kotlin inside the container
  and needs **~4 GB RAM**. In Docker Desktop, raise the VM memory limit
  (Settings → Resources) before running `compose-up-split.sh` or
  `build-all.sh` for the first time.
- **Volumes**: the database uses a named Docker volume
  (`secman-split-db-data` / `secman-db-data`), not a bind mount — named
  volumes avoid the significant I/O overhead of Docker Desktop's bind-mount
  file sharing on macOS, which matters for MariaDB's write pattern.

---

## Suggestions — other deployment targets

### Non-AWS

| Option | When it fits | Notes |
|---|---|---|
| **Plain `docker compose` on a VPS** (Hetzner, DigitalOcean, ...) | Small team, single host is enough | Put [Traefik](https://traefik.io/) or [Caddy](https://caddyserver.com/) in front for automatic Let's Encrypt TLS instead of the self-signed cert; add [Watchtower](https://containrrr.dev/watchtower/) to auto-pull new image tags. `docker-compose.split.yaml` works as-is — swap the frontend's self-signed cert for a Traefik/Caddy-terminated one. |
| **k3s / k3d** | Want Kubernetes without EKS's cost/complexity, or want to rehearse the EKS manifests locally first | `docker/eks/*.yaml` manifests are largely reusable: swap the `Ingress` class (`alb` → `traefik` or `nginx`), and swap the `ExternalSecret`'s backend (AWS Secrets Manager → a local `SecretStore`, e.g. backed by a Kubernetes `Secret` or Vault). Good staging ground before a real EKS deploy. |
| **Docker Swarm** | Multi-host but Kubernetes feels like overkill | The same three images work as a `docker stack deploy` with a `docker-compose.split.yaml`-derived stack file (add `deploy:` blocks); much less operational surface than K8s for the same 3-container topology. |
| **Backups (any of the above)** | Any self-hosted MariaDB container | Cron + `mariadb-dump` + [restic](https://restic.net/) or `rclone` to S3-compatible object storage (AWS S3, Backblaze B2, MinIO, ...). None of the options above include automated backups — RDS (see AWS options) is the only one with that built in. |

### AWS

| Option | When it fits | Notes |
|---|---|---|
| **ECS/Fargate** (Option 2, already implemented) | Default recommendation unless you're already standardizing on Kubernetes elsewhere | Simplest AWS option here — no cluster control plane, no CSI/IRSA-per-addon complexity. See `docs/DOCKER_AWS.md`. |
| **EKS Fargate** (Option 4) | Standardizing on Kubernetes across other services, or want K8s-native tooling (kubectl, Helm charts, GitOps) | Serverless per-pod billing like ECS/Fargate, but with EKS's control-plane cost (~$0.10/hr) and the extra moving parts (AWS Load Balancer Controller, External Secrets Operator, IRSA) on top. See `docs/DOCKER_EKS.md`. |
| **EKS with managed node groups** | Need features Fargate doesn't support — DaemonSets, `hostPort`, persistent EBS volumes for an in-cluster database instead of RDS, GPU nodes | More ops overhead (patch/size the nodes yourself, or use Karpenter/Cluster Autoscaler) in exchange for full Kubernetes feature support. `docs/DOCKER_EKS.md` has an appendix on running the database as an EBS-backed StatefulSet on a node group instead of RDS. |
