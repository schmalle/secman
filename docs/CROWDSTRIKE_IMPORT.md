# CrowdStrike Vulnerability Import

## Independent devices and duplicate hostnames

A hostname is a searchable label, not a unique machine identity. Two Falcon AIDs
may share the same hostname, domain and account and remain separate SecMan assets.
Each asset keeps its own findings, addresses, snapshots, permissions and history.
Devices without verified cloud instance/account continuity remain separate by AID.
A shared hostname or IP never authorizes merging or retiring another device.

Only records reporting the same non-empty cloud instance and account are automatic
replacement candidates. Within that identity, latest valid Falcon `last_seen`
selects the active enrollment; `first_seen` remains enrollment continuity evidence.
Missing contact timestamps, future contacts, contacts preceding enrollment, and
latest-contact ties reject selection when ranking is necessary. An explicit AID
lookup does not require ranking against unrelated devices. A failed selected fetch
never falls back to another device. Stale responses cannot overwrite newer contact
or identity observations.

A hostname-only interactive query defaults to the AID with the most recent valid
contact and reports the other matching identities. This preference does not retire
them. If contact ordering changes, the default may change while asset IDs remain
stable. Asset-scoped queries and synchronization use that asset's stored AID.
For explicit CLI selection:

```bash
./scripts/secman query servers --hostnames ARCGIS
./scripts/secman query servers --hostnames ARCGIS --all-matches
./scripts/secman query servers --aid <falcon-aid>
```

Add `--save` to import the selected devices. Bulk imports preserve all independent
AIDs, including complete zero-finding snapshots; incomplete fetches preserve stored
findings. Activity filters apply per independent identity after selection.

Admins can use `GET /api/crowdstrike/devices?hostname=ARCGIS&allMatches=true`
or `GET /api/crowdstrike/devices?aid=<aid>` for explicit live queries.
Other users use the authorized `/api/vulnerabilities?assetId=<id>` path.
The inventory shows SecMan IDs and AIDs alongside contact and cloud metadata;
links and actions address the SecMan asset ID.

## Legacy conflict review

`GET /api/crowdstrike/identity-preview?hostname=ARCGIS` is ADMIN-only and read-only.
It returns up to 101 matching stored assets, their AIDs and cloud metadata, with
`BOUND_DEVICE_PRESERVE`, `LEGACY_UNBOUND_REVIEW`, `MULTIPLE_BINDINGS_REVIEW`, or
`UNVERIFIED_BINDING_REVIEW`. Compare this inventory with the explicit live-device
query before repairing old bindings. More matches require narrowing the review.
The preview does not prove supersession or perform repairs. Existing ambiguous
bindings, account/domain conflicts and instance-change enrollment mismatches still
require evidence and review; this change does not silently repair all legacy
production errors. No migration or bulk rewrite of existing assets is required.

### Repair independent EC2 devices bound to one legacy asset

Use `reconcile-crowdstrike-identities` for an asset with 2..20 unverified legacy
AID bindings when Falcon confirms distinct EC2 instances. The command defaults to
a read-only preview. It calls the ADMIN-only
`POST /api/crowdstrike/identity-reconcile` endpoint. Preview fetches device metadata
and checks enrollment, account, instance and destination ownership before any
Spotlight download. Apply repeats these checks, then fetches complete, unfiltered
findings for every expected AID and revalidates identity before committing.
No SQL edits or invented Falcon timestamps are needed.

For automatic discovery across the inventory, omit asset IDs and AIDs:

```bash
# Discover candidates and validate each repair without changing data
./scripts/secman reconcile-crowdstrike-identities \
  --all-candidates \
  --reason 'Reconcile legacy Falcon identity bindings'

# Discover and apply each verified repair
./scripts/secman reconcile-crowdstrike-identities \
  --all-candidates --apply \
  --reason 'Reconcile legacy Falcon identity bindings'
```

`--all-candidates` is mutually exclusive with `--asset-id` and `--expected-aids`.
It discovers assets with 2..20 bindings whose Falcon enrollment/contact and
identity-observation timestamps are all null. Discovery is local evidence only;
each candidate must still pass the complete server-side provider and repair
validation described below. Assets with verified bindings or more than 20 AIDs
remain outside this automatic recovery and require separate review.

The ADMIN-only `GET /api/crowdstrike/identity-reconcile/candidates` accepts
`afterId` (default 0), `limit` (default 50, range 1..100), and optional `throughId`.
It returns `candidates[]` (`assetId`, `hostname`, `expectedAids`), `nextAfterId`,
`hasMore`, and `throughId`. The first page captures the highest inventory asset ID;
the CLI reuses it for later pages. New assets above that boundary are handled on
the next invocation. Pages advance by scanned asset ID rather than offset, so
repairs removing earlier candidates do not skip later ones. Concurrently resolved
rows can produce an empty advancing page, which the CLI follows normally.

The CLI uses one authenticated session, fetches one bounded page at a time, and
calls the existing repair endpoint separately for each candidate. There is no
fleet-wide transaction. A rejected or failed candidate leaves that asset unchanged
and does not stop unrelated repairs. Authentication loss stops the run. Complete
discovery failure also stops the run without undoing earlier committed repairs.
After a gateway error or lost response, the CLI performs one read-only lookup of
the durable request status. A confirmed terminal result is reported normally;
an unconfirmed outcome is `UNKNOWN`. No write is retried automatically.

Automatic mode writes one JSON object per line: a `type: request`, `status: STARTED`
event containing the client-generated `requestId` before each POST, a
`type: candidate` result, and finally a `type: summary` event. Candidate statuses are `PREVIEWED`,
`REPAIRED`, `REJECTED`, `ALREADY_RESOLVED`, `FAILED`, and `UNKNOWN`.
`ALREADY_RESOLVED` means the source now has one verified binding and no repair was
performed for this request; it does not independently certify every former device.
The summary includes per-status `totals`, `discovered`, `unresolved`, `complete`,
`throughId`, `lastScannedAssetId`, and `resumeAfterId`. `complete` describes
enumeration, not universal repair success. Exit 0 requires complete enumeration
and zero unresolved candidates; exit 1 reports incomplete processing or any
rejection/failure/unknown result. Invalid command arguments exit 2. No candidates
produces an empty successful summary and no repair requests.

`--batch-size` changes the discovery page size (default 50, range 1..100).
`--after-id` resumes after the given inventory ID. Every candidate event carries
`resumeAfterId`, the last checkpoint before the first unresolved result; it never
advances past a failure. Save stdout if a durable record is needed:

```bash
./scripts/secman reconcile-crowdstrike-identities \
  --all-candidates --apply --batch-size 25 --after-id 0 \
  --reason 'Reconcile legacy Falcon identity bindings' > identity-recovery.jsonl
```

After interruption, reuse the last reported `resumeAfterId` with `--after-id`.
Earlier repaired assets no longer qualify for discovery. Starting again from 0
also works and revisits unresolved candidates; it does not duplicate completed
splits. Resume captures a fresh upper inventory boundary. A preview checkpoint
is not an approval or reservation for a later apply run: start apply from 0 to
include every previewed candidate.

```bash
./scripts/secman reconcile-crowdstrike-identities \
  --asset-id 42 \
  --expected-aids <first-aid>,<second-aid> \
  --reason 'Confirmed two independent EC2 instances in Falcon'
```

Replace the placeholders with the complete AIDs from the identity preview.
Review each returned AID, instance, account, destination asset ID and supported
finding count when available. Preview returns `findings: null` and
`findingsFetched: false`: no findings were downloaded, so null does not mean zero.
Apply returns actual counts and `findingsFetched: true`. A null destination means
the apply operation will create an asset.
Use the same command with `--apply` to commit. Every apply refetches provider
evidence and revalidates stored state; a preview does not reserve identities.
Credentials use `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` through the existing secret
provider, and the backend origin uses `SECMAN_HOST`, `SECMAN_BACKEND_URL`, or
`--backend-url`. The CLI uses TLS verification and does not follow redirects.

The REST body is:

```json
{
  "assetId": 42,
  "expectedAids": ["first-aid", "second-aid"],
  "reason": "Confirmed two independent EC2 instances in Falcon",
  "dryRun": true,
  "requestId": "a6b2f8aa-340b-47dc-a09f-80c21f6bb9e2"
}
```

The operation requires all of the following:

- The expected AID set exactly matches the source asset's current bindings.
- Every binding has null Falcon first-seen, last-seen and identity-observation
  timestamps. Verified bindings require a separate review; this recovery does not
  override established enrollment history.
- Each AID reports a valid EC2 instance, valid enrollment/contact timestamps and
  the same nonempty 12-digit AWS account as the stored asset. Missing or conflicting
  accounts stop the operation; hostname equality cannot authorize a cross-account move.
- Every instance is distinct, and exactly one matches the source's stored instance.
- No destination has multiple stored owners or unrelated Falcon bindings.
- Every provider query and snapshot is complete, within the configured sensor
  retention policy, and accepted by the normal import validation. Findings
  completeness is checked during apply, not the metadata-only preview.

The source asset keeps its ID, instance, owner, display-name override, workgroups,
manual findings and other inventory relationships. New assets receive no copied
manual grants or relationships; normal account/domain access rules still apply.
An existing unbound asset with the exact account and instance can be reused while
preserving its own relationships. An already bound destination is rejected for
review. All old mixed CrowdStrike findings on the source are replaced by its own
device's freshly fetched findings, including a verified zero result. Source software
inventory is invalidated and must be repopulated by the normal product import;
ambiguous software rows are not moved by hostname.

Binding changes, per-device findings, snapshot provenance and append-only
`crowdstrike_identity_repair` audit rows commit in one transaction. Failure on any
device rolls back the entire repair, including changes already staged for another
device. Audit rows record operation ID, actor, reason, source/destination IDs, AID,
instance/account and Falcon timestamps. Migration V293 adds this audit table;
V294 adds durable request tracking. Neither repairs or deletes production inventory.
Cache invalidation and deferred view
refresh are requested after commit; refresh failures are logged separately.

HTTP 409 returns a safe `errorCode`, explanation, `requestId` and, where relevant,
the affected `aid`. The CLI preserves these fields in its candidate output.
Common classifications are:

| Code | Meaning / next action |
|---|---|
| `SOURCE_ACCOUNT_MISSING`, `SOURCE_ACCOUNT_INVALID`, `SOURCE_INSTANCE_MISSING`, `SOURCE_NOT_EC2` | Stored inventory lacks valid EC2/account evidence. Review the source asset. |
| `DEVICE_ACCOUNT_MISSING`, `DEVICE_INSTANCE_MISSING`, `DEVICE_INSTANCE_INVALID` | Falcon metadata lacks valid evidence for the reported AID. |
| `CLOUD_ACCOUNT_CONFLICT` | Falcon and stored accounts differ. Review ownership; hostname equality cannot authorize a move. |
| `SAME_INSTANCE_ENROLLMENTS` | Several AIDs represent enrollments of the same instance. This independent-instance split does not apply. |
| `ENROLLMENT_TIMESTAMP_MISSING`, `CONTACT_TIMESTAMP_INVALID`, `SENSOR_RETENTION_EXCLUDED` | Enrollment/contact evidence is absent, invalid or outside retention. |
| `RETAINED_INSTANCE_NOT_FOUND` | No device matches the source's stored instance. |
| `DESTINATION_AMBIGUOUS`, `DESTINATION_ALREADY_BOUND` | Destination ownership needs review. |
| `BINDINGS_CHANGED`, `SOURCE_CHANGED`, `VERIFIED_BINDING_PRESENT` | Stored state no longer meets recovery preconditions. Preview again or review verified history. |
| `INCOMPLETE_FINDINGS`, `IDENTITY_CHANGED_DURING_FETCH`, `STALE_DEVICE_OBSERVATION` | Apply could not establish a complete, consistent snapshot. Existing data is preserved. |

403 denies non-admin callers and 404 masks unavailable assets. Unexpected failures
use safe messages; provider/database details remain in server logs correlated by
request ID. HTTP 504 alone does not establish whether a repair committed.

Check a request without resubmitting its repair:

```bash
./scripts/secman reconcile-crowdstrike-identities \
  --request-status a6b2f8aa-340b-47dc-a09f-80c21f6bb9e2
```

`--request-status` accepts no repair/discovery options or reason. It calls the
ADMIN-only `GET /api/crowdstrike/identity-reconcile/requests/{requestId}` and exits
0 for `PREVIEWED`/`REPAIRED` or `ALREADY_RESOLVED`, 1 otherwise. Status includes request ID, asset ID,
mode, timestamps, safe rejection details and the completed report when available.
Manual repair emits its initial request event to stderr; automatic mode emits it
to stdout. Preserve that event even if the process is interrupted.

The server commits `RUNNING` before provider I/O. `REPAIRED` and its report commit
in the same transaction as the inventory repair and audit rows. `PREVIEWED`,
`REJECTED` and `FAILED` are also durable terminal states. `RUNNING` can mean work
is active or was interrupted by a backend restart; it is not a liveness guarantee.
A missing status or `RUNNING` leaves the outcome unconfirmed: inspect correlated
backend/audit evidence before retrying. This is synchronous execution with durable
tracking, not a background job queue. Request records persist until an explicit
retention mechanism is introduced.

REST callers may supply a UUID `requestId`; the CLI always does. Omitting it lets
the server generate one, which cannot be recovered by the caller if the entire
response is lost. Reusing the same ID, actor and payload returns the stored result
or `REQUEST_IN_PROGRESS` without executing again; different payloads/actors return
`REQUEST_ID_CONFLICT`. Repeating a completed split with a new ID no longer meets
the multi-binding precondition. Ordinary per-device imports remain repeatable.

Legacy upload payloads cannot create multiple AID bindings or silently replace an
existing multi-AID binding. Normal imports report AID and instance alongside each
rejection; structured outcomes also include `aid` and `accountId`. Failed-device
counts use device outcomes rather than deduplicated hostnames. Single-host query
age statistics are saved only when every selected device successfully imports;
a fetched-findings summary is not a persistence success signal.

If Falcon omits a cloud device's hostname, its instance ID supplies the asset name
and it participates in selection with named aliases only when the same instance and account are verified.
Devices lacking both names and instance IDs are excluded only after Falcon confirms
they have no open findings; otherwise the import stops before replacement.

The CLI sends one active AID and a separate bounded `deviceSelection` containing
Falcon enrollment/check-in timestamps and superseded records. Zero-finding winners
are sent too. These payloads require an ADMIN account. After a successful fetch,
the backend validates the selection and performs import and cleanup in one per-host
transaction. It removes old bindings on the canonical asset and deletes proven
superseded duplicate assets using audited cascade deletion. It preserves the
canonical asset's display-name override, owner and workgroups; it does not copy
access grants from duplicates. Cross-account/domain conflicts, unknown bindings,
ambiguous legacy assets, unknown legacy duplicates stop that host's replacement for review. Proven retired
duplicates with manual or integration relationships retain their inventory, grants
and manual findings; only their retired CrowdStrike contribution is removed. Legacy duplicate assets without
AID bindings require a matching retired cloud instance and CrowdStrike import ownership.

On a device transition, the canonical asset's old CrowdStrike snapshot is cleared
across all severities before importing the winner's queried slice. Other vulnerability
sources on the canonical asset are preserved. Its old installed-product snapshot
is invalidated on the transition and repopulated by the product importer, which
rejects retired AIDs even when their hostname matches the surviving asset. A CRITICAL/HIGH-only import still has
only CRITICAL/HIGH coverage; invalidating old LOW/MEDIUM rows is not evidence of
remediation. Subsequent imports of the same selected device retain severity-scoped
replacement. Old clients cannot reattach a retired AID to an already selected asset.
Non-admin domain synchronization queries selected devices but does not retire assets.

Migration `V283__falcon_device_enrollment_timestamps.sql` adds nullable Falcon
`first_seen`/`last_seen` columns to the identity table. Existing `first_seen_at` and
`last_seen_at` remain SecMan observation times. The next successful ADMIN import
populates the Falcon timestamps and performs evidence-backed cleanup; the migration
itself deletes nothing. Deploy the backend migration before the rebuilt CLI.

## Verified snapshot synchronization

Migration `V286__crowdstrike_snapshot_provenance.sql` adds per-severity snapshots
and finding provenance, plus a database identity lock to serialize imports across
backend processes. It does not invent provenance for historical rows or delete
them. Legacy findings remain visible and explicitly unverified until a successful
authoritative synchronization establishes their disposition.

Use `POST /api/crowdstrike/vulnerabilities/sync` as ADMIN with
`{"hostname":"server.example.com","assetId":42}`. The backend resolves the stored
instance/account, refetches all severities from Falcon, and commits binding,
coverage and findings together. The compatibility save endpoint also refetches;
browser-submitted findings are not trusted as replacement evidence. The UI action
is **Synchronize from Falcon**, including when the live result has zero findings.

A successful empty result with reported total zero clears the fetched scope.
Missing pagination totals, 404, provider errors, inconsistent totals and incomplete
pagination preserve stored findings. A successful same-enrollment scoped import
retains other severity scopes; enrollment replacement invalidates all retired
CrowdStrike scopes and installed products. Saved server queries fetch every age in
the requested severity scope, regardless of `--min-days-open`, so presentation
filters cannot incorrectly erase younger findings.

Responses expose asset ID, selected AID, successful snapshot time and coverage.
A verified empty database snapshot remains a database result. Current ORM/native
readers exclude retired proven snapshots; unknown coverage does not count as a
clean AWS server. Sensor contact today and findings snapshot age remain distinct.

ADMIN can page through local reconciliation evidence using
`GET /api/crowdstrike/snapshots/reconciliation?afterId=0&limit=100`, then synchronize
individual asset IDs. This preview does not contact Falcon or claim that missing
provider records are retired. It reports legacy findings, binding counts and
verified scopes. Resolve ambiguous/unverified identity groups individually before
attempting retirement; no migration performs an absence-based fleet purge.

## Asset IP addresses

The import reads the current `local_ip`, `external_ip`, `connection_ip`, and
`ip` fields from CrowdStrike device entities in bounded 100-device chunks,
deduplicating up to 100 addresses per device. It does not query network-address
history. Current device addresses take precedence over older Spotlight host
snapshots; Spotlight addresses are a fallback when device addresses are missing.

When a matched asset has a non-empty set of reported addresses, the import
**replaces** its stored `asset_ip_address` set and updates `Asset.ip` to the
reported local IP (or the first sorted current address). For example, an asset
previously reporting IP1 that now reports only IP2 will contain only IP2 after
import. Repeating the same import leaves the same addresses.

Missing or blank IP data preserves the existing primary and address set. The
asset register has no per-address source ownership: a non-empty CrowdStrike
result replaces the entire set, including addresses entered manually or by other
importers. Other importers retain their existing behavior.

The set is returned as `ipAddresses` by the detail and paginated inventory APIs.
Inventory filtering searches primary and secondary addresses and counts each
matching asset once. These are addresses reported in the latest imported data,
including external addresses; they are not a verified interface inventory.
Existing historical addresses are removed on the next import that supplies IP
data; no database migration or bulk cleanup is required.

Service: `CrowdStrikeVulnerabilityImportService` (`src/backendng/src/main/kotlin/com/secman/service/`).
Spec: `specs/048-prevent-duplicate-vulnerabilities/`.

CLI invocation:
```bash
SECMAN_BACKEND_URL=https://secman.example.com \
  ./scripts/secman query servers --device-type SERVER_FAMILY \
  --severity CRITICAL,HIGH --min-days-open 1 --last-seen-days 30 --save
```

## Domain controller coverage

CrowdStrike classifies domain controllers separately from ordinary servers. Use
`SERVER_FAMILY` to query both exact Falcon device categories without including
workstations. The production vulnerability and installed-product import scripts
use this scope.

Available scopes are:

- `SERVER` — ordinary Falcon `Server` devices only.
- `DOMAIN_CONTROLLER` — Falcon `Domain Controller` devices only.
- `SERVER_FAMILY` — `Server` plus `Domain Controller`, queried separately and
  deduplicated by Falcon agent ID.
- `WORKSTATION` — Falcon `Workstation` devices only.
- `ALL` — all three exact device categories.

The values are case-insensitive CLI inputs, but their spelling is otherwise
literal: use `DOMAIN_CONTROLLER` and `SERVER_FAMILY` with underscores. The CLI
keeps `SERVER` as its backward-compatible default; the production wrapper
scripts explicitly select `SERVER_FAMILY` so domain controllers are not omitted.

Domain controllers are stored as SecMan `SERVER` assets. This intentionally
preserves the existing authorization, risk, KPI, and notification semantics; no
schema migration or new SecMan asset type is required. Asset identity still
resolves fail-closed by Falcon agent ID, cloud instance ID, and hostname as
described below.

## User-edited asset names

`Asset.name` is the user-facing display name. CrowdStrike's latest reported name is
stored separately in `Asset.crowdStrikeHostname` and is used for later import and
reconciliation matching. This prevents a user rename from making the next import create
a second asset under the source hostname.

The import resolves an existing asset by Falcon agent ID (AID), then cloud instance ID,
then by the source hostname (short name or FQDN) for legacy payloads. Latest-device
payloads retain one active AID per asset and retire proven superseded enrollments. Conflicting AID or cloud-instance mappings fail
the host transaction instead of silently merging assets. The importer falls back to `Asset.name` only for legacy rows whose
source hostname has not yet been adopted. When that legacy row is found by cloud instance
ID and its display name differs, SecMan conservatively records it as a legacy user override.

Editing `name` through `PUT /api/assets/{id}` or MCP `update_asset` sets override metadata.
Imports continue updating `crowdStrikeHostname`, IP, groups, account/instance IDs, domain,
OS and timestamps, but do not touch the overridden display name. To return control to
CrowdStrike, either:

```http
POST /api/assets/{id}/name/reset
```

or call MCP `update_asset` with:

```json
{"assetId": 42, "resetNameToCrowdStrike": true}
```

Both reset paths use the normal unified asset-access check. A reset fails when the asset
has never had a CrowdStrike hostname. Supplying `name` and `resetNameToCrowdStrike` in the
same request is rejected as ambiguous.

## Operating system per asset

The `query servers --save` import also captures the operating system of each host
and stores it on `Asset.osVersion` (column `os_version`). No separate command or
extra CrowdStrike round-trip is needed — OS is resolved in the same pass as the
other host metadata (IP, AD domain, cloud account/instance).

- **Source**: the device entity from `/devices/entities/devices/v2`
  (`os_version`, e.g. `"Windows Server 2019"`; falls back to `platform_name`),
  with a final fallback to `host_info.os_version` / `host_info.platform` on the
  vulnerability record. Extraction lives in
  `CrowdStrikeApiClientImpl.mapResponseToDtos` (shared module) and is carried on
  `CrowdStrikeVulnerabilityDto.osVersion`.
- **Wiring**: `ServersCommand` selects the latest-detected non-blank OS per host
  into `ServerVulnerabilityBatch.osVersion`, which the CLI posts as
  `osVersion` on each `CrowdStrikeVulnerabilityBatchDto`.
- **Persistence**: `createNewAsset` sets it on create; `updateAsset` overwrites it
  when a newer non-blank value differs (existing behaviour — unchanged).
- **Visibility**: exposed read-only on `GET /api/assets` (`AssetResponse.osVersion`)
  and shown as the **OS** column in the asset management UI, plus the MCP
  `get_assets` / `get_asset_profile` tools and the asset Excel export.

## Installed products import

Installed products are synchronized from CrowdStrike Discover with the separate CLI command `secman installed-products`. This is a software-inventory import, not a vulnerability import, and it intentionally only attaches rows to assets that already exist in SecMan. Run a vulnerability/asset import such as `query servers --save` first when onboarding a new environment.

```bash
./scripts/secman installed-products --device-type SERVER_FAMILY --dry-run
./scripts/secman installed-products --device-type SERVER_FAMILY --backend-url https://secman.example.com
./scripts/secman installed-products --device-type ALL --limit 500 --verbose
```

Operational notes:

1. `--dry-run` queries CrowdStrike Discover and reports row/host counts without authenticating to the SecMan backend.
2. Import mode authenticates with `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` and posts each page to `POST /api/installed-products/import`; the backend allows `ADMIN` and `VULN` roles for this endpoint.
3. CrowdStrike host filters are `SERVER`, `DOMAIN_CONTROLLER`, `SERVER_FAMILY`, `WORKSTATION`, or `ALL`; composite scopes run the exact Falcon filters separately and deduplicate hosts by agent ID.
4. Backend matching is hostname-based and case-insensitive, with a fallback from FQDN to short hostname. Missing assets are counted as `unknown systems` and skipped rather than auto-created.
5. Upserts prefer CrowdStrike external ID scoped to the asset, then fall back to logical duplicate matching on asset, product name, vendor, and version. Conflicting external IDs assigned to another asset are skipped.
6. `GET /api/installed-products` lists imported products for `ADMIN`, `VULN`, and `SECCHAMPION`; non-admin callers only see products for accessible assets.

See `docs/CLI.md` for the full option table and troubleshooting guidance.

## Installer / setup payload classification

CrowdStrike Discover reports installer payloads as **first-class application entities**, separate
from the products they install: `Chrome Installer` is a different application from `Chrome`. EOL
and vulnerability findings are therefore raised against things that never run.

`ProductClassificationService` marks those rows `product_class = 'INSTALLER_ARTIFACT'`, and every
vulnerability/EOL list, count, export, statistic and materialized view hides them unless the caller
passes `includeInstallerFindings=true`. Nothing is deleted, and `UNKNOWN` — the column default —
reads exactly like `INSTALLED`, so a lagging materialization over-shows rather than over-hides.

Rules live in `product_classification_rule` (ADMIN CRUD at `/api/product-classification`, UI at
`/admin/product-classification`). A rule is a **glob** (`*`, `?`) — never a regex, which from an
admin would be a ReDoS vector on a per-row path — matched case-insensitively against a product
name, a vendor or an installation path. Rules classifying as `INSTALLED` are an allowlist and are
always evaluated first, whatever their priority.

### Why product identity and not the installation path

Measured against the live tenant on 2026-08-19, before building anything:

| Path pattern | Rows estate-wide | What it actually means |
|---|---|---|
| `*Downloads*` | 5 | a genuine stray installer |
| `*ccmcache*` | 5 | genuine |
| `*Temp*` | 9 | all false hits (a product literally named "EasyTemp") |
| `C:\WINDOWS\Installer\*.msi` | 565594 | Windows' MSI cache **for an installed product** |
| `C:\ProgramData\Package Cache\` | 150812 | WiX bundle cache **for an installed bundle** |

Two conclusions, both load-bearing:

1. **`Windows\Installer` and `Package Cache` must classify as `INSTALLED`.** They are where Windows
   and WiX keep the package of an installed product for repair and uninstall. Splunk Universal
   Forwarder is 100% the former (293 EOL findings) and is running; AWS CLI and Intune Management
   Extension are the same shape. Seeding them as artifact locations would have hidden ~716000 rows
   of genuine risk.
2. **The path cannot carry the signal at all for the worst offender.** `Chrome Installer` — 222 EOL
   findings, 17910 Discover rows — returns **no installation path**. A path-only design would have
   classified none of it.

Path rules are still supported and seeded for `Downloads` / `Temp` / `ccmcache` / `$Recycle.Bin`,
but they are correctness rules, not volume rules. Note also that CrowdStrike's own FQL wildcard
matching on `installation_paths` is **case-sensitive** (`*Downloads*` matches, `*downloads*` returns
0), which is one reason classification happens in Kotlin against a normalized value rather than
server-side in the query.

### `facet=install_usage` is mandatory

`installation_paths`, `installation_timestamp` and `last_used_timestamp` are returned by
`/discover/combined/applications/v1` **only** when `facet=install_usage` is requested. Requesting
only `host_info` makes CrowdStrike omit the fields entirely — no error, no warning. Before this was
fixed, all 182131 `installed_product` rows had `installation_path`, `installed_at` and `last_used_at`
NULL while the mapping code looked correct.

`CrowdStrikeApiClientImplInstalledProductsTest` asserts the facet is on the query string, because a
regression here is silent.

### Where it is applied

- Write time: `InstalledProductImportService` (per row) and
  `CrowdStrikeVulnerabilityImportService` (per asset, beside the existing exception recompute).
- `EolScanService` denormalizes the class from the source `InstalledProduct` onto `ASSET_PRODUCT`
  findings; OS and repository findings are always `INSTALLED`.
- Read time: `VulnQuerySql.NOT_INSTALLER_ARTIFACT` / `VISIBLE`, the 16 status-filtered list
  families, `EolFindingRepository`, `OutdatedAssetMaterializedViewRepository`, and the MCP
  `get_vulnerabilities` tool.
- Rule changes need `POST /api/product-classification/reclassify` ("Reclassify now") to be applied
  to stored rows; it returns 202 and runs in the background, chunked by primary-key range.

**Deliberately unfiltered:** `GET /api/vulnerabilities/current?exceptionStatus=all` falls through to
`findLatestVulnerabilitiesPerAssetWithFilters` / `findLatestVulnerabilitiesWithAccessControl`, which
carry neither the `excepted` nor the `product_class` predicate. That is not an oversight — it is the
"show me everything" path, and `extensions/secman_visual_check` depends on it: it lists existing
findings to deduplicate before uploading, so hiding a row from it would make it re-upload that row.
The UI never sends `all` (the controller defaults a blank value to `not_excepted`).

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
- `VULN_USE_PATCH_PUBLICATION_DATE=true` (**default since 2026-08-24**): the SLA
  anchor is when a **fix became available**, per row, falling back to detection age
  when no patch date is published.
- `VULN_USE_PATCH_PUBLICATION_DATE=false`: anchor is CrowdStrike detection time.

### Why remediation age, and what enabling it costs

Detection age answers "how long have we known", which resets whenever a host is
rebuilt or newly onboarded — a 2023 CVE on a host added last month reads as a month
old. Remediation age answers "how long could we have fixed this and did not", which
is the number an auditor means and the only one that does not reward re-imaging.

Two consequences to plan for:

1. **Ages jump, so overdue counts jump.** A patch date is normally much earlier than
   the detection date, so rows get older the moment they are re-imported, and more of
   them cross the reminder threshold at once. Expect a larger-than-usual notification
   run after the first import following the change.
2. **It is a one-way door.** `first_seen_at` is preserved as `min(prior, new)` across
   re-imports (that is what stops a re-import resetting the SLA clock), so once an
   import writes the older patch date, setting the flag back to `false` does **not**
   restore the previous anchors — the earlier date keeps winning. A genuine revert is:

   ```sql
   UPDATE vulnerability SET first_seen_at = NULL WHERE source = 'CROWDSTRIKE';
   ```
   then set `VULN_USE_PATCH_PUBLICATION_DATE=false` and run a full re-import, which
   re-anchors from detection time. Take a backup first.

Nothing changes until the next import: existing rows keep their current anchors.

### The three dates, and why they differ (2026-08-24)

Falcon hands us three dates that mean three different things. Conflating any two
of them produces numbers that look wrong to a reader and cannot be argued with:

| Date | Field | Meaning |
|---|---|---|
| `created_timestamp` | `detectedAt` → `scan_timestamp` / `first_seen_at` | when **Falcon created this finding on this host** |
| `cve.published_date` | `cve_published_date` (V259) | when the **CVE was disclosed** |
| `remediation.vendor_release_date` | `patch_publication_date` | when a **fix became available** |

**Detection dates cluster; publication dates do not.** Falcon creates every
finding against a package in one batch when that version is first scanned, so a
host shows one identical "days open" for dozens of CVEs — including CVEs whose
IDs carry different years. That is genuine Falcon semantics, not an import bug.

**A CVE's ID year is its reservation year, not its publication year.** Live
example from this estate: `CVE-2023-53178` has `cve.published_date =
2025-09-15`. Linux kernel CVEs are routinely assigned an old-year ID and
published much later, so "a 2023 CVE can't be only 62 days old" is not a sound
inference. The UI now shows the publication date next to the detection age so
this is visible rather than guessed at.

Four defects were fixed alongside (see `docs/CHANGELOG.md` 2026-08-24):

1. `extractPatchPublicationDate` asked `cve.published_date` **first**, so the
   field named for a patch — and used as the SLA anchor when the toggle above is
   on — usually held the CVE disclosure date. Genuine remediation dates now win;
   the CVE fields stay as the **last** resort because
   `require-patch-publication-date` filters imports on this field being non-null.
2. The ad-hoc lookup path parsed `"…Z"` by **stripping the Z** and reparsing as
   local time, relabelling a UTC instant instead of converting it (±1 day on the
   whole-day age). All four copies of Falcon date parsing now share
   `FalconTimestamps`.
2b. An unreadable date silently became `LocalDateTime.now()` — which reads as a
   zero-day-old finding and therefore **never trips an overdue threshold**.
   `FalconTimestamps.parse` returns null, and `CrowdStrikeVulnerabilityDto.detectedAt`
   is nullable so the unknown actually propagates instead of being coerced back at
   the call site. `scan_timestamp` is `NOT NULL`, so persistence still falls back to
   the import time — but logs a WARN naming the CVE and asset, because that row's age
   is then a floor rather than a measurement. `first_seen_at` keeps the earliest
   value, so a later import supplying a real date corrects it one-way.
3. The high-volume CLI batch payload (`/api/crowdstrike/servers/import`) sent
   only `cveId`/`severity`/`affectedProduct`/`daysOpen`. Both publication dates
   were dropped in transit, which is why `VULN_USE_PATCH_PUBLICATION_DATE` could
   never take effect on the main import path. It now sends `detectedAt` and both
   dates, and the backend prefers `detectedAt` over re-deriving a timestamp from
   `daysOpen` against its own clock.
4. Saving from the ad-hoc lookup dropped `patch_publication_date` entirely.

`daysOpen` remains a display-only string. Never compute from it: it is a
whole-day integer measured against whichever clock produced it, and every
`parseDaysOpenToInt` call site defaults a parse failure to `0` — i.e. "not
overdue". `first_seen_at` is the SLA anchor.

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

## Per-host mode (`--hostnames`)

`query servers --hostnames a,b,c` bypasses the two-stage bulk enumeration and
resolves each hostname directly. Each name is resolved to **all** matching device
ids (5-strategy FQL cascade — exact, prefix, contains, lowercase, uppercase; the
first strategy that matches returns its complete deduped list) and Spotlight rows
are merged across those aids. This matters because a re-imaged or re-enrolled host
keeps its hostname but gets a new aid: the bulk import aggregates across all of a
hostname's aids naturally (it enumerates devices, then groups by metadata-derived
hostname — ~2.4 aids per hostname observed in production), so the per-host path
must do the same or it under-reports (observed 2026-08-25: five hosts read 0 rows
via their first-returned aid while a sibling aid held thousands). Each host still
arrives at the backend as ONE batch, so the transactional replace is untouched.
`--device-type` and `--last-seen-days` are deliberately ignored in this mode (a
host named explicitly is resolved without device filters, so a stale aid cannot
silently vanish); the CLI prints a warning and a per-host status line
(`not found` / `resolved, 0 matching` / `N vulnerabilities`).

Bulk pagination is guarded against cursor loops: every `after` token followed per
batch is remembered, a repeated token fails the batch into `failedDeviceIds`
(hosts keep their old rows; the reconcile sweep skips them), continuation requires
a full page, and Falcon's `meta.pagination.total` bounds the fetch. Prior to this
guard, a two-token ping-pong re-fetched the same pages up to the 50-page cap and
inflated single hosts to ~27x their real row count (2026-08-25 incident).

For streaming `--save` imports, incomplete batches contribute no rows to storage.
If one device fails, successful sibling devices for the same hostname or cloud
instance are also excluded from replacement and reconciliation. Hostnames connected by a shared short name or cloud instance
are kept together in an outer fetch batch. Aliases of one cloud instance use a
canonical hostname; distinct cloud instances retain separate identities even when their hostnames match, so later batches cannot overwrite an
earlier partial host payload. Age-filtered pages do not terminate pagination.
Incomplete fetches are included in the final error count and cause exit code `1`.

Multi-AID imports carry the complete device identity set separately from vulnerability
rows. Deduplicating `(CVE, affected product)` therefore does not discard AIDs,
including sibling devices with zero matching findings. The CLI groups replacements
by instance ID with cloud-account compatibility checks when available, so two
machines with the exact same hostname can produce separate payloads. Missing
account/domain metadata does not split a sibling group; conflicting known accounts
or non-cloud AD domains refuse replacement. Compatible short/FQDN aliases make one
non-cloud snapshot; distinct FQDNs sharing a short name are ambiguous and refuse
replacement without a stable machine identity. Metadata comes from device observations rather than the
creation date of a vulnerability. Missing device metadata aborts a streaming run
before replacement starts, because an unresolved AID might belong to any sibling
group.

Hostname-specific lookups expose failed AIDs. A failed device query, cursor loop,
404 after pagination starts, exhausted timeout retry, or page cap makes the lookup
incomplete. Such a lookup cannot replace its stored host snapshot; a multi-host save may still
import independent complete hosts. Read-only queries
may show partial findings with an explicit warning; save failures return nonzero. Product-filtered single-host lookups are read-only:
`--product` with `--save` refuses replacement. Severity-filtered saves forward the
requested severity slice so other severities remain intact.

The backend rejects conflicting stored AID/instance/account/domain identities before
replacement. Reconciliation resolves stable identities first and permits short-name
fallback only when exactly one compatible asset exists. Ambiguous matches are
excluded from both stale deletion and agent-seen stamping, with a warning. Existing
source/severity restrictions, timestamp fences and deletion safety brakes still apply.
Normal imports do not move an existing AID mapping between independent assets.
The explicit ADMIN legacy-repair command described above can split unverified
multi-AID bindings after refetching and validating every device; `--all-candidates`
automates candidate selection, while `--apply` remains required to commit repairs.

An ADMIN import may correct an existing asset's EC2 instance ID when the current
Falcon AID and verified enrollment first-seen timestamp are unchanged, the AWS
account is present and matches, and the AD domain matches. The incoming instance
must be valid and unowned by another asset in that account. Missing identity
evidence, additional unexplained bindings, an older observation, and an older
provider contact timestamp reject the correction. The original asset ID and its
manual relationships remain intact. AID and instance locks serialize concurrent
claims; metadata, findings, snapshots, and the append-only
`crowdstrike_instance_transition` audit commit or roll back together. A repeat
save does not create another transition. V287 adds this history and a nullable
last-accepted identity observation; legacy observation times are not invented.

When a superseded AID now reports a different EC2 instance than its stored asset,
an ADMIN import retires that old binding only with the same verified enrollment,
matching AWS account, non-decreasing Falcon contact and a newer identity observation.
The former physical asset and its manual data remain intact; its CrowdStrike findings,
installed-product snapshot and active vulnerability snapshots are retired. The selected
device is imported into its own compatible asset, or a new asset. An append-only
transition with reason `SUPERSEDED_ENROLLMENT_MOVED` records the former binding.
Missing evidence or unexplained additional AIDs still reject the transaction.

V288 adds the cloud-instance/account lookup index. Identity-key locks serialize claims;
owner discovery uses bound equality predicates without locking the asset-table scan.
Only resolved asset rows receive write locks, avoiding contention between unrelated imports.

Verified snapshots cover supported CVE findings in their declared severity
scope. Findings without CVE IDs are counted as exclusions. Invalid supported
findings or incomplete provider fetches cannot replace stored data. Each new
snapshot records request-wide fetched, accepted, missing-CVE, and duplicate
counts with `coverage_kind=SUPPORTED_CVE`; those totals repeat across its severity
rows and must not be summed across severities. Legacy snapshots retain unknown
counts. Import responses add `hostOutcomes` while retaining existing aggregate
fields. The CLI reports committed instance changes and leaves rejected assets
marked unchanged.

Bulk `query servers` defaults to 4,000 rows per Spotlight page (maximum 5,000).
When a multi-device shard reaches the 50-page bound, its partial rows are discarded
and the device set is split into smaller shards and retried. Splitting stops at a
single device; an incomplete single-device result remains a reported failure.
Cursor anomalies are not retried through this path.

Streaming CLI imports register an authenticated run before fetching, heartbeat every
30 seconds, and explicitly finish after storage and reconciliation. Only the initiating
user can heartbeat or finish the run, and a competing run receives HTTP 409. The
backend holds deferred refreshes and completion notifications while that run is active.
A crashed CLI loses its lease after ten minutes; it does not produce a success event.
An active-run HTTP 409 includes the current lease expiry, which the CLI displays
with retry guidance. Ctrl+C and normal JVM termination attempt to finish the run
as failed, with a five-second cleanup bound; force termination or an unreachable
backend still relies on lease expiry. Normal close and shutdown share one cleanup
request. The backend retains the last 100 finalized results in memory so an owner
can retry finish without changing the original outcome or clearing a newer run.
Failed and reconciliation-aborted runs also suppress success notifications. Older
callers retain the existing quiet-period fallback. CLI backend authentication is renewed
every twenty minutes during long runs. These leases are local to the backend process;
a backend restart invalidates an active run and the CLI must rerun.

## Performance

The streaming importer resolves current device metadata once per run,
in 100-device chunks with four concurrent workers, and reuses the result during
Spotlight fetching. Logs include metadata duration, fetch duration per outer batch,
and storage duration. Backend DEBUG logs include per-asset resolution, prior-date
read, deletion, insertion, exception/classification and compliance timings. These
stage timings exclude transaction commit; the batch storage duration includes it.
Prior first-seen dates are read as a grouped scalar projection across the resolved
asset IDs, preserving the oldest `COALESCE(first_seen_at, scan_timestamp)` per CVE
and product without hydrating vulnerability entities.


| Dataset | Time | Bottleneck |
|---|---|---|
| 10 servers / 50 vulns | <5 s | network |
| 100 / 500 | <30 s | DB writes |
| 1000 / 5000 | <5 min | DB writes |

Per-server transactions = small commits, low contention, parallel-friendly. Bulk SQL for both delete and insert. V217 trimmed the `vulnerability` table from 9 secondary indexes down to 6 — `(asset_id, scan_timestamp)`, `(cvss_severity)`, `(vulnerability_id)`, `(asset_id, vulnerability_id)`, `(scan_timestamp)`, `(asset_id, import_timestamp)` — dropping `idx_vulnerability_asset`, `idx_vulnerability_sort_order`, and `idx_vulnerability_product` because they were prefix-redundant, served no actual ORDER BY (the named ORDER BY interleaves a column from a joined table), and used disqualified `LOWER(...) LIKE '%...%'` patterns respectively. That drop cut per-row index maintenance by ~33% during import. Two unrelated features later added their own indexes (`idx_vulnerability_first_seen_at`, `idx_vulnerability_excepted_sort`), so the table carries 9 secondary indexes again today — none of them `idx_vulnerability_sort_order`, which stays gone.

## Concurrency: READ COMMITTED + jittered retry

The CLI dispatches batches across 3 worker threads. Each worker POSTs to `/api/crowdstrike/vulnerabilities/save`, where the backend runs the per-server `@Transactional` delete-then-insert. With ~1881 servers and the `vulnerability` table carrying 9 indexes at the time, three concurrent inserters under MariaDB's default REPEATABLE READ would collide on next-key (gap) locks at the head of `idx_vulnerability_sort_order` (timestamp-leading — since removed by the V217 trim above), `idx_vulnerability_severity` (4-5 distinct values), and `idx_vulnerability_cve` (popular CVEs).

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

The sweep runs as a **background job**: the POST returns `202 Accepted` with a
`jobId` immediately and the CLI polls
`GET /api/crowdstrike/servers/reconcile-stale/{jobId}/status` (every 5s,
15 min budget; tunable via `secman.crowdstrike.reconcile-poll-interval-ms` /
`reconcile-poll-timeout-ms`) until the job reports `COMPLETED` or `FAILED`.
This exists because the synchronous sweep took ~65s on a 1.3M-row table and
nginx's 60s proxy timeout returned 504 to the CLI even though the backend
finished successfully (2026-07-21 incident). Only one reconcile job may run
at a time — a concurrent POST gets `409` with the running job's id. A
safety-brake-aborted sweep is `COMPLETED` with `result.aborted = true`, not
`FAILED`. Jobs stuck >30 min (backend restart mid-sweep) are auto-failed at
the next start.

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
   `ReconcileFailedException` on any HTTP/transport error, a `FAILED` job,
   or a polling timeout; the CLI exits with code `2` and prints an explicit
   operator message. Cron pipelines see the failure instead of silently
   leaving stale rows.

Coverage: `CrowdStrikeStaleVulnerabilityIntegrationTest` (5 cases — happy path,
empty payload, source-on-human-owned-asset, severity drift, XLSX isolation)
plus `CrowdStrikeReconcileJobIntegrationTest` (async job lifecycle, 409 guard,
unknown-job 404) and `CrowdStrikeReconcileJobServiceTest` (job state machine).

## EDR-presence signal: `asset.crowdstrike_agent_seen_at` (V248)

The same reconcile call also stamps `Asset.crowdStrikeAgentSeenAt` on every asset
resolved from `queriedHosts`, and that timestamp — **not**
`crowdstrike_last_imported_at` — is the numerator source for the EDR-coverage KPI
(`EdrCoverageKpiService`, `GET /api/dashboard/edr-coverage-kpi`).

The distinction matters and is easy to get wrong: `crowdstrike_last_imported_at` is
only written for hosts that returned findings. `scripts/import.sh` runs
`--severity CRITICAL,HIGH` and `CrowdStrikeApiClientImpl` forwards a batch only when
it is non-empty, so a **fully-patched host with a healthy Falcon sensor never sets
it**. A coverage metric built on that column would report low coverage precisely for
well-maintained fleets. `queriedHosts` is the full Stage-1 device population,
including hosts with zero matching vulnerabilities, which is what "CrowdStrike knows
about this box" actually means.

Two deliberate details:

- The stamp happens **before** the zero-refresh safety brake. An aborted sweep is an
  empty/failed *vulnerability* run, but its queried-host list is still valid evidence
  of agent presence; stamping afterwards would collapse the KPI on exactly the days
  the brake fires.
- Empty or absent `queriedHosts` stamps nothing, mirroring the sweep's existing
  "delete nothing" fail-safe — an old CLI against a new backend degrades to a stale
  KPI rather than a wrong one.

Resolution reuses `resolveQueriedAssetIds`, which matches by exact `cloudInstanceId`
or by hostname (exact or `short.%` FQDN prefix), so one device can stamp several
assets. Resolved-vs-queried counts are logged so that drift is observable; the sweep
already trusts the same resolution for the strictly more destructive DELETE.

## Tests

`src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServiceTest.kt` covers: idempotent re-import, initial create, remediation removal, per-asset isolation, null-CVE filtering, expansion.

`src/backendng/src/test/kotlin/com/secman/integration/CrowdStrikeStaleVulnerabilityIntegrationTest.kt` covers the reconcile/source/severity-history pipeline end-to-end against a real MariaDB.

```bash
./gradlew test --tests "CrowdStrikeVulnerabilityImportServiceTest"
./gradlew test --tests "CrowdStrikeStaleVulnerabilityIntegrationTest"
```

CrowdStrike findings shown in SecMan are restricted to HIGH and CRITICAL,
including live lookups, stored finding lists, exports and operational counts.
Complete synchronization still records evidence for every fetched severity so
that empty results and enrollment replacement can be verified safely. Findings
below HIGH remain internal reconciliation data; they are excluded by the shared
ORM/native read boundary. Other vulnerability sources retain their existing
severity visibility.


## Asset cleanup and sensor retention

The inventory shows **Falcon last contact** separately from **Last imported into SecMan**.
A fresh import of a historical Falcon record does not indicate a fresh sensor contact.

`delete-asset-not-seen` defaults to `--basis import-inactivity`: both the import and
Falcon contact must be old (unknown contact is allowed). Use `--basis sensor-contact`
to select verified Falcon contact older than the threshold even when its import is recent:

```bash
./scripts/secman delete-asset-not-seen 3 --basis sensor-contact --dry-run --verbose
# After reviewing the preview:
./scripts/secman delete-asset-not-seen 3 --basis sensor-contact --verbose
```

Unknown contact times are excluded in sensor mode. Manual and scan-owned inventory
is protected in both modes. `--include-legacy` applies only to import-inactivity and
still protects recent sensor contact. Eligibility is rechecked under the deletion lock.
CLI/API/MCP responses include policy, UTC cutoff, both timestamps, retention configuration,
exclusion counts and audit run ID. Candidate detail responses show at most 200 rows;
candidate counts and deletion cover the entire selected population. Existing API callers retain import-inactivity behavior.

To prevent deleted historical records from being imported again, explicitly set
`CROWDSTRIKE_SENSOR_RETENTION_DAYS=3` in the backend environment and the shell running
`import.sh`, then restart the backend. The default `0` keeps historical inventory.
The backend independently excludes old, unknown or future contact from persistence
when this policy is enabled, including per-host saves. These skips are reported as
`SKIPPED_RETENTION`, preserve existing rows and are excluded from findings reconciliation.
The import wrapper bounds discovery by this setting and uses the same window for
normal and isolated runs (`CROWDSTRIKE_IMPORT_LAST_SEEN_DAYS`, default 3; wrapper caps wider values to 3).

Scheduled cleanup remains disabled by default. `CROWDSTRIKE_CLEANUP_BASIS` selects
its policy; the percentage brake remains active. Import-inactivity schedules also
require a successful complete CLI run within 24 hours, observed since backend startup.
Failed runs invalidate that evidence; a backend restart requires a new successful run.
An active CLI import or in-flight import save blocks destructive cleanup in both modes.
A cleanup permit prevents a new CLI run or save from overlapping the deletion run. History distinguishes
`ABORTED_IMPORT_HEALTH` from `ABORTED_SAFETY_BRAKE`. A three-day sensor cutoff is an
inventory retention decision and is not proof that a stopped machine has been retired.

## Current asset visibility

Operational asset inventory and asset-derived current data use verified observations from the last 72 hours, in addition to existing authorization. Stored-record ingestion, repair, scanner discovery and historical relationships retain their distinct scope. See [Current asset visibility](CURRENT_ASSETS.md) for timestamp sources, cache expiry, script defaults, rollout and fixture requirements.


### Delete an asset after a rejected query import

`query servers --hostnames ARCGIS --save --deleteonfail` opts into destructive
recovery. Single-host `query --hostname ARCGIS --save --deleteonfail` and explicit
`query servers --aid <aid> --save --deleteonfail` support the same flag. ADMIN
permission and `--save` are required. Fleet streaming queries reject this flag:
future hostname siblings cannot safely be ruled out. Dry runs never save or delete.
Without the flag, existing import behavior is unchanged.

The CLI sends `POST /api/crowdstrike/servers/import?deleteOnFail=true`. The backend
handles at most two delete-and-retry cycles after the initial save (three save
attempts total per device), reusing the fetched payload. Only account, AD-domain
or enrollment conflicts with an exact rejected asset ID are eligible. Generic
validation failures, transport errors, incomplete snapshots, lock contention and
stale identity observations never trigger deletion. Recovery requires one exact
hostname match and no unrelated Falcon bindings. Multiple payloads sharing a
hostname are saved without destructive recovery. Same-host imports are serialized across conflict detection, deletion and retry.
Protected asset references, identity changes during deletion, and cascade timeouts
stop recovery.

Deletion removes the asset and cascading data, including its old vulnerabilities
and exceptions. Recreation may assign a new ID; unrelated metadata and
relationships are not restored. Backend history, completion notifications, CLI
statistics and reconciliation use the final outcome, counting each device once.
Destructive import requests are never automatically resent after gateway errors
or timeouts, since the backend may already have used its recovery budget. This
feature requires the updated backend as well as the updated CLI.
