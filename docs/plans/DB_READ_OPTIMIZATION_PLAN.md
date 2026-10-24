# Database read-time optimization plan

Status: proposal (2026-10-24). Based on a code review of the backend read paths. No EXPLAIN plans or production
timings have been taken yet, so Phase 0 exists to confirm the ranking before larger work starts.

## 1. Diagnosis

Read cost in secman comes from five structural causes. Each one appears in many places.

| # | Root cause | Where | Effect |
|---|---|---|---|
| R1 | **Indexes cannot be used by the predicates that appear in every query** | `AssetCurrentnessSql.OBSERVED_AT` (the timestamp sits inside a `CASE`); `CrowdStrikeSnapshotSql.VISIBLE` (`UPPER(COALESCE(cvss_severity,''))` plus an `IN (subquery)`, injected into every entity load through `@SQLRestriction` on `Vulnerability`); `LOWER(a.ad_domain)` in `AssetAccessSql`; `COALESCE(first_seen_at, scan_timestamp)` for SLA | Full scans of `asset` and `vulnerability` on almost every request, whatever indexes exist |
| R2 | **The access scope is recomputed per request and sent back as huge `IN` lists** | `AssetAccessSql.IDS` (a six-way `OR`, so no single index can drive it) → Kotlin `List<Long>` → `currentIds()` re-chunks it in batches of 1000 → `IN (:ids)` in the vulnerability, EOL, scan, heatmap and MCP queries. For admins, EOL sends *every* current id. `canAccessAsset` runs the full query just to check one id | Repeated full scans, statement text in the megabytes (`useServerPrepStmts=false` inlines the values), no plan reuse |
| R3 | **Pagination by OFFSET with an exact COUNT on every page** | Default vulnerability UI path (`VulnerabilityService` hard-codes `knownTotal = null`); JPQL `Page` count queries; the export job loops OFFSET pages and re-counts each one, which is O(N²) | The count often costs more than the page itself; export cost grows quadratically |
| R4 | **The cache layer is weaker than the configuration suggests** | `asset_queries`, `workgroup_memberships`, `vulnerability_total_count` and `mcp_accessible_assets` are configured but never populated. `vulnerability_not_excepted_count` lost its `@Cacheable`, so the post-refresh "warm" is now a wasted full COUNT. Config, reference data and identity lookups are not cached | The same rows are read on every request |
| R5 | **The ORM loads too much and does N+1** | `VersionedEntity.release`, `Release.createdBy` and 29 other `@ManyToOne` fields are EAGER by default. `Requirement.usecases/norms` are EAGER. `default_batch_fetch_size` is unset. About 80 `findAll()` sites. Only 6 DTO projections. 347 write transactions against 93 `readOnly` | Requirement lists, exports, reports and user lists cost 3–5 queries per row |

Per-request overhead on top of that:

- `CurrentIdentityRule` reloads the user and their roles on **every** authenticated request.
- `/api/auth/status` runs about 5 more queries on every page navigation.
- MCP authentication does, on every call, an `isIpBlocked` COUNT, a key lookup, BCrypt (~80 ms of CPU), an `UPDATE lastUsedAt` and an audit `INSERT`.

Analytical refreshes (outdated view, four statistics aggregates, heatmap, KPIs) run serially. Each costs about 60–100 s, and they share the same 40-connection pool as interactive traffic.

### Correctness bugs found on the way (fix regardless of performance)

- The default UI path for `not_excepted`/`overdue`/`excepted` with a sort still uses the correlated
  `NOT EXISTS(EXCEPTION_MATCH)`. The tuned repository queries use the materialized `v.excepted` flag. The two
  can disagree in the window between an exception change and its materialization.
- `GetVulnerabilitiesTool` / `GetAllVulnerabilitiesDetailTool` filter exceptions and installer findings
  *after* pagination. Pages come back short and `total` is wrong.
- `OutdatedAssetMaterializedView.workgroupIds LIKE '%id%'` makes workgroup `1` match `11`, `21` and so on. Only the
  admin CLI and MCP notification paths use it, so it is a targeting bug, not an access leak. The UI filters by live grants.
- The `overdue` anchor differs between the tuned query (`scan_timestamp`) and the custom-sort path
  (`COALESCE(first_seen_at, scan_timestamp)`).

## 2. Target architecture

```
                 ┌──────────────────────────── request ───────────────────────────┐
                 │                                                                 │
  L0 per-request │ AccessibleAssetIdsCache (exists)                                │
  L1 in-process  │ Caffeine: identity, API-key verdicts, config, access scope,     │
                 │ count/stat results keyed by data generation                     │
  L2 shared KV   │ (optional, only when >1 instance) Valkey/Redis behind the same  │
                 │ Micronaut cache names — no code change at call sites            │
  L3 derived DB  │ asset_access (user_id, asset_id) · observed_at / sla_anchor_at  │
                 │ generated columns · vulnerability.visible flag · rollup tables  │
  L4 source DB   │ MariaDB primary (OLTP pool) + analytics pool / replica          │
                 └─────────────────────────────────────────────────────────────────┘
```

Principles:

1. **Fix the SQL before adding a cache.** A cache over a full scan only hides the scan until the next miss
   or invalidation, and in this system invalidation happens on every import.
2. **Do authorization with a join, not with lists.** Precompute the access scope as a relational table that SQL can
   join against. Lists of ids must never travel through the JVM and back.
3. **Invalidate by generation, not only by TTL.** A monotonically increasing `data_generation` (bumped at
   import commit, at exception materialization and at grant change) is part of every result-cache key. TTLs stay
   as a safety net. The 72-hour currentness window is time-dependent, so cache keys also include a coarse time bucket.
4. **A key/value store is optional, not the foundation.** The deployment is a single instance (see the
   datasource comment in `application.yml`). In-process Caffeine is faster than any network KV store and
   already on the classpath. Introduce Valkey/Redis only when one of these becomes true:
   (a) horizontal scaling; (b) cached state must survive restarts (warm-up after deploy currently means the
   first requests pay full scan cost); (c) the relay or CLI needs to read shared state. Micronaut's cache
   abstraction (`micronaut-redis-lettuce`) makes it a configuration swap if the cache names are stable.

## 3. Phased plan

Each phase is shippable on its own and ordered by impact per effort. Effort: S < 1 day, M 1–3 days, L ≥ 1 week.

### Phase 0 — Measure (S/M, prerequisite)

- Enable the MariaDB slow query log (`long_query_time=0.5`, `log_queries_not_using_indexes`) and
  `performance_schema` statement digests on a production-like dataset (≥ 1M vulnerabilities).
- Micrometer timers around repository calls (Micronaut Data already emits them when
  `micronaut.metrics` is enabled). Set Hibernate `generate_statistics` in a profile only.
- Baseline EXPLAIN ANALYZE for the 15 queries named in §1 and §4. Store the baselines next to this file.
- Add a perf regression harness: seed the disposable runner database with synthetic volume and assert
  query counts per endpoint, for example with a Hibernate `StatementInspector` counting statements in integration
  tests. Query count is deterministic, so it makes a stable CI gate where wall-clock time is not.

### Phase 1 — Quick wins, no schema change (S each, ~1–2 weeks total)

| Item | Change | Expected effect |
|---|---|---|
| 1.1 | `hibernate.default_batch_fetch_size: 64` and `query.in_clause_parameter_padding: true` | Most N+1 become IN-batches; better statement-cache hit rate |
| 1.2 | Switch read-named methods and GET handlers to `@Transactional(readOnly = true)` (11 in `WorkgroupController`, 2 in `ReportController`, about 32 service methods). Fix `AppSettingsService.getBaseUrl` → `getOrCreateSettings` on read | No flush or dirty-checking; Hibernate read-only entities |
| 1.3 | Default vulnerability path: replace `NOT EXISTS(EXCEPTION_MATCH)` with `v.excepted = 0/1` in `executeStatusFilteredCustomSortQuery`. Align the overdue anchor | Removes a correlated subquery per row on the most-used page |
| 1.4 | Count policy: pass `knownTotal` from the client on page > 0, use `countMode=deferred` in the UI, cache unfiltered counts keyed by `(scope, generation)`. Remove the duplicate `countAll()` in the "all" path. Restore `@Cacheable` on the not-excepted count, or delete the warm-up | Removes one full COUNT per page view |
| 1.5 | Export: keyset iteration (`WHERE v.id > :afterId ORDER BY v.id LIMIT n`; `CrowdStrikeSnapshotSql.IDS_AFTER` already exists), one count at job start | O(N²) → O(N) |
| 1.6 | `CurrentIdentityRule`: Caffeine `userId → (enabled, username, email, roles, version)`, TTL 30 s, **invalidated synchronously** on user disable, role change, delete and email change (publish an event from `UserService`) | Removes 2 queries from every request |
| 1.7 | `/api/auth/status`: one native query that returns all counts; cache per user for 60 s, invalidated with 1.6 | 6 queries → 1 per navigation |
| 1.8 | MCP auth: Caffeine `HMAC-SHA256(secret, server pepper) → keyId` for verified keys, TTL 5 min, evicted on revoke and deactivate. BCrypt only on a miss. Debounce `lastUsedAt` to at most 1 write/min/key. Batch audit inserts asynchronously. Cache `isIpBlocked` for 30 s | Removes ~80 ms of CPU and 2 writes per MCP call |
| 1.9 | Cache config and reference data: AppSettings, maintenance banners (public, every page), notification, vulnerability and translation config, identity providers. Evict on write | Removes DB hits from hot static data |
| 1.10 | Admin short-circuits: `EolQueryService`, `UserDashboardService.assetCount` (use `countCurrent()`) and MCP SECCHAMPION use a join on the currentness predicate instead of an id list | Ends megabyte-long `IN` lists for admins |
| 1.11 | Replace `findAll()` on large or hot tables: `WorkgroupController:1171` (assets), heatmap endpoints and MCP tool, `VulnerabilityExceptionService:107`, `CrowdStrikeVulnerabilityService:580`, `ReportController` summaries (→ `GROUP BY` count queries), `/owner-candidates` (→ a `{id, username, email}` projection, paged and searchable) | Bounded memory, no N+1 |
| 1.12 | Delete the unused cache configs, or wire them up in Phase 3, so the configuration reflects reality | Hygiene |

### Phase 2 — Make predicates index-friendly (M, schema migrations)

All columns below are **generated or maintained in the same transaction** as their sources, so there is no
staleness window. Flyway migrations add them, and backfill on large tables runs in batches.

| Item | Change |
|---|---|
| 2.1 | `asset.observed_at` — `STORED` generated column equal to today's `OBSERVED_AT` CASE expression, plus `INDEX(observed_at, id)`. `AssetCurrentnessSql.CURRENT` becomes `observed_at BETWEEN …`, a range scan. Keep the expression in exactly one place (the column definition) and add a test that asserts the column and the old JPQL agree on fixture data |
| 2.2 | `asset.ad_domain_norm` — `STORED` `LOWER(ad_domain)`, indexed. Store `workgroup_ad_domain.ad_domain` lower-cased with the same collation, so the `COLLATE` cast disappears |
| 2.3 | `vulnerability.sla_anchor_at` — `STORED` `COALESCE(first_seen_at, scan_timestamp)`, indexed together with `excepted` |
| 2.4 | `vulnerability.visible` — a `TINYINT` maintained by the import and snapshot-activation code (the same transaction that flips `crowdstrike_snapshot.active`), replacing the `VISIBLE` subquery in `@SQLRestriction`. Normalize `cvss_severity` to upper case at write time so the `UPPER(COALESCE())` goes away. Add a nightly consistency check that compares `visible` with the old expression and alerts on drift |
| 2.5 | Composite indexes for the actual access patterns (confirm with Phase 0 EXPLAIN):<br>• `vulnerability(visible, excepted, scan_timestamp DESC, id)` — default UI sort<br>• `vulnerability(visible, excepted, sla_anchor_at, id)` — overdue<br>• `vulnerability(asset_id, visible, excepted)` — per-asset views and counts<br>• `asset_workgroups(workgroup_id, asset_id)`<br>• `aws_account_sharing(target_user_id)`; `user_mapping(email, aws_account_id)`, `user_mapping(email, domain)`<br>• `eol_finding(asset_id, status, eol_date)` |
| 2.6 | Sort on the denormalized `v.asset_name` instead of the joined `a.name` (avoids a filesort across tables) |
| 2.7 | Rewrite `AssetAccessSql.IDS` as a `UNION` of six branches, each indexed, instead of an `OR` of six `IN` subqueries. Add a single-asset `EXISTS` variant for `canAccessAsset` |
| 2.8 | Replace `OutdatedAssetMaterializedView.workgroupIds` (CSV) with a join table `outdated_asset_workgroup(view_id, workgroup_id)`. Fixes the `1`/`11` bug |
| 2.9 | Text search: replace `LOWER(x) LIKE '%term%'` on hostnames, CVEs and products with prefix search on normalized indexed columns (`name_norm LIKE 'term%'`). Where substring search really matters (product names), use a small `product_dim` table with a FULLTEXT index and join to it by id |

### Phase 3 — Materialize the access scope (L, needs a security decision)

Create a derived table `asset_access(user_id, asset_id, source_bits, PRIMARY KEY(user_id, asset_id), INDEX(asset_id))`.
Every list query then becomes `JOIN asset_access aa ON aa.asset_id = v.asset_id AND aa.user_id = :uid`. There are
no id lists, and the plan is stable and index-driven. `source_bits` records which of the five grant routes applies, for audit.

Maintenance:

- **Grant changes** (workgroup membership, workgroup asset, account or domain grant, personal mapping, sharing, workgroup
  enable/disable): recompute the affected users' rows **in the same transaction** as the mutation. The hook points
  already exist: every one of them calls `McpAccessibleAssetsCacheInvalidator` today.
- **Asset changes** (new asset or changed `cloud_account_id`/`ad_domain` from imports): recompute the affected assets' rows
  in the import transaction. This is a cheap reverse lookup: who holds this account or domain?
- **Reconciliation job** (nightly plus on demand): recompute everything into a shadow table, diff it, alert on drift, swap.

**Security implications — decision required.** CLAUDE.md states that SQL pre-filters are
"perf hints, never the auth boundary". A materialized access table *would* become the list-level boundary.
Options:

- **(a) Strict:** the table drives SQL selection only. `AssetFilterService` stays authoritative for single-object
  reads and mutations (`canAccessAsset`), and a sampled runtime cross-check compares results. Revocation is
  transactional, so there is no window in which a revoked user still sees data.
- **(b) Conservative:** keep live evaluation and instead cache the *result* of 2.7 per user in Caffeine
  (`userId → RoaringBitmap of asset ids`, invalidated by the existing invalidator plus a generation key). Less gain,
  because id lists still reach SQL, but the boundary model does not change. This could ship earlier as Phase 3a.

Recommendation: ship (b) first (the dead `mcp_accessible_assets` cache becomes its home), then move to (a)
with an update to `docs/AUTHORIZATION_REWORK.md` and a `/security-review`. Both options keep the 72-hour
currentness check live, as a range predicate on `asset.observed_at` from 2.1.

### Phase 4 — Separate analytical reads from interactive reads (M/L)

- **Second datasource `analytics`** (Hikari pool of about 4, `socketTimeout` sized for long reads) for materialized-view
  refresh, statistics, heatmap, KPIs and the export job. Interactive traffic keeps the 40-connection pool to itself. Later the same
  datasource can point at a **MariaDB replica**. Refresh jobs tolerate replication lag; interactive reads do not.
- **Incremental derived data:** imports already know which assets they touched. Recompute heatmap rows, outdated-view
  rows and per-asset rollups for those assets only, instead of a full rebuild per refresh. Keep the full rebuild nightly.
- **Atomic swap for full rebuilds:** build into `*_next` and then `RENAME TABLE live TO old, next TO live`. Readers never
  wait, and there is no long DELETE+INSERT transaction. Use `INSERT … SELECT` for the heatmap instead of `saveAll`
  with IDENTITY ids, which cannot batch.
- **Rollup table `asset_vuln_rollup(asset_id, severity, excepted, overdue, count)`** maintained with the import. Account,
  workgroup and dashboard counts, the heatmap and statistics then become joins of `asset_access` × rollup: thousands of rows, not
  millions. `AssetVulnCountsQuery` and its three correlated `EXISTS` per row go away.
- Non-admin statistics (`ForAssets`) become cacheable through the rollup plus a per-user generation key.

### Phase 5 — API and frontend shape (M)

- **Keyset pagination** for the vulnerability, asset overview and MCP list endpoints. Cursor = last `(sort_key, id)`,
  encoded opaquely, keeping `page` for backward compatibility. Check the `extensions/` clients (Principle 5b) before
  changing any response shape. The cursor is additive.
- **DTO projections** (`SELECT new …` / Micronaut Data `@Introspected` projections) for list endpoints. Stop building
  `AssetOverviewRow` from full entities.
- Make `VersionedEntity.release`, `Release.createdBy`, `Requirement.usecases/norms` and the 29 default-EAGER
  `@ManyToOne` fields LAZY. Use explicit `JOIN FETCH` or `@EntityGraph` where needed. Return DTOs from `RiskController`,
  `DemandController` and `StandardController` instead of entities.
- Frontend: merge the asset overview's 4 calls into 2. Load owner candidates lazily with server-side search. Review the
  serial-await hotspots the optimizer scan reported (`RequirementManagement`, `DemandManagement`, `Export`, `ReleaseManagement`).
- HTTP caching: `ETag` derived from `data_generation` on read-mostly endpoints (statistics, heatmap, outdated
  view, public standards) so browsers revalidate with 304s.

## 4. Key/value store decision record

| Option | Use for | Verdict |
|---|---|---|
| Caffeine (exists) | identity, API-key verdicts, config, access bitmaps, generation-keyed results | **Use now.** Zero network hop; already configured |
| Valkey/Redis via `micronaut-redis-lettuce` | the same cache names when scaled out; warm cache across deploys; pub/sub for cross-instance invalidation | **Defer** until horizontal scaling or restart warm-up becomes a measured problem. It is a new dependency (A06): state the reason in the PR, pin the version, require TLS and AUTH, and never cache tokens or secrets in clear text |
| MariaDB derived tables (`asset_access`, rollups, generated columns) | everything that must be joined in SQL or must be transactionally consistent with grants | **Primary lever.** Consistency with the source of truth comes for free from the transaction |
| Search engine (OpenSearch etc.) | full-text across findings | **Not justified** at current volume; 2.9 covers the actual search patterns |

Cache-safety rules for any tier:
- Every security-relevant cache entry (identity, roles, API key, access scope) is **evicted synchronously** on the
  mutation that revokes it. TTL is a backstop, never the revocation mechanism.
- Cache keys include the user id or scope hash. A response computed for one scope is never served to another.
- Never cache raw secrets. API-key verdicts are keyed by a peppered HMAC, never by the secret.

## 5. Suggested order and expected effect

| Order | Work | Effort | Expected read-time effect (to be confirmed in Phase 0) |
|---|---|---|---|
| 1 | Phase 0 measurement | S/M | — |
| 2 | 1.3, 1.4, 1.5 (default vulnerability page, counts, export) | S | Largest single user-visible gain; export O(N²) → O(N) |
| 3 | 1.6–1.9 (per-request identity, status, MCP auth, config) | S | Lowers latency on every request; MCP calls lose ~80 ms+ |
| 4 | 1.1, 1.2, 1.10, 1.11 | S | Removes N+1 and unbounded loads |
| 5 | 2.1, 2.3, 2.4, 2.5, 2.7 | M | Full scans → index range scans on the base predicates |
| 6 | Phase 3a (cached access bitmap) | M | Access scope computed once per grant change, not per request |
| 7 | Phase 4 (analytics pool, incremental and rollup tables, atomic swap) | L | Refreshes no longer starve interactive reads; dashboards read thousands of rows |
| 8 | Phase 3 (a) materialized `asset_access` + Phase 5 keyset | L | Stable, index-driven plans independent of scope size |

Each step must pass the gates in CLAUDE.md: tests with query-count assertions, `./scripts/owasp-check.sh`,
and `/security-review` for 1.6–1.8 and Phase 3, since they touch authentication and authorization.

## 6. Execution status (2026-10-24)

Implemented in the working tree. **Backend compile and test validation are still outstanding**: the
session that wrote this could not reach Maven Central or services.gradle.org, so no Kotlin was
compiled or run. The V295 migration was validated against MariaDB 10.11 (applies, is idempotent, and
EXPLAIN shows both new indexes used for range scans). The frontend built, and its unit tier showed
345 passing tests and one pre-existing failure (`aiBuddyPatchGuide.test.ts`, unrelated to this work).
`owasp-check`, `humanizer-scan` and `optimizer-scan` report 0 BLOCK findings on the diff.

### Done

| Item | Change |
|---|---|
| 0 | `testutil/QueryCounter` (Hibernate `StatementInspector`, test profile only) plus `ReadPathIntegrationTest` pinning single-round-trip access checks |
| 1.1 | `default_batch_fetch_size: 64`, `in_clause_parameter_padding: true` |
| 1.2 | `@Transactional(readOnly = true)` on the 11 read-only `WorkgroupController` GETs and the 2 `ReportController` GETs. `WorkgroupController` moved from the Jakarta annotation, which has no `readOnly`, to the Micronaut one |
| 1.3 | Default vulnerability page (custom sort plus status) reads `v.excepted` through shared `VulnQuerySql` fragments instead of a per-row `EXCEPTION_MATCH`. The same change went into `AssetVulnCountsQuery` (three correlated subqueries per row) and the CVE/product drilldowns in `VulnerabilityStatisticsService` |
| 1.4 | Removed the duplicate `countAll()` on the unfiltered admin page, the wasted not-excepted "warm-up" after each refresh, and two count shortcuts that ignored `includeInstallerFindings` |
| 1.5 | Export loops count once and fetch later pages in deferred-count mode, removing the O(N²) recount |
| 1.6 | `CurrentIdentityRule`: one JPQL projection instead of entity plus role-collection loads. **No cross-request cache**, so role changes and disabling a user still take effect on the very next request |
| 1.7 | `/api/auth/status`: 6 queries → 2 |
| 1.8 | MCP secret-only auth remembers which key row a secret matched, keyed by an HMAC under a per-process random key. The key row is still read and checked (active, unexpired, same hash) on every call, so revocation is immediate. `lastUsedAt` is written at most once a minute |
| 1.10 | Grant-scoped id set and currentness in one query (`findCurrentAccessibleIds`); statistics filters push currentness into SQL |
| 1.11 | `ReportController` uses `GROUP BY` aggregates; `/owner-candidates` uses a username/email projection, and the asset overview loads it lazily |
| 1.12 | Removed three cache configs that nothing used |
| 2.1–2.3 | V295: VIRTUAL generated `asset.observed_at` and `vulnerability.sla_anchor_at` with indexes. `idx_vulnerability_first_seen_at` was replaced, so the index count on `vulnerability` is unchanged. AD domains normalized |
| 2.5–2.7 | Composite indexes on `asset_workgroups` and `eol_finding`. Sorts use `v.asset_name`. `AssetAccessSql.IDS` is now a UNION of indexed branches, plus a single-asset `CONTAINS` check |
| 2.8 | Outdated-view workgroup filter uses `FIND_IN_SET` (fixes `1` matching `11`) |
| Bug | `get_vulnerabilities` MCP tool: scope, paging and the excepted/installer flags all run in SQL, so `total` is now correct |
| 4 (part) | Heatmap rebuild is one `INSERT … SELECT` instead of one INSERT per asset |
| 5 (part) | `VersionedEntity.release` is LAZY |

### Deliberately changed or deferred

- **Count caching / client `knownTotal` (1.4):** not reintroduced. `docs/CURRENT_ASSETS.md` forbids
  fixed-duration count caches because totals must follow assets ageing out of the 72-hour window.
- **Config caching (1.9):** skipped. The tables are a handful of rows and each read is a sub-millisecond
  indexed lookup, so a cache would add invalidation risk for no measurable gain.
- **Cross-request access-scope cache (3a) and the `asset_access` table (3):** not built. The existing
  invalidation hooks do not cover every grant write path (that was never verified, because the cache was
  never filled), and the UNION rewrite makes the exact per-request query cheap. Revisit only if
  Phase 0 measurements show the access query is still significant.
- **Analytics datasource, replica, atomic table swap, rollup table (4):** deferred. Refreshes already run
  serially on one connection; InnoDB MVCC already keeps readers off the DELETE+INSERT refresh;
  a second JPA persistence unit is a large change to justify only with measurements.
- **Keyset API, DTO projections for list endpoints, making `Requirement` collections lazy, ETags (5):**
  deferred. Each changes a response contract or serialization path that the `extensions/` clients and
  the frontend rely on (Principle 5b), and none can be verified without a running build.
- **`GET /api/assets` (unpaged legacy list), `minDaysOpen` post-filter in
  `get_all_vulnerabilities_detail`, EOL admin id lists:** left as is; each needs a contract decision.
