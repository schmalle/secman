---
name: testsuite
description: >
  Run and repair secman's fast test tier — the part that does not need a running
  stack: backend unit + integration tests (Gradle, external MariaDB), CLI tests,
  frontend unit tests (node:test), the frontend `npm ci && npm run build` gate,
  the `.claude`/`.agents` skill-sync check, and the self-tests for the OWASP
  and code-hygiene gates — then report name-reference
  coverage per area via `./scripts/test-coverage-report.sh` and name the biggest
  gaps. Fixes failures it finds, looping up to 5 iterations. Use this skill when
  the user says "run the tests", "run the test suite", "testsuite", "unit tests",
  "are the tests green", "test coverage", "check coverage", "where are we
  untested", "gradle test", "npm test", or asks what still needs test coverage.
  This is the fast tier only — it does not replace the mandatory `/e2ejs` and
  `/e2evulnexception` gates, which need a running stack.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.agents/skills/testsuite/SKILL.md` are one skill kept in two harness trees
> — Claude Code reads this copy, Codex reads the other. Whichever copy an
> agent edits, the same change is ported to the other **in the same commit**;
> translate harness-specific mechanics rather than copying verbatim (e.g. Bash
> tool `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions:
> "require_escalated"`). `.claude/skills/` is the tie-breaker when the two
> disagree — that is a conflict rule, not a licence to edit one side only.
> Verify with `./scripts/check-skill-sync.sh` (exit 0) before calling the
> change done. See `CLAUDE.md` §"Tooling Conventions" and `AGENTS.md` §Skills.

# Test suite — fast tier run, repair, and coverage evaluation

You run every test in this repo that does **not** need a running backend and
frontend, fix what fails, and then tell the user where the coverage actually is.

The fast tier is worth having its own skill because the two mandatory E2E gates
(`/e2ejs`, `/e2evulnexception`) are slow, need `pass-cli`, and answer a different
question. They prove the assembled system works. This tier proves the units do,
and it is where a coverage gap is visible at all.

## Scope fence

**In scope:** `./gradlew :backendng:test`, `./gradlew :cli:test`, the frontend
unit tests and build gate, `./scripts/check-skill-sync.sh`,
`./scripts/test/owasp-check-test.sh`, `./scripts/test/humanizer-scan-test.sh`,
and `./scripts/test-coverage-report.sh`.

**Out of scope:** starting the backend or frontend, `/e2ejs`,
`/e2evulnexception`, Playwright under `tests/e2e/`, and anything that imports
data. Do not start the stack. The one interaction with the running stack is the
opposite: you must **stop** it (step 1), because the integration tests bind 8080.

If the work being tested touched `src/`, close your report by reminding the user
that CLAUDE.md's gates — `./gradlew build` clean, a clean
`./scripts/startbackenddev.sh`, `/e2ejs`, `/e2evulnexception` — still apply. Do
not run them here.

---

## Step 1 — Free port 8080

Integration tests start a Micronaut context on 8080. A dev backend already bound
there makes the whole suite hang on a `BindException`, which reads like a slow
test rather than a port clash.

Run unconditionally — the script is a safe no-op when nothing is running:

```bash
./scripts/stopbackenddev.sh
```

Then confirm the port is actually free (port-bind check, not an HTTP probe):

```bash
lsof -iTCP:8080 -sTCP:LISTEN -n -P
```

No output means free. If something still holds it, report that and stop — do not
`kill` by PID; the stop script owns process lifecycle.

## Step 2 — Backend tests

`TEST_DB_*` comes from `pass-cli`, so this runs **outside any sandbox**
(Bash tool `dangerouslyDisableSandbox: true`). A sandboxed shell cannot reach
`pass-cli`, and the datasource silently falls back to defaults that may not exist.

```bash
./scripts/runbackendtests.sh
```

> ⚠️ The test schema is `create-drop`. `TEST_DB_URL` must point at a disposable
> database — never at `DB_CONNECT`. If the resolved URL looks like the dev or
> production database, **stop and report**; do not run the suite.

Integration tests fail rather than skip when no database is reachable. A
connection error at startup is therefore an environment finding to report, not a
code bug to fix — say so plainly instead of "fixing" tests to skip.

Read the HTML report at `src/backendng/build/reports/tests/test/index.html` for
anything the console truncates.

## Step 3 — CLI tests

```bash
./gradlew :cli:test
```

These are Picocli argument-contract tests: no database, no network.

## Step 4 — Frontend

```bash
cd src/frontend && npm ci && npm test && npm run build
```

All three matter and they fail differently:

- **`npm ci`** fails when `package-lock.json` and `package.json` disagree. That
  is a real finding — it means CLAUDE.md's frontend gate has been unrunnable for
  everyone. Fix with `npm install` (which rewrites the lock file), then re-run
  `npm ci` to confirm, and call it out in the report.
- **`npm test`** runs `node --test` over `src/**/*.test.ts` with the resolver
  hook in `src/frontend/test/`. See docs/TESTING.md §Frontend for the two rules
  that trip people up: imports resolve `.ts` only, and JSX cannot be imported.
- **`npm run build`** is the type-check gate. A test-only change can still break
  it, so never skip it after editing frontend source.

## Step 5 — Skill sync and the static gate self-tests

```bash
./scripts/check-skill-sync.sh
./scripts/test/owasp-check-test.sh
./scripts/test/humanizer-scan-test.sh
```

The two trees — `.claude/skills/` (Claude Code) and `.agents/skills/` (Codex) —
carry the same skills and must move together in **both** directions: an edit in
either tree belongs in the other in the same commit. The script reports drift and
never edits either tree — decide per finding which side is newer and port it by
hand. `.claude/skills/` is the tie-breaker only when neither side is clearly
newer.

`owasp-check-test.sh` is the self-test for `./scripts/owasp-check.sh`, the
static OWASP gate behind `/secure-code`. It plants a deliberately vulnerable
fixture per rule, asserts the rule fires at the right severity, then plants the
repo's *approved* pattern for the same risk and asserts the rule stays silent.
It runs entirely inside a throwaway git repo under `/tmp` and touches nothing
here. Treat a failure as a real regression: a scanner rule that stopped matching
reports OK forever and nobody finds out.

`humanizer-scan-test.sh` is the same arrangement for `./scripts/humanizer-scan.sh`,
the code-hygiene gate behind `/humanizer`. It asserts both directions per rule —
a violation fires, and clean code stays silent — and several of its silent cases
pin house style the scanner once flagged by mistake (`// --- Section ---`
dividers, Go's short receiver names, emoji in CLI output). Its length assertions
check the exact measured line count, not merely that a rule fired: every desync
bug that scanner has had reported a wildly wrong number while still firing.

## Step 6 — Coverage evaluation

```bash
./scripts/test-coverage-report.sh
```

This is **name-reference coverage**, not line coverage: for each production unit
it asks whether any test file mentions its name. There is no JaCoCo/Kover in this
build, so no line data exists.

Read the numbers with both biases in mind, and say so when you report them:

- `service`, `util`, `frontend-utils`, `frontend-services` are close to honest —
  those units are normally reached by unit tests or not at all.
- `controller` and `mcp-tools` **understate** coverage badly. The E2E gates and
  `tests/e2e/` drive controllers over HTTP without naming a Kotlin class, and
  `McpToolPermissionsTest` asserts over all 85 MCP tools in one table.

Never quote the total as "test coverage" without that caveat. A percentage that
gets repeated without its meaning is worse than no number.

## Step 7 — Fix loop

For each failure, classify before editing:

| Symptom | Classification | Action |
|---|---|---|
| Assertion failure in a test whose subject you just changed | stale test | update the test to the new intended behaviour |
| Assertion failure in code you did not touch | probable real bug | fix the source, keep the test |
| `BindException: 8080` | environment | back to step 1 |
| Connection error at Micronaut startup | environment | report; `TEST_DB_*` unset or DB unreachable |
| `ERR_MODULE_NOT_FOUND` for a `.tsx` path in `npm test` | test design | extract the logic into a sibling `.ts` module (docs/TESTING.md §Frontend) |
| Passes alone, fails in the suite | shared state | unique fixtures (`"host-${System.nanoTime()}"`), clean up in `@AfterEach` |

Re-run only the affected tier after a fix, then the full tier once at the end.
**Maximum 5 iterations.** If it is still red, stop and report what remains and
why — do not delete, `@Disabled`, or weaken an assertion to reach green. A test
that was deleted to make a run pass is the single most expensive thing this skill
could produce.

## Step 8 — Extend coverage, if asked

When the user asked to *add* tests rather than just run them, pick targets from
step 6's uncovered list in this order:

1. Security controls (sanitizers, validators, permission predicates) — a
   regression here is a vulnerability that a green build and green E2E both miss.
2. Logic duplicated in two places — the test's job is to assert the two copies
   still agree, because nothing else will notice them diverging.
3. Pure functions with branchy edge cases (dates, parsing, formatting).

Skip thin CRUD controllers and DTOs; a test that restates the mapping adds
maintenance, not signal.

Follow the conventions in docs/TESTING.md: `<Class>Test.kt` with `ID-NNN:`
`@DisplayName`s for Kotlin, `<module>.test.ts` next to the module for frontend.
**Do not introduce a new test dependency** — `junit-jupiter-params` is not on the
classpath, so `@ParameterizedTest` will not compile; loop inside a plain `@Test`
with an `.as(...)` description instead.

## Report

Report once, at the end:

1. **Per-tier result** — backend / CLI / frontend unit / frontend build /
   skill-sync / owasp-gate self-test, each pass or fail with counts.
2. **What you fixed**, and for each: was it a stale test or a real bug?
3. **What is still red**, and why, if you exhausted the iteration budget.
4. **Coverage table** from step 6, with the understatement caveat.
5. **The next three targets** worth testing, chosen by the step 8 ranking.
6. If `src/` changed: the reminder that the E2E gates still apply.

State plainly what did not run. An environment that could not reach the test
database, or a Gradle distribution that could not be downloaded, means the
backend tier is **unknown** — never report it as passing.
