---
name: createtestdata
description: >
  Seed a complete SecMan test fixture using admin credentials from Proton Pass:
  a fresh test user, a test system, a vulnerability on that system, and an
  exception request filed by the test user scoped to that one system.
  Use this skill when the user says "create test data", "createtestdata",
  "seed a test user and vulnerability", "make me an exception request to play
  with", or similar.
context: fork
---
# Create Test Data — User, System, Vulnerability, Scoped Exception Request

> **Sync policy**: This file mirrors `.claude/skills/createtestdata/SKILL.md`,
> which is the **leading, authoritative** copy for this repo (see
> `CLAUDE.md` §"Tooling Conventions"). Whenever the Claude Code version
> changes, port the same change here, translating Claude-specific mechanics
> to their Codex equivalent (e.g. Bash tool `dangerouslyDisableSandbox: true`
> ↔ `sandbox_permissions: "require_escalated"`). Never let this file diverge
> ahead of the Claude Code version.

Seeds one self-contained fixture for manual/UI testing. Everything is created
through the REST API against `SECMAN_HOST`, with every credential resolved from
Proton Pass by `pass-cli`. **Nothing is ever deleted.**

## What Gets Created

| # | Object | Detail |
|---|---|---|
| 1 | Test user | `testdata-user-<timestamp>`, role **USER** only, password = `SECMAN_USER_PASS` |
| 2 | Test system | `testdata-host-<timestamp>`, type `Server`, **owner = the test user** |
| 3 | Vulnerability | `CVE-2021-44228`, `HIGH`, 45 days open, on that system |
| 4 | Exception request | Filed **by the test user**, `subject=CVE` × `scope=ASSET` → that one system, status `PENDING` |

Names are timestamped, so every run produces a fresh, non-colliding fixture.

## How to Run

```bash
./scripts/test/create-test-data.sh
```

The script re-execs itself under `pass-cli run`, so **run it outside any
sandbox**. In Codex, run this command with
`sandbox_permissions: "require_escalated"`. A sandboxed shell cannot reach
`pass-cli` and the script will fail at secret resolution.

Requires `jq`.

## Stack Handling (deliberate deviation)

Unlike the E2E runner skills, this skill does **not** force a cold restart.
It seeds data into whatever stack is already running, because a restart would
kill a session the user is actively testing in the browser.

1. Check liveness by port bind — `lsof -iTCP:8080 -sTCP:LISTEN -n -P` and `:4321`.
2. Only if a port is unbound, start that side with `./scripts/startbackenddev.sh`
   / `./scripts/startfrontenddev.sh`. These start commands must be executed
   outside the sandbox. Then wait for the port bind.
3. Never `curl localhost` — all traffic goes to `SECMAN_HOST` from Proton Pass.

## Invariants the Script Asserts

These are the ways this fixture silently goes wrong. The script fails loudly
on each rather than producing a subtly useless fixture.

- **`assetCreated == false`** after the `cli-add` call. `POST /api/vulnerabilities/cli-add`
  upserts by hostname; if it reports `true`, the vulnerability landed on a
  *second* asset and the exception is scoped to the wrong one.
- **`status == PENDING`** on the exception request. `createRequest` auto-approves
  for **ADMIN and SECCHAMPION**, so the requester must hold *only* `USER`.
  An auto-approved request is not "a user requesting an exception".

## API Constraints That Bite

| Constraint | Consequence if ignored |
|---|---|
| `reason` must be **50–2048 characters** | HTTP 400 |
| `scopeValue` must be **null** when `scope=ASSET` | HTTP 400 — the target comes from `assetId` |
| `expirationDate` must be a **future** `LocalDateTime` (`2026-08-30T12:00:00`) | HTTP 400 |
| `subject=ALL_VULNS` needs ADMIN/VULN | Not used here; `CVE` needs no extra role |
| Auth is a **HttpOnly cookie** from `POST /api/auth/login` | Bearer tokens do not work — use a cookie jar |

## Verifying the Result

The script prints all four IDs. To re-inspect the request as admin:

```
GET /api/vulnerability-exception-requests/{id}
```

Expect `subject: "CVE"`, `scope: "ASSET"`, `assetId` = the created system,
`autoApproved: false`, `status: "PENDING"`.

Then drive the UI: approve or reject at `${SECMAN_HOST}/exception-requests`
as admin, or log in as the test user to see the request under their own
requests.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `SECMAN_HOST not resolved by pass-cli` | Ran inside a sandbox, or `pass-cli` not unlocked |
| Login fails for the test user | Vault `SECMAN_USER_PASS` drifted from what was set at creation |
| `409` on the exception request | A duplicate active request exists — rerun for a fresh timestamped fixture |
| `403` on `/api/users` | Admin credentials in the vault lack ADMIN |

## What This Skill Does NOT Do

- No cleanup or teardown. Fixtures accumulate; delete them manually if needed.
- No approval of the exception request — leaving it `PENDING` is the point.
