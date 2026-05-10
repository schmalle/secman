# AWS dev scripts (alternative to pass-cli)

This document covers the AWS-flavored dev launchers:

- `scripts/startbackenddevaws.sh` — backend dev (counterpart to `scripts/startbackenddev.sh`)
- `scripts/startfrontenddevaws.sh` — frontend dev (counterpart to `scripts/startfrontenddev.sh`)

They behave exactly like the canonical `pass-cli` versions but pull credentials from
**AWS Secrets Manager** instead of Proton Pass. Useful when running secman on EC2
(Amazon Linux 2 / 2023) where pass-cli is not the natural choice and the EC2 instance
already has an IAM role.

The original `pass-cli` scripts remain canonical (per `CLAUDE.md`). The AWS variants
are an alternative entry point — they are not intended to replace pass-cli for local
developer workflows.

## How it works

Both scripts:

1. Read a single JSON secret from AWS Secrets Manager (default name `secman/dev`).
2. Export each JSON field to the environment variable expected by the backend / frontend.
3. Exec the appropriate dev command (`gradle :backendng:run` or `npm run dev`).

Stop scripts (`stopbackenddev.sh`, `stopfrontenddev.sh`) work unchanged for both
flavors — they kill whatever is bound to ports 8080 / 4321.

## Required tools on Amazon Linux

### Amazon Linux 2023

```bash
# AWS CLI v2 (preinstalled on most AL2023 AMIs — verify with: aws --version)
sudo dnf install -y awscli

# JSON parser
sudo dnf install -y jq

# OpenSSL + lsof (typically already installed)
sudo dnf install -y openssl lsof

# Java 21 (Corretto)
sudo dnf install -y java-21-amazon-corretto-devel

# Node.js 20+ (for the frontend)
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs

# Git
sudo dnf install -y git
```

### Amazon Linux 2

```bash
sudo yum install -y jq openssl lsof git

# AWS CLI v2 (replaces preinstalled v1)
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
unzip awscliv2.zip && sudo ./aws/install --update

# Java 21 (Corretto)
sudo amazon-linux-extras enable corretto21
sudo yum install -y java-21-amazon-corretto-devel

# Node.js 20+
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo yum install -y nodejs
```

### Gradle

The project ships a Gradle wrapper (`./gradlew`), so a system Gradle install is
optional — `startbackenddevaws.sh` falls back to `./gradlew` if `gradle` is not
on `PATH`. To install a system Gradle (matching the project's 9.5.0):

```bash
curl -fsSL https://services.gradle.org/distributions/gradle-9.5-bin.zip -o /tmp/gradle.zip
sudo mkdir -p /opt/gradle && sudo unzip -d /opt/gradle /tmp/gradle.zip
echo 'export PATH=$PATH:/opt/gradle/gradle-9.5/bin' | sudo tee /etc/profile.d/gradle.sh
. /etc/profile.d/gradle.sh
```

## AWS authentication

The scripts use the standard AWS credential provider chain. Pick whichever fits
the environment:

| Environment | Recommended |
|---|---|
| EC2 (Amazon Linux) | **Instance profile** — attach an IAM role to the instance, no static keys needed |
| Local dev | `aws configure` (creates `~/.aws/credentials`) or `AWS_PROFILE=...` |
| CI | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` env vars |

### IAM permissions

The principal that runs the script needs permission to read the secret:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ],
      "Resource": "arn:aws:secretsmanager:<region>:<account>:secret:secman/dev-*"
    }
  ]
}
```

If the secret was created with a customer-managed KMS key, also grant
`kms:Decrypt` on that key.

## Creating the secret

Store all required keys as a single JSON object. Keys mirror the field names
used by `pass-cli` under `test/secman` (see `docs/PASS_CLI.md`).

```bash
aws secretsmanager create-secret \
  --name secman/dev \
  --description "Secman backend + frontend dev secrets" \
  --secret-string file://secman-dev.json \
  --region eu-central-1
```

`secman-dev.json` (template — fill in the real values):

```json
{
  "DB_CONNECT": "jdbc:mariadb://localhost:3306/secman?useSSL=false",
  "SECMAN_BACKEND_BASE_URL": "https://secman.example.com",
  "SECMAN_HOST": "https://secman.example.com",
  "SECMAN_SSL_ACCEPT_ALL": "false",

  "SECMAN_ADMIN_NAME": "admin",
  "SECMAN_ADMIN_PASS": "<bootstrap-admin-password>",
  "SECMAN_ADMIN_EMAIL": "admin@example.com",
  "SECMAN_MCP_KEY": "<mcp-bearer-key>",

  "FALCON_CLIENT_ID": "<crowdstrike-client-id>",
  "FALCON_CLIENT_SECRET": "<crowdstrike-client-secret>",
  "FALCON_CLOUD_REGION": "us-1",

  "OPENROUTER_API_KEY": "<openrouter-api-key>",

  "SECMAN_AWS_ACCESS_KEY_ID": "",
  "SECMAN_AWS_SECRET_ACCESS_KEY": "",
  "SECMAN_AWS_ACCESS_TOKEN": ""
}
```

The `SECMAN_AWS_*` fields are application-side AWS credentials (used by the backend
to talk to S3/CrowdStrike on its own behalf). **Leave them empty when running on
EC2 with an instance role** — the backend will then inherit the role automatically.
Populate them only for local dev where no instance role is available.

To update an existing secret:

```bash
aws secretsmanager put-secret-value \
  --secret-id secman/dev \
  --secret-string file://secman-dev.json \
  --region eu-central-1
```

### Seeding from pass-cli (one-time migration)

If the values already live in pass-cli, you can mint the JSON directly:

```bash
jq -n \
  --arg DB_CONNECT              "$(pass-cli read 'test/secman/DB_CONNECT')" \
  --arg SECMAN_BACKEND_BASE_URL "$(pass-cli read 'test/secman/SECMAN_BACKEND_BASE_URL')" \
  --arg SECMAN_HOST             "$(pass-cli read 'test/secman/SECMAN_HOST')" \
  --arg SECMAN_SSL_ACCEPT_ALL   "$(pass-cli read 'test/secman/SECMAN_SSL_ACCEPT_ALL')" \
  --arg SECMAN_ADMIN_NAME       "$(pass-cli read 'test/secman/SECMAN_ADMIN_NAME')" \
  --arg SECMAN_ADMIN_PASS       "$(pass-cli read 'test/secman/SECMAN_ADMIN_PASS')" \
  --arg SECMAN_ADMIN_EMAIL      "$(pass-cli read 'test/secman/SECMAN_ADMIN_EMAIL')" \
  --arg SECMAN_MCP_KEY          "$(pass-cli read 'test/secman/SECMAN_MCP_KEY')" \
  --arg FALCON_CLIENT_ID        "$(pass-cli read 'test/secman/FALCON_CLIENT_ID')" \
  --arg FALCON_CLIENT_SECRET    "$(pass-cli read 'test/secman/FALCON_CLIENT_SECRET')" \
  --arg FALCON_CLOUD_REGION     "$(pass-cli read 'test/secman/FALCON_CLOUD_REGION')" \
  --arg OPENROUTER_API_KEY      "$(pass-cli read 'test/secman/OPENROUTER_API_KEY')" \
  '{$DB_CONNECT, $SECMAN_BACKEND_BASE_URL, $SECMAN_HOST, $SECMAN_SSL_ACCEPT_ALL,
    $SECMAN_ADMIN_NAME, $SECMAN_ADMIN_PASS, $SECMAN_ADMIN_EMAIL, $SECMAN_MCP_KEY,
    $FALCON_CLIENT_ID, $FALCON_CLIENT_SECRET, $FALCON_CLOUD_REGION,
    $OPENROUTER_API_KEY}' \
  > secman-dev.json
```

## Usage

From the project root:

```bash
# Backend (port 8080)
./scripts/startbackenddevaws.sh

# Frontend (port 4321) — separate terminal
./scripts/startfrontenddevaws.sh

# Stop (same scripts as the pass-cli flavor)
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```

### Configuration via env vars

| Variable | Default | Purpose |
|---|---|---|
| `SECMAN_AWS_SECRET_ID` | `secman/dev` | Secret name or full ARN to fetch |
| `AWS_REGION`           | `eu-central-1` | Region containing the secret |
| `AWS_DEFAULT_REGION`   | (fallback for `AWS_REGION`) | |
| `AWS_PROFILE`          | (none) | Named profile from `~/.aws/credentials` |

Examples:

```bash
# Custom secret in us-east-1
SECMAN_AWS_SECRET_ID=secman/staging AWS_REGION=us-east-1 ./scripts/startbackenddevaws.sh

# Local dev with a named profile
AWS_PROFILE=secman-dev ./scripts/startbackenddevaws.sh
```

## Secret keys reference

| JSON key in secret | Exported as | Used by |
|---|---|---|
| `DB_CONNECT` | `DB_CONNECT` | backend |
| `SECMAN_BACKEND_BASE_URL` | `SECMAN_BACKEND_URL` (backend), `SECMAN_DOMAIN` (frontend) | both |
| `SECMAN_HOST` | `SECMAN_HOST` | frontend |
| `SECMAN_SSL_ACCEPT_ALL` | `SECMAN_INSECURE` | backend |
| `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` / `SECMAN_ADMIN_EMAIL` | same names | backend bootstrap |
| `SECMAN_MCP_KEY` | `SECMAN_MCP_KEY` | backend MCP |
| `FALCON_CLIENT_ID` / `FALCON_CLIENT_SECRET` / `FALCON_CLOUD_REGION` | same names | backend CrowdStrike |
| `OPENROUTER_API_KEY` | `SECMAN_OPENROUTER_API_KEY` | backend translation |
| `SECMAN_AWS_ACCESS_KEY_ID` | `AWS_ACCESS_KEY_ID` | backend (optional override) |
| `SECMAN_AWS_SECRET_ACCESS_KEY` | `AWS_SECRET_ACCESS_KEY` | backend (optional override) |
| `SECMAN_AWS_ACCESS_TOKEN` | `AWS_SESSION_TOKEN` | backend (optional override) |

Missing keys are skipped — they do not cause failure. This is intentional so an
EC2 instance role can supply application-side AWS credentials without static
keys having to live in the secret.

`JWT_SECRET` is generated fresh on every backend start (`openssl rand -base64 48`),
matching `startbackenddev.sh`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `aws: command not found` | Install AWS CLI v2 (see install section above). |
| `Unable to locate credentials` | Configure credentials: attach an instance role, run `aws configure`, or set `AWS_PROFILE`. |
| `AccessDeniedException ... GetSecretValue` | Add the IAM policy in *IAM permissions* above; if the secret uses a CMK, also grant `kms:Decrypt`. |
| `ResourceNotFoundException` | The secret name is wrong, or the wrong region is in use. Set `SECMAN_AWS_SECRET_ID` and `AWS_REGION`. |
| `jq: command not found` | `sudo dnf install -y jq` (AL2023) or `sudo yum install -y jq` (AL2). |
| Backend starts but Flyway / DB connection fails | Verify `DB_CONNECT` in the secret points at a reachable MariaDB instance; remember the secret value must be a full JDBC URL. |
| Backend starts but auth fails | Check that `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` exist in the secret. The first start bootstraps the admin user from these values. |

## Relationship to canonical workflow

- The `pass-cli` scripts remain canonical for local developer workflows
  (per `CLAUDE.md`). Tests, E2E runners, and the CLI wrapper continue to
  resolve secrets via pass-cli.
- The AWS scripts target the deployed EC2 environment where pass-cli is
  unavailable but Secrets Manager + IAM are already in place.
- If you add new env vars to `startbackenddev.sh` / `startfrontenddev.sh`,
  add the matching JSON key to `secman/dev` and update the table above.
