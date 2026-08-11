---
name: account-onboarding
description: >
  Run the end-to-end test for the welcome-mail and guided-questionnaire
  onboarding of newly discovered AWS accounts. Seeds a SECCHAMPION, an owner, two
  use cases, tagged requirements, an ACTIVE requirements release, three
  onboarding questions and four rules; imports brand-new 12-digit accounts in
  WELCOME_ONLY, DIRECT and GUIDED mode via the CLI and again via MCP; walks the
  owner's tokenized questionnaire link; and asserts that several matching rules
  union into one assessment, that a replayed, unknown or malformed token is
  refused with a byte-identical body, that a burst of lookups is rate limited,
  that answers matching nothing are recorded without consuming the link, that a
  dry run mints no token and sends no mail, and that a bare
  --start-risk-assessment still behaves exactly as it did before onboarding modes
  existed. Cleans up before and after, including leftovers from earlier runs. Use
  this skill when the user says "account onboarding", "run the onboarding e2e",
  "welcome email test", "guided assessment", "onboarding rules", "options to use
  cases", or similar.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and `.agents/skills/account-
> onboarding/SKILL.md` are one skill kept in two harness trees — Claude Code
> reads this copy, Codex reads the other. Whichever copy an agent edits, the same
> change is ported to the other **in the same commit**; translate
> harness-specific mechanics rather than copying verbatim (e.g. Bash tool
> `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions:
> "require_escalated"`). `.claude/skills/` is the tie-breaker when the two
> disagree — that is a conflict rule, not a licence to edit one side only.
> Verify with `./scripts/check-skill-sync.sh` (exit 0) before calling the
> change done. See `CLAUDE.md` §"Tooling Conventions" and `AGENTS.md` §Skills.

# Account Onboarding — Iterative Fix Loop

You are an orchestration agent. Bring up the stack, run the CLI + MCP + public
questionnaire driver, and **iteratively fix every failure** until it passes or the
retry budget is spent.

**Start tool calls immediately.** Do not pre-read anything under `docs/` — load
`docs/ACCOUNT_ONBOARDING.md` only when a specific failure needs it.

## High-Level Loop

```
0. Kill any running backend/frontend via the stop scripts — always,
   even if ports look free (never `kill`).
1. Start backend  (./scripts/startbackenddev.sh outside the sandbox)
2. Start frontend (./scripts/startfrontenddev.sh outside the sandbox)
3. Wait for both ports to be BOUND (port binding, not HTTP)
4. Run driver:
     pass-cli run --env-file ./secmanpp.env -- \
       ./scripts/test/test-e2e-account-onboarding.sh --verbose \
       2>&1 | tee .e2e-logs/e2e-account-onboarding-run-<N>.log
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
sandbox (Bash tool `dangerouslyDisableSandbox: true`). Both source secrets via
`pass-cli`, which a sandboxed shell cannot reach.

Build the CLI jar first (`./gradlew :cli:shadowJar`) — the driver refuses to start
without `src/cli/build/libs/cli-0.1.0-all.jar`. Backend changes always require a
backend restart; rebuild the jar only when you touched `src/cli/`.

**The backend log matters here more than in other skills.** An invite token is a
credential, so no API, CLI printout or MCP result ever returns one — several
assertions check exactly that. The only place the full token legitimately appears
is the questionnaire URL inside the rendered mail, which lands in
`.e2e-logs/backend.log`. If that file is not where the driver looks, the
owner-flow phases report `[WARN]` and are skipped; point `SECMAN_BACKEND_LOG` at
the real path rather than treating the skip as a pass.

## Phase 2 — Run the driver

```bash
mkdir -p .e2e-logs
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-account-onboarding.sh --verbose \
  2>&1 | tee .e2e-logs/e2e-account-onboarding-run-1.log
```

Required env (all via `pass-cli`): `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`,
`SECMAN_ADMIN_EMAIL`, `SECMAN_MCP_KEY`, and `BASE_URL` / `SECMAN_BACKEND_URL`.

Useful flags: `--skip-mcp` (CLI only), `--skip-cli` (MCP only).

Success is `Failed: 0` in the summary block. `[WARN]` lines are not failures.

## Phase 2.5 — Classify the failure

| Symptom | Likely cause | Where to look |
|---|---|---|
| `a bare --start-risk-assessment sent a welcome mail` | **backward-compatibility regression — stop and fix before anything else** | `domain/AccountOnboardingMode.kt` `resolve`, and `planFrom`'s `sendWelcomeEmail ?: (explicitMode != null)` in `service/AccountOnboardingService.kt` |
| `expected 1 assessment for <legacy account>, found 0` | the legacy flag no longer reaches the assessment path | `service/UserMappingBulkImportService.kt` `execute` → `onboardNewAccounts`, DIRECT branch |
| `no questionnaire invite reported` | the GUIDED branch never minted one | `AccountOnboardingService.onboardGuided` — check the idempotency guard did not skip it |
| `expected 0 assessments for <guided account>` | GUIDED created an assessment before the owner answered | `onboardGuided` must only mint an invite; creation happens in `submitAnswers` |
| `the full account id leaked` / `the owner email leaked` | the public GET is returning too much | `controller/AccountOnboardingPublicController.getQuestionnaire` — only `maskedAccountId`, expiry and questions |
| `expected union '<a,b>', got '<a>'` | the matcher is not unioning | `service/AccountOnboardingRuleMatcher.resolve` — every matching rule contributes; `priorityOrder` decides nothing |
| `expected 4 requirements` | the questionnaire is not the union | `ReleaseRequirementScopeService.requirementsForRelease(releaseId, useCaseIds)` — the **plural** overload |
| `replay returned 200` | single-use is broken | `AccountOnboardingInviteRepository.claim` must be a guarded UPDATE **claimed before** `createAssessment` |
| `bodies differ` on unknown vs used | an enumeration oracle | every token failure must return `refuse()`'s single body in `AccountOnboardingPublicController` |
| `30 rapid lookups were never rate limited` | limiter not wired or bucket too loose | `service/AccountOnboardingRateLimiter.kt`, and the `FAILED_LOOKUP` call inside `refuse()` |
| `the link was consumed by a submission that resolved to nothing` | the claim happens too early | `submitAnswers` must resolve **and** check the questionnaire is non-empty *before* claiming |
| `expected 409, got 200` on unmatched answers | a fallback rule is still active, or the matcher invented one | the driver deactivates `e2e-onb-rule-default` for that phase; check it took effect |
| `dry run printed a 64-hex value` | a dry run minted a token | `onboardGuided`'s `if (dryRun)` branch must return before `createInvite` |
| MCP JSON-RPC `error` on an onboarding tool | tool not authorized on the streamable transport | `mcp/McpToolPermissions.kt` `CALLING` — **a separate map from `LISTING`, and `ToolCategories.CATEGORY_PERMISSIONS` is folded in LAST and overwrites** |
| `expected a role refusal` but it succeeded | guard missing, which fails **open** | the `requireAnyRole(context, "ADMIN", "SECCHAMPION", …)` preamble in each tool's `execute()` |
| `expected 403 for a plain USER` | controller annotation missing | `@Secured("ADMIN", "SECCHAMPION")` on `AccountOnboardingController` |
| `expected 400 for a comma-bearing address` | the recipient boundary was bypassed | `util/EmailAddressValidator.isValidRecipient` — one shared copy, never a fourth |
| `expected 400 for an unmatchable rule` | the single-select clash check is gone | `AccountOnboardingController.applyRule` |
| `expected exit 2 for the incompatible combination` | CLI pre-flight missing | `ImportCommand.validateOnboardingOptions` |
| `Could not create questions` HTTP 4xx | the admin API rejected the seed | `AccountOnboardingController.validateQuestion` — keys are lowercase kebab |

Backend errors: `tail -200 .e2e-logs/backend.log` and look for stack traces inside
the run window.

## Phase 3 — Stop, Fix, Restart

1. `./scripts/stopbackenddev.sh` and `./scripts/stopfrontenddev.sh`.
2. Fix in source. **Backend before frontend.** For a frontend edit, `cd
   src/frontend && npm ci && npm run build` must exit 0 before restarting.
3. Restart both via the canonical start scripts, outside the sandbox.
4. Re-run the driver, incrementing the log suffix.
5. Guard rails: max **5** iterations; if the same assertion fails twice with the
   same message, stop and report rather than trying a third variation.

## Phase 4 — Teardown & Report

The driver cleans up **before** the run and again on `EXIT`: users, use cases,
requirements, release, mappings, onboarding rules and questions, tracked
assessments and the `AWS_ACCOUNT` basis assets the feature auto-creates. Rules are
deleted before questions, because the API deliberately refuses to delete a
question a rule still references. Stop both services when done.

Cleanup is keyed on the stable `e2e-onb-` prefix rather than on this run's
timestamped account ids, so leftovers from an earlier interrupted run are swept up
too. If you add a phase, give its account the same `87[0-9]<6-digit stamp>000`
shape the asset sweep matches — an account outside that shape leaves its asset
behind for good.

Report a table: phase, assertion, result, and for anything still failing the file
and the reason. Say explicitly if the owner-flow phases were skipped for want of a
backend log — that is a gap in coverage, not a pass.

## Constraints

- Never commit or push.
- Never `curl localhost` — go through `BASE_URL` from `pass-cli`.
- Never `kill` a dev server; use the stop scripts.
- **Never print a full invite token**, and never "fix" a failing assertion by
  making an API return one. The assertions that no 64-hex value appears in the
  CLI printout, the MCP result or a dry run are the point, not an obstacle.
- The driver is destructive **only** within its own `e2e-onb-` prefix and the
  synthetic account IDs it mints. Never widen a cleanup filter to a bare
  substring: matching on `contains("870")` would delete real assessments for any
  genuine AWS account whose id happens to contain those digits.
- The rate-limit phase deliberately makes ~30 rapid requests. Do not "fix" a
  subsequent 429 elsewhere by raising the limits — wait for the window to roll.
- Creating a release snapshots the **entire** requirement corpus. On a large
  environment the setup phase is slower than it looks; do not treat that as a hang.
