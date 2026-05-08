# Contract: `GET /api/crowdstrike/cleanup/runs?limit=20`

**Auth**: `@Secured("ADMIN")` (existing)
**Backend**: `CrowdStrikeCleanupController.listRuns`
**Status codes**: 200 OK on success; 401/403 on auth failure.

## Request

```http
GET /api/crowdstrike/cleanup/runs?limit=20 HTTP/1.1
Authorization: Bearer <admin-jwt>
```

| Query param | Type | Default | Notes |
|---|---|---|---|
| `limit` | int (1..200) | 20 | Existing — max number of runs returned, ordered by `startedAt` desc. |

## Response — 200 OK

Array of `CrowdStrikeCleanupRun` rows (existing entity shape) with two new fields appended.

```json
[
  {
    "id": 42,
    "status": "SUCCESS",
    "triggeredBy": "scheduler",
    "staleDays": 30,
    "cutoff": "2026-04-08T02:30:00",
    "candidateCount": 38,
    "deletedCount": 38,
    "errorCount": 0,
    "totalCrowdStrikeTracked": 6520,
    "startedAt": "2026-05-08T02:30:00",
    "completedAt": "2026-05-08T02:30:14",
    "durationMs": 14102,
    "errorMessage": null,
    "legacyCandidateCount": 12,
    "legacyDeletedCount": 12
  },
  {
    "id": 41,
    "status": "ABORTED_SAFETY_BRAKE",
    "triggeredBy": "scheduler",
    "staleDays": 30,
    "cutoff": "2026-04-07T02:30:00",
    "candidateCount": 980,
    "deletedCount": 0,
    "errorCount": 0,
    "totalCrowdStrikeTracked": 6520,
    "startedAt": "2026-05-07T02:30:00",
    "completedAt": "2026-05-07T02:30:01",
    "durationMs": 1042,
    "errorMessage": "Safety brake: 15.03% of CrowdStrike-tracked assets would be deleted (limit 10%). Investigate before re-running.",
    "legacyCandidateCount": 800,
    "legacyDeletedCount": 0
  }
]
```

### New fields

| Field | Type | Notes |
|---|---|---|
| `legacyCandidateCount` | int | **NEW (Feature 087)**: count of rule-B candidates this run identified. Always ≤ `candidateCount`. |
| `legacyDeletedCount` | int | **NEW**: count of rule-B candidates this run actually deleted. Always ≤ `legacyCandidateCount`. Zero for dry-runs (which are not persisted) or aborted runs. |

### Historical-run handling

Rows persisted before V210 read both new fields as `0` (column DEFAULT 0). The frontend MUST handle this case as "no legacy contribution recorded" rather than treating zero as "definitely no legacy candidates existed at the time".

## Backward compatibility

Additive fields only. Existing clients that ignore unknown fields continue to work unchanged.

## Verification

- **Unit**: existing controller tests assert the new fields are serialized.
- **Integration**: `CrowdStrikeCleanupAuditServiceIntegrationTest` round-trips a row through the repository and asserts `legacyCandidateCount` / `legacyDeletedCount` are persisted across SUCCESS, PARTIAL, ABORTED_SAFETY_BRAKE outcomes.
- **Manual**: visit the Stale Asset Cleanup history table on `/admin/falcon-config` after a run; legacy column renders for the new run and shows zero for older rows.
