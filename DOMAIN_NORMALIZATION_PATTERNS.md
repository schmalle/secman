# Domain Normalization - Reusable Code Patterns for Feature 043

## Quick Code Snippets

### 1. Storage-Level Normalization (Entity Level)

Apply to any domain field that should be normalized:

```kotlin
@Entity
@Table(name = "your_table")
data class YourEntity(
    // ... other fields ...

    @Column(name = "domain", nullable = true, length = 255)
    var domain: String? = null

    // ... more fields ...
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
        // Normalize domain: trim whitespace, then lowercase
        domain = domain?.trim()?.lowercase()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
        // Optionally re-normalize on update
        domain = domain?.trim()?.lowercase()
    }
}
```

---

### 2. Domain Validation Function (Reusable)

```kotlin
/**
 * Validate domain name format
 *
 * Allowed: lowercase alphanumeric, dots, hyphens
 * Rejected: trailing/leading dots, spaces, leading/trailing hyphens
 *
 * @param domain Domain to validate (case doesn't matter for validation)
 * @return true if valid, false otherwise
 */
fun validateDomain(domain: String?): Boolean {
    if (domain.isNullOrBlank()) {
        return true  // Null domains allowed (nullable field)
    }

    val normalized = domain.lowercase().trim()

    // Regex: only lowercase alphanumeric, dots, hyphens
    return normalized.matches(Regex("^[a-z0-9.-]+$")) &&
           // Reject leading/trailing dots
           !normalized.startsWith(".") &&
           !normalized.endsWith(".") &&
           // Reject leading/trailing hyphens
           !normalized.startsWith("-") &&
           !normalized.endsWith("-") &&
           // Reject internal spaces
           !normalized.contains(" ") &&
           // Reject consecutive dots
           !normalized.contains("..")
}
```

---

### 3. Domain Normalization in Import Services

For Excel import (similar to UserMappingImportService):

```kotlin
@Singleton
open class YourImportService(
    private val repository: YourRepository
) {

    companion object {
        private const val DOMAIN_PATTERN = "^[a-z0-9.-]+$"
    }

    private fun parseRowToEntity(row: Row, headerMap: Map<String, Int>, rowNumber: Int): YourEntity? {
        val domainRaw = getCellValue(row, headerMap, "Domain")?.trim()

        // Validate at import time
        if (!domainRaw.isNullOrBlank() && !validateDomain(domainRaw)) {
            throw IllegalArgumentException("Invalid domain format: $domainRaw")
        }

        // Return entity with raw domain - @PrePersist will normalize
        return YourEntity(
            // ... other fields ...
            domain = if (domainRaw.isNullOrBlank()) null else domainRaw
        )
    }

    private fun validateDomain(domain: String): Boolean {
        val normalized = domain.lowercase()
        return normalized.matches(Regex(DOMAIN_PATTERN)) &&
               !normalized.startsWith(".") &&
               !normalized.endsWith(".") &&
               !normalized.startsWith("-") &&
               !normalized.endsWith("-") &&
               !normalized.contains(" ")
    }
}
```

For CSV import (similar to CSVUserMappingParser):

```kotlin
@Singleton
open class YourCsvParser(
    private val repository: YourRepository
) {

    private fun parseRecord(
        record: CSVRecord,
        headerMap: Map<String, Int>,
        lineNumber: Int
    ): YourEntity? {
        val domainRaw = getColumnValue(record, headerMap, "domain")

        // Validate
        if (!domainRaw.isNullOrBlank() && !validateDomain(domainRaw)) {
            throw IllegalArgumentException("Invalid domain format: $domainRaw")
        }

        // Normalize for deduplication check
        val normalizedDomain = domainRaw?.lowercase()?.trim()

        // Check for duplicates (already normalized)
        if (normalizedDomain != null &&
            repository.existsByDomain(normalizedDomain)) {
            throw IllegalArgumentException("Domain already exists: $normalizedDomain")
        }

        // Create entity - @PrePersist will normalize again (idempotent)
        return YourEntity(
            // ... other fields ...
            domain = normalizedDomain
        )
    }
}
```

---

### 4. Query-Time Case-Insensitive Comparison Patterns

**Pattern A: Simple Equality**
```kotlin
// Used when checking if a user has access to a domain
fun hasAccessToDomain(userDomain: String?, assetDomain: String?): Boolean {
    return userDomain?.lowercase() == assetDomain?.lowercase()
}

// Usage in access control
if (hasAccessToDomain(mapping.domain, asset.adDomain)) {
    // User can access this asset
}
```

**Pattern B: Set Membership (Recommended for Multiple Comparisons)**
```kotlin
// More efficient for checking against multiple domains
fun getAccessibleAssets(userDomains: List<String?>, assets: List<Asset>): List<Asset> {
    val normalizedUserDomains = userDomains
        .mapNotNull { it?.lowercase()?.trim() }
        .toSet()

    return assets.filter { asset ->
        asset.adDomain?.lowercase() in normalizedUserDomains
    }
}

// Usage in AssetFilterService
val userDomains = userMappingRepository.findDistinctDomainByEmail(userEmail)
val userDomainsSet = userDomains.map { it.lowercase() }.toSet()
val accessibleAssets = allAssets.filter { asset ->
    asset.adDomain?.lowercase() in userDomainsSet
}
```

**Pattern C: Substring Match (for filtering)**
```kotlin
// Used for search/filter endpoints
fun filterAssetsByDomain(assets: List<Asset>, searchDomain: String?): List<Asset> {
    if (searchDomain.isNullOrBlank()) {
        return assets  // No filter
    }

    return assets.filter { asset ->
        asset.adDomain?.contains(searchDomain, ignoreCase = true) == true
    }
}

// Usage in VulnerabilityService or asset list endpoints
if (!adDomainFilter.isNullOrBlank()) {
    assets = assets.filter { asset ->
        asset.adDomain?.contains(adDomainFilter, ignoreCase = true) == true
    }
}
```

**Pattern D: Exact Match with Null Safety**
```kotlin
// For queries where both sides might be null
fun domainsMatch(domain1: String?, domain2: String?): Boolean {
    return when {
        domain1 == null && domain2 == null -> true
        domain1 == null || domain2 == null -> false
        else -> domain1.lowercase() == domain2.lowercase()
    }
}
```

---

### 5. Service-Level Deduplication Function

```kotlin
/**
 * Check if domain mapping already exists (case-insensitive)
 * Used during import to skip duplicates
 */
@Transactional
open fun isDomainDuplicate(email: String, domain: String?): Boolean {
    if (domain.isNullOrBlank()) {
        return false  // Null domains don't count as duplicates
    }

    val normalizedDomain = domain.lowercase().trim()
    val normalizedEmail = email.lowercase().trim()

    return userMappingRepository.existsByEmailAndDomain(
        normalizedEmail,
        normalizedDomain
    )
}
```

---

### 6. Test Cases (JUnit 5 Examples)

```kotlin
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class DomainNormalizationTest {

    private val domainValidator = YourDomainValidator()

    @Test
    @DisplayName("Should normalize domain to lowercase")
    fun testDomainNormalization() {
        val domain = "CONTOSO.COM"
        val normalized = domain.lowercase()
        assertEquals("contoso.com", normalized)
    }

    @Test
    @DisplayName("Should accept valid domain format")
    fun testValidDomain() {
        assertEquals(true, domainValidator.validateDomain("contoso.com"))
        assertEquals(true, domainValidator.validateDomain("example-corp.org"))
        assertEquals(true, domainValidator.validateDomain("sales.division.company"))
    }

    @Test
    @DisplayName("Should reject trailing dot")
    fun testRejectTrailingDot() {
        assertEquals(false, domainValidator.validateDomain("contoso.com."))
    }

    @Test
    @DisplayName("Should reject leading dot")
    fun testRejectLeadingDot() {
        assertEquals(false, domainValidator.validateDomain(".contoso.com"))
    }

    @Test
    @DisplayName("Should reject internal spaces")
    fun testRejectInternalSpaces() {
        assertEquals(false, domainValidator.validateDomain("contoso corp.com"))
    }

    @Test
    @DisplayName("Should reject leading hyphen")
    fun testRejectLeadingHyphen() {
        assertEquals(false, domainValidator.validateDomain("-contoso.com"))
    }

    @Test
    @DisplayName("Should reject trailing hyphen")
    fun testRejectTrailingHyphen() {
        assertEquals(false, domainValidator.validateDomain("contoso.com-"))
    }

    @Test
    @DisplayName("Should allow null domain")
    fun testAllowNullDomain() {
        assertEquals(true, domainValidator.validateDomain(null))
    }

    @Test
    @DisplayName("Should reject consecutive dots")
    fun testRejectConsecutiveDots() {
        assertEquals(false, domainValidator.validateDomain("contoso..com"))
    }

    @Test
    @DisplayName("Case-insensitive comparison should work")
    fun testCaseInsensitiveComparison() {
        val domain1 = "CONTOSO"
        val domain2 = "contoso"
        assertEquals(domain1.lowercase(), domain2.lowercase())
    }

    @Test
    @DisplayName("Should handle trimming and lowercasing")
    fun testTrimAndLowercase() {
        val domain = "  CONTOSO.COM  "
        val normalized = domain.trim().lowercase()
        assertEquals("contoso.com", normalized)
    }
}
```

---

### 7. Repository Query Examples (Micronaut Data)

```kotlin
@Repository
interface DomainMappingRepository : CrudRepository<YourEntity, Long> {

    /**
     * Find mapping by email and domain (normalized comparison)
     * Note: Both email and domain stored in lowercase
     */
    fun findByEmailAndDomain(
        email: String,
        domain: String?
    ): Optional<YourEntity>

    /**
     * Check if domain mapping exists (case-insensitive)
     * Caller responsible for normalizing: email.lowercase(), domain?.lowercase()
     */
    fun existsByEmailAndDomain(
        email: String,
        domain: String?
    ): Boolean

    /**
     * Get all distinct domains for an email
     * Domains already stored in lowercase
     */
    @Query("SELECT DISTINCT ym.domain FROM YourEntity ym WHERE ym.email = :email AND ym.domain IS NOT NULL")
    fun findDistinctDomainByEmail(email: String): List<String>

    /**
     * Find by partial domain match
     * Can be used for search/filter UI
     */
    fun findByDomainContainingIgnoreCase(domainPart: String): List<YourEntity>
}
```

---

### 8. Complete Service Example

```kotlin
@Singleton
open class DomainAccessService(
    private val assetRepository: AssetRepository,
    private val userMappingRepository: UserMappingRepository
) {

    /**
     * Check if user has access to asset based on domain mapping
     */
    fun canAccessAssetByDomain(userEmail: String, assetId: Long): Boolean {
        val asset = assetRepository.findById(assetId).orElse(null)
            ?: return false

        if (asset.adDomain == null) {
            return false  // Asset has no domain, no domain-based access
        }

        val userDomains = userMappingRepository.findDistinctDomainByEmail(userEmail)
        if (userDomains.isEmpty()) {
            return false  // User has no domain mappings
        }

        // Both sides are already lowercase from storage
        return asset.adDomain in userDomains
    }

    /**
     * Get all assets accessible to user via domain mapping
     */
    fun getAssetsByUserDomain(userEmail: String): List<Asset> {
        val userDomains = userMappingRepository.findDistinctDomainByEmail(userEmail)
        if (userDomains.isEmpty()) {
            return emptyList()
        }

        // Domains already lowercase
        val userDomainsSet = userDomains.toSet()
        return assetRepository.findAll().filter { asset ->
            asset.adDomain in userDomainsSet
        }
    }

    /**
     * Validate and normalize domain before storage
     */
    fun normalizeDomain(domain: String?): String? {
        if (domain.isNullOrBlank()) {
            return null
        }

        if (!validateDomain(domain)) {
            throw IllegalArgumentException("Invalid domain format: $domain")
        }

        return domain.trim().lowercase()
    }

    private fun validateDomain(domain: String): Boolean {
        val normalized = domain.lowercase().trim()
        return normalized.matches(Regex("^[a-z0-9.-]+$")) &&
               !normalized.startsWith(".") &&
               !normalized.endsWith(".") &&
               !normalized.startsWith("-") &&
               !normalized.endsWith("-") &&
               !normalized.contains(" ")
    }
}
```

---

## Summary

**Use These Patterns For**:

1. **Storage**: `@PrePersist` with `.trim().lowercase()`
2. **Validation**: Regex check + rejection rules
3. **Queries**: `.lowercase() in set` or `.contains(text, ignoreCase=true)`
4. **Import**: Validate, then normalize before saving
5. **Comparison**: Always case-insensitive (one side or both `.lowercase()`)

**Key Principles**:
- Normalize once at storage
- Compare anywhere with `.lowercase()` or `.ignoreCase`
- Allow null domains (use `?` operator)
- Reject invalid formats early (validation)
- Use sets for multiple comparisons (performance)
- Test edge cases (trailing dots, mixed case, spaces)

---

**All patterns follow RFC 1035 standards and match Feature 042 implementation precedent.**
