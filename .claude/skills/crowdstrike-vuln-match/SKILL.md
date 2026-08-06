---
name: crowdstrike-vuln-match
description: >
  Select a stable sample of SecMan assets (200 by default) and compare each
  asset's currently stored CrowdStrike vulnerability rows with a fresh ad-hoc
  CrowdStrike Falcon query that does not save data. Starts the local backend
  and frontend fresh before running. Use when the user says "CrowdStrike
  vulnerability match", "compare SecMan to CrowdStrike", "check 200 assets",
  or similar.
context: fork
---
# CrowdStrike Vulnerability Match Test

This skill validates that SecMan's current CrowdStrike vulnerability state still
matches Falcon for a bounded sample of assets. The matcher itself is
**read-only** against SecMan data: it authenticates to SecMan, reads assets and
vulnerabilities, then runs `secman query --hostname ... --output ...` for every
sampled host without `--save`.

> **Read `../_shared/stack-lifecycle.md` in full before touching the stack.** It
> defines the cold-start sequence, port-bind liveness, credential and host-URL
> rules this skill assumes. In short: stop both services unconditionally, verify
> the ports freed, start outside the sandbox, log to `.e2e-logs/`, target
> `SECMAN_HOST` from `pass-cli` and never `localhost`.

## Phase 0 — Precondition: a current CLI jar

The comparison calls Falcon through the SecMan CLI, so a stale jar silently
compares against outdated query logic and the mismatches it reports look like
real SecMan/Falcon drift. Rebuild unconditionally and gate on the artifact —
"rebuild if it isn't current" is not checkable, so don't try:

```bash
./gradlew :cli:shadowJar
JAR=src/cli/build/libs/cli-0.1.0-all.jar
test -f "$JAR" || { echo "FATAL: CLI jar build did not produce $JAR"; exit 1; }
```

If the build fails, stop here and surface the Gradle error. There is no point
starting services for a comparison that cannot run.

## Phase 1 — Cold-start the stack

Follow `../_shared/stack-lifecycle.md` §1–§4: stop both services
unconditionally, verify the ports are free, start both outside the sandbox with
logs under `.e2e-logs/`, and wait for the port-bind liveness checks.

A cold stack matters more here than in most skills: the whole output is a
statement about what SecMan currently holds, and a backend predating the working
tree would make every mismatch unattributable.

## Phase 2 — Run the Matcher

Use the wrapper from the repository root:

```bash
./scripts/test/test-crowdstrike-vulnerability-match.sh \
  --sample-size 200 \
  --asset-type SERVER \
  --severity HIGH,CRITICAL
```

The wrapper resolves `secmanpp.env` through `pass-cli` when available and then
executes `scripts/test/crowdstrike_vulnerability_match.py`.

### Required environment

Resolved through `pass-cli run --env-file ./secmanpp.env -- ...` or exported
before running the wrapper:

- `SECMAN_HOST` or `SECMAN_BACKEND_URL` — SecMan backend URL (from `pass-cli`;
  never hardcode `localhost`).
- `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` — account that can read assets and
  vulnerability rows.
- `FALCON_CLIENT_ID` / `FALCON_CLIENT_SECRET` plus the configured Falcon region
  or base URL used by `./scripts/secman query`.

## What is compared

For each sampled asset:

1. Read SecMan vulnerabilities from `GET /api/assets/{assetId}/vulnerabilities`.
2. Keep only rows with `source = CROWDSTRIKE` unless
   `--include-non-crowdstrike` is explicitly passed.
3. Run an ad-hoc Falcon query with the SecMan CLI for the asset hostname.
4. Normalize both sides to the CrowdStrike import identity:
   `(CVE ID uppercased, affected product normalized)`.
5. Report rows missing from Falcon, rows missing from SecMan, and severity drift
   for matching keys.

This mirrors the backend import service's dedupe key (`CVE`, affected product),
so duplicate CrowdStrike rows for the same product do not produce false
positives.

## Outputs and exit codes

Default report files:

- `crowdstrike-vulnerability-match-report.json` — machine-readable summary and
  mismatch details.
- `crowdstrike-vulnerability-match-report.md` — human-readable report for PRs,
  incident notes, or operations handoff.

Exit codes:

- `0` — all sampled assets matched.
- `1` — at least one asset had a mismatch or Falcon query error.
- `2`/other shell failures — setup, authentication, or environment problem.

Do **not** run a saving import from this skill. If Falcon is authoritative and
the mismatch is expected, run the normal operational import separately:

```bash
./scripts/secman query servers --save --severity HIGH,CRITICAL --device-type SERVER
```

## Phase 3 — Read the report and summarize it

**Exit code 0 is not the deliverable, and exit code 1 is not a failure.** A
mismatch is the *finding* this skill exists to produce — reporting "exit 1, the
matcher failed" is the single most likely way to waste a run. You must open
`crowdstrike-vulnerability-match-report.md` and tell the user what it says.

Read the Markdown report (the JSON is for automation and for re-checking exact
`(CVE, product)` keys) and report:

```
# CrowdStrike ↔ SecMan comparison

Sampled: <n> assets (type <T>, severity <S>)   Exit code: <0|1|2>

| Category | Count |
|---|---|
| Assets fully matching | |
| Assets with mismatches | |
| Rows in SecMan, missing from Falcon | |
| Rows in Falcon, missing from SecMan | |
| Severity drift on matching keys | |

## Notable mismatches
<up to ~10, each with hostname and (CVE, product); say how many were omitted>

## Assets that could not be compared
<Falcon query errors, auth failures — these are NOT matches, do not count them as clean>

Reports: crowdstrike-vulnerability-match-report.{md,json}
```

Interpretation, so the counts mean something:

- **Rows in SecMan, missing from Falcon** — usually remediation that the import
  has not yet reconciled, or a stale row the cleanup sweep should have removed.
- **Rows in Falcon, missing from SecMan** — usually a missed or partial import.
  This is the direction that means SecMan is under-reporting risk.
- **Severity drift** — Falcon re-scored a CVE after import.

Exit code `2` (or another shell failure) is a genuine failure — setup, auth or
environment. Say so plainly and do not present partial counts as a result.

## Phase 4 — Teardown

Stop both services, on the failure path too — `../_shared/stack-lifecycle.md` §7.
