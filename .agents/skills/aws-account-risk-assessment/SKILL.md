---
name: aws-account-risk-assessment
description: >
  Run the end-to-end test for auto-starting a risk assessment when an AWS
  account-mapping import introduces an account SecMan has never seen. Seeds a
  SECCHAMPION, an owner user, a use case, tagged requirements and an ACTIVE
  requirements release; imports a brand-new 12-digit account via the CLI and
  again via MCP; asserts that nothing starts without the opt-in flag, that the
  assessment is pinned to the ACTIVE release, that its questionnaire is exactly
  that release's use-case-tagged requirements and does not drift when more
  requirements are imported, that re-import is idempotent and reports the skip
  as a skip rather than a failure, and that a missing ACTIVE release or an
  out-of-range deadline is rejected up front on both surfaces. Cleans up before
  and after, including leftovers from earlier runs. Use this skill when the user
  says "aws account risk assessment", "run the new account risk assessment e2e",
  "test new aws account assessment", or similar.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and `.claude/skills/aws-
> account-risk-assessment/SKILL.md` are one skill kept in two harness trees —
> Codex reads this copy, Claude Code reads the other. Whichever copy an agent
> edits, the same change is ported to the other **in the same commit**;
> translate harness-specific mechanics rather than copying verbatim (e.g.
> `sandbox_permissions: "require_escalated"` ↔ Bash tool
> `dangerouslyDisableSandbox: true`). `.claude/skills/` is the tie-breaker
> when the two disagree — that is a conflict rule, not a licence to edit one
> side only. Verify with `./scripts/check-skill-sync.sh` (exit 0) before
> calling the change done. See `CLAUDE.md` §"Tooling Conventions" and
> `AGENTS.md` §Skills.

# AWS Account Risk Assessment — Iterative Fix Loop

You are an orchestration agent. Bring up the stack, run the CLI + MCP driver, and
**iteratively fix every failure** until it passes or the retry budget is spent.

**Start tool calls immediately.** Do not pre-read anything under `docs/` — load
`docs/AWS_ACCOUNT_RISK_ASSESSMENT.md` only when a specific failure needs it.

## High-Level Loop

```
0. Kill any running backend/frontend via the stop scripts — always,
   even if ports look free (never `kill`).
1. Start backend  (./scripts/startbackenddev.sh outside the sandbox / with escalated permissions)
2. Start frontend (./scripts/startfrontenddev.sh outside the sandbox / with escalated permissions)
3. Wait for both ports to be BOUND (port binding, not HTTP)
4. Run driver:
     pass-cli run --env-file ./secmanpp.env -- \
       ./scripts/test/test-e2e-aws-account-risk-assessment.sh --verbose \
       2>&1 | tee .e2e-logs/e2e-aws-account-ra-run-<N>.log
5. `Failed: 0` → done.
6. Failure → stop services, classify, fix, restart, re-run (max 5 iterations).
```

**CRITICAL**: on any failure, **stop both services BEFORE editing**, then
**restart both** before retrying. Never edit code while services are running.

## Phase 1 — Start services

Pinned to Proton Pass. Never hardcode `localhost:8080` / `localhost:4321`; the
driver reads `BASE_URL` / `SECMAN_BACKEND_URL`.

| Setting            | Default                                    |
|--------------------|--------------------------------------------|
| Stop first         | `./scripts/stopbackenddev.sh`, `./scripts/stopfrontenddev.sh` (unconditional, safe no-ops) |
| Backend start      | `./scripts/startbackenddev.sh`             |
| Backend port wait  | `lsof -iTCP:8080 -sTCP:LISTEN -n -P`, 120s  |
| Frontend start     | `./scripts/startfrontenddev.sh`            |
| Frontend port wait | `lsof -iTCP:4321 -sTCP:LISTEN -n -P`, 60s   |

**Outside-sandbox requirement:** always start both dev scripts outside the
sandbox (`sandbox_permissions: "require_escalated"`). Both source secrets via
`pass-cli`, which a sandboxed shell cannot reach.

The driver builds the CLI jar itself if `src/cli/build/libs/cli-0.1.0-all.jar` is
missing, but building it up front (`./gradlew :cli:shadowJar`) makes the first run
faster. **Backend Kotlin changes require `./gradlew :cli:shadowJar` only when you
touched `src/cli/`; backend changes always require a backend restart.**

## Phase 2 — Run the driver

```bash
mkdir -p .e2e-logs
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-aws-account-risk-assessment.sh --verbose \
  2>&1 | tee .e2e-logs/e2e-aws-account-ra-run-1.log
```

Required env (all via `pass-cli`): `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`,
`SECMAN_ADMIN_EMAIL`, `SECMAN_MCP_KEY`, and `BASE_URL` / `SECMAN_BACKEND_URL`.

Useful flags: `--skip-mcp` (CLI only), `--skip-cli` (MCP only).

Success is `Failed: 0` in the summary block. `[WARN]` lines are not failures.

## Phase 2.5 — Classify the failure

| Symptom | Likely cause | Where to look |
|---|---|---|
| `no tracked risk assessment found` | assessment never started | `service/AwsAccountRiskAssessmentService.kt` `startAssessmentsForNewAccounts`; check backend log for the per-pair error |
| `expected release <v>, got ''` | pinning not applied | `createAssessment` — `lockedRelease` / `isReleaseLocked` / `contentSnapshotTaken` |
| `Questionnaire mismatch` | wrong requirement scoping | `service/ReleaseRequirementScopeService.kt`, `controller/ResponseController.kt` `getRequirementsForAssessment` |
| `Questionnaire drifted` | pinning not honoured on read | `getRequirementsForAssessment` is falling through to the unpinned branch |
| `Expected 1 tracked assessment, found 2` | idempotency guard broken | `createAssessment` open-assessment check |
| `a skip must not fail the run` / `rendered the skip as a failure` | the skip is coming back in `error` instead of `skipped` | `createAssessment` idempotency branch, and the `ra.skipped` arm in `cli/commands/ImportCommand.kt` / `ImportS3Command.kt` |
| `No risk assessment started (opt-in default holds)` fails | the flag is not actually gating the side effect | `service/UserMappingBulkImportService.kt` `execute` — the `request.startRiskAssessment && !request.dryRun` guard |
| `CLI accepted a 100000-day deadline` | upper bound missing | `validateRiskAssessmentOptions` (CLI) and `validateStartRequest` `MAX_DEADLINE_DAYS` (backend) |
| `MCP did not reject a 100000-day deadline` | CLI-only bound; the backend is the real boundary | `AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS` |
| MCP JSON-RPC `error` on `import_user_mappings` | tool not authorized on the streamable transport | `mcp/McpToolPermissions.kt` `CALLING` — **this map is separate from the `LISTING` map used by `tools/list`, and a missing entry silently denies** |
| `ADMIN_REQUIRED` where admin expected | delegation email wrong, or key lacks the permission | `SECMAN_MCP_KEY` needs `USER_ACTIVITY` and `ASSESSMENTS_READ` |
| `CLI accepted the import despite no ACTIVE release` | validation gap | `validateStartRequest` |
| `Could not create release` HTTP 4xx | version collides, or admin lacks ADMIN/REQADMIN | `controller/ReleaseController.kt` |
| Non-admin negative fails | roles wrong on the seeded user | setup phase in the driver |

Backend errors: `tail -200 .e2e-logs/backend.log` (or wherever the start script
logs) and look for stack traces inside the run window.

## Phase 3 — Stop, Fix, Restart

1. `./scripts/stopbackenddev.sh` and `./scripts/stopfrontenddev.sh`.
2. Fix in source. **Backend before frontend.** For a frontend edit, `cd
   src/frontend && npm ci && npm run build` must exit 0 before restarting.
3. Restart both via the canonical start scripts, outside the sandbox / with escalated permissions.
4. Re-run the driver, incrementing the log suffix.
5. Guard rails: max **5** iterations; if the same assertion fails twice with the
   same message, stop and report rather than trying a third variation.

## Phase 4 — Teardown & Report

The driver cleans up **before** the run and again on `EXIT`: users, use case,
requirements, release, mappings, tracked assessments and the `AWS_ACCOUNT` basis
assets the feature auto-creates. Stop both services when done.

Cleanup is keyed on the stable `e2e-awsra-` owner prefix rather than on this run's
timestamped account ids, so leftovers from an earlier interrupted run are swept up
too. If you add a phase, give its account the same `88[4-9]<6-digit stamp>000`
shape the asset sweep matches — an account outside that shape leaves its asset
behind for good.

Report a table: phase, assertion, result, and for anything still failing the file
and the reason.

## Constraints

- Never commit or push.
- Never `curl localhost` — go through `BASE_URL` from `pass-cli`.
- Never `kill` a dev server; use the stop scripts.
- The driver is destructive **only** within its own `e2e-awsra-` prefix and the
  synthetic account IDs it mints. It must never delete other releases; if the
  environment already has an unrelated ACTIVE release, the no-ACTIVE-release
  negative is skipped with a `[WARN]` — that is expected, not a failure.
- Never widen a cleanup filter to a bare substring. Matching assessments on
  `contains("886")`, as an earlier version did, deletes real assessments for any
  genuine AWS account whose id happens to contain those digits.
- Creating a release snapshots the **entire** requirement corpus. On a large
  environment the setup phase is slower than it looks; do not treat that as a hang.
