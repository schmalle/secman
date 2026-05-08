# Quickstart — Verifying Feature 087 manually after deploy

**Audience**: ADMIN role on a deployed secman instance.
**Prerequisites**: feature 087 merged; backend restarted to pick up the new flag and Flyway migration; user has `ADMIN` role; `pass-cli` provides `SECMAN_ADMIN_NAME`/`SECMAN_ADMIN_PASS`/`SECMAN_HOST`.

This recipe walks through a safe rollout sequence: dry-run → real-run → audit. Use a test or staging environment first.

---

## Step 0 — Confirm the feature is wired

```bash
TOKEN=$(curl -s "$SECMAN_HOST/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SECMAN_ADMIN_NAME\",\"password\":\"$SECMAN_ADMIN_PASS\"}" | jq -r .token)

curl -s -H "Authorization: Bearer $TOKEN" "$SECMAN_HOST/api/crowdstrike/cleanup/config" | jq
```

Expect:

```json
{
  "enabled": false,
  "staleDays": 30,
  "maxDeletePercent": 10,
  "cron": "0 30 2 * * ?",
  "includeLegacy": false
}
```

The `includeLegacy: false` field is the new wiring. If it's missing, the deploy is incomplete.

## Step 1 — Run a baseline dry-run with the toggle OFF (existing behaviour)

Open `https://<host>/admin/falcon-config` in a browser, scroll to **Stale Asset Cleanup**, leave the new "Include legacy CrowdStrike rows" toggle OFF, set "Stale days for manual run" to 30, click **Dry-run**.

Expect:
- Summary banner: `Dry-run complete — candidates: N (timestamp: N, legacy: 0, deleted: 0)`.
- Or via API:

```bash
curl -s -X POST "$SECMAN_HOST/api/assets/delete-not-seen-by-crowdstrike" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"days":30,"dryRun":true}' | jq '{candidateCount, legacyCandidateCount, deletedCount}'
```

`legacyCandidateCount` MUST be `0` and every entry in `candidates[]` MUST have `reason = "TIMESTAMP_STALE"`.

## Step 2 — Dry-run with the toggle ON (preview the legacy population)

In the UI, flip the toggle ON, click **Dry-run** again. Or via API:

```bash
curl -s -X POST "$SECMAN_HOST/api/assets/delete-not-seen-by-crowdstrike" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"days":30,"dryRun":true,"includeLegacy":true}' | jq '{candidateCount, legacyCandidateCount, candidates: (.candidates | group_by(.reason) | map({reason: .[0].reason, count: length}))}'
```

Expect:
- `legacyCandidateCount > 0` if legacy rows exist on this DB.
- `candidates[]` contains entries with `reason: "LEGACY_NULL_TIMESTAMP"` and `crowdStrikeLastImportedAt: null`.
- Summary banner shows `candidates: N (timestamp: X, legacy: Y, deleted: 0)` with `Y > 0`.

## Step 3 — Sanity check: protected rows are NOT in the legacy candidates

Pick any candidate in the dry-run output, then run:

```bash
ASSET_ID=<id from candidates[]>
curl -s -H "Authorization: Bearer $TOKEN" "$SECMAN_HOST/api/assets/$ASSET_ID" | jq '{owner, manualCreator, scanUploader, crowdStrikeLastImportedAt}'
```

Expect for legacy candidates: `owner = "CrowdStrike Import"`, `manualCreator = null`, `scanUploader = null`, `crowdStrikeLastImportedAt = null`. If any of those are set, the rule misfired — file a bug, do not proceed.

## Step 4 — Execute a real run with the toggle ON

In the UI, with the toggle ON and after reviewing the dry-run output, click **Run cleanup now**. Confirm.

Expect:
- The summary banner switches to `Cleanup complete — candidates: N (timestamp: X, legacy: Y, deleted: Z)` where `Z > 0`.
- The history panel grows by one row with `legacy_candidate_count = Y` and `legacy_deleted_count = Z`.
- Candidate count drops to zero (or near zero) on the next dry-run for the same threshold.

## Step 5 — Audit the run via the runs endpoint

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$SECMAN_HOST/api/crowdstrike/cleanup/runs?limit=5" | jq '.[0] | {id, status, candidateCount, deletedCount, legacyCandidateCount, legacyDeletedCount, triggeredBy}'
```

Expect non-zero `legacyDeletedCount` matching the UI summary, and `triggeredBy` set to your admin username.

## Step 6 — Flip the configured default for the scheduler (optional, after several manual runs prove the rule is well-behaved)

In your environment configuration or `application.yml`:

```yaml
secman:
  crowdstrike:
    cleanup:
      include-legacy: true     # was false
```

Or via env: `CROWDSTRIKE_CLEANUP_INCLUDE_LEGACY=true`.

Restart the backend. The next 02:30 cron run uses the widened candidate set with the safety brake's widened denominator.

## Rollback

If the legacy rule misbehaves in production, set `secman.crowdstrike.cleanup.include-legacy=false` (or the env var) and restart. The rule is fully gated; rule A continues to operate. No deleted assets can be recovered (deletes are real), but no further legacy deletes will occur.

## Mandatory project gates (non-quickstart, blocking for merge)

- `./gradlew build` clean.
- `./scriptpp/startbackenddev.sh` starts cleanly, then stop.
- `/e2ejs` reports 0 errors for both admin and normal-user roles against `SECMAN_HOST`.
- `/e2evulnexception` runs with 0 failures.

These are policy gates per `CLAUDE.md` and are NOT part of this quickstart's verification path — they are checked by the implementer before opening a PR.
