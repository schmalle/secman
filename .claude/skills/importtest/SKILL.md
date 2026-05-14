---
name: importtest
description: >
  Run the CrowdStrike import pipeline end-to-end and iteratively fix backend
  bugs. Builds the CLI jar if missing, starts the local backend and frontend
  via the canonical scripts, invokes `./scripts/import.sh`, and watches the
  backend log for ERROR-level stack traces produced during the import window.
  Each stack trace classified as a backend bug is fixed in source, the backend
  is restarted, and the import is rerun — looping up to 5 iterations or until
  the backend log is clean AND `import.sh` reports `Errors (0)`. Use this
  skill when the user says "importtest", "run import test", "test the import",
  "fix import bugs", or similar.
context: fork
---
# CrowdStrike Import — Iterative Fix Loop

You are an orchestration agent that brings up the local secman environment,
runs `./scripts/import.sh` end-to-end, and **iteratively fixes every backend
stack trace surfaced during the import** until the run is clean or you've
exhausted the retry budget.

## Why This Skill Exists

`./scripts/import.sh` is the canonical CrowdStrike ingestion path: it runs
the Kotlin CLI (`secman query servers --save`), which paginates the
CrowdStrike Falcon API and POSTs server vulnerability batches to
`POST /api/crowdstrike/servers/import` on the backend. The import touches
the most concurrency-sensitive code path in the project (per-server
transactions, materialized-view refresh, asset compliance tracking,
reconcile sweep). Failures that only surface under real data — InnoDB
deadlocks, transaction-manager misconfiguration, Hibernate batch flush
errors, missing FK rows — show up here and nowhere else. This skill is
the harness for catching and repairing them.

## High-Level Loop

```
1. Verify / build CLI jar
2. Start backend  (./scripts/startbackenddev.sh)
3. Start frontend (./scripts/startfrontenddev.sh)
4. Wait for both healthy (port-bind check)
5. Record backend-log offset (byte position) → IMPORT_START_OFFSET
6. Run ./scripts/import.sh, capture stdout + exit code → .e2e-logs/import-run-<N>.log
7. Scan backend log from IMPORT_START_OFFSET → end for ERROR-level entries
   with stack traces (java.* / kotlin.* / com.secman.* frames)
8. Parse import.sh stdout for the final "Errors (M)" tally
9. IF backend log is clean AND M == 0  → done, success
10. IF backend stack traces present  →
    a. Pick highest-severity / most-frequent trace
    b. Classify as backend bug (controller, service, repository, transaction config)
    c. Fix in source
    d. Restart backend (./scripts/stopbackenddev.sh && ./scripts/startbackenddev.sh)
    e. Go to step 5
11. After 5 total iterations → stop and present remaining failures
```

## Detailed Instructions

### Phase 0 — Always Rebuild the CLI

Before every skill invocation, **always** rebuild the CLI jar — never
trust a cached one. This guarantees the run reflects the current state of
`src/cli/` and `src/shared/` even when those modules were touched by a
previous fix (or by an out-of-band edit between runs):

```bash
./gradlew :cli:shadowJar
JAR=src/cli/build/libs/cli-0.1.0-all.jar
test -f "$JAR" || { echo "FATAL: CLI jar build did not produce $JAR"; exit 1; }
```

If `shadowJar` fails, **stop the skill immediately** — there is no point
starting services for an import that can't run. Surface the Gradle error
to the user.

Within the fix loop (Phase 3), rebuild the CLI again **only** when the
fix touched files under `src/cli/` or `src/shared/`. The initial Phase 0
rebuild is mandatory; the per-iteration rebuilds are conditional.

### Phase 1 — Environment Setup

Always start services via the wrapper scripts below — never `./gradlew run`
directly (CLAUDE.md §"Tooling Conventions"). They source `pass-cli` for
secrets and configure the JVM.

| Setting                  | Default                           |
| ------------------------ | --------------------------------- |
| `backend.start`          | `./scripts/startbackenddev.sh`    |
| `backend.healthPort`     | `8080` (liveness = port-bind)     |
| `backend.healthTimeout`  | `120` (seconds)                   |
| `frontend.start`         | `./scripts/startfrontenddev.sh`   |
| `frontend.healthPort`    | `4321`                            |
| `frontend.healthTimeout` | `60` (seconds)                    |

**Starting services:**

- Start each service in a background process via `nohup ... &`,
  redirecting stdout/stderr to log files under `.e2e-logs/`.
  Standardize filenames so later phases can find them:
  - `.e2e-logs/backend.log`
  - `.e2e-logs/frontend.log`
- Record the PIDs for teardown.
- Liveness is **port-bind**, not HTTP — per CLAUDE.md §"E2E Runner":
  ```bash
  lsof -iTCP:8080 -sTCP:LISTEN -n -P    # 120s budget
  lsof -iTCP:4321 -sTCP:LISTEN -n -P    # 60s budget
  ```
  Use `scripts/wait-for-health.sh` if it exists and supports port-bind.

### Phase 2 — Run the Import

1. **Record the backend log offset** so you only scan log lines produced by
   *this* import run (not historic ones):

   ```bash
   IMPORT_START_OFFSET=$(stat -f%z .e2e-logs/backend.log 2>/dev/null || echo 0)
   ```

2. **Run the import** and capture everything:

   ```bash
   ./scripts/import.sh > .e2e-logs/import-run-<N>.log 2>&1
   IMPORT_EXIT=$?
   ```

   `import.sh` already uses `pass-cli run` to inject credentials; no
   additional secret wrangling needed in the skill.

3. **Note the import end time** for the report.

### Phase 2.5 — Error Classification

Scan two channels:

#### Channel A — Backend log (the primary signal the user asked us to watch)

Extract everything written *after* `IMPORT_START_OFFSET`:

```bash
tail -c +"$((IMPORT_START_OFFSET + 1))" .e2e-logs/backend.log > .e2e-logs/backend-window-<N>.log
```

Look for ERROR-level entries with stack traces in
`.e2e-logs/backend-window-<N>.log`. Classification table:

| Backend-log pattern                                                           | Category                | Action                                                                                          |
| ----------------------------------------------------------------------------- | ----------------------- | ----------------------------------------------------------------------------------------------- |
| `ERROR ... com.secman.*` + Java stack trace                                   | **backend (code)**      | Read the indicated `com.secman.*` line, identify root cause, fix                                |
| `InvalidIsolationLevelException` / `HibernateTransactionManager is not allowed` | **backend (tx config)** | Revert custom-isolation annotations OR adjust `hibernate.connection.handling_mode`              |
| `Deadlock found when trying to get lock` (errorCode 1213 / SQLState 40001)    | **backend (concurrency)** | Add retry-on-deadlock at the offending tx boundary; consider isolation/lock-strategy review     |
| `LazyInitializationException` / `could not initialize proxy`                  | **backend (JPA scope)** | Widen the transaction or eager-fetch the association                                            |
| `ConstraintViolationException` (unique / FK)                                  | **backend (data)**      | Check upsert logic, dedup-before-save, or schema mismatch                                       |
| `OutOfMemoryError` / Hibernate batch size errors                              | **backend (memory)**    | Reduce chunk size, add flush+clear loop, check entity cascade                                   |
| `Connection is not available, request timed out` (HikariCP)                   | **backend (pool)**      | Audit long-running transactions or n+1 queries; check `datasource.default.maximum-pool-size`    |
| `FlywayException` / migration failure on startup                              | **backend (schema)**    | Fix the offending V<n>__*.sql; never edit an applied migration — write a new one if needed      |

#### Channel B — `import.sh` stdout

The CLI prints a final block on its own that looks like:

```
--- Import Statistics ---
Servers processed: 1882
...
--- Errors (15) ---
 - Failed to import server 'EC2AMAZ-XXXX': could not execute statement [(conn=...) Deadlock found ...
```

Parse the `Errors (N)` count. Each individual error message has already
been logged on the backend side, so backing-channel A is the source of
truth for *why* — channel B tells you only the count and the affected
host. The CLI exit code is non-zero only on hard CLI failures (missing
jar, can't authenticate). Per-server import failures do **not** flip
the CLI exit code.

**Success criterion for this skill (both must hold):**
- Channel A: zero new ERROR entries in the import window.
- Channel B: `Errors (0)` in the final stats block (or no `Errors` block at all).

WARN-level lines like `Deadlock retry 1/3 for import server '...'` are
**not** failures — they are evidence the retry path is working as
designed and the deadlock was absorbed transparently. Do not fix these.

### Phase 3 — Fix Loop

Fix in priority order: **transaction-config errors first** (they prevent
the import from even getting off the ground), then concurrency issues,
then data issues.

#### Key Files for the CrowdStrike Import Path

- **Controller**: `src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt`
- **Import service**: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt`
- **Falcon client service**: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt`
- **Vulnerability repository**: `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`
- **Asset repository**: `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`
- **Vulnerability entity**: `src/backendng/src/main/kotlin/com/secman/domain/Vulnerability.kt`
- **Asset entity**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`
- **Flyway migrations**: `src/backendng/src/main/resources/db/migration/`
- **Backend config**: `src/backendng/src/main/resources/application.yml`
- **CLI command**: `src/cli/src/main/kotlin/com/secman/cli/commands/ServersCommand.kt`
- **CLI storage**: `src/cli/src/main/kotlin/com/secman/cli/service/VulnerabilityStorageService.kt`
- **Reference docs**: `docs/CROWDSTRIKE_IMPORT.md`

CLAUDE.md §"Patterns" has the canonical transactional-replace pattern for
this path and the explicit "DO NOT cascade=ALL on Asset.vulnerabilities"
warning — consult it before any fix that touches the entity mapping.

#### Restart Rules

- **Backend code change** → run `./scripts/stopbackenddev.sh`, then
  `./scripts/startbackenddev.sh`, wait for port-bind, **then reset the
  backend log offset** (the file is recreated on restart, so
  `IMPORT_START_OFFSET` becomes 0 again).
- **CLI code change** → rebuild the jar (`./gradlew :cli:shadowJar`),
  then rerun `import.sh`. Backend stays up.
- **Flyway migration / schema change** → backend restart will replay
  migrations; watch `.e2e-logs/backend.log` for `FlywayException` and
  ensure the new V<n>__*.sql applies cleanly.
- **application.yml** → backend restart required.
- **Frontend** → not involved in the import path, but if a fix
  inadvertently touches it, Astro/Vite hot-reloads in ~3s.

Never raw-`kill` services. The stop scripts handle PID files and
graceful shutdown.

#### Guard Rails

- **Track each error you've attempted to fix** (path + brief
  description). If the same error persists after two fix attempts, stop
  and surface it — you're attacking the symptom, not the root cause.
  Consult `superpowers:systematic-debugging` if available.
- **Max 5 iterations** total. If iteration 5 still has stack traces or
  `Errors > 0`, stop and present the remaining failures with the most
  recent backend-log window and `import-run-<N>.log` as evidence.
- **Never broaden scope on the fly**: this skill is for backend bugs
  exposed by the import. If the trace points outside `com.secman.*`
  (e.g. a Micronaut framework bug, a Flyway internal), surface it; do
  not attempt to patch third-party code.
- **CLAUDE.md non-negotiables still apply**: `./gradlew build` must be
  clean and the backend must boot cleanly after every fix. Compile-clean
  ≠ runtime-clean — the very startup of the backend after a fix is part
  of the loop.

### Phase 4 — Teardown & Report

- Stop backend and frontend via `./scripts/stopbackenddev.sh` and
  `./scripts/stopfrontenddev.sh`.
- Confirm ports 8080 and 4321 are released (`lsof` shows nothing).
- Print a summary table:

```
| Iter | Backend stack traces | import.sh Errors | Fix applied                                          | Backend restarted |
|------|----------------------|------------------|------------------------------------------------------|-------------------|
| 1    | 1 (Deadlock x15)     | 15               | CrowdStrikeVulnerabilityImportService.kt: add retry  | yes               |
| 2    | 0                    | 0                | —                                                    | —                 |
```

Followed by a one-line verdict:
- `PASS` — clean import (0 traces, 0 per-server errors).
- `FAIL` — exhausted budget; print the remaining traces + a pointer to
  `.e2e-logs/backend-window-<N>.log` and `.e2e-logs/import-run-<N>.log`.

## Important Notes

- **Never commit or push.** Apply fixes locally and let the user review.
- **Secrets only via Proton Pass.** `import.sh`, `startbackenddev.sh`,
  and `startfrontenddev.sh` all wrap their commands in `pass-cli run`
  — do not hardcode credentials anywhere in the skill or in fixes.
- **Port collisions**: before starting, ensure 8080 and 4321 are free.
  If they are bound by stale dev processes, stop them via the stop
  scripts; only resort to `kill -9` after a graceful stop has been
  attempted and reported.
- **The import is read-heavy on the Falcon API**; the full run can take
  several minutes (1800+ servers in the latest baseline). Don't
  prematurely declare the run hung — give it at least 10 minutes per
  iteration before declaring a timeout.
- **`Deadlock retry N/3 for import server '...'` WARN lines are
  EXPECTED** when the retry path absorbs a transient gap-lock collision.
  They are evidence of success, not failure. The fix criterion is
  ERROR-level entries with stack traces, not WARN-level retries.
