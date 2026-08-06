# Contract: `POST /api/assets/delete-not-seen-by-crowdstrike`

**Auth**: `@Secured("ADMIN")` (existing)
**Backend**: `AssetController.deleteNotSeenByCrowdStrike`
**Status codes**: 200 OK on success; 400 on `days <= 0`; 401/403 on auth failure; 500 on unexpected service error.

## Request

```json
{
  "days": 30,
  "dryRun": true,
  "includeLegacy": true
}
```

### Field reference

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `days` | int (>0) | yes | — | Existing — staleness threshold. |
| `dryRun` | boolean | no | `false` | Existing — when true, no deletes occur and no audit row is written. |
| `includeLegacy` | `Boolean?` | no | `null` | **NEW (Feature 087)** — `true` forces rule B on for this run; `false` forces it off; `null`/omitted falls back to the configured default `secman.crowdstrike.cleanup.include-legacy`. |

## Response — 200 OK

```json
{
  "days": 30,
  "cutoff": "2026-04-08T09:17:00",
  "dryRun": true,
  "candidateCount": 142,
  "deletedCount": 0,
  "skippedCount": 142,
  "candidates": [
    {
      "assetId": 5318,
      "name": "1864-SCCM-SQL-Q",
      "crowdStrikeLastImportedAt": null,
      "reason": "LEGACY_NULL_TIMESTAMP"
    },
    {
      "assetId": 7281,
      "name": "host-001",
      "crowdStrikeLastImportedAt": "2026-03-15T22:18:14",
      "reason": "TIMESTAMP_STALE"
    }
  ],
  "errors": [],
  "status": null,
  "runId": null,
  "legacyCandidateCount": 104,
  "legacyDeletedCount": 0
}
```

### Field reference

| Field | Type | Notes |
|---|---|---|
| `candidates[].crowdStrikeLastImportedAt` | `LocalDateTime?` | **CHANGED (Feature 087)**: now nullable — null on legacy candidates. |
| `candidates[].reason` | enum string | **NEW**: `"TIMESTAMP_STALE"` or `"LEGACY_NULL_TIMESTAMP"`. |
| `legacyCandidateCount` | int | **NEW**: count of candidates selected by rule B. |
| `legacyDeletedCount` | int | **NEW**: count of rule B candidates that were actually deleted (zero on dry-run, ≤ `legacyCandidateCount` on real runs). |

On a real (non-dry) run with deletions, `status` is `"SUCCESS"` or `"PARTIAL"`, and `runId` references the persisted `crowdstrike_cleanup_run` row.

## Behaviour

- Manual runs do NOT apply the safety brake — `auditService.run(..., maxDeletePercent = null)`. Existing behaviour preserved when `includeLegacy=true`.
- Candidate de-duplication by `id` happens server-side; the response array contains each asset at most once.
- `errors[]` is populated when individual asset deletes fail; the run continues for the remaining candidates.

## Backward compatibility

- Request: existing clients omit `includeLegacy`; the configured default applies. No breakage.
- Response: existing clients that read only `candidateCount`/`deletedCount` continue to see correct totals (which now include both rule contributions). Clients that previously assumed `crowdStrikeLastImportedAt` was non-null may need updating; only the admin Falcon-config UI consumes this DTO and is being updated in this PR.

## Verification

- **Unit**: `CrowdStrikeAssetCleanupServiceTest` covers `includeLegacy` true/false and the four-part fence violations.
- **Integration**: `CrowdStrikeCleanupAuditServiceIntegrationTest` covers persisted-row assertions for `legacyCandidateCount`/`legacyDeletedCount`.
- **Manual**: `/e2ejs` admin-role pass exercises the dry-run via the UI; `/e2evulnexception` pass guards regression on adjacent admin flows.
