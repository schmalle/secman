# CrowdStrike Vulnerability Import

Service: `CrowdStrikeVulnerabilityImportService` (`src/backendng/src/main/kotlin/com/secman/service/`).
Spec: `specs/048-prevent-duplicate-vulnerabilities/`.

CLI invocation:
```bash
SECMAN_BACKEND_URL=https://secman.example.com SECMAN_SSL_INSECURE=true \
  ./scripts/secmanng query servers --device-type SERVER \
  --severity CRITICAL,HIGH --min-days-open 1 --last-seen-days 1 --save
```

## Installed products import

Installed products are synchronized from CrowdStrike Discover with the separate CLI command `secman installed-products`. This is a software-inventory import, not a vulnerability import, and it intentionally only attaches rows to assets that already exist in SecMan. Run a vulnerability/asset import such as `query servers --save` first when onboarding a new environment.

```bash
./scripts/secman installed-products --device-type SERVER --dry-run
./scripts/secman installed-products --device-type SERVER --backend-url https://secman.example.com
./scripts/secman installed-products --device-type ALL --limit 500 --verbose
```

Operational notes:

1. `--dry-run` queries CrowdStrike Discover and reports row/host counts without authenticating to the SecMan backend.
2. Import mode authenticates with `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` and posts each page to `POST /api/installed-products/import`; the backend allows `ADMIN` and `VULN` roles for this endpoint.
3. CrowdStrike host filters are `SERVER`, `WORKSTATION`, or `ALL`; `ALL` runs the server and workstation filters separately.
4. Backend matching is hostname-based and case-insensitive, with a fallback from FQDN to short hostname. Missing assets are counted as `unknown systems` and skipped rather than auto-created.
5. Upserts prefer CrowdStrike external ID scoped to the asset, then fall back to logical duplicate matching on asset, product name, vendor, and version. Conflicting external IDs assigned to another asset are skipped.
6. `GET /api/installed-products` lists imported products for `ADMIN`, `VULN`, and `SECCHAMPION`; non-admin callers only see products for accessible assets.

See `docs/CLI.md` for the full option table and troubleshooting guidance.

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

Per-server transactions = small commits, low contention, parallel-friendly. Bulk SQL for both delete and insert. The `vulnerability` table carries 6 secondary indexes (post V217 trim): `(asset_id, scan_timestamp)`, `(cvss_severity)`, `(vulnerability_id)`, `(asset_id, vulnerability_id)`, `(scan_timestamp)`, `(asset_id, import_timestamp)`. Three earlier indexes — `idx_vulnerability_asset`, `idx_vulnerability_sort_order`, `idx_vulnerability_product` — were dropped because they were prefix-redundant, served no actual ORDER BY (the named ORDER BY interleaves a column from a joined table), and used disqualified `LOWER(...) LIKE '%...%'` patterns respectively. The drop cut per-row index maintenance by ~33% during import.

## Concurrency: READ COMMITTED + jittered retry

The CLI dispatches batches across 3 worker threads. Each worker POSTs to `/api/crowdstrike/vulnerabilities/save`, where the backend runs the per-server `@Transactional` delete-then-insert. With ~1881 servers and the `vulnerability` table carrying 9 indexes, three concurrent inserters under MariaDB's default REPEATABLE READ would collide on next-key (gap) locks at the head of `idx_vulnerability_sort_order` (timestamp-leading), `idx_vulnerability_severity` (4-5 distinct values), and `idx_vulnerability_cve` (popular CVEs).

**Mitigation, in order:**

1. **Connection pool defaults to READ COMMITTED** (`application.yml`: `datasources.default.transaction-isolation: TRANSACTION_READ_COMMITTED`). Under RC, secondary-index INSERTs take only the row lock — no gap locks — so concurrent inserters on the same hot pages no longer deadlock. This is the load-bearing fix; the rest are belts.
2. **Audited safe**: zero code paths in the repo depend on RR snapshot semantics. All multi-step writes use `PESSIMISTIC_WRITE` (isolation-agnostic) or `@Version` optimistic locks. All check-then-act flows are protected by DB-level unique constraints.
3. **`withDeadlockRetry`** in `CrowdStrikeVulnerabilityImportService` wraps each per-server transaction in 5 attempts (4 retries) with exponential + full jitter (100/200/400/800 ms, randomized ±50%). Catches MariaDB 1213 (deadlock) and 1205 (lock-wait timeout). Each retry is a fresh proxy-wrapped transaction.
4. **Rollback path**: revert the `transaction-isolation` line in `application.yml` — the per-transaction code paths are unchanged, so the system falls back to REPEATABLE READ instantly. Expect deadlock-error counts to climb back to ~0.5–1% of server imports if you do.

If you see deadlock errors again, check: (a) was the isolation reverted? (`SHOW VARIABLES LIKE 'transaction_isolation'`); (b) did someone add a new low-cardinality index to `vulnerability`?; (c) did CLI parallelism get cranked above 3?

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

## Stale vulnerability cleanup (silent-remediation safeguard)

The per-host transactional replace only runs for hosts the CLI actually
sends. When yesterday's critical is patched and today Falcon reports nothing
(or only LOW findings) for that host, the host drops out of the per-host
batch entirely — the per-host wipe never fires for it.

To close that gap, after all per-host imports the CLI calls
`POST /api/crowdstrike/servers/reconcile-stale`, which deletes any
CrowdStrike-sourced vuln row whose `import_timestamp` predates the run start.

Three layers of safety prevent over-deletion:

1. **`vulnerability.source` (V213)** — every row carries the importer that
   wrote it. The reconcile sweep only touches rows where
   `source = 'CROWDSTRIKE'`, so XLSX/manual rows are isolated.
   See `com.secman.constants.VulnerabilitySources`.
2. **Severity history (V214)** — `crowdstrike_severity_history` records every
   severity ever queried by a CrowdStrike run. The sweep uses the union of
   the current run's `--severity` flag and that history. Drift between runs
   (e.g. yesterday `CRITICAL,HIGH`, today `CRITICAL`) no longer creates a
   gap: today's sweep still covers HIGH because HIGH is in the history.
3. **Hard-fail on reconcile error** — `VulnerabilityStorageService` throws
   `ReconcileFailedException` on any HTTP/transport error; the CLI exits
   with code `2` and prints an explicit operator message. Cron pipelines
   see the failure instead of silently leaving stale rows.

Coverage: `CrowdStrikeStaleVulnerabilityIntegrationTest` (5 cases — happy path,
empty payload, source-on-human-owned-asset, severity drift, XLSX isolation).

## Tests

`src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServiceTest.kt` covers: idempotent re-import, initial create, remediation removal, per-asset isolation, null-CVE filtering, expansion.

`src/backendng/src/test/kotlin/com/secman/integration/CrowdStrikeStaleVulnerabilityIntegrationTest.kt` covers the reconcile/source/severity-history pipeline end-to-end against a real MariaDB.

```bash
./gradlew test --tests "CrowdStrikeVulnerabilityImportServiceTest"
./gradlew test --tests "CrowdStrikeStaleVulnerabilityIntegrationTest"
```
