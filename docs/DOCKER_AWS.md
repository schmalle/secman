# Secman All-in-One Docker Container (AWS)

Single-container deployment: the **Astro SSR web server** and the **Micronaut
backend** run together in one image, fronted by an internal **nginx** on port
80. Designed for AWS (ECR + ECS/Fargate behind an ALB), but runs anywhere
Docker runs. The database is **not** part of the container — point it at
Amazon RDS (MariaDB) or any reachable MariaDB 11.4.

For the local multi-container setup (separate backend/frontend/database
containers), see `docker/README.md`. This document only covers the
all-in-one image.

## Architecture

```
                        ┌──────────────────────────── container ───────────────────────────┐
   ALB (TLS, :443)      │  nginx :80                                                       │
  ───────────────────►  │   ├── /api/, /oauth/, /mcp, /health ──► Micronaut  127.0.0.1:8080│──► RDS MariaDB
                        │   ├── /_astro/, static assets ───────► served from disk          │
                        │   └── all other routes (SSR pages) ──► Astro/Node 127.0.0.1:4321 │
                        └──────────────────────────────────────────────────────────────────┘
```

- TLS terminates at the ALB; nginx listens on plain HTTP :80 inside.
- The backend (8080) and the Astro server (4321) bind to loopback only —
  nothing but nginx is reachable from outside the container.
- The entrypoint (`docker/aws/entrypoint.sh`) starts all three processes and
  exits when the **first one dies**, so the orchestrator restarts the whole
  container instead of running half-broken.
- SSE endpoints (exception badge updates, refresh progress) work through
  nginx: `/api/` is proxied with `proxy_buffering off` and a long read
  timeout.

## Files

| File | Purpose |
|---|---|
| `docker/aws/Dockerfile` | 3-stage build: Gradle fat JAR → Astro build → runtime (JRE 25 + Node 22 + nginx) |
| `docker/aws/nginx.conf` | Internal reverse proxy / static file server |
| `docker/aws/entrypoint.sh` | Process supervisor (backend + SSR server + nginx) |
| `.dockerignore` | Keeps the build context lean (repo root is the context) |

## Building the image

From the **repository root**:

```bash
docker build -f docker/aws/Dockerfile -t secman-aio .
```

Notes:
- The Gradle stage compiles Kotlin inside the container and needs **~4 GB
  RAM**. On Docker Desktop raise the VM memory; on AWS CodeBuild use at least
  `BUILD_GENERAL1_MEDIUM` (7 GB).
- Tests are skipped in the image build (`-x test`); run `./gradlew build`
  in CI before building the image.
- No secrets are baked into the image — everything is injected at runtime.

## Runtime configuration — passing keys into the container

All configuration is passed as **environment variables**. The full catalog
lives in `docs/ENVIRONMENT.md`; the deployment-critical set:

### Required

| Variable | Purpose | Example / generation |
|---|---|---|
| `DB_CONNECT` | JDBC URL to MariaDB/RDS | `jdbc:mariadb://mydb.xyz.eu-central-1.rds.amazonaws.com:3306/secman` |
| `DB_USERNAME` | DB user | `secman` |
| `DB_PASSWORD` | DB password | from Secrets Manager |
| `JWT_SECRET` | signs auth tokens, ≥256 bits | `openssl rand -base64 32` |
| `SECMAN_ENCRYPTION_PASSWORD` | encrypts stored credentials (OAuth secrets, API keys). **Never rotate** — orphans encrypted data | `openssl rand -hex 32` |
| `SECMAN_ENCRYPTION_SALT` | exactly 16 hex chars. Same never-rotate warning | `openssl rand -hex 8` |
| `FRONTEND_URL` | public URL (CORS, email links, OAuth callbacks) | `https://secman.example.com` |
| `SECMAN_BACKEND_URL` | public URL (same host in single-container mode) | `https://secman.example.com` |

### Common optional keys

| Variable | Enables |
|---|---|
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM_ADDRESS`, `SMTP_ENABLE_TLS` | e-mail notifications |
| `OPENROUTER_API_KEY` (+ `AI_RISK_ASSESSMENT_ENABLED=true`) | AI-assisted risk assessment |
| `FALCON_CLIENT_ID`, `FALCON_CLIENT_SECRET`, `FALCON_CLOUD_REGION` | CrowdStrike Falcon integration |
| `FLYWAY_DATASOURCES_DEFAULT_ENABLED` | `false` (default) for a **fresh** DB — Hibernate creates the schema; `true` for an existing Flyway-managed schema |
| `JAVA_OPTS` | JVM sizing, default `-Xmx1024m -Xms256m -XX:+UseContainerSupport` |
| `SECMAN_AUTH_COOKIE_SECURE` | `true` (default). Only set `false` for plain-HTTP local testing |

### Local run (docker run)

```bash
# individual -e flags
docker run -d --name secman -p 80:80 \
  -e DB_CONNECT="jdbc:mariadb://host.docker.internal:3306/secman" \
  -e DB_USERNAME=secman \
  -e DB_PASSWORD=changeme \
  -e JWT_SECRET="$(openssl rand -base64 32)" \
  -e SECMAN_ENCRYPTION_PASSWORD="$(openssl rand -hex 32)" \
  -e SECMAN_ENCRYPTION_SALT="$(openssl rand -hex 8)" \
  -e FRONTEND_URL=http://localhost \
  -e SECMAN_BACKEND_URL=http://localhost \
  -e SECMAN_AUTH_COOKIE_SECURE=false \
  secman-aio

# or with an env file (never commit it)
docker run -d --name secman -p 80:80 --env-file secman.env secman-aio
```

Verify: `curl -s http://localhost/health` → `{"status":"UP"}`, then open
`http://localhost/`.

### AWS: injecting secrets the right way

Do **not** put secrets in the task-definition `environment` block (visible in
plain text in the console/API). Use the `secrets` block, which pulls values
from **AWS Secrets Manager** or **SSM Parameter Store** at container start:

```jsonc
// task definition (excerpt)
{
  "containerDefinitions": [{
    "name": "secman",
    "image": "<account>.dkr.ecr.<region>.amazonaws.com/secman-aio:latest",
    "portMappings": [{ "containerPort": 80, "protocol": "tcp" }],
    "environment": [
      { "name": "DB_CONNECT",  "value": "jdbc:mariadb://mydb.xyz.rds.amazonaws.com:3306/secman" },
      { "name": "DB_USERNAME", "value": "secman" },
      { "name": "FRONTEND_URL",       "value": "https://secman.example.com" },
      { "name": "SECMAN_BACKEND_URL", "value": "https://secman.example.com" }
    ],
    "secrets": [
      { "name": "DB_PASSWORD",                "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/db-password" },
      { "name": "JWT_SECRET",                 "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/jwt-secret" },
      { "name": "SECMAN_ENCRYPTION_PASSWORD", "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/enc-password" },
      { "name": "SECMAN_ENCRYPTION_SALT",     "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/enc-salt" },
      { "name": "SMTP_PASSWORD",              "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/smtp-password" },
      { "name": "OPENROUTER_API_KEY",         "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/openrouter-key" }
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/secman",
        "awslogs-region": "<region>",
        "awslogs-stream-prefix": "secman"
      }
    }
  }],
  "cpu": "1024",
  "memory": "2048",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "executionRoleArn": "arn:aws:iam::<account>:role/secmanEcsExecutionRole"
}
```

Create the secrets once:

```bash
aws secretsmanager create-secret --name secman/db-password        --secret-string 'REPLACE'
aws secretsmanager create-secret --name secman/jwt-secret         --secret-string "$(openssl rand -base64 32)"
aws secretsmanager create-secret --name secman/enc-password       --secret-string "$(openssl rand -hex 32)"
aws secretsmanager create-secret --name secman/enc-salt           --secret-string "$(openssl rand -hex 8)"
```

The ECS **execution role** needs read access to those secrets:

```json
{
  "Effect": "Allow",
  "Action": ["secretsmanager:GetSecretValue"],
  "Resource": "arn:aws:secretsmanager:<region>:<account>:secret:secman/*"
}
```

(SSM alternative: store as `SecureString` parameters and use the parameter
ARN in `valueFrom` plus `ssm:GetParameters` on the role.)

## Deploying to AWS step by step

1. **Push to ECR**
   ```bash
   aws ecr create-repository --repository-name secman-aio
   aws ecr get-login-password --region <region> \
     | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
   docker tag secman-aio:latest <account>.dkr.ecr.<region>.amazonaws.com/secman-aio:latest
   docker push <account>.dkr.ecr.<region>.amazonaws.com/secman-aio:latest
   ```
   (On Apple Silicon build with `--platform linux/amd64` for x86 Fargate, or
   run Fargate on ARM64 with `"runtimePlatform": {"cpuArchitecture": "ARM64"}`.)

2. **Database** — RDS MariaDB 11.4, security group opened to the ECS tasks on
   3306. Create an empty `secman` database; the first boot creates the schema
   (leave `FLYWAY_DATASOURCES_DEFAULT_ENABLED=false`).

3. **ECS service** — Fargate, 1 vCPU / 2 GB to start, the task definition
   above, in private subnets with NAT (the backend calls out to CrowdStrike /
   OpenRouter / GitHub if those features are enabled).

4. **ALB** — HTTPS listener (ACM certificate) → target group on port 80,
   health check path **`/health`** (success code 200, start period is long:
   the backend takes ~60–90 s to boot). Point `FRONTEND_URL` /
   `SECMAN_BACKEND_URL` at the public HTTPS URL.

5. **Scaling note** — auth is a stateless JWT cookie, so multiple tasks work
   without sticky sessions. Background jobs (materialized-view refresh,
   schedulers) run in **every** task; keep a single task unless you have
   reviewed the schedulers for multi-instance safety.

## Operations

- **Logs**: all three processes write to stdout/stderr → CloudWatch via the
  `awslogs` driver. Backend log verbosity: `SECMAN_LOGGING`
  (see `docs/ENVIRONMENT.md`); keep `SECMAN_DEBUG=false` in production.
- **Health**: `/health` (backend, proxied by nginx) is used by both the
  container HEALTHCHECK and the ALB target group.
- **Restart behavior**: if any of nginx / backend / SSR server dies, the
  container exits and ECS replaces the task.

## Troubleshooting

| Symptom | Check |
|---|---|
| Container exits immediately | `docker logs` — usually DB unreachable (`DB_CONNECT`) or a malformed `JAVA_OPTS` |
| `502` from nginx on `/api/` | backend still booting (wait for start period) or crashed — check logs for Micronaut bean/Flyway errors |
| Pages render but API calls fail with 401 | `SECMAN_AUTH_COOKIE_SECURE=true` while testing over plain HTTP — use HTTPS (ALB) or set it to `false` locally |
| OAuth callback loops / wrong redirect | `FRONTEND_URL` / `SECMAN_BACKEND_URL` must be the public HTTPS URL, not `localhost` |
| E-mails contain `localhost` links | same two URL variables |
| Schema errors on first boot against a fresh DB | ensure `FLYWAY_DATASOURCES_DEFAULT_ENABLED=false` so Hibernate creates the schema |

---
*See also: `docs/ENVIRONMENT.md` (full variable catalog), `docs/DEPLOYMENT.md`,
`docs/AWS_SECRETS_SETUP.md`, `docker/README.md` (local multi-container setup).*
