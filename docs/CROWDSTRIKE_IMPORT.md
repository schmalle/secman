# CrowdStrike Vulnerability Import

Service: `CrowdStrikeVulnerabilityImportService` (`src/backendng/src/main/kotlin/com/secman/service/`).
Spec: `specs/048-prevent-duplicate-vulnerabilities/`.

CLI invocation:
```bash
SECMAN_BACKEND_URL=https://secman.example.com SECMAN_SSL_INSECURE=true \
  ./scripts/secmanng query servers --device-type SERVER \
  --severity CRITICAL,HIGH --min-days-open 1 --last-seen-days 1 --save
```

## Pattern: transactional replace

For each (Asset, batch) pair, in one transaction:

```kotlin
@Transactional
open fun importVulnerabilitiesForServer(batch: CrowdStrikeVulnerabilityBatchDto): ServerImportResult {
    val (asset, isNewAsset) = findOrCreateAsset(batch)
    vulnerabilityRepository.deleteByAssetId(asset.id!!)            // wipe
    vulnerabilityRepository.saveAll(batch.vulnerabilities.map { Vulnerability(asset, it.cveId, …) })  // reinsert
    return ServerImportResult(...)
}
```

Guarantees:
1. **Idempotent** — same input → same DB state (5 vulns stays 5, never grows).
2. **No duplicates** — `(asset, cve)` unique by construction.
3. **Atomic** — delete + insert all-or-nothing per server; one server's failure doesn't affect others.
4. **Remediation tracking** — CVEs missing in the next batch are removed (= patched).

## Why not upsert / soft-delete / diff?

| Approach | Why rejected |
|---|---|
| Upsert | complex merge logic; slow per-row; remediation hard to detect |
| Soft delete | DB bloat; query complexity; stale data |
| Differential sync | high complexity, error-prone, weak consistency guarantees |
| **Transactional replace** | ✅ chosen — simple, accurate remediation, bulk-friendly, clean state |

Bulk delete + bulk insert outperforms per-row upsert; remediation falls out of "missing rows".

## CRITICAL: no JPA cascade on `Asset.vulnerabilities`

```kotlin
// ❌ WRONG — silent 99% data loss (real incident: 166,812 imported → 1,819 retained)
@OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true)
var vulnerabilities: MutableList<Vulnerability> = mutableListOf()

// ✅ CORRECT
@OneToMany(mappedBy = "asset", fetch = FetchType.LAZY)
var vulnerabilities: MutableList<Vulnerability> = mutableListOf()
```

Sequence with cascade enabled: service `deleteByAssetId()` → `saveAll(new)` → transaction commits → JPA flush sees the new rows are not in `asset.vulnerabilities` collection → cascade-deletes them → 99% loss.

Use **explicit** repository calls in service code:

```kotlin
// CrowdStrikeVulnerabilityImportService.kt
vulnerabilityRepository.deleteByAssetId(asset.id!!)
vulnerabilityRepository.saveAll(vulnerabilities)

// AssetCascadeDeleteService.kt
vulnerabilityRepository.deleteByAssetId(assetId)
assetRepository.delete(asset)
```

## Timestamp fix (overdue calculation)

Fixed 2025-11-17. Earlier code used `scanTimestamp = LocalDateTime.now()`, so `daysOpen = now − scanTimestamp` was always 0–1, masking real age.

Now:
```kotlin
val (scanTimestamp, daysOpenText) = if (usePatchPublicationDate && patchPublicationDate != null) {
    Pair(patchPublicationDate, daysText)                                   // Feature 041
} else {
    Pair(currentImportTime.minusDays(vulnDto.daysOpen.toLong()), daysText) // discovery time
}
```

Example: now = 2025-11-17, CrowdStrike `daysOpen=901` → `scanTimestamp = 2023-04-16` → overdue = 901 days → red badge ✅.

Toggles via env (`docs/ENVIRONMENT.md`):
- `VULN_USE_PATCH_PUBLICATION_DATE=false` (default): days_open = now − discovery
- `VULN_USE_PATCH_PUBLICATION_DATE=true`: days_open = scan_ts − patch_publication_date

### One-shot migration for legacy rows

`POST /api/vulnerabilities/migrate-timestamps?dryRun=true` (ADMIN). Parses `daysOpen` text ("901 days" → 901), recomputes `scanTimestamp = now − daysOpen`, optionally honors `patchPublicationDate`.

```bash
curl -X POST 'https://secman.example.com/api/vulnerabilities/migrate-timestamps?dryRun=true' \
  -H "Authorization: Bearer $TOKEN"
curl -X POST 'https://secman.example.com/api/vulnerabilities/migrate-timestamps' \
  -H "Authorization: Bearer $TOKEN"
```

Verify:
```sql
SELECT id, vulnerability_id, days_open, scan_timestamp,
       DATEDIFF(NOW(), scan_timestamp) AS calculated_days
FROM vulnerability ORDER BY scan_timestamp DESC LIMIT 10;
```

UI: vulns >30 days should show red OVERDUE badge. Threshold under Admin > Vulnerability Settings.

## Edge cases

- **Same hostname twice in one batch** — last entry wins (per-server transactions). Dedupe upstream.
- **Concurrent imports for same asset** — serialized by transaction lock. Last commit wins; no duplicates.
- **Vulns without CVE ID** — filtered out, counted in `ImportStatisticsDto.vulnerabilitiesSkipped`.
- **Error mid-import** — transaction rolls back; DB unchanged; per-server isolation preserved.

## Performance

| Dataset | Time | Bottleneck |
|---|---|---|
| 10 servers / 50 vulns | <5 s | network |
| 100 / 500 | <30 s | DB writes |
| 1000 / 5000 | <5 min | DB writes |

Per-server transactions = small commits, low contention, parallel-friendly. Bulk SQL for both delete and insert. Indexes: `asset_id`, `(asset_id, vulnerability_id)`.

## Idempotency

```
Import 1 (5 CVEs)  →  5 rows
Import 2 (same)    →  5 rows         (NOT 10)
Import 3 (same)    →  5 rows         (NOT 15)

Statistics distinguish them:
  Import 1: serversCreated=1, serversUpdated=0, vulnerabilitiesImported=5
  Import 2: serversCreated=0, serversUpdated=1, vulnerabilitiesImported=5  ← replaced
```

## Public freshness endpoint

```
GET /api/crowdstrike/last-checkin              (no auth, text/plain)
→ "2026-04-21T08:42:13.511"   or   "never"
```

Reads latest `crowdstrike_import_history.imported_at`, written by `recordImportHistory()` at the end of every import (CLI included).

Telegram alert script: `src/clinotify/check_crowdstrike_checkin.py`. Stdlib-only Python:

```cron
*/10 * * * * TELEGRAM_BOT_TOKEN=… TELEGRAM_CHAT_ID=… \
  /opt/secman/src/clinotify/check_crowdstrike_checkin.py \
  --url https://secman.example.com --max-age-minutes 120
```

## Tests

`src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServiceTest.kt` covers: idempotent re-import, initial create, remediation removal, per-asset isolation, null-CVE filtering, expansion.

```bash
./gradlew test --tests "CrowdStrikeVulnerabilityImportServiceTest"
```
