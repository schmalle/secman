---
name: e2eeol
description: >
  Run the end-of-life (EOL) lifecycle E2E test covering every function the EOL
  feature added: the `secman eol-sync` catalogue download and matching scan, the
  `secman send-eol-notifications` owner mail, the asset-scoped findings/summary/
  per-system read APIs, the ADMIN/SECCHAMPION top-10 repository ranking, and the
  authorization negatives that prove a plain user cannot reach another tenant's
  rows or the admin verbs. Starts backend and frontend cold, runs the driver, and
  iteratively fixes failures in both layers. Use this skill when the user says
  "run eol e2e", "e2eeol", "test end of life", "test the EOL feature", "check the
  EOL sync", "does the EOL notification work", or similar.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.claude/skills/e2eeol/SKILL.md` are one skill kept in two harness
> trees — Codex reads this copy, Claude Code reads the other. Whichever copy
> an agent edits, the same change is ported to the other **in the same
> commit**; translate harness-specific mechanics rather than copying verbatim
> (e.g. `sandbox_permissions: "require_escalated"` ↔ Bash tool
> `dangerouslyDisableSandbox: true`). `.claude/skills/` is the tie-breaker
> when the two disagree — that is a conflict rule, not a licence to edit one
> side only. Verify with `./scripts/check-skill-sync.sh` (exit 0) before
> calling the change done. See `CLAUDE.md` §"Tooling Conventions" and
> `AGENTS.md` §Skills.

# End-of-Life Lifecycle E2E — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, executes
the EOL lifecycle E2E driver, and **iteratively fixes every failure** until the
driver passes or you have exhausted the retry budget.

> **Read `../_shared/stack-lifecycle.md` in full before touching the stack.** It
> defines the cold-start sequence, port-bind liveness, credentials, logging and
> the 5-iteration budget this skill assumes.

**This driver is non-destructive.** Everything it creates carries the
`e2e-eol-` prefix and is removed by cleanup, which runs both before the test
(unconditional, so leftovers from a crashed earlier run are cleared) and after
it via `trap EXIT`. It never deletes an asset, user or finding it did not create.

It *does* trigger a global EOL rescan, which rewrites the `eol_finding` table.
That table is derived data — rebuilt from the inventory on every sync — so this
is a refresh, not data loss. Say so if the user asks; do not describe it as
destructive, and do not skip it to "be safe", because the scan is the feature.

## What is under test

| # | Surface | Assertion |
|---|---|---|
| 1 | `POST /api/eol/catalog/sync` | catalogue download + scan succeeds; a re-scan is idempotent (finding count unchanged) |
| 2 | Matching | a seeded system with an ancient OS produces an `EOL` finding; a seeded system with a current LTS produces **none** |
| 3 | `GET /api/eol/findings` / `/summary` / `/assets/{id}` | 200, documented counters present, horizon reported |
| 4 | `GET /api/eol/repositories/top` | 200 for ADMIN, honours `limit`, clamps an oversized `limit`, ranks are dense 1..n |
| 5 | `POST /api/eol/notifications/send` | dry run sends nothing; `months` out of range is rejected **server-side** with 400 |
| 6 | CLI `eol-sync`, `send-eol-notifications` | help text documents the source and flags; contradictory flags rejected; both commands reach the backend |
| 7 | Authorization | a plain USER reads their own scope, sees **zero** admin-owned findings, gets 404 (not 403/200) for an out-of-scope asset id, and is denied the ranking, the sync and the notification run; anonymous access denied; an unknown `status` filter is rejected with 400 |

### The two assertions that matter most

- **The false-positive check** (row 2, second half). A matcher that flags
  everything passes every other assertion in this table. `e2e-eol-new-*` runs a
  current Ubuntu LTS and *must not* appear. If that one fails, the matcher is
  broken even if the counts look plausible.
- **The scoping check** (row 7). The admin-owned findings genuinely exist; the
  test user simply must not be able to reach them. A `0` there is only meaningful
  because the admin run in row 2 proved the rows are there. If row 2 skipped
  (empty catalogue), say so when reporting row 7 — it is a weaker result.

## Catalogue availability is an environment fact, not a failure

The upstream source (endoflife.date by default) is a live third party. When the
backend cannot reach it, the driver reports **SKIP**, not FAIL, on the
catalogue-dependent assertions, and the run can still pass.

Do not "fix" a skip by weakening an assertion, and do not chase it as a bug
without first checking whether the backend has egress at all. Two legitimate
responses:

- run with `--offline`, which scans against whatever catalogue is already stored;
- seed the catalogue once from a reachable host and re-run.

A run whose catalogue assertions all skipped is a **partial** result. Report it
that way — never as a clean pass.

## Running it

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-eol.sh --verbose
```

Flags: `--offline` (skip the download), `--skip-cli` (API-only), `--verbose`.

The driver needs the CLI jar for phase 6. Build it once if missing:

```bash
./gradlew :cli:shadowJar
```

If the jar is absent the driver downgrades to API-only coverage and says so —
that is a partial run, so build the jar rather than accepting it.

## Sequence

1. **Cold start.** Follow `../_shared/stack-lifecycle.md` exactly: stop both
   services unconditionally, confirm the ports are free, start both via the
   canonical scripts, wait for port-bind liveness (8080 / 4321).
2. **Build the CLI jar** if `src/cli/build/libs/cli-0.1.0-all.jar` is missing.
3. **Run the driver** with `--verbose`.
4. **Classify each failure** using the table below and fix it in source.
5. **Restart the backend** after any Kotlin change (frontend edits hot-reload).
6. **Re-run.** Maximum 5 iterations.

## Error classification

| Symptom | Layer | Likely cause |
|---|---|---|
| `HTTP 500` on `/api/eol/catalog/sync` | backend | `EolCatalogClient` threw outside the caught path, or the Micronaut bean graph is incomplete — check `.e2e-logs/backend.log` |
| Sync reports `PARTIAL` with "upstream" in the summary | environment | backend has no egress to the EOL source; use `--offline` |
| `HTTP 400 "EOL source host is not in secman.eol.allowed-hosts"` | config | `secman.eol.base-url` was changed without updating `secman.eol.allowed-hosts` — that pairing is deliberate (§A10), fix the config, never the check |
| Re-scan changed the finding count | backend | the replace-per-run cleanup in `EolScanService.deleteStaleFindings` is not removing prior-run rows |
| Current LTS reported as EOL | backend | `EolVersionMatcher` cycle resolution — almost always a prefix match that compares version segments as strings instead of whole segments |
| Ancient OS not reported | backend | alias resolution in `EolVersionMatcher.Index`, or the catalogue simply lacks the product |
| USER sees admin-owned findings | **backend, security** | `EolQueryService` bypassed `AccessibleAssetIdsCache` — this is an A01 finding, fix before anything else |
| Out-of-scope asset id returns 403 instead of 404 | backend | `EolController.findingsForAsset` is distinguishing "exists but denied" from "missing"; it must not |
| `months` out of range accepted | backend | validation exists only in the CLI; it must be enforced in `EolController` too |
| CLI phase fails with "Unknown command" | CLI | the command is not wired into `SecmanCli.execute` / the help map |

## Reporting

Report per-phase pass/skip/fail counts, then state plainly:

- whether the catalogue was reachable (and therefore whether rows 2's positive
  assertion actually ran);
- whether the CLI phase ran or was downgraded;
- every fix you applied, with the file and the reason.

Never report a pass while any assertion failed, and never report a clean pass
when catalogue assertions skipped — call that a partial run.
