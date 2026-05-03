# pass-cli (Proton Pass)

`pass-cli` is the **canonical** secret-resolution tool for this project (per `CLAUDE.md`). All scripts that need credentials must source them via `pass-cli`. Never hardcode secrets. Never reintroduce `op run` / 1Password.

> Earlier docs referred to 1Password CLI (`op`). That setup is deprecated. Existing references to `op://…` URIs in the codebase should be migrated to `pass-cli` lookups.

## Setup

1. Install [`pass-cli`](https://proton.me/pass) (Proton Pass CLI).
2. Authenticate: `pass-cli login`.
3. Verify access to the items below.

## Items used by this repo

### `test/secman` (primary)

| Field | Env var | Used by |
|---|---|---|
| `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` / `SECMAN_ADMIN_EMAIL` | same | backend dev, CLI, E2E |
| `SECMAN_USER_USER` / `SECMAN_USER_PASS` | same | E2E (Playwright) |
| `SECMAN_USER_NAME` / `SECMAN_USER_PASS` | same | `/e2ejs` normal-user run |
| `SECMAN_MCP_KEY` | same | backend, CLI, MCP/release E2E |
| `SECMAN_HOST` | `SECMAN_HOST` / `SECMAN_BACKEND_URL` | CLI, JS scanner, **all tests** |
| `SECMAN_BACKEND_BASE_URL` | `SECMAN_BACKEND_URL` / `SECMAN_DOMAIN` | backend dev, frontend dev |
| `DB_CONNECT` | same (full JDBC URL) | backend dev |
| `SECMAN_SSL_ACCEPT_ALL` | `SECMAN_INSECURE` | CLI, JS scanner (self-signed TLS) |
| `SECMAN_TEST_DOMAIN` | same | MCP E2E |
| `FALCON_CLIENT_ID` / `FALCON_CLIENT_SECRET` / `FALCON_CLOUD_REGION` | same | CLI `query servers` |
| `OPENROUTER_API_KEY` | `SECMAN_OPENROUTER_API_KEY` | backend (translation) |
| `SECMAN_AWS_ACCESS_KEY_ID` / `..._SECRET_ACCESS_KEY` / `..._ACCESS_TOKEN` | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` | backend, CLI |

### `test/secman-s3` (S3 testing)

| Field | Env var |
|---|---|
| `S3_TEST_BUCKET`, `S3_TEST_REGION` | same |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | same |

## Usage

### Canonical entry points
- Backend dev: `./scriptpp/startbackenddev.sh` (sets `MICRONAUT_ENVIRONMENTS=dev`, `SECMAN_DEBUG=true`, generates random `JWT_SECRET`, sources `pass-cli`).
- Frontend dev: `./scriptpp/startfrontenddev.sh`.
- CLI: `./scriptpp/secman <cmd>` or `./scriptpp/secmanng <cmd>`.
- E2E (Playwright): `./tests/e2e/run-e2e.sh`.

### Manual (no wrapper)
```bash
SECMAN_BASE_URL=http://localhost:4321 \
SECMAN_ADMIN_NAME=admin SECMAN_ADMIN_PASS=secret \
SECMAN_USER_USER=user SECMAN_USER_PASS=secret \
npx playwright test
```
> **Tests must never hardcode `localhost`.** Use `SECMAN_HOST` (resolved via `pass-cli`).

### Read a single secret
```bash
pass-cli read "test/secman/SECMAN_ADMIN_NAME"
```

## Scripts that consume `pass-cli`

| Script | Secrets |
|---|---|
| `scriptpp/backend` | full `secman.env` |
| `scriptpp/startbackenddev.sh` | `DB_CONNECT`, `SECMAN_BACKEND_BASE_URL` |
| `scriptpp/startfrontenddev.sh` | `SECMAN_BACKEND_BASE_URL`, `SECMAN_HOST` |
| `scriptpp/secmancli` | CrowdStrike + admin creds + AWS + MCP key + host |
| `scriptpp/secmanng` | admin creds, host, SSL setting |
| `scriptpp/secmanserverca`, `scriptpp/import.sh` | full `secman.env` |
| `tests/e2e/run-e2e.sh` | admin + user creds |
| `tests/js-error-scanner.sh` | admin creds, host, SSL setting |
| `tests/mcp-e2e-*-test.sh`, `tests/release-e2e-test.sh`, `tests/alignment-review-e2e-test.sh` | admin creds, MCP key, test domain |
| `tests/bulk-user-mapping-test.sh` | admin creds |
| `tests/s3-user-mapping-import-e2e-test.sh`, `tests/s3-list-bucket-e2e-test.sh` | `secman-s3` vault, admin email |

## Troubleshooting

| Symptom | Fix |
|---|---|
| `pass-cli: command not found` | install Proton Pass CLI; ensure on `PATH` |
| `not signed in` / `auth required` | `pass-cli login` |
| `could not find item` | verify access to `test/secman` and `test/secman-s3` items |
| Wrapper fails / want to bypass | export the required env var directly before invoking — most scripts use `${VAR:-pass-cli://…}` patterns |
