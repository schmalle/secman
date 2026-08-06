# Data Model: IP Address Mapping

**Feature**: 020-i-want-to
**Date**: 2025-10-15
**Status**: Final

## Overview

This feature extends the existing `UserMapping` entity (Feature 013) to support IP address and IP range mappings in addition to AWS account mappings. The design maintains backward compatibility while enabling efficient IP-based access control queries.

## Entity Extensions

### UserMapping (EXTENDED)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`

**New Fields**:

```kotlin
@Entity
@Table(
    name = "user_mapping",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_mapping_composite",
            columnNames = ["email", "aws_account_id", "domain", "ip_address"]  // ← UPDATED
        )
    ],
    indexes = [
        Index(name = "idx_user_mapping_email", columnList = "email"),  // Existing
        Index(name = "idx_user_mapping_aws_account", columnList = "aws_account_id"),  // Existing
        Index(name = "idx_user_mapping_domain", columnList = "domain"),  // Existing
        Index(name = "idx_user_mapping_email_aws", columnList = "email,aws_account_id"),  // Existing
        // NEW INDEXES FOR IP SUPPORT
        Index(name = "idx_user_mapping_ip_address", columnList = "ip_address"),
        Index(name = "idx_user_mapping_ip_range", columnList = "ip_range_start,ip_range_end"),
        Index(name = "idx_user_mapping_email_ip", columnList = "email,ip_range_start")
    ]
)
@Serdeable
data class UserMapping(
    // ... existing fields (id, email, awsAccountId, domain, createdAt, updatedAt) ...

    /**
     * Original IP address string as entered by user
     * - Single IP: "192.168.1.100"
     * - CIDR range: "192.168.1.0/24"
     * - Dash range: "10.0.0.1-10.0.0.255"
     * Nullable - either awsAccountId OR ipAddress must be non-null
     */
    @Column(name = "ip_address", nullable = true, length = 50)
    @Pattern(
        regexp = "^(\\d{1,3}\\.){3}\\d{1,3}(\\/\\d{1,2}|-(\\d{1,3}\\.){3}\\d{1,3})?$",
        message = "IP address must be valid IPv4, CIDR, or dash range"
    )
    var ipAddress: String? = null,

    /**
     * Type of IP mapping (discriminator for querying)
     * - SINGLE: Single IP address (192.168.1.100)
     * - CIDR: CIDR notation range (192.168.1.0/24)
     * - DASH_RANGE: Dash-separated range (10.0.0.1-10.0.0.255)
     * Computed from ipAddress on insert/update
     */
    @Column(name = "ip_range_type", nullable = true, length = 20)
    @Enumerated(EnumType.STRING)
    var ipRangeType: IpRangeType? = null,

    /**
     * Numeric representation of start IP in range (or single IP)
     * Stored as BIGINT for efficient range queries via BETWEEN clause
     * Formula: (octet1 << 24) | (octet2 << 16) | (octet3 << 8) | octet4
     * Example: 192.168.1.0 → 3232235776
     */
    @Column(name = "ip_range_start", nullable = true)
    var ipRangeStart: Long? = null,

    /**
     * Numeric representation of end IP in range (or single IP for SINGLE type)
     * For single IPs: ipRangeStart == ipRangeEnd
     * For CIDR: broadcast address of subnet (e.g., /24 → .255)
     * For dash range: ending IP of user-specified range
     * Example: 192.168.1.255 → 3232236031
     */
    @Column(name = "ip_range_end", nullable = true)
    var ipRangeEnd: Long? = null
) {
    // ... existing methods (onCreate, onUpdate, toString, equals, hashCode) ...

    /**
     * Validates that at least one mapping type is present
     * Called by @PrePersist and @PreUpdate hooks
     */
    private fun validateMappingType() {
        require(awsAccountId != null || ipAddress != null) {
            "UserMapping must have either awsAccountId or ipAddress (or both)"
        }
    }

    /**
     * Computes IP range fields from ipAddress string
     * Called by @PrePersist and @PreUpdate hooks
     */
    private fun computeIpRangeFields() {
        if (ipAddress != null) {
            val ipInfo = IpAddressParser.parse(ipAddress!!)
            this.ipRangeType = ipInfo.type
            this.ipRangeStart = ipInfo.startIpNumeric
            this.ipRangeEnd = ipInfo.endIpNumeric
        }
    }

    @PrePersist
    override fun onCreate() {
        // ... existing normalization logic ...
        validateMappingType()
        computeIpRangeFields()
    }

    @PreUpdate
    override fun onUpdate() {
        // ... existing update logic ...
        validateMappingType()
        computeIpRangeFields()
    }
}
```

**Field Constraints**:
- `ipAddress`: Optional, max 50 chars (enough for "255.255.255.255-255.255.255.255")
- `ipRangeType`: Optional enum (SINGLE, CIDR, DASH_RANGE)
- `ipRangeStart`, `ipRangeEnd`: Optional BIGINT (range: 0 to 4,294,967,295)
- Unique constraint updated: (email, awsAccountId, domain, ipAddress) prevents duplicates
- Check constraint (database level): `CHECK (aws_account_id IS NOT NULL OR ip_address IS NOT NULL)`

**Migration Impact**:
- Existing rows: New columns default to NULL (backward compatible)
- New rows: Must have either awsAccountId OR ipAddress (enforced by validation)
- No data loss: Existing AWS account mappings unchanged

---

### IpRangeType (NEW ENUM)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/IpRangeType.kt`

```kotlin
package com.secman.domain

/**
 * Discriminator for IP address mapping type
 * Used to optimize queries and UI display
 */
enum class IpRangeType {
    /**
     * Single IP address (e.g., 192.168.1.100)
     * ipRangeStart == ipRangeEnd
     */
    SINGLE,

    /**
     * CIDR notation range (e.g., 192.168.1.0/24)
     * ipRangeStart = network address (192.168.1.0)
     * ipRangeEnd = broadcast address (192.168.1.255)
     */
    CIDR,

    /**
     * Dash-separated range (e.g., 10.0.0.1-10.0.0.255)
     * ipRangeStart = start IP (10.0.0.1)
     * ipRangeEnd = end IP (10.0.0.255)
     */
    DASH_RANGE
}
```

---

### Asset (EXTENDED)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**New Field**:

```kotlin
@Entity
@Table(
    name = "assets",
    indexes = [
        // ... existing indexes ...
        Index(name = "idx_assets_ip_numeric", columnList = "ip_numeric")  // NEW
    ]
)
data class Asset(
    // ... existing fields (id, name, type, ip, owner, ...) ...

    /**
     * Numeric representation of IP address for efficient range queries
     * Computed from 'ip' field on insert/update via @PrePersist hook
     * NULL if ip field is null or empty
     * Formula: (octet1 << 24) | (octet2 << 16) | (octet3 << 8) | octet4
     * Example: 192.168.1.100 → 3232235876
     */
    @Column(name = "ip_numeric", nullable = true)
    var ipNumeric: Long? = null
) {
    // ... existing methods ...

    /**
     * Computes ipNumeric from ip string
     * Called by @PrePersist and @PreUpdate hooks
     */
    private fun computeIpNumeric() {
        if (!ip.isNullOrBlank()) {
            this.ipNumeric = IpAddressParser.ipToLong(ip!!)
        } else {
            this.ipNumeric = null
        }
    }

    @PrePersist
    fun onCreate() {
        // ... existing logic ...
        computeIpNumeric()
    }

    @PreUpdate
    fun onUpdate() {
        // ... existing logic ...
        computeIpNumeric()
    }
}
```

**Field Constraints**:
- `ipNumeric`: Optional BIGINT (computed field, never set by user)
- Index: `idx_assets_ip_numeric` for BETWEEN queries

**Migration Impact**:
- Existing rows: Compute ipNumeric from existing ip field via migration script
- New rows: Auto-computed on insert
- No manual maintenance required

---

## DTOs

### UserMappingDto (EXTENDED)

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/UserMappingDto.kt`

```kotlin
package com.secman.dto

import com.secman.domain.IpRangeType
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

@Serdeable
data class UserMappingDto(
    val id: Long?,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    // NEW FIELDS
    val ipAddress: String?,
    val ipRangeType: IpRangeType?,
    val ipCount: Long?,  // Human-readable IP count for UI display (e.g., "256 IPs")
    // EXISTING FIELDS
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    companion object {
        /**
         * Converts UserMapping entity to DTO
         * Computes ipCount for human-readable display
         */
        fun fromEntity(entity: UserMapping): UserMappingDto {
            val ipCount = if (entity.ipRangeStart != null && entity.ipRangeEnd != null) {
                entity.ipRangeEnd!! - entity.ipRangeStart!! + 1
            } else null

            return UserMappingDto(
                id = entity.id,
                email = entity.email,
                awsAccountId = entity.awsAccountId,
                domain = entity.domain,
                ipAddress = entity.ipAddress,
                ipRangeType = entity.ipRangeType,
                ipCount = ipCount,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
    }
}
```

---

### IpMappingUploadResult (NEW DTO)

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/IpMappingUploadResult.kt`

```kotlin
package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Result of IP mapping CSV/Excel upload
 * Reuses ImportResult structure from Feature 016 for consistency
 */
@Serdeable
data class IpMappingUploadResult(
    val message: String,
    val imported: Int,
    val skipped: Int,
    val errors: List<IpMappingUploadError>
)

@Serdeable
data class IpMappingUploadError(
    val row: Int,
    val reason: String,
    val ipAddress: String?,  // Optional: include problematic IP for debugging
    val email: String?       // Optional: include email for debugging
)
```

---

## Helper Classes

### IpAddressParser (NEW UTILITY)

**Location**: `src/backendng/src/main/kotlin/com/secman/util/IpAddressParser.kt`

```kotlin
package com.secman.util

import com.secman.domain.IpRangeType
import org.apache.commons.net.util.SubnetUtils

/**
 * Utility for parsing IP addresses and ranges
 * Supports: single IPs, CIDR notation, dash ranges
 */
object IpAddressParser {

    data class IpRangeInfo(
        val type: IpRangeType,
        val startIpNumeric: Long,
        val endIpNumeric: Long,
        val originalString: String
    )

    /**
     * Parses IP address string into IpRangeInfo
     * @throws IllegalArgumentException if format invalid
     */
    fun parse(ipString: String): IpRangeInfo {
        return when {
            ipString.contains("/") -> parseCidr(ipString)
            ipString.contains("-") -> parseDashRange(ipString)
            else -> parseSingleIp(ipString)
        }
    }

    /**
     * Converts IPv4 dotted-decimal to Long
     * Example: "192.168.1.100" → 3232235876
     */
    fun ipToLong(ip: String): Long {
        val octets = ip.split(".").map { it.toInt() }
        require(octets.size == 4) { "Invalid IPv4 format: $ip" }
        require(octets.all { it in 0..255 }) { "Invalid octet value in: $ip" }

        return ((octets[0].toLong() shl 24) or
                (octets[1].toLong() shl 16) or
                (octets[2].toLong() shl 8) or
                 octets[3].toLong())
    }

    /**
     * Converts Long back to IPv4 dotted-decimal
     * Example: 3232235876 → "192.168.1.100"
     */
    fun longToIp(ipNumeric: Long): String {
        return "${(ipNumeric shr 24) and 0xFF}." +
               "${(ipNumeric shr 16) and 0xFF}." +
               "${(ipNumeric shr 8) and 0xFF}." +
               "${ipNumeric and 0xFF}"
    }

    private fun parseSingleIp(ip: String): IpRangeInfo {
        val ipNumeric = ipToLong(ip)
        return IpRangeInfo(
            type = IpRangeType.SINGLE,
            startIpNumeric = ipNumeric,
            endIpNumeric = ipNumeric,
            originalString = ip
        )
    }

    private fun parseCidr(cidr: String): IpRangeInfo {
        val (ip, prefixStr) = cidr.split("/")
        val prefix = prefixStr.toIntOrNull()
        require(prefix != null && prefix in 0..32) {
            "Invalid CIDR prefix: $prefixStr (must be 0-32)"
        }

        // Use Apache Commons Net for CIDR parsing
        val subnet = SubnetUtils(cidr)
        subnet.isInclusiveHostCount = true  // Include network and broadcast addresses

        val info = subnet.info
        return IpRangeInfo(
            type = IpRangeType.CIDR,
            startIpNumeric = ipToLong(info.networkAddress),
            endIpNumeric = ipToLong(info.broadcastAddress),
            originalString = cidr
        )
    }

    private fun parseDashRange(range: String): IpRangeInfo {
        val (startIp, endIp) = range.split("-").map { it.trim() }
        val startNumeric = ipToLong(startIp)
        val endNumeric = ipToLong(endIp)

        require(startNumeric <= endNumeric) {
            "Invalid range: start IP ($startIp) must be <= end IP ($endIp)"
        }

        // Warn if range is very large (>65k IPs = /16 or larger)
        val ipCount = endNumeric - startNumeric + 1
        if (ipCount > 65536) {
            // Log warning (implementation will add logger)
            println("WARNING: Large IP range detected: $ipCount IPs in $range")
        }

        return IpRangeInfo(
            type = IpRangeType.DASH_RANGE,
            startIpNumeric = startNumeric,
            endIpNumeric = endNumeric,
            originalString = range
        )
    }
}
```

---

## Validation Rules

### IP Address Validation

**Rules**:
1. **Format validation**: Regex pattern matches single IP, CIDR, or dash range
2. **Octet validation**: Each octet must be 0-255 (checked by IpAddressParser)
3. **CIDR prefix validation**: Must be 0-32 for IPv4
4. **Range validation**: Start IP must be <= end IP in dash ranges
5. **Special case rejection**: 0.0.0.0/0 (all IPs) rejected with error

**Error Messages**:
- "Invalid IP format: must be IPv4 address (e.g., 192.168.1.100)"
- "Invalid CIDR notation: prefix must be 0-32 (e.g., 192.168.1.0/24)"
- "Invalid dash range: start IP must be less than or equal to end IP"
- "Cannot map all IP addresses (0.0.0.0/0). Please specify a more specific range."

### Duplicate Detection

**Uniqueness Check**: (email, awsAccountId, domain, ipAddress) composite

**Examples**:
- ✅ Allowed: user@example.com → 192.168.1.0/24 (first time)
- ❌ Duplicate: user@example.com → 192.168.1.0/24 (same IP, same user)
- ✅ Allowed: user@example.com → 192.168.1.0/25 (different range, same user)
- ✅ Allowed: other@example.com → 192.168.1.0/24 (same IP, different user)

---

## Query Patterns

### Find Assets by User's IP Mappings

**Use Case**: AccountVulnsService needs to fetch assets visible to user based on IP mappings

**SQL Query**:
```sql
SELECT DISTINCT a.*
FROM assets a
JOIN user_mapping um ON (
    -- Exact IP match (SINGLE type)
    (a.ip_numeric = um.ip_range_start AND um.ip_range_type = 'SINGLE')
    OR
    -- Range match (CIDR or DASH_RANGE types)
    (a.ip_numeric >= um.ip_range_start AND a.ip_numeric <= um.ip_range_end
     AND um.ip_range_type IN ('CIDR', 'DASH_RANGE'))
)
WHERE um.email = :userEmail
  AND a.ip_numeric IS NOT NULL
ORDER BY a.name;
```

**Kotlin Repository Method**:
```kotlin
@Repository
interface AssetRepository : JpaRepository<Asset, Long> {

    @Query("""
        SELECT DISTINCT a FROM Asset a
        JOIN UserMapping um ON (
            (a.ipNumeric = um.ipRangeStart AND um.ipRangeType = 'SINGLE')
            OR
            (a.ipNumeric >= um.ipRangeStart AND a.ipNumeric <= um.ipRangeEnd
             AND um.ipRangeType IN ('CIDR', 'DASH_RANGE'))
        )
        WHERE um.email = :email
        AND a.ipNumeric IS NOT NULL
    """)
    fun findByUserIpMappings(@Param("email") email: String): List<Asset>
}
```

**Performance**:
- Index usage: `idx_user_mapping_email_ip` + `idx_assets_ip_numeric`
- Expected time: <100ms for 10k mappings, 100k assets
- Query plan: Index seek → nested loop join

---

## Database Migration

### Schema Changes

**New Columns in `user_mapping`**:
```sql
ALTER TABLE user_mapping
ADD COLUMN ip_address VARCHAR(50) NULL,
ADD COLUMN ip_range_type VARCHAR(20) NULL,
ADD COLUMN ip_range_start BIGINT NULL,
ADD COLUMN ip_range_end BIGINT NULL;

-- Update unique constraint to include ip_address
ALTER TABLE user_mapping
DROP CONSTRAINT uk_user_mapping_composite,
ADD CONSTRAINT uk_user_mapping_composite
    UNIQUE (email, aws_account_id, domain, ip_address);

-- Add check constraint to enforce at least one mapping type
ALTER TABLE user_mapping
ADD CONSTRAINT chk_user_mapping_type
    CHECK (aws_account_id IS NOT NULL OR ip_address IS NOT NULL);

-- Add indexes for IP queries
CREATE INDEX idx_user_mapping_ip_address ON user_mapping(ip_address);
CREATE INDEX idx_user_mapping_ip_range ON user_mapping(ip_range_start, ip_range_end);
CREATE INDEX idx_user_mapping_email_ip ON user_mapping(email, ip_range_start);
```

**New Column in `assets`**:
```sql
ALTER TABLE assets
ADD COLUMN ip_numeric BIGINT NULL;

-- Backfill existing assets (one-time migration)
UPDATE assets
SET ip_numeric = (
    CAST(SUBSTRING_INDEX(ip, '.', 1) AS UNSIGNED) << 24 |
    CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(ip, '.', 2), '.', -1) AS UNSIGNED) << 16 |
    CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(ip, '.', 3), '.', -1) AS UNSIGNED) << 8 |
    CAST(SUBSTRING_INDEX(ip, '.', -1) AS UNSIGNED)
)
WHERE ip IS NOT NULL AND ip != '';

-- Add index for range queries
CREATE INDEX idx_assets_ip_numeric ON assets(ip_numeric);
```

**Rollback Plan**:
```sql
-- Drop new columns if needed
ALTER TABLE user_mapping
DROP COLUMN ip_address,
DROP COLUMN ip_range_type,
DROP COLUMN ip_range_start,
DROP COLUMN ip_range_end;

-- Drop indexes
DROP INDEX idx_user_mapping_ip_address ON user_mapping;
DROP INDEX idx_user_mapping_ip_range ON user_mapping;
DROP INDEX idx_user_mapping_email_ip ON user_mapping;

-- Restore original unique constraint
ALTER TABLE user_mapping
DROP CONSTRAINT uk_user_mapping_composite,
ADD CONSTRAINT uk_user_mapping_composite
    UNIQUE (email, aws_account_id, domain);

-- Drop asset column
ALTER TABLE assets DROP COLUMN ip_numeric;
DROP INDEX idx_assets_ip_numeric ON assets;
```

---

## Data Examples

### Single IP Mapping

```json
{
  "id": 1,
  "email": "user@example.com",
  "awsAccountId": null,
  "domain": "example.com",
  "ipAddress": "192.168.1.100",
  "ipRangeType": "SINGLE",
  "ipRangeStart": 3232235876,
  "ipRangeEnd": 3232235876,
  "ipCount": 1,
  "createdAt": "2025-10-15T10:00:00Z",
  "updatedAt": "2025-10-15T10:00:00Z"
}
```

### CIDR Range Mapping

```json
{
  "id": 2,
  "email": "admin@example.com",
  "awsAccountId": null,
  "domain": "-NONE-",
  "ipAddress": "10.0.0.0/24",
  "ipRangeType": "CIDR",
  "ipRangeStart": 167772160,   // 10.0.0.0
  "ipRangeEnd": 167772415,     // 10.0.0.255
  "ipCount": 256,
  "createdAt": "2025-10-15T11:00:00Z",
  "updatedAt": "2025-10-15T11:00:00Z"
}
```

### Dash Range Mapping

```json
{
  "id": 3,
  "email": "team@example.com",
  "awsAccountId": null,
  "domain": "team.example.com",
  "ipAddress": "172.16.0.1-172.16.0.100",
  "ipRangeType": "DASH_RANGE",
  "ipRangeStart": 2886729729,  // 172.16.0.1
  "ipRangeEnd": 2886729828,    // 172.16.0.100
  "ipCount": 100,
  "createdAt": "2025-10-15T12:00:00Z",
  "updatedAt": "2025-10-15T12:00:00Z"
}
```

### Combined AWS + IP Mapping

```json
{
  "id": 4,
  "email": "hybrid@example.com",
  "awsAccountId": "123456789012",
  "domain": "aws.example.com",
  "ipAddress": "192.168.1.0/24",
  "ipRangeType": "CIDR",
  "ipRangeStart": 3232235776,
  "ipRangeEnd": 3232236031,
  "ipCount": 256,
  "createdAt": "2025-10-15T13:00:00Z",
  "updatedAt": "2025-10-15T13:00:00Z"
}
```

---

## Summary

**Entities Modified**: 2 (UserMapping, Asset)
**Entities Created**: 1 enum (IpRangeType)
**DTOs Created**: 2 (UserMappingDto extended, IpMappingUploadResult new)
**Utility Classes**: 1 (IpAddressParser)
**Database Columns Added**: 5 (4 in user_mapping, 1 in assets)
**Indexes Added**: 4 (3 in user_mapping, 1 in assets)

**Backward Compatibility**: ✅ Maintained
- Existing UserMapping rows unchanged (new columns NULL)
- Existing API endpoints unchanged (new fields optional in DTOs)
- Existing queries unaffected (indexes added, not replaced)
