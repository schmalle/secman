# adread — Azure AD → secman Workgroup Import

`src/adread/read.py` reads Azure AD groups whose `displayName` starts with `AWS-`
(case-insensitive) and, optionally, creates matching workgroups in secman with the AD
group members assigned.

## Overview

| Mode | Command | Effect |
|---|---|---|
| Read-only (default) | `uv run python read.py` | Print AD groups + members to stdout. No secman calls. |
| Import | `uv run python read.py --import` | Read AD groups **and** create/update secman workgroups. |
| Dry run | `uv run python read.py --import --dry-run` | Log what would be created/assigned; no writes. |

## Prerequisites

- Python 3.11+ with [`uv`](https://docs.astral.sh/uv/)
- An Azure service principal with `Group.Read.All` and `GroupMember.Read.All` Graph API permissions
- A secman **ADMIN** account (cross-domain user creation requires ADMIN)

## Environment Variables

### Azure AD (always required)

| Var | Description |
|---|---|
| `AZURE_TENANT_ID` | Azure AD tenant ID |
| `AZURE_CLIENT_ID` | Service principal (app registration) client ID |
| `AZURE_CLIENT_SECRET` | Service principal secret |

### secman backend (required with `--import`)

| Var | Default | Description |
|---|---|---|
| `SECMAN_BACKEND_URL` | — | Backend URL, e.g. `http://localhost:8080` |
| `SECMAN_ADMIN_NAME` | — | secman username with ADMIN role |
| `SECMAN_ADMIN_PASS` | — | Password for the above account |

Optional:

| Var | Default | Description |
|---|---|---|
| `LOG_LEVEL` | `INFO` | Standard log level (`DEBUG`, `INFO`, `WARNING`, `ERROR`) |

## Behaviour

- **Group filter:** AD groups are fetched with a server-side `startswith` filter on `AWS-`, `aws-`, and `Aws-`.
- **Workgroup naming:** The AD group `displayName` is used verbatim as the secman workgroup name. Names longer than 100 characters are skipped with a warning.
- **Sync mode:** Additive only. Members are added; no members are ever removed. Re-runs are safe and idempotent.
- **Lazy user creation:** If an AD member's email does not exist in secman, a User row is created automatically (username derived from the email prefix, roles set to `USER, VULN, REQ`). The ADMIN account is required for this to work across email domains.
- **Error handling:** If one group fails (HTTP error, name too long, etc.) the run continues with the remaining groups and exits non-zero when done so cron/CI notices.

## Running

### With Proton Pass (canonical)

```bash
./src/adread/import-workgroups.sh              # real import
./src/adread/import-workgroups.sh --dry-run    # dry run
```

Secrets are injected via `pass-cli` using `src/adread/adread.env`. All six entries
must exist in Proton Pass under `Test/SECMAN/`:

```
AZURE_TENANT_ID
AZURE_CLIENT_ID
AZURE_CLIENT_SECRET
SECMAN_BACKEND_URL    # or a literal URL in adread.env if the same for all environments
SECMAN_ADMIN_NAME
SECMAN_ADMIN_PASS
```

### Without Proton Pass

```bash
cp src/adread/adread.env.local.example src/adread/adread.env.local
# edit adread.env.local with real values
./src/adread/import-workgroups-noproton.sh              # real import
./src/adread/import-workgroups-noproton.sh --dry-run    # dry run
```

`adread.env.local` is gitignored — never commit it. Alternatively, export the six vars
into your shell before calling the script.

### Direct invocation

```bash
cd src/adread
export AZURE_TENANT_ID=…  AZURE_CLIENT_ID=…  AZURE_CLIENT_SECRET=…
# read-only (no secman vars needed):
uv run python read.py

# import:
export SECMAN_BACKEND_URL=http://localhost:8080 SECMAN_ADMIN_NAME=admin SECMAN_ADMIN_PASS=…
uv run python read.py --import
uv run python read.py --import --dry-run
```

## File Layout

```
src/adread/
├── read.py                         # main script
├── pyproject.toml                  # uv project metadata
├── import-workgroups.sh            # Proton Pass launcher
├── import-workgroups-noproton.sh   # plain-env launcher
├── adread.env                      # Proton Pass secret references (committed)
├── adread.env.local.example        # template for plain-env secrets (committed)
└── adread.env.local                # actual plain-env secrets (gitignored)
```

## Logging

Set `LOG_LEVEL=DEBUG` to see full Graph API request/response detail (bearer tokens
are redacted). Audit events are logged at `INFO`:

```
AUDIT: operation=CREATE_WORKGROUP, name='AWS-Foo-Admins', id=42
AUDIT: operation=ADD_MEMBERS, workgroup_id=42, count=5
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `KeyError: 'AZURE_TENANT_ID'` | Azure env vars not set | Export the three `AZURE_*` vars (or use the launcher scripts) |
| `Login failed: HTTP 401` | Wrong admin credentials | Check `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| Warning: user does not have ADMIN role | Non-admin account used | Use an account with the ADMIN role; cross-domain lazy-create won't work otherwise |
| `Workgroup name '...' exceeds 100-char limit` | AD group name too long | Rename the AD group, or exclude it |
| `ModuleNotFoundError: No module named 'requests'` | Dependencies not installed | Run `uv sync` in `src/adread/`, or use `uv run` which installs automatically |
