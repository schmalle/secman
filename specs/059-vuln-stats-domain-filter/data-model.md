# Data Model: Vulnerability Statistics Domain Filter

**Feature**: 059-vuln-stats-domain-filter
**Date**: 2026-01-04

## Entities

### Existing Entities (No Changes Required)

#### Asset
The domain filter leverages the existing `ad_domain` field on the Asset entity.

| Field | Type | Description |
|-------|------|-------------|
| adDomain | String (nullable) | Active Directory domain name, normalized to lowercase |

**Index**: `idx_asset_ad_domain` already exists for query optimization.

---

### New DTOs

#### AvailableDomainsDto

Response DTO for the available domains endpoint.

| Field | Type | Description |
|-------|------|-------------|
| domains | List<String> | Sorted list of unique domain names |
| totalAssetCount | Integer | Total count of assets across all domains |

**Validation Rules**:
- `domains` is never null (may be empty list)
- Domain names are returned in lowercase (normalized)
- List is alphabetically sorted

---

## State Model

### Frontend Domain Filter State

```
┌──────────────────────────────────────────────────────────┐
│                    DomainSelector State                   │
├──────────────────────────────────────────────────────────┤
│ loading: boolean      → true while fetching domains      │
│ error: string | null  → error message if fetch failed    │
│ domains: string[]     → available domains list           │
│ selected: string|null → currently selected (null = all)  │
└──────────────────────────────────────────────────────────┘

State Transitions:
  INITIAL → LOADING → LOADED (success) or ERROR (failure)
  LOADED: user can select domain → triggers parent callback
  ERROR: show error message, allow retry
```

### Session Storage

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `secman.vuln-stats.selectedDomain` | string \| null | null | Persisted domain selection |

**Lifecycle**:
- Written on domain selection change
- Read on component mount
- Cleared on logout (session ends)

---

## Query Patterns

### Domain Filtering Logic

The domain filter is applied as an additional WHERE clause on existing queries:

```sql
-- Existing access control (pseudocode)
WHERE asset.id IN (accessible_asset_ids)

-- With domain filter (when domain is not null)
WHERE asset.id IN (accessible_asset_ids)
  AND asset.ad_domain = :selectedDomain
```

**Important**: The domain filter NEVER bypasses access control. It is always applied as an additional constraint.

### Available Domains Query

```sql
SELECT DISTINCT a.ad_domain
FROM asset a
WHERE a.id IN (accessible_asset_ids)
  AND a.ad_domain IS NOT NULL
ORDER BY a.ad_domain ASC
```

---

## Relationships

```
┌─────────────────┐         ┌──────────────────────┐
│  DomainSelector │────────▶│ Available Domains    │
│  (Frontend)     │ fetches │ Endpoint (Backend)   │
└─────────────────┘         └──────────────────────┘
        │
        │ passes selected domain
        ▼
┌─────────────────────────────────────────────────────┐
│              Statistics Components                   │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ │
│  │ MostCommon   │ │ MostVuln     │ │ Severity     │ │
│  │ Vulns        │ │ Products     │ │ Distribution │ │
│  └──────────────┘ └──────────────┘ └──────────────┘ │
└─────────────────────────────────────────────────────┘
        │
        │ passes domain as query param
        ▼
┌─────────────────────────────────────────────────────┐
│         Existing Statistics Endpoints                │
│  (with optional ?domain= parameter)                  │
└─────────────────────────────────────────────────────┘
```
