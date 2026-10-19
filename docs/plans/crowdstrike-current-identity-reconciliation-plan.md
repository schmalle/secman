# CrowdStrike current identity and vulnerability reconciliation

Date: 2026-10-10. Status: core identity, snapshot and reader changes implemented in the working tree; validation and evidence-backed fleet reconciliation are separate rollout steps.

## Outcome and evidence boundary

For each independently identified machine, SecMan should have one current Falcon enrollment and a vulnerability snapshot demonstrably fetched for that enrollment. Retired enrollments belong in audit history, not the current identity table or current findings. Verified empty snapshots must remove old CrowdStrike findings. Failed or incomplete queries must never be interpreted as clean machines.

The screenshots show stored findings for `2F1E2864E4824E3`, instance `i-02c65906e034a9dab`, followed by a live hostname lookup with zero findings. This demonstrates a disagreement between views, but does not establish which AID either view used, when the stored rows were imported, whether the live result was complete, or whether both views refer to the same instance/account. Old CVE dates and long days-open values are not evidence of an obsolete device mapping.

This analysis inspected the current working tree, including pre-existing edits, on branch `dev`. It did not inspect the deployed build, query the application database or contact Falcon. Source findings below are confirmed in this checkout; their role in this exact host's incident requires the read-only comparison in Phase 1. Existing edits are preserved.

## Baseline source findings before implementation

| Finding | Evidence | Consequence |
|---|---|---|
| Lookup and import use different winners | `CrowdStrikeApiClientImpl.selectMostRecentHostnameDevice` ranks by `lastSeen`, then `firstSeen`; `selectLatestCrowdStrikeDevices` ranks by `firstSeen`, then `lastSeen` | A live lookup can query a different AID from the importer, even for the same machine |
| Lookup can choose across independent machines | Hostname lookup accepts matching full/short names and chooses one recent device; import groups by instance/account, or hostname/domain/account | Reused names can compare different machines. Name equality does not prove replacement |
| Interactive selection lacks retirement evidence | Hostname lookup creates `CrowdStrikeDeviceSelection(identity)` without superseded records; `QueryCommand --save` forwards it | A save can fail when replacement encounters an old binding outside the supplied evidence set |
| Zero-result UI save is impossible | `CrowdStrikeVulnerabilityLookup.handleSaveToDatabase` rejects empty findings; `CrowdStrikeVulnerabilityService.saveToDatabase` requires a nonempty list | A verified clean live result cannot clear a stored snapshot through this path |
| UI save bypasses the current enrollment writer | `saveToDatabase` resolves by hostname/name/instance and uses `deleteByAssetId`; it does not call `CrowdStrikeDeviceReplacementService` | No enrollment proof on this write path; replacement also deletes findings from other sources |
| Findings lack device provenance | `Vulnerability` has asset/source/import timestamps, but no originating AID or snapshot reference | Readers cannot prove that CrowdStrike rows belong to the current enrollment; a new import timestamp alone does not prove it |
| Stored reads do not enforce enrollment validity | `CrowdStrikeQueryService.queryFromDatabaseByHostname` chooses an asset by name and reads its findings; current-vulnerability service/query paths lack an enrollment/snapshot validity join | Retained old rows may still be presented as current. Sensor contact today does not prove today's findings were fetched |
| Failed replacement preserves old rows but does not classify their validity | Replacement rejects unknown bindings, ambiguous duplicates and unsafe relationship deletion | Transaction safety is sound, but there is no explicit obsolete/unknown snapshot state on these reads |
| Scoped reconciliation cannot repair the entire inventory | Reconciliation operates on resolved queried hosts; selection applies device category and activity scope | Unqueried, retired, category-changed or unresolved identities can survive. Absence from a restricted run cannot establish retirement |
| Historical documentation disagrees with current behavior | Opening sections of `docs/CROWDSTRIKE_IMPORT.md` describe one selected AID; later hostname/multi-AID sections still describe querying/merging all siblings | Operators and future changes can follow incompatible contracts |

### Controls already present and worth retaining

- Complete discovery and bounded metadata fetching; timestamp ambiguity guards and explicit failed-AID reporting.
- ADMIN-only latest-enrollment replacement, per-host transactions, asset locking and an import-run lease.
- Evidence-backed superseded binding removal and enrollment history, with guards against deleting unrelated inventory relationships.
- Source-scoped bulk replacement. Enrollment changes clear all old CrowdStrike severities and installed-product state; ordinary same-enrollment imports retain severity scope.
- Zero-finding selected devices are carried by the bulk CLI. Do not regress this while changing the UI path.
- Reconciliation clock-skew checks, deletion safety brakes, and failure exclusions.

## Canonical identity and selection contract

1. Resolve a machine before selecting an enrollment. Within the configured Falcon tenant, prefer exact AID bindings and cloud account plus instance ID. For non-cloud machines, use exact normalized full hostname plus AD domain and available account context. Short-name search is discovery only; return candidate identities if it is ambiguous.
2. Reuse the bulk enrollment contract for every authoritative lookup and save: newest Falcon `first_seen`, then `last_seen` for an enrollment tie. This is a proposed SecMan rule, not a claim that timestamps alone prove hardware identity. A conflicting newer device must remain unresolved rather than silently replacing a different machine.
3. A hostname lookup from an existing asset should carry the asset ID and resolve through `AssetFilterService`, then constrain discovery to its stable identity. For free-form names that resolve to multiple machines, require an explicit instance/account candidate selection. Show the selected AID, instance/account and both Falcon timestamps.
4. Enumerate all enrollments in the resolved identity before applying activity or category eligibility. Filtering out an enrollment before ranking must not revive an older enrollment. Discovering evidence may need a broader scope than the requested findings import; this does not authorize deleting machines outside the import scope.
5. Preserve retired records as audit history. Current bindings must contain only one AID per resolved asset. Add a unique asset constraint only after legacy duplicates have been reviewed/reconciled. Existing unique AID constraints remain.
6. Different cloud instances with the same hostname remain separate assets unless independent evidence establishes retirement/replacement. A rebuilt instance can represent a new machine. Do not implement global hostname deduplication.

## One authoritative snapshot writer

Route the UI sync, single-host CLI save and fleet import through the existing per-host import/replacement service. Remove the separate hostname-only CrowdStrike replacement implementation after callers move. Preserve existing role boundaries: global identity retirement remains ADMIN-only; scoped non-admin operations cannot silently inherit that authority.

For UI sync, accept an authorized asset/candidate selection and fetch/validate the snapshot on the backend. Do not accept a browser's filtered findings list as proof of a complete provider snapshot. Keep any trusted CLI upload contract explicit about selected AID, bounded superseded evidence, coverage and completeness, and reject legacy identity-changing uploads without this evidence.

Within one short database transaction, after provider I/O completes:

1. Lock the canonical asset and recheck selected enrollment and expected snapshot version. Reject an older enrollment, a retired AID and an older concurrent snapshot of the same AID. Local lock stripes alone do not coordinate multiple backend processes; use database locking/version checks and uniqueness for that boundary.
2. Validate complete device discovery, metadata and Spotlight pagination. Require every finding to carry the selected AID. An authenticated successful response with empty resources and reported total zero is a verified empty snapshot. Missing totals, errors, truncation, 404, rate limits and timeouts are unknown/failed outcomes, not verified empty snapshots.
3. On enrollment transition, invalidate all previous CrowdStrike findings and installed products for the canonical asset. Populate only the new enrollment's fetched scope. Preserve manual/XLSX/integration findings and inventory relationships.
4. On the same enrollment, replace only the fetched severity scope. A filtered HIGH/CRITICAL result cannot prove LOW/MEDIUM clean. Findings filters such as product/minimum days open must not feed a full replacement; either reject such saves or use an explicitly recorded replacement scope.
5. Commit selected binding, enrollment audit, snapshot metadata and new findings together, including the empty case. Scope first-seen/SLA anchors to the enrollment: preserve detection history within an enrollment; do not inherit it from a retired machine.
6. Publish invalidation/refresh work after commit for every snapshot change, including deletion to zero. Rebuild derived statistics and views, invalidate lookup/count caches and recalculate affected KPIs. Record derived-refresh failure separately and retry; do not report derived data fresh before refresh finishes.

Unsafe duplicate deletion should not block publication of a separately proven valid current snapshot unnecessarily. Retain mixed-source or relationship-bound duplicate inventory records for review, exclude their proven retired CrowdStrike contribution, and keep grants/risks/manual findings intact. Delete an asset only when existing ownership and cascade guards prove that deletion is safe.

## Persist snapshot provenance and expose validity

Use a small source snapshot record rather than expanding generic asset timestamps into several meanings. Proposed minimum fields: snapshot ID, asset ID, selected AID, provider collection time, successful completion time, fetched coverage, and validity state. CrowdStrike finding rows reference their snapshot. The snapshot AID supplies row provenance, so a second redundant AID column is unnecessary.

Coverage must be recorded for each independently replaced severity scope so a scoped refresh cannot make retained scopes appear newly verified. Maintain a current pointer/version per asset and coverage scope; the selected enrollment remains common to all scopes. Decide the physical table layout during implementation, preserving bounded/indexed SQL reads.

States should distinguish:

- Verified current enrollment with a successful snapshot, including zero findings.
- Proven superseded/retired enrollment: excluded from current findings.
- Legacy/unverified snapshot: retained but explicitly labelled, not asserted current.
- Latest fetch failed or incomplete: last successfully verified snapshot retained with its actual age and failure notice; never presented as a newly verified clean result.

A failed attempt does not itself retire a successfully verified snapshot. Conversely, known supersession invalidates old findings even if the new enrollment's findings cannot yet be fetched; show unknown coverage instead of zero-risk/clean.

Apply the same validity predicate at the database query boundary to current lists, counts, exports, account views, overdue snapshots, heatmaps, notifications, MCP, relay and AWS clean-server KPI. Preserve `AssetFilterService`/`AssetAccessSql` scopes. Healthy requires eligible current identity plus sufficient successful findings coverage; missing/obsolete/failed coverage is unknown, never clean. Keep the existing Berlin-day sensor eligibility rule separate from findings freshness.

Freshness must show the successful snapshot time, not request time or latest sensor contact. A successfully stored empty snapshot must be returned from the database as zero findings rather than triggering live lookup simply because no finding row exists. Stale/unknown records need a review view and counts so exclusion does not conceal unresolved vulnerability risk.

## Repair and rollout

### Phase 1 — read-only incident proof

Capture deployed backend/CLI versions and migration status before relying on working-tree behavior. For `2F1E2864E4824E3`, collect all matching asset IDs and names, instance/account/domain, current bindings and Falcon timestamps, enrollment history, last import outcome/rejection, source/severity row counts and import timestamps. Discover all matching Falcon devices and retain exact AID/account/instance/domain/first_seen/last_seen metadata. Query the resolved current AID directly with complete pagination, without save flags or imports.

Compare the existing lookup winner, bulk-import winner and stored bindings. Classify the incident as different machine, different enrollment selection, failed replacement, older successful snapshot, lookup failure represented as empty, or a combination. If the same AID has verified zero findings, the expected repair is a source-scoped empty replacement. If AIDs differ, prove machine identity and supersession first.

### Phase 2 — close entry-point gaps

Share identity resolution/selection across lookup, single-host and bulk paths. Replace UI save with backend snapshot sync, support verified empty snapshots, expose selected identity/completeness, and correct outdated documentation. Cover the confirmed divergence and writer bypass with regression tests before rollout.

### Phase 3 — provenance and fleet reconciliation

Deploy additive snapshot storage and validity queries. Leave legacy rows unverified; do not infer their AID from the binding currently present. Produce a fleet dry-run report from complete authoritative inventory, with selected/retired AIDs, proposed binding changes, affected CrowdStrike counts, coverage, conflicts and relationship-bound duplicates. Missing metadata/permissions or incomplete discovery prohibits absence-based cleanup.

Refetch current snapshots to establish provenance. Process bounded batches and report unresolved groups individually. Retire a missing AID only from complete inventory in the explicitly covered tenant/device scope, or explicit provider lifecycle evidence. Restricted category/activity searches are insufficient. Capture before-state evidence and a tested recovery strategy for snapshot and binding changes; the existing contact-stamp repair script repairs timestamps only and is not a mapping/findings repair.

Only after reconciled groups are verified, enforce one current binding per asset and switch current projections to proven current snapshots. Track unresolved legacy groups and coverage explicitly during transition. Account/domain changes can alter authorization scope; preserve explicit grants and re-evaluate access, rather than copying grants from duplicate assets.

### Phase 4 — acceptance and operation

Required regressions:

- New enrollment versus older enrollment with more recent contact: all entry points choose the same AID for the resolved machine.
- Equal timestamps, missing timestamps, missing metadata, hostname collisions across account/domain/FQDN and different cloud instances: unresolved, without deletion or unrelated asset disclosure.
- Verified zero versus 404, failed pagination, missing total, cursor loop and partial fetch: only verified zero clears the in-scope snapshot.
- Re-enrollment and rename of the same instance: one current binding; retired findings/products excluded; enrollment history retained; no inherited SLA anchor.
- Retired-AID write and concurrent older same-AID response: rejected without overwriting newer state. Multi-process first import cannot create duplicate bindings/assets.
- Same-enrollment partial severity refresh and enrollment-change partial coverage: unrelated sources preserved and unqueried severities represented honestly.
- Mixed-source duplicate with workgroups/integration/risk relationships: no destructive cascade; retired CrowdStrike contribution excluded after proof.
- Zero snapshot reread, cached lookup, list/count/export/MCP/account/heatmap/overdue/notifications/relay/KPI: consistent selected identity, validity and coverage after refresh.
- Scoped unauthorized lookup/sync and account/domain changes: `AssetFilterService` authorization retained.
- Failed/filtered/incomplete imports do not update successful-snapshot freshness or produce a clean KPI.

Run focused shared/CLI/backend tests with the disposable database runner. After implementation, run mandatory `e2ejs` and `e2evulnexception` skills, OWASP review and appropriate broader tests. Then verify the affected real host with read-only database/API/Falcon evidence and sample multiple repaired identity groups. Record excluded/unresolved totals and derived-refresh completion separately from successful per-host imports.

## Security and completion boundary

This proposal changes no executable code. Implementation must review A01 (asset scope and ADMIN retirement), A03 (bound SQL/FQL validation), A04 (bounded enumeration and transactional invariants), A08 (provider snapshot integrity), A09 (actor/identity/snapshot/outcome audit) and A10 (existing outbound endpoint policy). A02/A05/A06/A07 controls remain binding. No new dependency or authentication mechanism is required.

The fix is complete only when current CrowdStrike findings can be traced to the selected enrollment and successful coverage, empty snapshots work, all writers enforce the contract, existing stale groups have a recorded disposition, derived readers agree and required gates pass. Retaining historical enrollment audit is compatible with having only current mappings/findings in operational data. There is no safe guarantee of instantaneous live accuracy between scheduled imports; expose snapshot age and coverage rather than imply it.


## Implementation notes (2026-10-10)

The working tree now uses one enrollment selector across interactive and import
paths, persists per-severity snapshot provenance (V286), supports server-refetched
ADMIN synchronization including verified zero, serializes identity changes in the
database, rejects older snapshots, and filters retired proven snapshots consistently
in ORM/native readers. Account projections retain active scopes from different
successful import times. Legacy writers invalidate verified coverage, and unknown
coverage is excluded from the clean-server numerator. Derived refresh completion
includes dependent work; failures are reported and queued for retry.

The additive migration deliberately retains legacy risk. The bounded ADMIN
reconciliation preview is local evidence, not the complete authoritative fleet
dry-run described in Phase 3. Successful ADMIN imports/refetches establish
provenance and retire only identities supported by selection evidence. Unknown
stored AIDs and cross-account conflicts remain unresolved rather than being
deleted by hostname or absence. No fleet-wide purge or real Falcon repair was
performed as part of the code implementation.

Failed provider fetches preserve the last successfully committed binding and
snapshot. Discovery and findings publication remain one transaction boundary:
known-but-uncommitted supersession is surfaced by live lookup but is not separately
published as an identity-only retirement. Thus the proposal's stronger
identity-discovery-only retirement on a failed findings fetch is still a future
rollout improvement; current stored results describe the last successful snapshot,
not guaranteed instantaneous Falcon state.
