# Research: Vulnerability Statistics Domain Filter

**Feature**: 059-vuln-stats-domain-filter
**Date**: 2026-01-04

## Research Tasks

### 1. Existing Domain Filtering Patterns in Codebase

**Question**: How does the existing codebase handle domain-based queries?

**Decision**: Follow the existing `DomainVulnsService` pattern for domain-related queries.

**Rationale**:
- `domainVulnsService.ts` already fetches domain-based vulnerability data
- Backend `Asset` entity has indexed `ad_domain` column (`idx_asset_ad_domain`)
- Existing access control pattern in `VulnerabilityStatisticsService` can be extended

**Alternatives Considered**:
- Create new service specifically for domain filtering → Rejected: unnecessary complexity, existing patterns sufficient
- Query domains from a separate table → Rejected: domains are derived from asset data, not standalone entities

---

### 2. API Design: Query Parameter vs New Endpoint

**Question**: Should domain filtering be a query parameter on existing endpoints or new dedicated endpoints?

**Decision**: Add optional `domain` query parameter to existing statistics endpoints.

**Rationale**:
- Backward compatible - existing calls without parameter work unchanged
- Follows REST best practices for filtering
- Matches existing pattern (e.g., `days` parameter on `/temporal-trends`)
- Single endpoint serves both filtered and unfiltered use cases

**Alternatives Considered**:
- New endpoints like `/most-common/by-domain/{domain}` → Rejected: proliferates endpoints, harder to maintain
- POST body with filter object → Rejected: violates REST GET semantics for read operations

---

### 3. Domain List Retrieval Strategy

**Question**: How should the frontend obtain the list of available domains?

**Decision**: Add new endpoint `GET /api/vulnerability-statistics/available-domains` that returns unique domains from user-accessible assets.

**Rationale**:
- Dedicated endpoint allows independent loading (per spec FR-009)
- Can be cached client-side for performance
- Respects existing access control - only returns domains from accessible assets
- Consistent with existing API structure

**Alternatives Considered**:
- Include domains in each statistics response → Rejected: bloats responses, not always needed
- Separate domains endpoint in different controller → Rejected: logically belongs with statistics

---

### 4. Session Storage Key Pattern

**Question**: What key pattern should be used for session storage persistence?

**Decision**: Use `secman.vuln-stats.selectedDomain` as the session storage key.

**Rationale**:
- Namespaced with `secman.` prefix for consistency
- Scoped to the feature `vuln-stats` to avoid conflicts
- Descriptive key name for debugging

**Alternatives Considered**:
- Generic key like `selectedDomain` → Rejected: collision risk with other features
- LocalStorage instead of SessionStorage → Rejected: spec requires session-only persistence (FR-007)

---

### 5. Component Communication Pattern

**Question**: How should the DomainSelector communicate domain changes to statistics components?

**Decision**: Use React state lifting - parent page component manages domain state and passes to all children.

**Rationale**:
- Simple and explicit data flow
- Works well with Astro islands architecture (each React component receives props)
- No need for global state management for this localized feature
- Aligns with existing patterns in the codebase

**Alternatives Considered**:
- React Context for domain state → Rejected: overkill for single page, adds complexity
- URL query parameter for domain → Rejected: clutters URL, not needed for session-only state
- Custom events between components → Rejected: harder to debug, implicit coupling

---

## Summary

All technical decisions align with existing codebase patterns:
- Extend existing controller/service with optional parameter
- Add one new endpoint for domain list
- Use session storage with namespaced key
- React state lifting for component communication

No blockers identified. Ready for Phase 1 design.
