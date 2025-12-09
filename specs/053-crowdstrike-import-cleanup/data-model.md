# Data Model: CrowdStrike Import Cleanup

**Feature**: 053-crowdstrike-import-cleanup
**Date**: 2025-12-08
**Type**: Bug Fix (No schema changes)

## Overview

This feature is a bug fix that does not introduce any new entities or schema changes. The fix modifies only the query logic in the service layer.

## Existing Entities (Reference)

### Vulnerability

The `Vulnerability` entity has an `importTimestamp` field that is critical for filtering to the latest import:

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Primary key |
| asset | Asset | Foreign key to Asset (ManyToOne) |
| vulnerabilityId | String | CVE identifier (e.g., "CVE-2024-1234") |
| cvssSeverity | String | Severity level (CRITICAL, HIGH, MEDIUM, LOW) |
| importTimestamp | LocalDateTime | When this record was imported (batch identifier) |
| scanTimestamp | LocalDateTime | When the vulnerability was discovered |
| ... | ... | Other fields unchanged |

**Key insight**: All vulnerabilities in a single import batch share the same `importTimestamp`. This allows grouping by `MAX(import_timestamp)` to find the current state.

### Asset

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Primary key |
| name | String | Hostname (unique identifier) |
| adDomain | String | AD domain for domain-based filtering |
| ... | ... | Other fields unchanged |

## Query Pattern

The fix adds a new repository query that uses `importTimestamp` for filtering:

```sql
SELECT v.* FROM vulnerability v
INNER JOIN (
    SELECT asset_id, MAX(import_timestamp) as max_ts
    FROM vulnerability
    WHERE asset_id IN :assetIds
    GROUP BY asset_id
) latest ON v.asset_id = latest.asset_id AND v.import_timestamp = latest.max_ts
WHERE v.asset_id IN :assetIds
```

**How it works**:
1. Subquery finds `MAX(import_timestamp)` per asset
2. Main query joins only vulnerabilities matching that timestamp
3. Result: Only current vulnerabilities, no historical duplicates

## No Schema Changes Required

The existing `idx_vulnerability_import_timestamp` index (if present) will optimize the subquery. No new indexes or schema modifications are needed.
