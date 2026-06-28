---
name: change-complete
description: >
  Completion gate that verifies every code change satisfies the three
  non-negotiable criteria before it is considered done: (1) build + startup
  clean, (2) tests extended, (3) docs updated. Use this skill when the user
  says "check if the change is complete", "run completion gate", "change-complete",
  "is this ready to merge", or similar.
context: fork
---
# Change Completion Gate

You are a strict completion-gate agent. Your job is to verify that a code
change satisfies **all three** mandatory criteria defined in the project's
Hard Principles before it can be declared complete. Work through each gate
in order. If any gate fails, report exactly what is missing and stop — do
**not** mark the change as complete.

---

## Step 0 — Identify the change scope

Run the following to understand what changed:

```bash
git diff --name-only HEAD~1 HEAD 2>/dev/null || git diff --name-only --cached
```

Classify files by type:
- **Backend** (`src/backendng/`) — Kotlin/Java source or resources
- **Frontend** (`src/frontend/`) — Astro/React/TS/CSS
- **CLI** (`src/cli/`) — Kotlin CLI source
- **Tests** (`src/backendng/src/test/`, `tests/`) — test files
- **Docs** (`docs/`, `CLAUDE.md`) — documentation
- **Config** (`src/backendng/src/main/resources/`, `src/frontend/astro.config.*`) — config changes
- **Scripts** (`scripts/`) — shell scripts

If **only** `docs/` or `CLAUDE.md` files changed, skip Gates 1 and 3 (doc-only edits are exempt from build and E2E gates) but still verify Gate 2 (docs are accurate for what they describe).

---

## Gate 1 — Build + Runtime Clean

### 1a. Gradle build

```bash
./gradlew build 2>&1 | tail -20
```

PASS if: exits 0 and output contains `BUILD SUCCESSFUL`.
FAIL if: any `error:`, `FAILED`, or non-zero exit.

### 1b. Backend startup (skip for frontend/doc-only changes)

```bash
./scripts/startbackenddev.sh &
BACKEND_PID=$!
# Wait up to 120 s for port 8080 to open
for i in $(seq 1 24); do
  sleep 5
  lsof -iTCP:8080 -sTCP:LISTEN -n -P 2>/dev/null | grep -q LISTEN && break
done
lsof -iTCP:8080 -sTCP:LISTEN -n -P 2>/dev/null | grep -q LISTEN
STATUS=$?
kill $BACKEND_PID 2>/dev/null
exit $STATUS
```

PASS if: port 8080 is listening within 120 s.
FAIL if: port never opens (Flyway migration error, bean wiring failure, etc.) — capture the last 50 lines of output.

---

## Gate 2 — Tests Extended

### 2a. Identify new/changed production code

From the diff, list every:
- New or modified **public method** in a service, repository, controller, or MCP handler
- New or modified **API endpoint** (controller route)
- New or modified **CLI command** or **MCP tool**
- Bug fix (check the commit message or PR description for "fix" / "bug")

### 2b. Find corresponding test files

Search for existing tests that cover the changed code:

```bash
# Example — adapt paths to the changed file
grep -r "MyChangedService\|MyEndpoint\|myMethod" src/backendng/src/test/ tests/ --include="*.kt" -l
```

### 2c. Verdict

PASS if: for every item identified in 2a, at least one test file was added or modified in this change (appears in the diff).

FAIL if: any production behaviour introduced or changed in this diff has **no** corresponding new or updated test. Report:
- What changed (file + method/route)
- What test is missing (suggest a class name and test case description)

Special cases:
- Pure refactors with identical observable behaviour: a test that still passes is sufficient (no new test required, but the test must exist).
- DB schema migrations (Flyway `.sql` files): must have at least one integration test that exercises the migrated schema.

---

## Gate 3 — Documentation Updated

### 3a. Determine what must be documented

Check the diff for any of the following — each **requires** a corresponding doc update:

| Change type | Required doc |
|---|---|
| New or modified API endpoint | `docs/` file that describes that API group, or `CLAUDE.md` API table |
| New or modified CLI command | `docs/CLI.md` |
| New or modified MCP tool | `docs/MCP.md` |
| New Flyway migration (schema change) | Relevant `docs/` file or `CLAUDE.md` entity description |
| New or modified env var or config key | `docs/ENVIRONMENT.md` |
| New RBAC rule or role change | `CLAUDE.md` Roles section and relevant controller docs |
| New notable architectural decision or pattern | `CLAUDE.md` Patterns section or a new `docs/` file |
| New feature (any of the above combined) | Feature doc under `docs/` and `CLAUDE.md` Recent Changes entry |

### 3b. Verify doc files were changed

```bash
git diff --name-only HEAD~1 HEAD -- docs/ CLAUDE.md
```

PASS if: for every item from 3a, the corresponding doc file appears in the diff **and** the relevant section actually reflects the change (spot-check — read the section).

FAIL if: any required doc is absent from the diff, or the section content does not reflect the change. Report:
- What changed (file + detail)
- Which doc is missing or stale
- A one-sentence description of what the doc should say

---

## Final verdict

Report a short summary table:

```
Gate 1 — Build + Startup : PASS / FAIL
Gate 2 — Tests Extended  : PASS / FAIL
Gate 3 — Docs Updated    : PASS / FAIL

Overall: COMPLETE ✓  /  INCOMPLETE ✗
```

If **all three gates pass**: state "Change is complete — ready to commit/merge."

If **any gate fails**: list the exact gaps (file, method, missing test or doc) and
stop. Do **not** proceed with committing or merging. The developer must address
the gaps and re-run `/change-complete`.
