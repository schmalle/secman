# Domain Name Normalization Strategy Research - Feature 043

**Prepared**: 2025-11-08
**Research Scope**: RFC 1035 standards, existing codebase patterns, edge case handling

---

## Executive Summary

Based on comprehensive research of RFC 1035, existing Feature 042 implementation, and current codebase patterns, the recommended approach is **lowercase normalization at storage with case-insensitive comparison at query time**. This aligns with DNS standards, maintains consistency with current UserMapping domain handling, and provides optimal performance for the secman system.

---

## 1. Domain Name Standards (RFC 1035)

### Case Sensitivity Rules

**RFC 1035 Specification**: "All comparisons between character strings (e.g., labels, domain names, etc.) are done in a case-insensitive manner."

**Key Points**:
- DNS treats domain names as **case-insensitive** at the protocol level
- "CONTOSO", "contoso", "ConTosO" are identical in DNS
- Original case should be preserved when data enters the system *when possible*
- However, for consistent storage and comparison, normalization is recommended

### Length Limits

Per RFC 1035:
- **Individual labels**: Maximum 63 characters per label
- **Complete FQDN**: Maximum 255 octets total (including length octets)
- **Format**: Labels separated by dots (e.g., "contoso.com" = 11 characters)

### Trailing Dot Specifications

RFC 1035 distinguishes:
- **Absolute names** (with trailing dot): "contoso.com." - considered complete
- **Relative names** (no trailing dot): "contoso.com" - may require origin concatenation

**For Active Directory domains**: Typically no trailing dot (relative form used in Windows environments)

---

## 2. Existing Codebase Patterns (Feature 042 & Related)

### Current Implementation in UserMapping Domain

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`

```kotlin
/**
 * Lifecycle callback - executed before entity is persisted to database
 * Normalizes email and domain to lowercase, trims whitespace
 */
@PrePersist
fun onCreate() {
    val now = Instant.now()
    createdAt = now
    updatedAt = now
    // Normalize email and domain to lowercase for case-insensitive matching
    email = email.lowercase().trim()
    domain = domain?.lowercase()?.trim()
    awsAccountId = awsAccountId?.trim()
}
```

**Key Findings**:
- Domain is normalized to **lowercase before storage** (lines 117-118)
- Normalization includes trimming whitespace
- Entity-level normalization ensures consistent storage format

### Validation Patterns

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt`

```kotlin
// Domain validation (line 252-260)
private fun validateDomain(domain: String): Boolean {
    val normalized = domain.lowercase()
    return normalized.matches(Regex(DOMAIN_PATTERN)) &&
           !normalized.startsWith(".") &&
           !normalized.endsWith(".") &&
           !normalized.startsWith("-") &&
           !normalized.endsWith("-") &&
           !normalized.contains(" ")
}
```

**Edge Cases Already Handled**:
- ✅ Trailing dots rejected (`.endwithdot.com.`)
- ✅ Leading dots rejected (`.startwithdot.com`)
- ✅ Leading/trailing hyphens rejected
- ✅ Internal whitespace rejected
- ✅ Case-insensitive validation via `.lowercase()`

### CSV Parser Implementation

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/CSVUserMappingParser.kt`

```kotlin
// Same validation pattern (lines 334-348)
private fun validateDomain(domain: String): Boolean {
    val normalized = domain.lowercase()
    // Special case: sentinel value "-NONE-"
    if (normalized == DEFAULT_DOMAIN.lowercase()) {
        return true
    }
    return normalized.matches(Regex(DOMAIN_PATTERN)) &&
           !normalized.startsWith(".") &&
           !normalized.endsWith(".") &&
           !normalized.startsWith("-") &&
           !normalized.endsWith("-") &&
           !normalized.contains(" ")
}
```

**Additional Features**:
- Supports sentinel value "-NONE-" for missing domains
- Normalizes to lowercase during import (line 449)

### Access Control Filtering (AssetFilterService)

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt`

```kotlin
// Get assets accessible via AD domain mapping (case-insensitive) - lines 84-101
val domainAssets = if (userEmail != null) {
    val userDomains = userMappingRepository.findDistinctDomainByEmail(userEmail)
    if (userDomains.isNotEmpty()) {
        val allAssetsWithDomain = assetRepository.findAll().filter { it.adDomain != null }

        // Filter assets whose adDomain matches any user domain (case-insensitive)
        val userDomainsLowercase = userDomains.map { it.lowercase() }.toSet()
        allAssetsWithDomain.filter { asset ->
            asset.adDomain?.lowercase() in userDomainsLowercase
        }
    } else {
        emptyList()
    }
} else {
    emptyList()
}
```

**Key Pattern**:
- Both sides lowercased at query time for comparison
- **NOT** requiring pre-normalized storage for this pattern
- Uses in-memory comparison (acceptable for current dataset size)

### Vulnerability Filter Example

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt`

```kotlin
// Apply AD domain filter (asset AD domain contains)
if (!adDomain.isNullOrBlank()) {
    currentVulns = currentVulns.filter { vuln ->
        vuln.asset.adDomain?.contains(adDomain, ignoreCase = true) == true
    }
}
```

**Observation**:
- Uses substring match with case-insensitive comparison
- No pre-normalization required; comparison is done at query time

### Test Coverage for Edge Cases

**File**: `/Users/flake/sources/misc/secman/src/test/kotlin/com/secman/service/CSVUserMappingParserTest.kt`

```kotlin
// Test data showing what's rejected:
"""
234567890123,user2@example.com,.startwithdot.com
345678901234,user3@example.com,endwithdot.com.
456789012345,user4@example.com,has space.com
""".trimIndent()

// Result: Only 1 imported, 3 skipped
assertEquals(1, result.imported, "Should only import 1 valid domain")
assertEquals(3, result.skipped, "Should skip 3 invalid domains")
```

---

## 3. Storage Format Decision

### Evaluated Options

| Option | Pros | Cons | Current Status |
|--------|------|------|-----------------|
| **Lowercase (RECOMMENDED)** | Consistent RFC 1035 compliance, matches email pattern, enables efficient indexing, simplifies comparison | Slight visual loss of original case | ✅ Already implemented in Feature 042 |
| Uppercase (CONTOSO) | Matches common AD display format | Not RFC compliant, inconsistent with email normalization, harder to read in logs | ❌ Not used in codebase |
| Original Case (ConTosO) | Preserves user input | Inconsistent storage, requires case-insensitive queries everywhere, harder to deduplicate | ❌ Not used in codebase |

### Decision Rationale

**Chosen Format**: **LOWERCASE**

**Justification**:
1. **RFC 1035 Compliance**: Aligns with DNS standard practices
2. **Consistency**: Email already normalized to lowercase in UserMapping (`email.lowercase()`)
3. **Database Efficiency**:
   - Enables case-sensitive indexing (one index serves all lookups)
   - Unique constraint on (email, awsAccountId, domain) works reliably
4. **Security**: Prevents duplicate entries via mixed-case variations (e.g., "CONTOSO" vs "contoso" counted as different)
5. **Feature 042 Precedent**: Already implemented and tested for domains

---

## 4. Comparison Strategy (Kotlin Implementation)

### Recommended Pattern: Normalize at Storage, Compare at Query

```kotlin
// Pattern 1: Simple case-insensitive equality (for exact matches)
val userDomainLowercase = userDomain.lowercase()
val assetDomainLowercase = asset.adDomain?.lowercase()
val matches = userDomainLowercase == assetDomainLowercase

// Pattern 2: Case-insensitive substring match
vuln.asset.adDomain?.contains(adDomain, ignoreCase = true) == true

// Pattern 3: Case-insensitive collection membership (for sets)
val userDomainsSet = userDomains.map { it.lowercase() }.toSet()
asset.adDomain?.lowercase() in userDomainsSet
```

### Why at Query Time (Not Storage)?

Current AssetFilterService uses query-time comparison:
- **Flexibility**: Supports contains() for filtering without pre-normalization
- **Performance**: For <10,000 assets in memory, in-memory comparison is fine
- **Simplicity**: Avoids separate normalized storage column
- **Consistency**: Matches existing vulnerability filter pattern

### For Storage Normalization

UserMapping already does this:
```kotlin
@PrePersist
fun onCreate() {
    domain = domain?.lowercase()?.trim()
}
```

**Benefit**: Database constraints and deduplication work correctly

---

## 5. Edge Cases & Handling

### Comprehensive Edge Case Matrix

| Edge Case | RFC 1035 Rule | Current Code | Decision |
|-----------|---------------|--------------|----------|
| **Trailing dot** (contoso.com.) | Absolute name; optional in AD context | Explicitly rejected in validation | ✅ REJECT |
| **Leading dot** (.contoso.com) | Invalid label format | Explicitly rejected in validation | ✅ REJECT |
| **Mixed case** (CoNtOsO) | Case-insensitive in DNS | Normalized to lowercase at storage | ✅ NORMALIZE TO LOWERCASE |
| **Leading/trailing spaces** ( contoso.com ) | Not valid in DNS | Trimmed in PrePersist | ✅ TRIM |
| **Internal spaces** (con toso.com) | Invalid per RFC | Explicitly rejected in validation | ✅ REJECT |
| **Null/empty domains** | N/A | Allowed in model (adDomain nullable) | ✅ ALLOW NULL |
| **Hyphenated domains** (contoso-sales.com) | Valid per RFC 1035 | Allowed via regex `[a-z0-9.-]+` | ✅ ALLOW |
| **Numeric-only labels** (123.456.789) | Valid per RFC (though unusual for AD) | Allowed via regex | ✅ ALLOW |
| **Internationalized domains** (münchen.de) | IDN/Punycode in DNS | Not currently supported | ❌ OUT OF SCOPE |
| **Consecutive dots** (contoso..com) | Invalid | Not explicitly checked, but invalid per regex | ⚠️ VALIDATED BY REGEX |
| **All numeric domain** (123.456) | Valid per RFC but unusual | Allowed via regex | ✅ ALLOW |

### Implementation Rules

1. **Storage Normalization (UserMapping.PrePersist)**
   - Trim whitespace: `domain?.trim()`
   - Lowercase: `domain?.lowercase()`
   - Order: trim → lowercase

2. **Import Validation**
   - Reject trailing dots: `!domain.endsWith(".")`
   - Reject leading dots: `!domain.startsWith(".")`
   - Reject internal spaces: `!domain.contains(" ")`
   - Reject leading/trailing hyphens: `!domain.startsWith("-") && !domain.endsWith("-")`
   - Allow alphanumeric + dots + hyphens: `matches(Regex("^[a-z0-9.-]+$"))`

3. **Query Comparison**
   - Always use case-insensitive: `.equals(other, ignoreCase=true)` or `.contains(text, ignoreCase=true)`
   - Works with null: `asset.adDomain?.lowercase() in userDomainsSet`

4. **Null Handling**
   - Nullable in Asset model: `adDomain: String? = null`
   - Nullable in UserMapping: `domain: String? = null`
   - Assets without domain excluded from domain-based filtering: `filter { it.adDomain != null }`

---

## 6. Unique Counting for Import Statistics

### Current Implementation (Feature 042)

**Deduplication with unique constraint** (UserMapping.kt lines 42-46):
```kotlin
@Table(
    name = "user_mapping",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_mapping_composite",
            columnNames = ["email", "aws_account_id", "domain", "ip_address"]
        )
    ]
)
```

**Import Service checks duplicates** (UserMappingImportService.kt lines 103-112):
```kotlin
if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
    mapping.email, mapping.awsAccountId, mapping.domain
)) {
    skipped++
    log.debug("Skipped duplicate mapping at row {}: {}", rowIndex + 1, mapping)
} else {
    userMappingRepository.save(mapping)
    imported++
}
```

**Key Point**: Because domain is stored lowercase, duplicate detection works:
- "CONTOSO" and "contoso" treated as identical (both stored as "contoso")
- Unique constraint prevents duplicates reliably

### For Asset Domain Statistics

When counting unique domains in import:
```kotlin
// Count unique domains (after normalization)
val uniqueDomains = assets
    .mapNotNull { it.adDomain?.lowercase() }
    .toSet()
    .size
```

---

## 7. Rationale: Why This Approach

### Principles

1. **Security**: Prevents bypassing unique constraints via mixed-case variations
2. **Performance**: Single lowercase index + normalized storage = O(1) lookups
3. **Maintenance**: Consistent with existing Feature 042 implementation
4. **Compliance**: Follows RFC 1035 DNS standards
5. **User Experience**:
   - Case-insensitive filtering in UI ("CONTOSO" matches "contoso" in searches)
   - Consistent results regardless of user input case

### Trade-offs Accepted

- **Lose original case display**: Mitigation: Display user's input case on UI if needed, store normalized in DB
- **Cannot distinguish CONTOSO=US vs contoso=subsidiary**: Rare in practice; if needed, separate domain field
- **No automatic punycode support**: IDN domains not supported (out of scope for Feature 043)

---

## 8. Alternatives Considered & Rejected

### Alternative 1: Uppercase Normalization (CONTOSO)

**Rejected Because**:
- Inconsistent with email normalization (emails are lowercase)
- Not RFC 1035 compliant (RFC implies lowercase as normalized form)
- Harder to read in logs/debug output
- No codebase precedent

---

### Alternative 2: Original Case Preservation (ConTosO)

**Rejected Because**:
- Requires case-insensitive comparison everywhere
- Difficult deduplication (is "CONTOSO" == "contoso"? Must check at query time)
- Breaks unique constraint (two rows with "CONTOSO" and "contoso" appear different)
- Inconsistent with email handling
- Performance impact: Every query must lowercase both sides
- Tested in CSVUserMappingParserTest - system design explicitly avoids this

---

### Alternative 3: Dual Columns (original + normalized)

**Rejected Because**:
- Extra storage overhead
- Data sync complexity (preupdate/prepersist must keep in sync)
- Violates DRY principle
- Not necessary given query-time lowercasing works fine
- No codebase precedent

---

### Alternative 4: Query-Time Normalization Only (no storage normalization)

**Rejected Because**:
- Breaks unique constraint: `(email, awsAccountId, "CONTOSO")` and `(email, awsAccountId, "contoso")` both stored
- Import deduplication fails (thinks they're different)
- Inconsistent storage format makes debugging harder
- Not followed by existing UserMapping (which does normalize at storage)

---

## 9. Code References & Implementation Patterns

### Existing Implementations in Codebase

| Component | File | Pattern | Purpose |
|-----------|------|---------|---------|
| **UserMapping Entity** | `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt` | `@PrePersist onCreate(): domain?.lowercase()` | Storage normalization |
| **UserMappingImportService** | `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt` | Validation + lowercase in parseRowToUserMapping() | Import normalization |
| **CSVUserMappingParser** | `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/CSVUserMappingParser.kt` | Same pattern; includes -NONE- sentinel | CSV import normalization |
| **AssetFilterService** | `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt` | `asset.adDomain?.lowercase() in userDomainsLowercase` | Query-time comparison |
| **VulnerabilityService** | `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt` | `contains(adDomain, ignoreCase=true)` | Query-time comparison |

### Kotlin Code Patterns to Use

**Pattern 1: Normalization at entity level (storage)**
```kotlin
@PrePersist
fun onCreate() {
    domain = domain?.lowercase()?.trim()
}
```

**Pattern 2: Import validation + normalization**
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

**Pattern 3: Query-time case-insensitive comparison (simple)**
```kotlin
val matches = asset.adDomain?.lowercase() == userDomain.lowercase()
```

**Pattern 4: Query-time case-insensitive comparison (set membership)**
```kotlin
val userDomainsSet = userDomains.map { it.lowercase() }.toSet()
val isAccessible = asset.adDomain?.lowercase() in userDomainsSet
```

**Pattern 5: Query-time case-insensitive contains**
```kotlin
val matches = asset.adDomain?.contains(filterDomain, ignoreCase=true) == true
```

---

## 10. Summary Table

| Aspect | Decision | Justification |
|--------|----------|---------------|
| **Storage Format** | Lowercase | RFC 1035 compliant, consistent with email normalization, enables reliable deduplication |
| **Normalization Point** | At storage (entity @PrePersist) | Ensures consistent database state; deduplication works correctly |
| **Comparison Method** | Case-insensitive at query time | Works with both normalized and unnormalized domains; matches existing patterns |
| **Null Handling** | Allow null in Asset.adDomain and UserMapping.domain | Represents unmapped assets/users; excluded from domain-based filtering |
| **Trailing Dot** | Reject (.endswith(".")) | Not valid in AD context; RFC optional only for absolute names |
| **Leading Dot** | Reject (.startswith(".")) | Invalid label format per RFC 1035 |
| **Internal Spaces** | Reject (.contains(" ")) | Invalid per RFC 1035 |
| **Leading/Trailing Hyphens** | Reject | Invalid per RFC 1035 |
| **Mixed Case** | Normalize to lowercase | Case-insensitive per RFC 1035; storage normalization prevents duplicates |
| **Hyphens in Domain** | Allow (example.com-test) | Valid per RFC 1035 for labels |
| **Numeric Domains** | Allow (123.456.789) | Valid per RFC 1035 (though unusual for AD) |
| **IDN/Punycode** | Out of scope | Can be added in future feature if needed |
| **Unique Constraint** | On (email, awsAccountId, domain) | Prevents duplicates across all three fields; works with lowercase normalization |
| **Import Deduplication** | Check before save; skip if exists | Implemented via repository.existsByEmailAndAwsAccountIdAndDomain() |
| **Database Indexing** | Index on (email, domain) for lookups | Single index sufficient since storage is normalized |

---

## Conclusion

The recommended domain name normalization strategy for Feature 043 is:

1. **Store domains in lowercase** (via @PrePersist normalization)
2. **Validate at import time** (reject trailing dots, leading dots, spaces, etc.)
3. **Compare case-insensitively at query time** (either via `.lowercase()` or `.ignoreCase=true`)
4. **Allow null domains** for assets without AD domain information
5. **Use existing UserMapping patterns** as template (Feature 042 already implements this correctly)

This approach is secure, performant, RFC-compliant, and maintains consistency with current codebase practices.
