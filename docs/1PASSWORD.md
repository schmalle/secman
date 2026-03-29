# 1Password Credentials

secman uses [1Password CLI](https://developer.1password.com/docs/cli/) (`op`) to inject secrets at runtime. All secret references use the `op://` URI format and are resolved by `op run` before reaching the application.

## Prerequisites

1. Install the [1Password CLI](https://developer.1password.com/docs/cli/get-started/)
2. Sign in: `op signin`
3. Ensure access to the required vaults listed below

## Vaults

### `test/secman` (Primary)

| 1Password Field | Environment Variable | Description | Used By |
|---|---|---|---|
| `SECMAN_ADMIN_NAME` | `SECMAN_ADMIN_NAME` | Admin username for authentication | Backend, CLI, E2E tests |
| `SECMAN_ADMIN_PASS` | `SECMAN_ADMIN_PASS` | Admin password | Backend, CLI, E2E tests |
| `SECMAN_ADMIN_EMAIL` | `SECMAN_ADMIN_EMAIL` | Admin email address | Backend, CLI, S3 import tests |
| `SECMAN_USER_USER` | `SECMAN_USER_USER` | Regular user username | E2E tests (Playwright) |
| `SECMAN_USER_PASS` | `SECMAN_USER_PASS` | Regular user password | E2E tests (Playwright) |
| `API_KEY` | `API_KEY` | MCP API key | Backend, CLI |
| `SECMAN_API_KEY` | `SECMAN_API_KEY` | API key (alternative reference) | MCP E2E tests, release tests |
| `SECMAN_HOST` | `SECMAN_HOST` / `SECMAN_BACKEND_URL` | Backend server URL | CLI, JS error scanner |
| `SECMAN_SSL_ACCEPT_ALL` | `SECMAN_INSECURE` | Accept self-signed SSL certificates | CLI, JS error scanner |
| `SECMAN_TEST_DOMAIN` | `SECMAN_TEST_DOMAIN` | Test domain for MCP tests | MCP E2E tests |
| `FALCON_CLIENT_ID` | `FALCON_CLIENT_ID` | CrowdStrike Falcon API client ID | CLI (query servers) |
| `FALCON_CLIENT_SECRET` | `FALCON_CLIENT_SECRET` | CrowdStrike Falcon API client secret | CLI (query servers) |
| `FALCON_CLOUD_REGION` | `FALCON_CLOUD_REGION` | CrowdStrike cloud region (e.g. `eu-1`) | CLI (query servers) |
| `OPENROUTER_API_KEY` | `SECMAN_OPENROUTER_API_KEY` | OpenRouter API key for AI features | Backend |
| `SECMAN_AWS_ACCESS_KEY_ID` | `AWS_ACCESS_KEY_ID` | AWS access key for general operations | Backend, CLI |
| `SECMAN_AWS_SECRET_ACCESS_KEY` | `AWS_SECRET_ACCESS_KEY` | AWS secret key | Backend, CLI |
| `SECMAN_AWS_ACCESS_TOKEN` | `AWS_SESSION_TOKEN` | AWS session token (temporary credentials) | Backend, CLI |

### `test/secman-s3` (S3 Testing)

| 1Password Field | Environment Variable | Description | Used By |
|---|---|---|---|
| `S3_TEST_BUCKET` | `S3_TEST_BUCKET` | S3 bucket name for import tests | S3 E2E tests |
| `S3_TEST_REGION` | `S3_TEST_REGION` | AWS region for the test bucket | S3 E2E tests |
| `AWS_ACCESS_KEY_ID` | `AWS_ACCESS_KEY_ID` | AWS access key for S3 operations | S3 E2E tests |
| `AWS_SECRET_ACCESS_KEY` | `AWS_SECRET_ACCESS_KEY` | AWS secret key for S3 operations | S3 E2E tests |

## Environment File

The central env file is `secman.env` in the repository root. It maps 1Password references to environment variables:

```
FALCON_CLIENT_ID=op://test/secman/FALCON_CLIENT_ID
FALCON_CLIENT_SECRET=op://test/secman/FALCON_CLIENT_SECRET
FALCON_CLOUD_REGION=op://test/secman/FALCON_CLOUD_REGION
SECMAN_OPENROUTER_API_KEY=op://test/secman/OPENROUTER_API_KEY
SECMAN_ADMIN_NAME=op://test/secman/SECMAN_ADMIN_NAME
SECMAN_ADMIN_PASS=op://test/secman/SECMAN_ADMIN_PASS
API_KEY=op://test/secman/API_KEY
SECMAN_ADMIN_EMAIL=op://test/secman/SECMAN_ADMIN_EMAIL
AWS_ACCESS_KEY_ID=op://test/secman/SECMAN_AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY=op://test/secman/SECMAN_AWS_SECRET_ACCESS_KEY
AWS_SESSION_TOKEN=op://test/secman/SECMAN_AWS_ACCESS_TOKEN
SECMAN_HOST=op://test/secman/SECMAN_HOST
```

## Usage Patterns

### Run backend with secrets injected

```bash
op run --env-file ./secman.env -- gradle :backendng:run
# or use the convenience script:
./bin/backend
```

### Run CLI commands

```bash
./bin/secmancli query servers --dry-run
```

The `bin/secmancli` script exports all `op://` references and wraps the command with `op run`.

### Run E2E tests (Playwright)

```bash
./tests/e2e/run-e2e.sh
```

This script checks for `op` availability and injects `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`, `SECMAN_USER_USER`, and `SECMAN_USER_PASS` before running Playwright.

### Run E2E tests manually (without 1Password)

```bash
cd tests/e2e
SECMAN_BASE_URL=http://localhost:4321 \
SECMAN_ADMIN_NAME=admin \
SECMAN_ADMIN_PASS=secret \
SECMAN_USER_USER=user \
SECMAN_USER_PASS=secret \
npx playwright test
```

### Read a single secret

```bash
op read "op://test/secman/SECMAN_ADMIN_NAME"
```

## Scripts Using 1Password

| Script | Secrets Required |
|---|---|
| `bin/backend` | All from `secman.env` |
| `bin/secmancli` | CrowdStrike, admin creds, AWS, API key, host |
| `bin/secmanng` | Admin creds, host, SSL setting |
| `bin/secmanserverca` | All from `secman.env` |
| `scripts/import.sh` | All from `secman.env` |
| `tests/e2e/run-e2e.sh` | Admin + user creds |
| `tests/js-error-scanner.sh` | Admin creds, host, SSL setting |
| `tests/mcp-e2e-workgroup-test.sh` | Admin creds, API key, test domain |
| `tests/mcp-e2e-default-roles-test.sh` | Admin creds, API key, test domain |
| `tests/release-e2e-test.sh` | Admin creds, API key |
| `tests/alignment-review-e2e-test.sh` | Admin creds, API key |
| `tests/bulk-user-mapping-test.sh` | Admin creds |
| `tests/s3-user-mapping-import-e2e-test.sh` | S3 config (secman-s3 vault), admin email |
| `tests/s3-list-bucket-e2e-test.sh` | S3 config (secman-s3 vault) |
| `scripts/release-e2e-test.sh` | Admin creds, API key |

## Troubleshooting

**`op` not found** - Install the 1Password CLI and ensure it is on your `PATH`.

**"not signed in"** - Run `op signin` to authenticate.

**"could not find item"** - Verify you have access to the `test` vault and the `secman` / `secman-s3` items exist.

**Fallback without 1Password** - Several scripts support setting environment variables directly as a fallback (using `${VAR:-op://...}` syntax). Export the required variables before running the script to bypass 1Password resolution.
