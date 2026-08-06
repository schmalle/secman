# Phase 0 — Research & Decisions

**Feature**: 087-crowdstrike-legacy-stale-cleanup
**Date**: 2026-05-08
**Inputs**: spec.md (Clarifications session 2026-05-08); existing code in `src/backendng/.../service/CrowdStrike*` and `repository/AssetRepository.kt`.

This document resolves every "NEEDS CLARIFICATION" candidate the planner identified in Technical Context. The spec already locked the user-facing contracts; this file resolves the implementation-level unknowns the planner needed to land on a confident plan.

---

## Decision 1 — JPQL `COALESCE` portability across H2 (unit) and MariaDB (production + integration)

**Decision**: Use `COALESCE(a.lastSeen, a.updatedAt, a.createdAt) < :cutoff` directly in the JPQL `@Query` annotation. No native fallback.

**Rationale**: `COALESCE` is JPA-spec compliant (JPQL §4.6.17) and is supported identically by Hibernate's H2 dialect and MariaDB dialect. It compiles to `COALESCE(...)` in MariaDB and to `COALESCE(...)` in H2. Three-valued-logic semantics are identical: if `last_seen` is non-null, the comparison short-circuits there; otherwise `updated_at`; otherwise `created_at`. Because `created_at` is auto-managed and effectively non-null on every existing row, the all-three-null edge case enumerated in the spec is theoretical only.

**Alternatives considered**:
- *Per-dialect native query*: would compile to `IFNULL` on MariaDB and `COALESCE` on H2. Rejected — adds a `@NativeQuery` annotation and forks the test stack. JPQL `COALESCE` works in both with no extra surface.
- *Computed column*: store a denormalized `effective_last_seen` column populated by a trigger. Rejected — far more invasive than a one-time cleanup feature warrants.
- *Chained `OR (lastSeen IS NULL AND updatedAt < :cutoff) OR (...)`*: equivalent in result but harder to read, and the fall-through ordering becomes implicit instead of explicit. Rejected.

---

## Decision 2 — Where to host the `"CrowdStrike Import"` literal as a single shared constant (FR-014)

**Decision**: New file `src/backendng/src/main/kotlin/com/secman/constants/AssetOwners.kt` with a top-level `object AssetOwners { const val CROWDSTRIKE_IMPORT = "CrowdStrike Import" }`. Three call sites are updated to reference it: `CrowdStrikeVulnerabilityImportService.createNewAsset` (the writer at line 376), `AssetController.getOwnerCandidates` (the dropdown source at line 202), and the new `findLegacyCrowdStrikeStale` query parameter binding in the service that calls the repository.

**Rationale**: A `const val` on a top-level Kotlin `object` compiles to a `public static final String` and is idiomatic in this codebase. The `constants` package does not currently exist but is a low-cost, semantically-clear home — searching for "CrowdStrike Import" in the codebase will reliably land here. Putting the constant on a domain class (Asset) would entangle a magic value with a JPA entity; putting it on the import service (companion object) would force the controller and the repository to depend on the import service which they otherwise don't.

**Alternatives considered**:
- *Companion object on `CrowdStrikeVulnerabilityImportService`*: rejected — creates a reverse dependency from controller to service for a 1-line constant.
- *Enum with a single value*: rejected — overkill, and the JSON/DB storage of the value is a plain string today; an enum changes nothing operationally.
- *Hibernate `@Where` clause on `Asset` keyed off the literal*: rejected — would change asset query semantics globally and is wholly unrelated to the cleanup feature.

---

## Decision 3 — Indexing strategy for `findLegacyCrowdStrikeStale`

**Decision**: Ship without a new index in v1. The query plan on MariaDB will likely use the existing `idx_asset_last_seen` for the cutoff comparison and then evaluate the four-part fence as a residual filter. With ≤2,000 estimated legacy rows and ≤7,000 total CrowdStrike-origin rows, a full filter scan is well within the <60s budget. Defer index decisions until production EXPLAIN data is available.

**Rationale**: Premature indexing on a one-time-drain workload is wasted ops surface. A composite index `(owner, crowdstrike_last_imported_at, manual_creator_id, scan_uploader_id)` would help the predicate, but the cardinality of `owner = 'CrowdStrike Import'` is already low enough that a sort-merge or hash-join plan over the existing single-column indexes is acceptable. The cleanup runs at 02:30 daily under low contention.

**Alternatives considered**:
- *Add composite index in this feature*: rejected — unnecessary now, easy to add later if EXPLAIN shows it's needed.
- *Add a materialized view*: rejected — way out of proportion; this is a CRUD cleanup, not a heatmap.

---

## Decision 4 — Backward-compatible mutation of `CrowdStrikeAssetCleanupCandidateDto`

**Decision**: Change the DTO's `crowdStrikeLastImportedAt` field from `LocalDateTime` (non-nullable) to `LocalDateTime?` (nullable), and add a new field `reason: CleanupCandidateReason` (an enum with values `TIMESTAMP_STALE` and `LEGACY_NULL_TIMESTAMP`). Existing rule-A candidates carry a populated timestamp and `reason = TIMESTAMP_STALE`. New rule-B candidates carry `crowdStrikeLastImportedAt = null` and `reason = LEGACY_NULL_TIMESTAMP`.

**Rationale**: The legacy candidates have, by definition, a NULL `crowdstrike_last_imported_at`. Rendering them with a sentinel (e.g., `LocalDateTime.MIN`) would lie about reality and would require every consumer to special-case the sentinel. Nullability is the honest representation. The only consumer of this DTO today is the admin frontend, which is being updated in this PR; the JSON shape change (a previously always-present field becoming sometimes-null) is observable but tolerable for an admin-only endpoint.

**Alternatives considered**:
- *Two parallel DTO types*: rejected — the consumer (admin UI) needs to render a unified candidate list; splitting forces the UI to merge two arrays for no benefit.
- *Sentinel value*: rejected (above).
- *Inline reason as a string*: rejected — an enum is type-safe and self-documenting; the JSON serialization is identical.

---

## Decision 5 — Manual-run safety brake when `includeLegacy=true`

**Decision**: Manual cleanup runs continue to bypass the safety brake (i.e., `auditService.run(..., maxDeletePercent = null)`) regardless of the `includeLegacy` flag. Confirmed by spec edge-case bullet and SC-004's parenthetical.

**Rationale**: The existing comment at `AssetController.deleteNotSeenByCrowdStrike` is explicit: "The safety brake is intentionally not applied here — the admin already chose this action (typically after a --dry-run)." Extending that contract to the legacy rule is mechanical. Inconsistency between the two rules — brake-on for legacy manual runs, brake-off for timestamp manual runs — would surprise admins and require ad-hoc justification.

**Alternatives considered**:
- *Apply brake to manual runs only when `includeLegacy=true`*: rejected — surprises admins who expect manual = no brake, and the workflow already requires a dry-run first.
- *Apply brake to all manual runs going forward*: out of scope; would also break existing operator scripts.

---

## Decision 6 — Behaviour of `includeLegacy` per-run override semantics

**Decision**: The optional `includeLegacy: Boolean?` field on `CrowdStrikeAssetCleanupRequest` overrides the configured default for a single run when non-null. When null (or omitted), the configured default from `secman.crowdstrike.cleanup.include-legacy` applies. The scheduled run does NOT accept overrides — it always reads the configured default.

**Rationale**: This matches the spec's Edge Cases ("override wins for that one run"). The scheduled job has no per-run input surface (it's a cron), so applying the configured default is the only sensible behaviour.

**Alternatives considered**:
- *Make request field non-nullable with a default of false*: rejected — would silently flip the default to "off" for every manual run regardless of configured global, which contradicts FR-009.
- *Add a `clusterOverride` mechanism*: out of scope.

---

## Decision 7 — Order of evaluation when both rules contribute candidates

**Decision**: Rule A evaluated first, rule B second; combined list is `(timestampCandidates + legacyCandidates).distinctBy { it.id }`. Reason is set when each rule first produces the candidate, so if an asset somehow appeared under both rules, it would carry the rule-A reason. In practice the predicates are mutually exclusive on `crowdstrike_last_imported_at IS NULL` vs `< cutoff`, so the de-dup is purely defensive.

**Rationale**: Spec Story 1 acceptance scenario #5 (post-clarify) explicitly notes the predicates' mutual exclusion. Choosing rule A as the priority preserves the existing invariant that a "timestamp-stale" deletion is attributed as such.

**Alternatives considered**:
- *De-dup by `(id, reason)` keeping both reasons*: rejected — adds complexity for a state that cannot occur under the current predicates.
- *Throw on overlap*: rejected — defensive programming should not crash production runs over a logically-impossible condition.

---

## Decision 8 — Flyway migration name and contents

**Decision**: `V210__crowdstrike_cleanup_run_legacy_columns.sql` adds two NOT NULL DEFAULT 0 columns. Hibernate's `ddl-auto` is configured to harmonize with this migration; the entity gets matching `var legacyCandidateCount: Int = 0` and `var legacyDeletedCount: Int = 0` fields with `@Column(name = ..., nullable = false)`.

**Rationale**: Continues the `V###__snake_case_purpose.sql` naming convention used by V200..V209. The DEFAULT 0 ensures existing rows don't violate NOT NULL during migration. The `Int = 0` defaults on the Kotlin field mean any code path constructing a `CrowdStrikeCleanupRun` without specifying these fields gets safe values.

**Alternatives considered**:
- *Nullable columns*: rejected — count columns should never be null; "no legacy contribution" is `0`, not `NULL`.
- *Single `legacy_stats_json` text column*: rejected — opaque to SQL queries and prevents the obvious ORDER BY in audit queries.

---

## Decision 9 — Frontend toggle initial state source

**Decision**: The React island fetches `/api/crowdstrike/cleanup/config` on mount (or accepts the value as a server-rendered prop from `falcon-config.astro`) and initializes the toggle from `config.includeLegacy`. No hardcoded default.

**Rationale**: Spec FR-009 + FR-011 + SC-006. The existing component already fetches config for `staleDays`, `maxDeletePercent`, and `enabled`; adding `includeLegacy` to the same fetch is a one-field extension. The Astro page can either pass it via props (server-side) or let the island fetch it (client-side); the existing pattern in `StaleAssetCleanup.tsx` will be followed.

**Alternatives considered**:
- *Hardcode `false` in the React component and pass override every time*: rejected — directly violates SC-006 and creates UI/backend drift.
- *Persist toggle state in localStorage*: rejected — admins would lose visibility into the configured default after their first override.

---

## Decision 10 — Test-data construction for new tests

**Decision**: Reuse `TestDataFactory` for asset and user fixtures; add a small helper (in the same factory) that builds a "legacy CrowdStrike asset" with `owner = AssetOwners.CROWDSTRIKE_IMPORT`, `crowdStrikeLastImportedAt = null`, `manualCreator = null`, `scanUploader = null`, `lastSeen = LocalDateTime.now().minusDays(60)`. Tests parameterize the staleness and the fence-violating field for each scenario.

**Rationale**: The existing factory is the canonical place for test fixtures. Concentrating the legacy-asset shape in one helper guarantees that every test exercises the same predicate the production query uses.

**Alternatives considered**:
- *Inline asset construction per test*: rejected — six tests with five-line constructors each is 30 lines of duplication that will drift.
- *Spring `@Sql` migration with seed data*: not used in this codebase; rejected.

---

## Decision 11 — OpenAPI/Swagger maintenance (Constitution III)

**Decision**: Feature 087 does NOT introduce an OpenAPI/Swagger spec. The HTTP contract MD files in `contracts/` document the JSON shapes for the new and changed endpoints during this feature's lifecycle.

**Rationale**: Audit of the repository (during the `/speckit.analyze` finding C1) shows no OpenAPI plugin (`io.micronaut.openapi` is absent from `src/backendng/build.gradle.kts`), no static `openapi.yml`/`swagger.yml` under `docs/` or `src/backendng/src/main/resources/`, and no Swagger UI endpoint. Constitution Principle III's mandate ("OpenAPI/Swagger documentation MUST be maintained") is currently unmet codebase-wide. Introducing OpenAPI for Feature 087 alone would create a partial spec covering only three endpoints out of the many on the API surface — a misleading deliverable. Resolving the constitution-level gap requires a separate, codebase-scoped initiative (add the plugin, annotate every controller, validate the generated spec against existing tests).

**Alternatives considered**:
- *Add OpenAPI for the modified endpoints only*: rejected — produces a misleading partial spec that implies the rest of the API is undocumented.
- *Block Feature 087 on adding repo-wide OpenAPI*: rejected — grossly out of proportion for an additive cleanup-rule feature, and would couple two unrelated initiatives.
- *Generate per-endpoint Swagger fragments inline in controller KDoc*: rejected — non-standard, not consumed by any tooling here, and creates the same partial-coverage problem.

**Follow-up**: A separate ticket should propose adding `io.micronaut.openapi` to the build and producing a complete spec. Until then, the per-feature `contracts/*.md` files in each `specs/NNN-…/contracts/` directory carry the contract documentation burden.

---

## Open items deferred to `/speckit.tasks`

- Exact ordering of unit-test cases (independent — order does not matter).
- Exact JS error patterns to look for in `/e2ejs` runs (handled by the e2ejs skill itself).
- Whether `CLAUDE.md` should grow a new top-level section or just append to the CrowdStrike notes (low impact, planner picks).

## All NEEDS CLARIFICATION resolved

No outstanding markers. Proceed to Phase 1.
