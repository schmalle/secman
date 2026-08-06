# Deploying Secman to AWS via Docker — Step-by-Step Runbook

A single, sequential, copy-paste procedure for getting the Secman **all-in-one**
container running on AWS (ECR → RDS → Secrets Manager → ECS/Fargate → ALB).

This runbook is the "do it in order" companion to `docs/DOCKER_AWS.md` (which
explains the architecture and the full option set) and `docs/ENVIRONMENT.md`
(the complete environment-variable catalog). If you just want to try the image
on your laptop first, jump to [Appendix A: Local smoke test](#appendix-a-local-smoke-test).

The all-in-one image runs the **Astro SSR web server**, the **Micronaut
backend**, and an internal **nginx** in one container listening on port **80**.
The database is *not* in the container — you point it at Amazon RDS (MariaDB).

```
ALB (TLS :443) ──► ECS task : nginx :80 ──┬─ /api,/oauth,/mcp,/health ─► Micronaut 127.0.0.1:8080 ─► RDS MariaDB
                                          ├─ /_astro, static assets ───► disk
                                          └─ SSR pages ────────────────► Astro/Node 127.0.0.1:4321
```

---

## Step 0 — Prerequisites

- **Tools**: Docker, the AWS CLI v2 (configured with credentials that can create
  ECR/RDS/ECS/IAM/ELB resources), and `openssl`.
- **A VPC** with at least two subnets. This runbook assumes **private subnets
  with a NAT gateway** for the ECS tasks (the backend calls out to CrowdStrike /
  OpenRouter / GitHub when those features are enabled) and public subnets for the
  ALB. A single public subnet works for a quick trial but is not recommended.
- **An ACM certificate** in the deployment region for your public hostname
  (e.g. `secman.example.com`) — needed for the HTTPS listener in Step 8.
- **Build host RAM**: the Gradle stage compiles Kotlin inside the image and needs
  **~4 GB**. On Docker Desktop raise the VM memory; in AWS CodeBuild use at least
  `BUILD_GENERAL1_MEDIUM` (7 GB).

Set these shell variables once and reuse them throughout:

```bash
export AWS_REGION=eu-central-1
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR_REPO=secman-aio
export IMAGE="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest"
export PUBLIC_URL=https://secman.example.com   # your real public hostname
```

---

## Step 1 — Build the image

From the **repository root**:

```bash
docker build -f docker/aws/Dockerfile -t "$ECR_REPO:latest" .
```

- Tests are skipped in the image build (`-x test`). Run `./gradlew build` in CI
  **before** building the image so a broken build fails fast.
- No secrets are baked in — everything is injected at runtime (Step 5).
- **Apple Silicon / ARM laptops**: Fargate defaults to x86, so build for it:
  ```bash
  docker build --platform linux/amd64 -f docker/aws/Dockerfile -t "$ECR_REPO:latest" .
  ```
  (Or run Fargate on ARM64 — see Step 7's `runtimePlatform` note.)

Optionally smoke-test locally now before pushing — see [Appendix A](#appendix-a-local-smoke-test).

---

## Step 2 — Push the image to ECR

```bash
# Create the repository once (ignore the error if it already exists)
aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION" || true

# Authenticate Docker to ECR
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

# Tag and push
docker tag "$ECR_REPO:latest" "$IMAGE"
docker push "$IMAGE"
```

---

## Step 3 — Provision the database (RDS MariaDB)

1. Create an **RDS MariaDB 11.4** instance in your VPC (a `db.t3.small` is fine to
   start). Note its endpoint, e.g. `mydb.xyz.eu-central-1.rds.amazonaws.com`.
2. Create an **empty** database named `secman`. Do **not** pre-create any tables —
   on first boot Hibernate auto-DDL creates the schema because the image ships
   `FLYWAY_DATASOURCES_DEFAULT_ENABLED=false`.
3. Attach a security group that allows inbound **TCP 3306 from the ECS task
   security group** (you will create that SG in Step 7; you can add the rule after,
   or use a placeholder SG now and reference it).

The resulting JDBC URL (used as `DB_CONNECT` below) looks like:

```
jdbc:mariadb://mydb.xyz.eu-central-1.rds.amazonaws.com:3306/secman
```

---

## Step 4 — Generate the secret values

These are generated **once** and then stored in Secrets Manager (Step 5).

> ⚠️ **Never rotate** `SECMAN_ENCRYPTION_PASSWORD` or `SECMAN_ENCRYPTION_SALT`
> after data exists — they encrypt stored credentials (OAuth secrets, API keys),
> and changing them orphans that data. `SECMAN_ENCRYPTION_SALT` must be **exactly
> 16 hex chars**.

```bash
openssl rand -base64 32   # JWT_SECRET (signs auth tokens, ≥256 bits)
openssl rand -hex 32      # SECMAN_ENCRYPTION_PASSWORD
openssl rand -hex 8       # SECMAN_ENCRYPTION_SALT  (16 hex chars)
# DB_PASSWORD = the password you set on the RDS user in Step 3
```

---

## Step 5 — Store secrets in AWS Secrets Manager

Never place secrets in the task definition's plain-text `environment` block — put
them in Secrets Manager and reference them from the `secrets` block (Step 7).

```bash
aws secretsmanager create-secret --name secman/db-password  --secret-string 'THE_RDS_PASSWORD'
aws secretsmanager create-secret --name secman/jwt-secret   --secret-string "$(openssl rand -base64 32)"
aws secretsmanager create-secret --name secman/enc-password --secret-string "$(openssl rand -hex 32)"
aws secretsmanager create-secret --name secman/enc-salt     --secret-string "$(openssl rand -hex 8)"
# Optional feature secrets, only if you enable those integrations:
# aws secretsmanager create-secret --name secman/smtp-password   --secret-string '...'
# aws secretsmanager create-secret --name secman/openrouter-key  --secret-string '...'
```

> SSM alternative: store each as an SSM `SecureString` parameter and use the
> parameter ARN in `valueFrom` (Step 7), granting `ssm:GetParameters` instead of
> `secretsmanager:GetSecretValue` in Step 6.

---

## Step 6 — Create the IAM roles

Two roles are involved:

- **Execution role** (`secmanEcsExecutionRole`) — used by the ECS agent to pull
  the image and read secrets. Attach the AWS-managed
  `AmazonECSTaskExecutionRolePolicy`, **plus** this inline policy so it can read
  the Secman secrets:

  ```json
  {
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["secretsmanager:GetSecretValue"],
      "Resource": "arn:aws:secretsmanager:<region>:<account>:secret:secman/*"
    }]
  }
  ```

- **Task role** (`secmanEcsTaskRole`) — the role the app itself runs as. It can be
  empty to start; add permissions only if you use AWS-integrated features (e.g. S3
  access for user-mapping/asset-snapshot imports — see `docs/S3_USER_MAPPING_IMPORT.md`
  and `docs/CLI_ASSET_MATCH_CLEAR.md`).

Also create a **CloudWatch log group** for the task logs:

```bash
aws logs create-log-group --log-group-name /ecs/secman --region "$AWS_REGION" || true
```

---

## Step 7 — Register the ECS task definition

Save the following as `secman-taskdef.json`, replacing every `<...>` placeholder
(the RDS endpoint, `<account>`, `<region>`, and `$PUBLIC_URL`). This is the
canonical single-container task definition; the env/secret split matches the image
defaults in `docker/aws/Dockerfile`.

```jsonc
{
  "family": "secman",
  "cpu": "1024",
  "memory": "2048",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "executionRoleArn": "arn:aws:iam::<account>:role/secmanEcsExecutionRole",
  "taskRoleArn": "arn:aws:iam::<account>:role/secmanEcsTaskRole",
  // For ARM64 Fargate, add: "runtimePlatform": { "cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX" }
  "containerDefinitions": [{
    "name": "secman",
    "image": "<account>.dkr.ecr.<region>.amazonaws.com/secman-aio:latest",
    "essential": true,
    "portMappings": [{ "containerPort": 80, "protocol": "tcp" }],
    "environment": [
      { "name": "DB_CONNECT",         "value": "jdbc:mariadb://mydb.xyz.rds.amazonaws.com:3306/secman" },
      { "name": "DB_USERNAME",        "value": "secman" },
      { "name": "FRONTEND_URL",       "value": "https://secman.example.com" },
      { "name": "SECMAN_BACKEND_URL", "value": "https://secman.example.com" },
      { "name": "SECMAN_AUTH_COOKIE_SECURE",           "value": "true" },
      { "name": "FLYWAY_DATASOURCES_DEFAULT_ENABLED",  "value": "false" }
    ],
    "secrets": [
      { "name": "DB_PASSWORD",                "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/db-password" },
      { "name": "JWT_SECRET",                 "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/jwt-secret" },
      { "name": "SECMAN_ENCRYPTION_PASSWORD", "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/enc-password" },
      { "name": "SECMAN_ENCRYPTION_SALT",     "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:secman/enc-salt" }
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/secman",
        "awslogs-region": "<region>",
        "awslogs-stream-prefix": "secman"
      }
    }
  }]
}
```

Register it:

```bash
aws ecs register-task-definition --cli-input-json file://secman-taskdef.json --region "$AWS_REGION"
```

---

## Step 8 — Create the ALB, target group, and HTTPS listener

1. **Security groups**
   - `secman-alb-sg`: inbound **443** from the internet (or your corporate CIDR).
   - `secman-task-sg`: inbound **80** from `secman-alb-sg` only. Add this SG as the
     *source* on the RDS security group's 3306 rule (Step 3).
2. **Application Load Balancer** in the public subnets, attached to `secman-alb-sg`.
3. **Target group** — type **ip** (required for Fargate `awsvpc`), protocol HTTP,
   port **80**, with the health check set to:
   - Path: **`/health`**
   - Success codes: **200**
   - A generous healthy threshold / interval — the backend takes **~60–90 s** to
     boot, so make the grace period long (see the service's health-check grace
     period in Step 9).
4. **HTTPS listener** on **443** using your ACM certificate, default action →
   forward to the target group. (Optional: add an HTTP :80 listener that redirects
   to HTTPS.)
5. Point your DNS record (`secman.example.com`) at the ALB.

Confirm `$PUBLIC_URL` matches `FRONTEND_URL` / `SECMAN_BACKEND_URL` from Step 7 —
these drive CORS, OAuth callbacks, and email links, so they must be the public
HTTPS URL, never `localhost`.

---

## Step 9 — Create the ECS service

Create the cluster (once) and the Fargate service wired to the ALB target group:

```bash
aws ecs create-cluster --cluster-name secman --region "$AWS_REGION" || true

aws ecs create-service \
  --cluster secman \
  --service-name secman \
  --task-definition secman \
  --desired-count 1 \
  --launch-type FARGATE \
  --health-check-grace-period-seconds 120 \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-aaa,subnet-bbb],securityGroups=[sg-secman-task-sg],assignPublicIp=DISABLED}" \
  --load-balancers "targetGroupArn=<target-group-arn>,containerName=secman,containerPort=80" \
  --region "$AWS_REGION"
```

- `assignPublicIp=DISABLED` assumes private subnets with a NAT gateway. For a
  quick public-subnet trial, use public subnets and `assignPublicIp=ENABLED`.
- `--health-check-grace-period-seconds 120` stops the ALB from killing the task
  while the backend is still booting.

> **Scaling note**: auth is a stateless JWT cookie, so multiple tasks work without
> sticky sessions. **But** background jobs (materialized-view refresh, schedulers)
> run in **every** task — keep `--desired-count 1` unless you have reviewed the
> schedulers for multi-instance safety.

---

## Step 10 — Verify

1. Watch the deployment reach a steady state:
   ```bash
   aws ecs describe-services --cluster secman --services secman --region "$AWS_REGION" \
     --query 'services[0].deployments'
   ```
2. Check container logs in CloudWatch (`/ecs/secman`) — the backend logs the
   schema creation and "Startup completed" on first boot.
3. Hit the health endpoint and the app through the ALB:
   ```bash
   curl -s "$PUBLIC_URL/health"     # => {"status":"UP"}
   ```
   Then open `$PUBLIC_URL/` in a browser and log in.

---

## Updating to a new version

```bash
docker build -f docker/aws/Dockerfile -t "$ECR_REPO:latest" .
docker tag "$ECR_REPO:latest" "$IMAGE" && docker push "$IMAGE"
aws ecs update-service --cluster secman --service secman --force-new-deployment --region "$AWS_REGION"
```

ECS does a rolling replacement; the ALB only shifts traffic once the new task
passes the `/health` check. (Pinning an immutable image tag per release, e.g.
`:2026-07-19`, is safer than `:latest` for auditable rollbacks.)

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Task keeps restarting right after start | CloudWatch logs — almost always DB unreachable (`DB_CONNECT`, RDS security group) or a malformed `JAVA_OPTS` |
| `502` from the ALB on `/api/` | Backend still booting (raise the health-check grace period) or crashed — check logs for Micronaut bean / Flyway errors |
| Pages render but API calls return 401 | `SECMAN_AUTH_COOKIE_SECURE=true` while reaching the app over plain HTTP — go through the HTTPS ALB (or set it `false` only for local HTTP testing) |
| OAuth callback loops / wrong redirect | `FRONTEND_URL` / `SECMAN_BACKEND_URL` must be the public HTTPS URL, not `localhost` |
| E-mails contain `localhost` links | Same two URL variables |
| Schema errors on first boot against a fresh DB | Ensure `FLYWAY_DATASOURCES_DEFAULT_ENABLED=false` so Hibernate creates the schema |
| Secrets not injected / "AccessDenied" pulling secrets | Execution role is missing `secretsmanager:GetSecretValue` on `secman/*` (Step 6) |

---

## Appendix A — Local smoke test

Run the same image on your machine before pushing to AWS. This assumes a local
MariaDB reachable from the container.

```bash
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
  "$ECR_REPO:latest"

curl -s http://localhost/health   # => {"status":"UP"}   (may take ~60–90 s on first boot)
docker logs -f secman             # watch startup
```

`SECMAN_AUTH_COOKIE_SECURE=false` is required for plain-HTTP local testing;
never use it in production.

---

## Optional feature environment variables

Add these to the task definition (`environment` for non-secret, `secrets` for
sensitive) only when you use the corresponding feature. See `docs/ENVIRONMENT.md`
for the full catalog.

| Purpose | Variables |
|---|---|
| E-mail notifications | `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` (secret), `SMTP_FROM_ADDRESS`, `SMTP_ENABLE_TLS` |
| AI-assisted risk assessment | `OPENROUTER_API_KEY` (secret) + `AI_RISK_ASSESSMENT_ENABLED=true` |
| CrowdStrike Falcon | `FALCON_CLIENT_ID`, `FALCON_CLIENT_SECRET` (secret), `FALCON_CLOUD_REGION` |
| JVM sizing | `JAVA_OPTS` (default baked into the AWS image: `-XX:MaxRAMPercentage=45.0 -XX:InitialRAMPercentage=12.5 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/secman-backend-oom.hprof -XX:+ExitOnOutOfMemoryError`; tune with the task CPU/memory — see `docs/DOCKER_AWS.md` §Memory sizing) |
| Existing Flyway-managed schema | `FLYWAY_DATASOURCES_DEFAULT_ENABLED=true` (leave `false` for a fresh DB) |

---

*See also: `docs/DOCKER_AWS.md` (architecture + reference), `docs/ENVIRONMENT.md`
(full variable catalog), `docs/AWS_SECRETS_SETUP.md`, `docs/DEPLOYMENT.md`,
`docker/README.md` (local multi-container setup).*
