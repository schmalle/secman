# adread — Workgroup Import and AWS Asset Synchronization

`src/adread/read.py` reads Azure AD groups whose `displayName` starts with `AWS-`
(case-insensitive) and, optionally, creates matching workgroups in secman with the AD
group owner stored and members assigned.

Its `sync-workgroup-assets` command uses data already stored in SecMan to assign
AWS assets through each workgroup's canonical AD owner email. This mode needs no
Azure or AWS credentials. See [the synchronization guide](WORKGROUP_ASSET_SYNC.md)
for the complete data flow, statistics and removal limitations.

## Overview

| Mode | Command | Effect |
|---|---|---|
| Read-only (default) | `uv run python read.py` | Print AD groups + members to stdout. No secman calls. |
| Import | `uv run python read.py --import` | Read AD groups **and** create/update secman workgroups. |
| Dry run | `uv run python read.py --import --dry-run` | Log what would be created/assigned; no writes. |
| AWS asset sync | `uv run --locked python read.py sync-workgroup-assets` | Add missing asset/workgroup links from stored email/account mappings. |
| AWS asset preview | `uv run --locked python read.py sync-workgroup-assets --dry-run` | Read SecMan and report the complete plan without assigning assets. |

The table's direct commands run from `src/adread/`. From the repository root,
use `./scripts/sync-workgroup-assets.sh [--dry-run]` for asset synchronization.
For production secrets in AWS Secrets Manager, use
`./scripts/sync-workgroup-assets-aws.sh [--dry-run]` with an explicit
`SECMAN_AWS_SECRET_ID`; see [AWS configuration](WORKGROUP_ASSET_SYNC.md#aws-secrets-manager-for-production).

## Prerequisites

- Python 3.11+ with [`uv`](https://docs.astral.sh/uv/)
- For AD read/import: an Azure service principal with `Group.Read.All`, `GroupMember.Read.All`, and `User.Read.All` Graph application permissions
- For SecMan imports or asset synchronization: a secman **ADMIN** account
- For the canonical synchronization wrapper: `pass-cli` installed and authenticated,
  and an HTTPS backend whose hostname-valid certificate is trusted by the operating system
- For the AWS synchronization wrapper: AWS CLI, `jq`, permission to read the
  selected secret, and the same trusted HTTPS backend

## Environment Variables

### Azure AD (AD read/import only)

| Var | Description |
|---|---|
| `AZURE_TENANT_ID` | Azure AD tenant ID |
| `AZURE_CLIENT_ID` | Service principal (app registration) client ID |
| `AZURE_CLIENT_SECRET` | Service principal secret |

### secman backend (required with `--import` or `sync-workgroup-assets`)

| Var | Default | Description |
|---|---|---|
| `SECMAN_BACKEND_URL` | — | Backend URL; asset synchronization requires HTTPS |
| `SECMAN_ADMIN_NAME` | — | secman username with ADMIN role |
| `SECMAN_ADMIN_PASS` | — | Password for the above account |

Optional:

| Var | Default | Description |
|---|---|---|
| `LOG_LEVEL` | `INFO` | Standard log level (`DEBUG`, `INFO`, `WARNING`, `ERROR`) |
| `REQUESTS_CA_BUNDLE` | Python's default trust store | PEM CA bundle for direct or AWS-wrapper connections to a private backend certificate |
| `SECMAN_INSECURE` | unset | Legacy AD import option; a true value is rejected by asset synchronization |

## AD read/import behaviour

- **Group filter:** AD groups are fetched with a server-side `startswith` filter on `AWS-`, `aws-`, and `Aws-`.
- **Workgroup naming:** The AD group `displayName` is used verbatim as the secman workgroup name. Names longer than 100 characters are skipped with a warning.
- **Canonical owner:** The importer reads user owners from Microsoft Graph and stores the normalized `mail` value, falling back to `userPrincipalName`. A group is skipped unless it has exactly one user owner with a valid email address.
- **Membership import:** Additive only. Members are added; no members are ever removed.
- **Lazy user creation:** If an AD member's email does not exist in secman, a User row is created automatically (username derived from the email prefix, roles set to `USER, VULN, REQ`). The ADMIN account is required for this to work across email domains.
- **Error handling:** If one group fails (HTTP error, name too long, etc.) the run continues with the remaining groups and exits non-zero when done so cron/CI notices.

## Running

### Synchronize assets from stored AWS ownership

Run these commands from the repository root after importing AD memberships,
AWS owner mappings and CrowdStrike assets:

```bash
./scripts/sync-workgroup-assets.sh --dry-run
./scripts/sync-workgroup-assets.sh
./scripts/sync-workgroup-assets.sh --dry-run
```

With unchanged source data, the final preview reports the same replacement counts:
existing desired links are removed and re-added.
All enabled workgroups are evaluated, including names without an `AWS-` prefix. Disabled
workgroups and workgroups without a canonical owner are ignored. Only the stored
owner email participates. For every participating workgroup, all existing asset
links—including manual links—are removed before the owner's desired AWS assets are added.

The wrapper resolves the three SecMan variables from Proton Pass and uses the
operating system certificate store; it does not load the Azure settings or
accept `--import`/`--insecure` for this command. Direct invocations can select
the same trust source with `--use-system-ca`.
See [configuration and exit codes](WORKGROUP_ASSET_SYNC.md#reconciliation-and-diagnostics).

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
├── sync_workgroup_assets.py        # AWS owner-email reconciliation
├── pyproject.toml                  # uv project metadata
├── import-workgroups.sh            # Proton Pass launcher
├── import-workgroups-noproton.sh   # plain-env launcher
├── adread.env                      # Proton Pass secret references (committed)
├── adread.env.local.example        # template for plain-env secrets (committed)
└── adread.env.local                # actual plain-env secrets (gitignored)
```

The synchronization launchers are `scripts/sync-workgroup-assets.sh` (Proton
Pass) and `scripts/sync-workgroup-assets-aws.sh` (AWS Secrets Manager).

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
