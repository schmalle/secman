---
name: crowdstrike-vuln-match
description: >
  Select a stable sample of SecMan assets (200 by default) and compare each
  asset's currently stored CrowdStrike vulnerability rows with a fresh ad-hoc
  CrowdStrike Falcon query that does not save data. Use when the user says
  "CrowdStrike vulnerability match", "compare SecMan to CrowdStrike", "check
  200 assets", or similar.
context: fork
---
# CrowdStrike Vulnerability Match Test

This skill validates that SecMan's current CrowdStrike vulnerability state still
matches Falcon for a bounded sample of assets. It is a **read-only** production
check: the matcher authenticates to SecMan, reads assets and vulnerabilities,
then runs `secman query --hostname ... --output ...` for every sampled host
without `--save`.

## Driver

Use the wrapper from the repository root:

```bash
./scripts/test/test-crowdstrike-vulnerability-match.sh \
  --sample-size 200 \
  --asset-type SERVER \
  --severity HIGH,CRITICAL
```

The wrapper resolves `secmanpp.env` through `pass-cli` when available and then
executes `scripts/test/crowdstrike_vulnerability_match.py`.

## Required environment

Resolved through `pass-cli run --env-file ./secmanpp.env -- ...` or exported
before running the wrapper:

- `SECMAN_HOST` or `SECMAN_BACKEND_URL` — SecMan backend URL.
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

## Recommended operation

1. Build the CLI first if it is not already current:

   ```bash
   ./gradlew :cli:shadowJar
   ```

2. Run the matcher against a stable sample:

   ```bash
   ./scripts/test/test-crowdstrike-vulnerability-match.sh \
     --sample-size 200 \
     --asset-type SERVER \
     --severity HIGH,CRITICAL
   ```

3. If the matcher fails, inspect the Markdown report first. Use the JSON report
   for automation or to re-check exact `(CVE, product)` keys.

4. Do **not** run a saving import from this skill. If Falcon is authoritative
   and the mismatch is expected, run the normal operational import separately:

   ```bash
   ./scripts/secman query servers --save --severity HIGH,CRITICAL --device-type SERVER
   ```
