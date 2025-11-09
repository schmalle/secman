# Domain Name Normalization - Decision Summary for Feature 043

## Quick Reference

**Decision**: Normalize Active Directory domain names to **lowercase at storage**, with **case-insensitive comparison at query time**.

---

## Decision

**Chosen Approach**: Lowercase normalization with case-insensitive comparison

Store all AD domain names in **lowercase** form in the database. Perform case-insensitive comparisons in Kotlin using `.lowercase()` or `.ignoreCase=true` Kotlin string functions.

---

## Storage Format

**Selected**: Lowercase (e.g., `contoso`, `example-corp`, `sales.domain`)

**Why**:
- RFC 1035 compliant (DNS treats domains as case-insensitive)
- Consistent with email normalization in UserMapping (emails are also lowercased)
- Enables reliable deduplication (prevents "CONTOSO" vs "contoso" duplicates)
- Single case-sensitive database index sufficient for all lookups
- Matches existing Feature 042 implementation

**Not Chosen**:
- Uppercase (CONTOSO): Inconsistent with email, not RFC compliant
- Original case (ConTosO): Breaks unique constraints, difficult deduplication
- Dual columns: Extra storage, violates DRY

---

## Comparison Method

### In Kotlin

**Simple Equality**:
```kotlin
// Both sides lowercase
asset.adDomain?.lowercase() == userDomain.lowercase()
```

**Set Membership** (for multiple domains):
```kotlin
val userDomainsSet = userDomains.map { it.lowercase() }.toSet()
asset.adDomain?.lowercase() in userDomainsSet  // returns Boolean
```

**Substring Match**:
```kotlin
// Case-insensitive contains
asset.adDomain?.contains(filterDomain, ignoreCase=true) == true
```

**Note**: Comparison at query time is acceptable (not required at storage time) because:
- AssetFilterService already uses this pattern successfully
- Performance is fine for <10,000 assets in memory
- Supports substring filtering ("sales" matches "sales.contoso")

---

## Edge Case Handling

| Edge Case | Rule | Implementation |
|-----------|------|-----------------|
| **Trailing dot** (contoso.com.) | Reject | `!domain.endsWith(".")` in validation |
| **Leading dot** (.contoso) | Reject | `!domain.startsWith(".")` in validation |
| **Mixed case** (CoNtOsO) | Normalize to lowercase | `domain.lowercase()` in @PrePersist |
| **Whitespace** (leading/trailing) | Trim | `domain.trim()` in @PrePersist |
| **Internal spaces** (con toso) | Reject | `!domain.contains(" ")` in validation |
| **Null/empty** | Allow null | `adDomain: String? = null` in Asset model |
| **Hyphens** (contoso-sales) | Allow | Regex allows `[a-z0-9.-]+` |

**Validation Regex**: `^[a-z0-9.-]+$` (applied to lowercase domain)

**Normalization Order**: Trim → Lowercase (trim first to remove spaces, then lowercase)

---

## Rationale

1. **Security**: Prevents duplicate entries via case variations
2. **RFC 1035 Compliance**: Matches DNS standard (domains are case-insensitive)
3. **Performance**: Single index handles all lookups when storage is normalized
4. **Consistency**: Aligns with UserMapping domain normalization (Feature 042) and email handling
5. **Simplicity**: One normalization point (storage) vs multiple comparison points

---

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Uppercase (CONTOSO) | Inconsistent with email normalization; not RFC standard |
| Original case (ConTosO) | Breaks unique constraints; deduplication fails at DB level |
| Dual columns | Extra storage; data sync complexity; violates DRY |
| Query-only normalization | Unique constraint doesn't work; import deduplication fails |

---

## Code References

**Existing Precedent** (Feature 042 - UserMapping):

1. **Storage Normalization** (`UserMapping.kt` lines 111-120):
```kotlin
@PrePersist
fun onCreate() {
    email = email.lowercase().trim()
    domain = domain?.lowercase()?.trim()
}
```

2. **Import Validation** (`UserMappingImportService.kt` lines 194-228):
```kotlin
private fun validateDomain(domain: String): Boolean {
    val normalized = domain.lowercase()
    return normalized.matches(Regex("^[a-z0-9.-]+$")) &&
           !normalized.startsWith(".") &&
           !normalized.endsWith(".") &&
           !normalized.startsWith("-") &&
           !normalized.endsWith("-") &&
           !normalized.contains(" ")
}
```

3. **Query-Time Comparison** (`AssetFilterService.kt` lines 92-95):
```kotlin
val userDomainsLowercase = userDomains.map { it.lowercase() }.toSet()
allAssetsWithDomain.filter { asset ->
    asset.adDomain?.lowercase() in userDomainsLowercase
}
```

---

## Implementation Checklist for Feature 043

- [ ] Apply @PrePersist normalization to Asset.adDomain (if importing domains during asset creation)
- [ ] Add validation function for domain format (reject trailing dots, leading dots, spaces)
- [ ] Normalize domains to lowercase during import (Excel/CSV parsing)
- [ ] Use case-insensitive comparison in queries: `asset.adDomain?.lowercase() in userDomainsSet`
- [ ] Test edge cases: trailing dots, mixed case, leading dots, whitespace
- [ ] Update unique constraint if needed: `(email, awsAccountId, domain)` works with lowercase storage
- [ ] Document domain format requirements in API docs

---

## Example Usage

```kotlin
// Import handling
val domain = "CONTOSO.COM"
val normalized = domain.lowercase().trim()  // "contoso.com"
// Stored as lowercase

// Query time comparison
val userMappingDomain = "contoso.com"  // Already lowercase from storage
val assetDomain = "CONTOSO.COM"        // Raw input
val matches = assetDomain.lowercase() == userMappingDomain  // true

// Multiple domains (case-insensitive set membership)
val userDomains = listOf("CONTOSO", "EXAMPLE.COM", "SALES")
val userDomainsSet = userDomains.map { it.lowercase() }.toSet()
val assetDomain = "contoso"
val accessible = assetDomain.lowercase() in userDomainsSet  // true
```

---

## Performance Impact

- **Storage**: No change (single lowercase string field)
- **Indexing**: Single case-sensitive index on normalized domain sufficient
- **Query**: Minimal (one `.lowercase()` call per comparison - negligible)
- **Deduplication**: Improved (catches case variants at import time)

---

## Out of Scope

- Internationalized Domain Names (IDN/Punycode): Can be added in future if needed
- Unicode normalization: Assumed ASCII-only AD domains
- FQDN vs relative names: AD domains typically used without trailing dot

---

**Decision Date**: 2025-11-08
**Based On**: RFC 1035, Feature 042 precedent, codebase analysis
**Next Step**: Implementation in Feature 043
