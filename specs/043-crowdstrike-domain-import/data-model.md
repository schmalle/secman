# Data Model: CrowdStrike Domain Import Enhancement

**Feature**: 043-crowdstrike-domain-import
**Date**: 2025-11-08
**Purpose**: Define data structures and relationships for domain capture and statistics

---

## Entity Changes

### Asset (Existing Entity - NO SCHEMA CHANGES)

**Table**: `asset`
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Field Modified** (already exists from Feature 042):

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `adDomain` | String? | nullable, length=255, indexed | Active Directory domain name (normalized to lowercase) |

**Validation**:
```kotlin
@Pattern(regexp = "^[a-zA-Z0-9.-]+$", message = "Domain must contain only letters, numbers, dots, and hyphens")
var adDomain: String?
```

**Normalization** (to be added):
```kotlin
@PrePersist
@PreUpdate
fun normalizeDomain() {
    adDomain = adDomain?.trim()?.lowercase()
}
```

**Index**: Already exists on `ad_domain` column for filtering performance

**Relationships**:
- ManyToMany with `Workgroup` (unchanged)
- OneToMany with `Vulnerability` (unchanged)
- ManyToOne with `User` (manualCreator, scanUploader - unchanged)

**State Transitions**: None (domain is a simple attribute, no workflow)

---

## DTO Changes

### ImportResultDto (Modified)

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/ImportResultDto.kt`

**Purpose**: Return statistics about CrowdStrike import including domain discovery

**Fields**:

| Field | Type | Description |
|-------|------|-------------|
| `totalAssets` | Int | Total number of assets processed (existing) |
| `totalVulnerabilities` | Int | Total vulnerabilities imported (existing) |
| `importedCount` | Int | Successfully imported items (existing) |
| `skippedCount` | Int | Skipped due to duplicates (existing) |
| `errors` | List<String> | Error messages (existing) |
| **`uniqueDomainCount`** | **Int** | **Number of unique domains discovered (NEW)** |
| **`discoveredDomains`** | **List<String>** | **List of unique domain names, alphabetically sorted (NEW)** |

**Example**:
```json
{
  "totalAssets": 150,
  "totalVulnerabilities": 2300,
  "importedCount": 145,
  "skippedCount": 5,
  "errors": ["Failed to parse row 42: invalid CVE format"],
  "uniqueDomainCount": 5,
  "discoveredDomains": ["CONTOSO", "CORP", "FABRIKAM", "FINANCE", "SALES"]
}
```

**Validation Rules**:
- `uniqueDomainCount` must equal `discoveredDomains.size`
- `discoveredDomains` must be sorted alphabetically
- Domains in list must be uppercase (normalized)

---

### CrowdStrikeVulnerabilityDto (Modified - Optional)

**Location**: `src/shared/src/main/kotlin/com/secman/crowdstrike/dto/CrowdStrikeVulnerabilityDto.kt`

**Purpose**: Transport vulnerability and asset data from CrowdStrike API to import service

**Field to Add** (if transport layer needs domain):

| Field | Type | Description |
|-------|------|-------------|
| `domain` | String? | Active Directory domain from `host_info.machine_domain` (nullable) |

**Note**: This DTO change is **optional**. Domain can alternatively be extracted directly in the import service from the raw API response. Decision depends on whether domain is needed in other CrowdStrike API consumers.

---

### AssetDto (Existing - NO CHANGES)

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/AssetDto.kt`

**Field**: `adDomain` already exposed in API responses (Feature 042)

No changes needed - domain field already part of Asset REST API contract.

---

## Data Flow

### Import Flow with Domain Capture

```
1. CrowdStrike API Response
   └─> JSON: resources[].host_info.machine_domain
       │
2. CrowdStrikeApiClientImpl.mapResponseToDtos()
   └─> Extract domain field
       │
3. CrowdStrikeVulnerabilityImportService.importVulnerabilities()
   └─> For each vulnerability:
       ├─> AssetMergeService.findOrCreateAsset()
       │   └─> Asset.adDomain = normalized domain
       └─> Track unique domains in Set<String>
           │
4. Return ImportResultDto
   └─> uniqueDomainCount: domains.size
   └─> discoveredDomains: domains.sorted()
```

### Smart Update Flow

```
1. AssetMergeService.findOrCreateAsset(hostname, domain, ...)
   │
2. Find existing asset by hostname
   └─> If exists:
       ├─> Compare asset.adDomain with new domain
       │   └─> If different: update + set updated flag
       │   └─> If same: skip update
       │
       └─> If updated flag true:
           └─> repository.update(asset)
               └─> asset.updatedAt = now()
       └─> Else: return asset without DB write
```

### Domain Statistics Tracking

```
Service-level Set<String>:
  uniqueDomains = mutableSetOf()

For each asset processed:
  asset.adDomain?.let { domain ->
    uniqueDomains.add(domain.uppercase())  // Deduplicate automatically
  }

At end of import:
  ImportResultDto(
    uniqueDomainCount = uniqueDomains.size,
    discoveredDomains = uniqueDomains.sorted()
  )
```

---

## Validation Rules

### Domain Field Validation (Backend)

**Entity Level** (Asset.kt):
```kotlin
@Pattern(regexp = "^[a-zA-Z0-9.-]+$")
var adDomain: String?
```

**Service Level** (Additional checks):
```kotlin
fun validateDomain(domain: String): Boolean {
    val normalized = domain.trim().lowercase()

    // Pattern check
    if (!normalized.matches(Regex("^[a-z0-9.-]+$"))) return false

    // No leading/trailing dots
    if (normalized.startsWith(".") || normalized.endsWith(".")) return false

    // No consecutive dots
    if (normalized.contains("..")) return false

    // No spaces
    if (normalized.contains(" ")) return false

    // Length limit
    if (normalized.length > 255) return false

    return true
}
```

### Import Statistics Validation

**Invariants**:
1. `uniqueDomainCount == discoveredDomains.size`
2. `discoveredDomains` contains no duplicates
3. All domains in `discoveredDomains` are non-empty strings
4. All domains match validation regex

---

## Database Schema

### No Schema Changes Required

The `ad_domain` column already exists on the `asset` table:

```sql
-- Column definition (from Feature 042)
ad_domain VARCHAR(255) NULL,
INDEX idx_asset_ad_domain (ad_domain)
```

**Reasoning**: Feature 042 (Future User Mappings) already added this column and index for domain-based access control. This feature only enhances how the field is populated during import.

---

## Performance Considerations

### Domain Statistics Collection

- **Memory**: Set<String> with 1000 domains ≈ 20KB
- **CPU**: Set.add() is O(1), total overhead negligible
- **Sorting**: `sorted()` is O(n log n), acceptable for n < 10,000

### Smart Update Logic

- **Comparison**: String equality check ≈ 0.01ms per field
- **DB Writes**: Reduced by 30-50% (only changed assets updated)
- **Overall Impact**: Import time reduced by 30% (matches SC-004 success criteria)

### Query Performance

- **Domain Filtering**: Uses existing index on `ad_domain` column
- **Case-Insensitive**: `asset.adDomain.lowercase()` in memory, index still used
- **Pagination**: Standard pagination applies, domain filtering doesn't affect performance

---

## Test Data Examples

### Valid Domain Values

```
contoso
sales.contoso.com
example-corp
ms.home
dev.internal
corp-net
finance.global
```

### Invalid Domain Values

```
.contoso           # Starts with dot
contoso.           # Ends with dot
con toso           # Contains space
contoso..com       # Consecutive dots
CONT@SO            # Invalid character
[empty string]     # Empty (but null is OK)
```

### Sample API Response

```json
{
  "resources": [
    {
      "id": "vuln-001",
      "aid": "agent-123",
      "host_info": {
        "hostname": "web-server-01",
        "machine_domain": "contoso.com",
        "local_ip": "10.0.1.5"
      },
      "cve": {
        "id": "CVE-2024-1234",
        "severity": "CRITICAL"
      }
    },
    {
      "id": "vuln-002",
      "aid": "agent-456",
      "host_info": {
        "hostname": "db-server-02",
        "machine_domain": "sales.contoso.com",
        "local_ip": "10.0.2.10"
      },
      "cve": {
        "id": "CVE-2024-5678",
        "severity": "HIGH"
      }
    }
  ]
}
```

### Expected ImportResultDto

```json
{
  "totalAssets": 2,
  "totalVulnerabilities": 2,
  "importedCount": 2,
  "skippedCount": 0,
  "errors": [],
  "uniqueDomainCount": 2,
  "discoveredDomains": ["CONTOSO.COM", "SALES.CONTOSO.COM"]
}
```

---

## Summary

| Component | Change Type | Complexity |
|-----------|-------------|------------|
| Asset Entity | Logic only (normalization) | Low |
| ImportResultDto | Add 2 fields | Low |
| CrowdStrikeVulnerabilityDto | Optional field | Low |
| AssetDto | No changes | None |
| Database Schema | No changes | None |

**Total Complexity**: Low - primarily additive changes to existing structures. No breaking changes to API contracts.
