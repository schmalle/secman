# AWS Secrets Manager: secret setup for `scripts/*aws.sh`

This guide is the step-by-step companion to [`docs/AWS.md`](AWS.md). It explains
how to create, populate, and grant access to the single AWS Secrets Manager
secret that every `scripts/*aws.sh` launcher depends on. Both flows are
covered:

- **Option A — AWS Management Console (web UI)**: click-by-click for operators
  who do not have local AWS CLI credentials, or who prefer the browser.
- **Option B — AWS CLI**: scriptable, repeatable, suitable for cron-style
  provisioning. The same flow as in `docs/AWS.md`, repeated here for symmetry.

> Once the secret exists, no script needs to be re-edited. The launchers fetch
> it via the standard AWS credential provider chain (instance profile on EC2,
> `AWS_PROFILE` or static keys elsewhere) and call
> `scripts/lib/aws-secrets.sh::secman_aws_export_envfile` to map JSON keys to
> the env vars that `pass-cli run --env-file secmanpp.env` normally provides.

## TL;DR

1. Create a Secrets Manager secret named **`secman/dev`** in region
   **`eu-central-1`** (override via `SECMAN_AWS_SECRET_ID` / `AWS_REGION`).
2. Store a flat JSON object — the schema is fixed by `secman_aws_export_envfile`
   in `scripts/lib/aws-secrets.sh`. See [Secret schema](#secret-schema) below.
3. Grant the principal that runs the scripts `secretsmanager:GetSecretValue`
   and `secretsmanager:DescribeSecret` on that secret's ARN. If the secret uses
   a customer-managed KMS key, also grant `kms:Decrypt` on the key.
4. Verify with `aws secretsmanager get-secret-value --secret-id secman/dev`
   and finally with `./scripts/startbackenddevaws.sh`.

---

## Secret schema

The secret value is a **single flat JSON object**. Keys mirror the field names
used by `pass-cli` under `test/secman` (see [`docs/PASS_CLI.md`](PASS_CLI.md)).
Missing keys are skipped at runtime — every entry below is technically optional
but the *Required for* column tells you what breaks if you omit it.

| JSON key                       | Required for                                       | Notes                                                                 |
|--------------------------------|----------------------------------------------------|-----------------------------------------------------------------------|
| `DB_CONNECT`                   | backend, CLI                                       | Full JDBC URL, e.g. `jdbc:mariadb://localhost:3306/secman?useSSL=false` |
| `SECMAN_BACKEND_BASE_URL`      | backend dev, frontend dev                          | Exposed as both `SECMAN_BACKEND_URL` and `SECMAN_DOMAIN`              |
| `SECMAN_HOST`                  | frontend, every E2E / test script                  | E.g. `https://secman.example.com`                                     |
| `SECMAN_SSL_ACCEPT_ALL`        | backend, CLI                                       | `true` to disable cert verification in dev/test                        |
| `SECMAN_ADMIN_NAME`            | bootstrap admin, CLI, E2E                          | Bootstrapped on first backend start                                   |
| `SECMAN_ADMIN_PASS`            | bootstrap admin, CLI, E2E                          | Same as above                                                         |
| `SECMAN_ADMIN_EMAIL`           | bootstrap admin                                    | Used for notifications and as the audit identity                       |
| `SECMAN_USER_NAME`             | E2E test-user provisioning                         | Optional for prod use                                                  |
| `SECMAN_USER_PASS`             | E2E test-user provisioning                         | Optional for prod use                                                  |
| `SECMAN_MCP_KEY`               | backend MCP, MCP E2E                               | Bearer key for `X-MCP-User-Email` flows                                |
| `SECMAN_DB_HOST`               | CLI paths that build JDBC themselves               | Host only, no port                                                     |
| `SECMAN_DB_PORT`               | CLI paths that build JDBC themselves               | Numeric                                                                |
| `SECMAN_DB_NAME`               | CLI paths that build JDBC themselves               | Database name                                                          |
| `SECMAN_DB_USER`               | CLI                                                | Exported as `DB_USERNAME`                                              |
| `SECMAN_DB_PASSWORD`           | CLI                                                | Exported as `DB_PASSWORD`                                              |
| `FALCON_CLIENT_ID`             | CrowdStrike import                                 | Optional unless you run `importaws.sh`                                 |
| `FALCON_CLIENT_SECRET`         | CrowdStrike import                                 | Optional unless you run `importaws.sh`                                 |
| `FALCON_CLOUD_REGION`          | CrowdStrike import                                 | E.g. `us-1`, `eu-1`                                                    |
| `OPENROUTER_API_KEY`           | AI risk-assessment, translation                    | Exported as `SECMAN_OPENROUTER_API_KEY`                                |
| `SECMAN_AWS_ACCESS_KEY_ID`     | backend (optional override)                        | **Leave empty on EC2** — instance role wins                            |
| `SECMAN_AWS_SECRET_ACCESS_KEY` | backend (optional override)                        | Same as above                                                          |
| `SECMAN_AWS_ACCESS_TOKEN`      | backend (optional override)                        | Exported as `AWS_SESSION_TOKEN`                                        |

`JWT_SECRET` is **not** stored in the secret — `startbackenddevaws.sh`
regenerates it on every start (`openssl rand -base64 48`), matching the
pass-cli flow.

### Template

Save this as `secman-dev.json` and fill in real values. Empty strings are
acceptable for optional keys — they will be skipped at export time.

```json
{
  "DB_CONNECT": "jdbc:mariadb://localhost:3306/secman?useSSL=false",
  "SECMAN_BACKEND_BASE_URL": "https://secman.example.com",
  "SECMAN_HOST": "https://secman.example.com",
  "SECMAN_SSL_ACCEPT_ALL": "false",

  "SECMAN_ADMIN_NAME": "admin",
  "SECMAN_ADMIN_PASS": "REPLACE-ME",
  "SECMAN_ADMIN_EMAIL": "admin@example.com",

  "SECMAN_USER_NAME": "testuser",
  "SECMAN_USER_PASS": "REPLACE-ME",

  "SECMAN_MCP_KEY": "REPLACE-ME",

  "SECMAN_DB_HOST": "localhost",
  "SECMAN_DB_PORT": "3306",
  "SECMAN_DB_NAME": "secman",
  "SECMAN_DB_USER": "secman",
  "SECMAN_DB_PASSWORD": "REPLACE-ME",

  "FALCON_CLIENT_ID": "",
  "FALCON_CLIENT_SECRET": "",
  "FALCON_CLOUD_REGION": "us-1",

  "OPENROUTER_API_KEY": "",

  "SECMAN_AWS_ACCESS_KEY_ID": "",
  "SECMAN_AWS_SECRET_ACCESS_KEY": "",
  "SECMAN_AWS_ACCESS_TOKEN": ""
}
```

---

## Prerequisites

You need:

- An AWS account with permission to create Secrets Manager secrets and IAM
  policies / roles. Typical managed policies that suffice for setup are
  `SecretsManagerReadWrite` plus `IAMFullAccess` (for the operator doing the
  setup — *not* for the principal that runs the scripts).
- A target region. Defaults assumed in this guide: **`eu-central-1`**.
- The Secrets Manager secret name. Default: **`secman/dev`** (override with
  `SECMAN_AWS_SECRET_ID`).
- The IAM principal that will run the scripts identified up front:
  - On EC2: the **instance role** attached to the host.
  - Locally: an IAM user, an SSO role, or a named profile in `~/.aws/credentials`.
  - In CI: the role assumed by the CI runner.

You do **not** need the principal to have AWS CLI credentials at setup time — only
at runtime.

---

## Option A — AWS Management Console (web UI)

Time required: ~5 minutes.

### Step 1 — Open Secrets Manager in the correct region

1. Sign in to the [AWS Management Console](https://console.aws.amazon.com/).
2. Use the region switcher (top-right corner of the console header) and pick
   the region you want the secret to live in — **`Europe (Frankfurt) eu-central-1`**
   by default. The region matters: `secman_aws_load_secret` resolves the
   region from `AWS_REGION` / `AWS_DEFAULT_REGION` (default `eu-central-1`),
   and a wrong region will yield `ResourceNotFoundException`.
3. In the search bar, type **Secrets Manager** and open the service.

### Step 2 — Start a new secret

1. Click **Store a new secret** (top-right of the Secrets list).
2. Under **Secret type**, pick **Other type of secret**. Do *not* pick the
   database-specific types — secman stores its own flat JSON, not the
   RDS-flavored credential schema.

### Step 3 — Paste the JSON payload

1. Locate the **Key/value pairs** card and switch the inner tab to
   **Plaintext** (it's the second tab, next to **Key/value**).
2. Paste the [template](#template) JSON, with all `REPLACE-ME` placeholders
   filled in. Make sure the document is a single flat object (no nested keys).
3. Under **Encryption key**, leave the default **`aws/secretsmanager`** AWS-managed
   key unless your org mandates a customer-managed KMS key (CMK). If you pick
   a CMK, write down its ARN — you'll need it in [IAM permissions](#iam-permissions).
4. Click **Next**.

### Step 4 — Name, description, tags

1. **Secret name** — type the name exactly: `secman/dev`. (Forward slashes are
   allowed and the launchers use this as the default.) If you want a different
   name, export `SECMAN_AWS_SECRET_ID` later when you run the scripts.
2. **Description** — suggested: `Secman backend + frontend dev secrets`.
3. **Tags** (optional but recommended for billing/lifecycle):
   - `app = secman`
   - `env = dev` (or `staging`, `prod`)
   - `owner = <team>`
4. **Resource permissions** — leave the resource policy empty unless you
   intend to do cross-account access. Identity-based IAM policies (covered
   below) are sufficient for the single-account case.
5. **Replicate secret** — leave **disabled** for dev. Enable per-region
   replication only if the application runs in multiple regions and you want
   read-locality.
6. Click **Next**.

### Step 5 — Rotation

Leave **Automatic rotation: Disable automatic rotation** selected. These are
application/admin credentials, not RDS-managed ones; rotating them invalidates
the backend bootstrap. If you later want rotation, write a Lambda that updates
the JSON value and the upstream services in lockstep — out of scope here.

Click **Next**.

### Step 6 — Review and store

1. Sanity-check the secret name, region, and the keys list. Do not screenshot
   the **Secret value** preview unless your screen-recording is muted.
2. Click **Store**.
3. You should land on the secret detail page. Confirm:
   - **Secret ARN** shape: `arn:aws:secretsmanager:eu-central-1:<account-id>:secret:secman/dev-XXXXXX`
   - **Encryption key**: `aws/secretsmanager` (or the CMK you picked).
4. Click **Retrieve secret value → Plaintext** to verify the JSON is intact.

### Step 7 — Grant the runner principal read access (console UI)

Choose the path that matches your runtime.

#### 7a. EC2 instance role (recommended for deployed hosts)

1. In the console, open **IAM → Roles** and pick the role attached to the
   EC2 instance that will run the scripts. If no role is attached, attach one
   first (EC2 → Instances → select instance → Actions → Security → Modify IAM role).
2. On the role's **Permissions** tab, click **Add permissions → Create inline
   policy**.
3. Switch to the **JSON** tab and paste the
   [least-privilege policy](#iam-permissions) below, replacing `<region>`,
   `<account>`, and (if relevant) `<kms-key-arn>`.
4. **Review policy** → name it `secman-secrets-read` → **Create policy**.

#### 7b. IAM user / SSO permission set (local dev or named profile)

1. **IAM → Users** (or **IAM Identity Center → Permission sets** for SSO).
2. Add the same inline JSON policy from below to the user, group, or
   permission set that the operator uses.
3. If using a named profile, confirm
   `aws sts get-caller-identity --profile <name>` returns the right principal.

#### 7c. CI runner role

1. Identify the role your CI assumes. For GitHub Actions, this is usually the
   role you configured via `aws-actions/configure-aws-credentials`.
2. Attach the same inline policy to that role.

### Step 8 — Verify from the console

On the secret's detail page:

1. Click **Retrieve secret value** and confirm the JSON renders correctly.
2. If the page shows `AccessDeniedException`, the operator's own session does
   not have read on the secret — check the [IAM policy](#iam-permissions),
   or add `SecretsManagerReadWrite` to your operator role temporarily.

You're done — proceed to [Verification with the scripts](#verification).

### Updating values via the console

When a secret value changes (rotating an admin password, swapping an API key):

1. Open **Secrets Manager → `secman/dev`**.
2. **Retrieve secret value → Edit**.
3. Edit the JSON in the **Plaintext** tab, then **Save**.
4. Restart the affected services (`./scripts/stopbackenddev.sh` then
   `./scripts/startbackenddevaws.sh`) so the new value is loaded.

> Secrets Manager keeps **version history**. Use **Versions** on the secret's
> detail page to roll back to a previous JSON if an edit breaks the backend.

---

## Option B — AWS CLI

This path is identical to the one in `docs/AWS.md` — it lives here too so the
two flows sit side-by-side.

### B1. Create the secret

```bash
aws secretsmanager create-secret \
  --name secman/dev \
  --description "Secman backend + frontend dev secrets" \
  --secret-string file://secman-dev.json \
  --region eu-central-1
```

Capture the returned `ARN` — you'll paste it into the IAM policy.

### B2. Update an existing secret

```bash
aws secretsmanager put-secret-value \
  --secret-id secman/dev \
  --secret-string file://secman-dev.json \
  --region eu-central-1
```

### B3. Attach the IAM policy

Save the policy below as `secman-secrets-read.json` (substitute `<region>`,
`<account>`, optional `<kms-key-arn>`):

```bash
aws iam put-role-policy \
  --role-name <runner-role-name> \
  --policy-name secman-secrets-read \
  --policy-document file://secman-secrets-read.json
```

For a user instead of a role, use `aws iam put-user-policy --user-name ...`.

### B4. Seeding from pass-cli (one-time migration)

```bash
jq -n \
  --arg DB_CONNECT              "$(pass-cli read 'test/secman/DB_CONNECT')" \
  --arg SECMAN_BACKEND_BASE_URL "$(pass-cli read 'test/secman/SECMAN_BACKEND_BASE_URL')" \
  --arg SECMAN_HOST             "$(pass-cli read 'test/secman/SECMAN_HOST')" \
  --arg SECMAN_SSL_ACCEPT_ALL   "$(pass-cli read 'test/secman/SECMAN_SSL_ACCEPT_ALL')" \
  --arg SECMAN_ADMIN_NAME       "$(pass-cli read 'test/secman/SECMAN_ADMIN_NAME')" \
  --arg SECMAN_ADMIN_PASS       "$(pass-cli read 'test/secman/SECMAN_ADMIN_PASS')" \
  --arg SECMAN_ADMIN_EMAIL      "$(pass-cli read 'test/secman/SECMAN_ADMIN_EMAIL')" \
  --arg SECMAN_USER_NAME        "$(pass-cli read 'test/secman/SECMAN_USER_NAME')" \
  --arg SECMAN_USER_PASS        "$(pass-cli read 'test/secman/SECMAN_USER_PASS')" \
  --arg SECMAN_MCP_KEY          "$(pass-cli read 'test/secman/SECMAN_MCP_KEY')" \
  --arg FALCON_CLIENT_ID        "$(pass-cli read 'test/secman/FALCON_CLIENT_ID')" \
  --arg FALCON_CLIENT_SECRET    "$(pass-cli read 'test/secman/FALCON_CLIENT_SECRET')" \
  --arg FALCON_CLOUD_REGION     "$(pass-cli read 'test/secman/FALCON_CLOUD_REGION')" \
  --arg OPENROUTER_API_KEY      "$(pass-cli read 'test/secman/OPENROUTER_API_KEY')" \
  '{$DB_CONNECT, $SECMAN_BACKEND_BASE_URL, $SECMAN_HOST, $SECMAN_SSL_ACCEPT_ALL,
    $SECMAN_ADMIN_NAME, $SECMAN_ADMIN_PASS, $SECMAN_ADMIN_EMAIL,
    $SECMAN_USER_NAME, $SECMAN_USER_PASS, $SECMAN_MCP_KEY,
    $FALCON_CLIENT_ID, $FALCON_CLIENT_SECRET, $FALCON_CLOUD_REGION,
    $OPENROUTER_API_KEY}' \
  > secman-dev.json

aws secretsmanager create-secret \
  --name secman/dev \
  --secret-string file://secman-dev.json \
  --region eu-central-1

shred -u secman-dev.json   # do not leave the plaintext on disk
```

---

## IAM permissions

Minimum policy for the principal that runs the `*aws.sh` scripts. Replace
placeholders with real values from the secret's ARN.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadSecmanSecret",
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

The trailing `-*` matters: Secrets Manager appends a random 6-character suffix
to every secret's ARN; the wildcard lets the policy keep matching if you ever
recreate the secret.

### With a customer-managed KMS key

If the secret is encrypted with a CMK rather than the default
`aws/secretsmanager` key, add a second statement:

```json
{
  "Sid": "DecryptSecmanSecret",
  "Effect": "Allow",
  "Action": "kms:Decrypt",
  "Resource": "<kms-key-arn>",
  "Condition": {
    "StringEquals": {
      "kms:ViaService": "secretsmanager.<region>.amazonaws.com"
    }
  }
}
```

The `kms:ViaService` condition restricts the decrypt grant to calls *through*
Secrets Manager — the principal cannot use the KMS key for any other purpose.

### Multi-environment

If you split dev / staging / prod into separate secrets (`secman/dev`,
`secman/staging`, `secman/prod`), broaden the `Resource` to a prefix match:

```json
"Resource": "arn:aws:secretsmanager:<region>:<account>:secret:secman/*"
```

Or grant each environment's runner role read on only its own secret.

---

## Verification

### 1. From the console / CLI

```bash
aws secretsmanager get-secret-value \
  --secret-id secman/dev \
  --region eu-central-1 \
  --query SecretString --output text | jq 'keys'
```

You should see the list of keys from your template. If `jq` errors with
`parse error`, the secret is not valid JSON — re-edit it.

### 2. From the launcher

```bash
./scripts/startbackenddevaws.sh
```

The first log line should be:

```
[aws-secrets] Fetching secret 'secman/dev' from AWS Secrets Manager (region eu-central-1)...
```

followed by a normal Micronaut startup. If you see:

- `Unable to locate credentials` → the runtime principal has no AWS
  credentials. Attach an instance role, run `aws configure`, or set
  `AWS_PROFILE` before the script.
- `AccessDeniedException` → the principal has credentials but no read on
  the secret. Re-check the [IAM policy](#iam-permissions).
- `ResourceNotFoundException` → wrong name or wrong region. Set
  `SECMAN_AWS_SECRET_ID` and `AWS_REGION` explicitly.

When the backend is up, stop it with `./scripts/stopbackenddev.sh`. Per
`CLAUDE.md`, a change is complete only when both `./gradlew build` and a clean
backend start succeed.

---

## Troubleshooting

| Symptom                                              | Cause                                                  | Fix                                                                                            |
|------------------------------------------------------|--------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `aws: command not found`                             | AWS CLI v2 not installed                               | Follow the install steps in `docs/AWS.md → Required tools on Amazon Linux`.                    |
| `jq: command not found`                              | `jq` missing                                           | `sudo dnf install -y jq` (AL2023) / `sudo yum install -y jq` (AL2).                            |
| `ResourceNotFoundException`                          | Wrong name or wrong region                             | `SECMAN_AWS_SECRET_ID=secman/dev AWS_REGION=eu-central-1 ./scripts/startbackenddevaws.sh`     |
| `AccessDeniedException ... GetSecretValue`           | IAM policy missing or scoped to a different ARN        | Re-attach the policy in [IAM permissions](#iam-permissions); confirm the ARN's `-*` wildcard.  |
| `AccessDeniedException ... kms:Decrypt`              | CMK is used but `kms:Decrypt` was not granted          | Add the `DecryptSecmanSecret` statement above.                                                 |
| Backend starts but Flyway / DB fails                 | `DB_CONNECT` points at an unreachable MariaDB          | Verify the JDBC URL is reachable from the host.                                                |
| Backend starts but admin login fails                 | Missing or wrong `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` | Check the secret JSON; backend bootstrap reads these on first start only.                    |
| `parse error: Invalid numeric literal` from `jq`     | The secret value is not valid JSON                     | Re-edit the secret; `secret-string` must be a single JSON object.                              |
| Cron entry runs interactively but fails under cron   | `~/.bashrc` / SDKMAN not sourced by cron               | The scripts handle this — but if SDKMAN lives outside `$HOME/.sdkman`, set `SDKMAN_DIR=...` in the crontab. See `docs/AWS.md → Running under cron`. |

---

## Related docs

- [`docs/AWS.md`](AWS.md) — launcher catalog, cron / systemd patterns, full
  schema reference, and per-script usage.
- [`docs/PASS_CLI.md`](PASS_CLI.md) — canonical pass-cli flow that the AWS
  variants mirror.
- [`docs/ENVIRONMENT.md`](ENVIRONMENT.md) — every env var the backend reads,
  including the OAuth-state retry knobs.
- [`scripts/lib/aws-secrets.sh`](../scripts/lib/aws-secrets.sh) — the exact
  JSON-key → env-var mapping. Treat this file as the source of truth; this
  doc and `docs/AWS.md` describe it.
