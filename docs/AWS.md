# AWS dev scripts (alternative to pass-cli)

This document covers the AWS-flavored launchers — counterparts to every
pass-cli script under `scripts/`. Each one resolves credentials from **AWS
Secrets Manager** instead of Proton Pass, and is safe to run from cron and
systemd on Amazon Linux.

The original `pass-cli` scripts remain canonical (per `CLAUDE.md`). The AWS
variants are an alternative entry point — not a replacement for local
developer workflows.

## Script index

| pass-cli script | AWS variant | Purpose |
|---|---|---|
| `scripts/startbackenddev.sh` | `scripts/startbackenddevaws.sh` | Backend dev (port 8080) |
| `scripts/startfrontenddev.sh` | `scripts/startfrontenddevaws.sh` | Frontend dev (port 4321) |
| `scripts/backend` | `scripts/backendaws.sh` | Backend run via Gradle (no `:clean`) |
| `scripts/deleteoutdated.sh` | `scripts/deleteoutdatedaws.sh` | CLI: dry-run delete-asset-not-seen |
| `scripts/e2e-test.sh` | `scripts/e2e-testaws.sh` | E2E smoke + JS-error scanner |
| `scripts/import.sh` | `scripts/importaws.sh` | CLI: query servers --save (CrowdStrike → backend) |
| `scripts/release-e2e-test.sh` | `scripts/release-e2e-testaws.sh` | Release lifecycle E2E (REQADMIN) |
| `scripts/secmancli` | `scripts/secmancliaws.sh` | General-purpose CLI wrapper |
| `scripts/secmanng` | `scripts/secmanngaws.sh` | CLI wrapper with insecure-SSL flag |
| `scripts/secmanreportenv` | `scripts/secmanreportenvaws.sh` | Report-env preset (sourceable) |
| `scripts/secmanserverca` | `scripts/secmansercaaws.sh` | Runs `secmanclisupport` |
| `scripts/test/provision-test-user.sh` | `scripts/test/provision-test-useraws.sh` | Idempotent test-user provisioning |
| `scripts/test/test-e2e-exception-workflow.sh` | `scripts/test/test-e2e-exception-workflowaws.sh` | Exception workflow E2E |
| `scripts/test/test-e2e-vuln-exception-full.sh` | `scripts/test/test-e2e-vuln-exception-fullaws.sh` | Full vuln + exception E2E (MCP + UI) |

All AWS variants share `scripts/lib/aws-secrets.sh` (sourced, not executed).
The shared library:

1. Builds a cron-safe `PATH` and sources SDKMAN (and nvm if present), so
   SDKMAN-managed `java`/`gradle` and nvm-managed `node`/`npm` are visible
   even though cron does not read `~/.bashrc`.
2. Reads a single JSON secret from AWS Secrets Manager (default name
   `secman/dev`).
3. Exposes `secman_aws_export_envfile` which exports the same env vars that
   `pass-cli run --env-file secmanpp.env` would set.

The stop helpers (`stopbackenddev.sh`, `stopfrontenddev.sh`) work for both
flavors — they kill whatever is bound to ports 8080 / 4321.

## Required tools on Amazon Linux

The scripts need three OS-level packages plus a Java/Node toolchain. The
toolchain can come from SDKMAN/nvm (recommended — see the next sections) or
from system packages.

### Amazon Linux 2023

```bash
# AWS CLI v2 — preinstalled on most AL2023 AMIs (verify with: aws --version)
sudo dnf install -y awscli

# Required: jq + openssl + lsof + git
sudo dnf install -y jq openssl lsof git unzip zip curl
```

### Amazon Linux 2

```bash
sudo yum install -y jq openssl lsof git unzip zip curl

# AWS CLI v2 (replaces the preinstalled v1)
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
unzip awscliv2.zip && sudo ./aws/install --update
```

`unzip`, `zip`, and `curl` are needed by the SDKMAN installer.

### Java + Gradle via SDKMAN (recommended)

The launcher scripts source SDKMAN automatically if it's installed under
`$SDKMAN_DIR` (default `$HOME/.sdkman`), so a system-wide Java/Gradle install
is not required.

```bash
# Install SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Java 21 (Amazon Corretto build via SDKMAN)
sdk install java 21-amzn

# Gradle 9.5.0 (project version)
sdk install gradle 9.5
```

The scripts source `${SDKMAN_DIR}/bin/sdkman-init.sh` themselves, so a fresh
shell (including a cron-spawned shell that does **not** read `~/.bashrc`)
will see `java` and `gradle` once the above is done. You do not have to wire
SDKMAN into your shell rc for the scripts to work.

If SDKMAN lives somewhere other than `$HOME/.sdkman`, point `SDKMAN_DIR` at it
in the cron environment.

### Java + Gradle via system packages (alternative)

```bash
# Java 21 (Corretto)
sudo dnf install -y java-21-amazon-corretto-devel   # AL2023
# or, on AL2:
sudo amazon-linux-extras enable corretto21 && sudo yum install -y java-21-amazon-corretto-devel

# System Gradle (optional — ./gradlew works as a fallback)
curl -fsSL https://services.gradle.org/distributions/gradle-9.5-bin.zip -o /tmp/gradle.zip
sudo mkdir -p /opt/gradle && sudo unzip -d /opt/gradle /tmp/gradle.zip
echo 'export PATH=$PATH:/opt/gradle/gradle-9.5/bin' | sudo tee /etc/profile.d/gradle.sh
```

The project ships a Gradle wrapper (`./gradlew`). `startbackenddevaws.sh` calls
the system `gradle` if available; otherwise it falls back to `./gradlew`.

### Node.js via nvm (optional)

`startfrontenddevaws.sh` sources nvm (`$NVM_DIR/nvm.sh`, default
`$HOME/.nvm/nvm.sh`) when present, so a Node managed by nvm is visible under
cron without needing system Node installed.

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
. "$HOME/.nvm/nvm.sh"
nvm install 20
nvm alias default 20
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
| `SDKMAN_DIR`           | `$HOME/.sdkman` | SDKMAN install location (sourced by both scripts) |
| `NVM_DIR`              | `$HOME/.nvm` | nvm install location (sourced by `startfrontenddevaws.sh`) |

Examples:

```bash
# Custom secret in us-east-1
SECMAN_AWS_SECRET_ID=secman/staging AWS_REGION=us-east-1 ./scripts/startbackenddevaws.sh

# Local dev with a named profile
AWS_PROFILE=secman-dev ./scripts/startbackenddevaws.sh
```

## Running under cron

Both scripts are written to be cron-safe:

- They build their own `PATH` (`$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin`),
  so they don't depend on whatever PATH cron passed in.
- They source `${SDKMAN_DIR}/bin/sdkman-init.sh` if SDKMAN is installed, exposing
  the SDKMAN-managed `java` and `gradle` to the script — even though cron does
  not source `~/.bashrc` / `~/.profile`.
- `startfrontenddevaws.sh` additionally sources `${NVM_DIR}/nvm.sh` if nvm is
  installed.
- They `cd` to the project root using their own location, so the working
  directory cron starts in does not matter.
- AWS credentials come from the standard provider chain. On EC2 the instance
  profile is picked up automatically; otherwise set `AWS_PROFILE` (and
  `AWS_SHARED_CREDENTIALS_FILE` / `AWS_CONFIG_FILE` if the user running cron
  doesn't have `~/.aws/`).

### Example crontab entry

Run as the same user that owns the SDKMAN install (cron uses that user's
`$HOME`):

```cron
# m h dom mon dow command
@reboot /home/ec2-user/secman/scripts/startbackenddevaws.sh  >> /var/log/secman/backend.log 2>&1
@reboot /home/ec2-user/secman/scripts/startfrontenddevaws.sh >> /var/log/secman/frontend.log 2>&1
```

If you keep SDKMAN somewhere else, override the path explicitly:

```cron
SDKMAN_DIR=/opt/sdkman
NVM_DIR=/opt/nvm
AWS_REGION=eu-central-1
@reboot /home/ec2-user/secman/scripts/startbackenddevaws.sh >> /var/log/secman/backend.log 2>&1
```

### Verifying the cron environment

Cron is notorious for "works in shell, fails in cron". To reproduce cron's
minimal environment from an interactive shell and confirm the scripts still
run, use `env -i`:

```bash
env -i HOME="$HOME" PATH=/usr/bin:/bin /home/ec2-user/secman/scripts/startbackenddevaws.sh
```

If that succeeds, the same invocation will succeed under cron.

### systemd alternative

For a long-running service, prefer a systemd unit over `@reboot` cron — it
gives you proper restart-on-failure, log capture via `journalctl`, and graceful
shutdown. Sketch:

```ini
# /etc/systemd/system/secman-backend.service
[Unit]
Description=Secman backend (dev)
After=network-online.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/secman
Environment=AWS_REGION=eu-central-1
ExecStart=/home/ec2-user/secman/scripts/startbackenddevaws.sh
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Then `sudo systemctl enable --now secman-backend.service`. The script's
SDKMAN/PATH bootstrap also handles the empty environment systemd provides.

## Secret keys reference

The shared library (`scripts/lib/aws-secrets.sh::secman_aws_export_envfile`)
maps every JSON field below to its env-var name. Missing keys are skipped — so
optional fields (e.g. application-side AWS credentials when an EC2 instance
role is in use) can simply be omitted from the secret.

| JSON key in secret | Exported as | Used by |
|---|---|---|
| `DB_CONNECT` | `DB_CONNECT` | backend, CLI |
| `SECMAN_BACKEND_BASE_URL` | `SECMAN_BACKEND_URL`, `SECMAN_DOMAIN` | backend dev, frontend dev |
| `SECMAN_HOST` | `SECMAN_HOST` | frontend, test scripts |
| `SECMAN_SSL_ACCEPT_ALL` | `SECMAN_INSECURE` | backend, CLI |
| `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` / `SECMAN_ADMIN_EMAIL` | same names | backend bootstrap, CLI, E2E |
| `SECMAN_USER_NAME` / `SECMAN_USER_PASS` | same names | E2E test-user provisioning |
| `SECMAN_MCP_KEY` | `SECMAN_MCP_KEY` | backend MCP, MCP E2E |
| `SECMAN_DB_HOST` / `SECMAN_DB_PORT` / `SECMAN_DB_NAME` | same names | CLI (paths that build JDBC themselves) |
| `SECMAN_DB_USER` | `DB_USERNAME` | CLI |
| `SECMAN_DB_PASSWORD` | `DB_PASSWORD` | CLI |
| `FALCON_CLIENT_ID` / `FALCON_CLIENT_SECRET` / `FALCON_CLOUD_REGION` | same names | CrowdStrike import |
| `OPENROUTER_API_KEY` | `SECMAN_OPENROUTER_API_KEY` | backend translation |
| `SECMAN_AWS_ACCESS_KEY_ID` | `AWS_ACCESS_KEY_ID` | backend (optional override) |
| `SECMAN_AWS_SECRET_ACCESS_KEY` | `AWS_SECRET_ACCESS_KEY` | backend (optional override) |
| `SECMAN_AWS_ACCESS_TOKEN` | `AWS_SESSION_TOKEN` | backend (optional override) |

`JWT_SECRET` is generated fresh on every backend start (`openssl rand -base64 48`),
matching `startbackenddev.sh`.

## Usage

All AWS variants are invoked the same way as their pass-cli counterparts —
just substitute the file name. Examples:

```bash
# Dev launchers
./scripts/startbackenddevaws.sh
./scripts/startfrontenddevaws.sh

# Headless backend (no :clean) — useful for cron / systemd
./scripts/backendaws.sh

# CLI invocations
./scripts/secmancliaws.sh help
./scripts/secmancliaws.sh manage-user-mappings list-bucket --bucket my-bucket
./scripts/secmanngaws.sh export-requirements --format xlsx
./scripts/importaws.sh
./scripts/deleteoutdatedaws.sh

# Report-style env preset (sources the standard env block; useful for ad-hoc
# `java -jar` invocations on AWS hosts)
source ./scripts/secmanreportenvaws.sh
java -jar src/cli/build/libs/cli-0.1.0-all.jar <some-report-command>

# E2E tests
./scripts/e2e-testaws.sh
./scripts/release-e2e-testaws.sh
./scripts/test/provision-test-useraws.sh
./scripts/test/test-e2e-exception-workflowaws.sh
./scripts/test/test-e2e-vuln-exception-fullaws.sh
```

Every variant requires the CLI shadow JAR (`./gradlew :cli:shadowJar`) to be
built before first use, just like the pass-cli versions.

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

## Cron usage for any AWS variant

Every AWS variant inherits cron-safety from `scripts/lib/aws-secrets.sh`, so
each one can be scheduled directly. Sample crontab entries:

```cron
# Hourly CrowdStrike import
17 * * * * /home/ec2-user/secman/scripts/importaws.sh \
            >> /var/log/secman/import.log 2>&1

# Nightly stale-asset dry-run
0 3 * * *  /home/ec2-user/secman/scripts/deleteoutdatedaws.sh \
            >> /var/log/secman/deleteoutdated.log 2>&1

# Reboot launchers
@reboot    /home/ec2-user/secman/scripts/startbackenddevaws.sh  >> /var/log/secman/backend.log 2>&1
@reboot    /home/ec2-user/secman/scripts/startfrontenddevaws.sh >> /var/log/secman/frontend.log 2>&1
```

Tips:

- Run cron entries as the **same user that owns the SDKMAN install** — cron
  uses that user's `$HOME` to find `~/.sdkman/bin/sdkman-init.sh`.
- If SDKMAN lives elsewhere, put `SDKMAN_DIR=/opt/sdkman` (and similarly
  `NVM_DIR=...`) at the top of the crontab.
- Reproduce the cron environment locally before scheduling to confirm the
  bootstrap works:
  ```bash
  env -i HOME="$HOME" PATH=/usr/bin:/bin /home/ec2-user/secman/scripts/secmancliaws.sh help
  ```

## Extending the AWS variants

If you add a new pass-cli script, add a matching `*aws.sh` variant by sourcing
the shared lib:

```bash
#!/bin/bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"
secman_aws_export_envfile
cd "${PROJECT_ROOT}"
exec <your command>
```

If your script needs an env var that isn't in the table above, either add it
to `secman_aws_export_envfile` in `scripts/lib/aws-secrets.sh` (preferred —
keeps every script in sync) or call `secman_aws_export <ENV_VAR> <SECRET_KEY>`
directly from your launcher.

## Relationship to canonical workflow

- The `pass-cli` scripts remain canonical for local developer workflows
  (per `CLAUDE.md`). Tests, E2E runners, and the CLI wrapper continue to
  resolve secrets via pass-cli during day-to-day development.
- The AWS scripts target the deployed EC2 environment where pass-cli is
  unavailable but Secrets Manager + IAM are already in place.
- When you add new env vars to a pass-cli script, add the matching JSON key
  to `secman/dev`, extend `secman_aws_export_envfile`, and create / update
  the `*aws.sh` variant.
