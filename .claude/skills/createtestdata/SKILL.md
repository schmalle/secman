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

> **Sync policy (two-way, mandatory)**: This file and
> `.agents/skills/createtestdata/SKILL.md` are one skill kept in two harness
> trees — Claude Code reads this copy, Codex reads the other. Whichever copy
> an agent edits, the same change is ported to the other **in the same
> commit**; translate harness-specific mechanics rather than copying verbatim
> (e.g. Bash tool `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions:
> "require_escalated"`). `.claude/skills/` is the tie-breaker when the two
> disagree — that is a conflict rule, not a licence to edit one side only.
> Verify with `./scripts/check-skill-sync.sh` (exit 0) before calling the
> change done. See `CLAUDE.md` §"Tooling Conventions" and `AGENTS.md` §Skills.

# Create Test Data — User, System, Vulnerability, Scoped Exception Request

Seeds one self-contained fixture for manual/UI testing. Everything is created
through the REST API against `SECMAN_HOST`, with every credential resolved from
Proton Pass by `pass-cli`.

> **Read `../_shared/stack-lifecycle.md` first** for the credential, host-URL and
> logging rules. **Section 1 (cold start) deliberately does not apply here** —
> see "Stack Handling" below.

**Nothing is ever deleted, and fixtures accumulate.** Every run adds a user, a
system, a vulnerability and an exception request to whatever database
`SECMAN_HOST` points at, with no upper bound. That is the intended behaviour for
manual testing, but it means this skill should not be run in a loop or as part of
an automated harness — delete old fixtures by hand when they pile up.

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
sandbox** (Bash tool `dangerouslyDisableSandbox: true`). A sandboxed shell
cannot reach `pass-cli` and the script will fail at secret resolution.

Requires `jq`.

## Stack Handling (deliberate deviation from the shared contract)

The shared contract makes cold start mandatory. **This skill is the documented
exception**: it seeds data into whatever stack is already running, because
restarting would kill the browser session the user is about to test the fixture
in. The reason the rule exists — never test against a stale build — does not
apply, because this skill writes data through the API rather than exercising code.

1. Check liveness by port bind — `lsof -iTCP:8080 -sTCP:LISTEN -n -P` and `:4321`.
2. Only if a port is unbound, start that side with `./scripts/startbackenddev.sh`
   / `./scripts/startfrontenddev.sh` (outside the sandbox), then wait for the bind.
   Shared contract §4 applies here: if `:4321` never binds, check `:4322` before
   concluding the frontend failed.
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

## Success criteria and what to report

The script runs under `set -euo pipefail` and asserts the invariants above
itself, so **exit 0 is the pass condition** — a non-zero exit means the fixture
is unusable, not merely imperfect.

Report back, in the final message:

- the four IDs (user, system, vulnerability, exception request) and the
  timestamped names, so the user can find them in the UI
- the `SECMAN_HOST` the fixture landed in — not the value, the fact that it
  resolved, since seeding the wrong instance is the expensive mistake here
- the direct URL to drive it: `${SECMAN_HOST}/exception-requests`

If the script failed, say at which step and quote the assertion that fired.
"Something went wrong" costs the user a full re-read of the script output.

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

| Symptom | Cause | Fix |
|---|---|---|
| `SECMAN_HOST not resolved by pass-cli` | Ran inside a sandbox, or `pass-cli` not unlocked | Re-run outside the sandbox; ask the user to unlock Proton Pass |
| Login fails for the test user | Vault `SECMAN_USER_PASS` drifted from what was set at creation | Report the drift to the user — do not guess a password. This is a vault problem, not a code problem |
| Login fails because the account does not exist | The normal-user account was never provisioned on this instance | `./scripts/test/provision-test-user.sh` (idempotent, exits 0 if present) |
| `409` on the exception request | A duplicate active request exists | Rerun — names are timestamped, so a fresh run collides with nothing |
| `403` on `/api/users` | Admin credentials in the vault lack ADMIN | Report to the user; the fixture cannot be seeded without ADMIN |

## What This Skill Does NOT Do

- No cleanup or teardown — see the accumulation warning at the top.
- No approval of the exception request — leaving it `PENDING` is the point.
- No cold restart — see "Stack Handling".
