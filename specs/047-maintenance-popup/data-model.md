# Data Model: Maintenance Popup Banner

**Feature**: 047-maintenance-popup
**Date**: 2025-11-15
**Purpose**: Define database schema and entity relationships

## Entity: MaintenanceBanner

### Description

Represents a scheduled maintenance notification that displays on the start/login page during a configured time window. Each banner has a message, time range, and creation metadata.

### Fields

| Field Name | Type | Constraints | Description |
|-----------|------|-------------|-------------|
| id | Long | PRIMARY KEY, AUTO_INCREMENT | Unique identifier for the banner |
| message | String | NOT NULL, LENGTH(1-2000) | Maintenance message text (plain text, sanitized) |
| startTime | Instant | NOT NULL, INDEX | UTC timestamp when banner becomes active |
| endTime | Instant | NOT NULL, INDEX | UTC timestamp when banner deactivates |
| createdAt | Instant | NOT NULL | UTC timestamp when banner was created |
| createdBy | User | FOREIGN KEY, NOT NULL | Admin user who created the banner |

### Constraints

1. **Time Range Validation**: `endTime` MUST be after `startTime`
   - Enforced in service layer before save
   - Database CHECK constraint: `end_time > start_time`

2. **Message Length**: 1-2000 characters
   - Prevents database overflow
   - Enforced via JPA `@Column(length = 2000)`

3. **Non-Null Fields**: All fields required
   - No optional fields in MVP

4. **Cascade Delete**: When User deleted, set `createdBy` to NULL or soft-delete banner
   - Decision: Preserve banner history, set `createdBy` to NULL
   - Alternative: Prevent user deletion if they have banners (rejected for UX)

### Indexes

```sql
-- Composite index for time-range queries
CREATE INDEX idx_maintenance_banner_time_range ON maintenance_banner(start_time, end_time);

-- Index on creation time for admin list ordering
CREATE INDEX idx_maintenance_banner_created_at ON maintenance_banner(created_at DESC);
```

**Query Patterns**:
- Find active banners: `WHERE NOW() BETWEEN start_time AND end_time` (uses time_range index)
- Admin list: `ORDER BY created_at DESC` (uses created_at index)

### Relationships

```
MaintenanceBanner ──(ManyToOne)──> User (createdBy)
```

- **MaintenanceBanner.createdBy**: References User.id
- **Cascade**: ON DELETE SET NULL (preserve banner history)
- **Fetch**: LAZY (user details not needed for banner display)

### Derived Fields (Not Stored)

| Field Name | Type | Derivation | Description |
|-----------|------|------------|-------------|
| status | Enum | Computed from current time vs. time range | UPCOMING, ACTIVE, EXPIRED |
| isActive | Boolean | `NOW() BETWEEN startTime AND endTime` | True if banner should display |

**Status Calculation**:
```kotlin
enum class BannerStatus {
    UPCOMING,  // startTime > now
    ACTIVE,    // startTime <= now <= endTime
    EXPIRED    // endTime < now
}

fun MaintenanceBanner.getStatus(): BannerStatus {
    val now = Instant.now()
    return when {
        startTime > now -> BannerStatus.UPCOMING
        endTime < now -> BannerStatus.EXPIRED
        else -> BannerStatus.ACTIVE
    }
}
```

---

## JPA Entity Definition

```kotlin
package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "maintenance_banner",
    indexes = [
        Index(name = "idx_start_time", columnList = "start_time"),
        Index(name = "idx_end_time", columnList = "end_time"),
        Index(name = "idx_created_at", columnList = "created_at")
    ]
)
data class MaintenanceBanner(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 2000)
    var message: String,

    @Column(nullable = false, name = "start_time")
    var startTime: Instant,

    @Column(nullable = false, name = "end_time")
    var endTime: Instant,

    @Column(nullable = false, name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = true)  // Nullable to preserve history on user deletion
    var createdBy: User? = null
) {
    /**
     * Check if this banner is currently active based on the current time.
     */
    fun isActive(): Boolean {
        val now = Instant.now()
        return now >= startTime && now <= endTime
    }

    /**
     * Get the current status of this banner.
     */
    fun getStatus(): BannerStatus {
        val now = Instant.now()
        return when {
            startTime > now -> BannerStatus.UPCOMING
            endTime < now -> BannerStatus.EXPIRED
            else -> BannerStatus.ACTIVE
        }
    }
}

enum class BannerStatus {
    UPCOMING,
    ACTIVE,
    EXPIRED
}
```

---

## DTOs (Data Transfer Objects)

### MaintenanceBannerRequest (Create/Update)

```kotlin
package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

@Serdeable
data class MaintenanceBannerRequest(
    @field:NotBlank(message = "Message is required")
    @field:Size(min = 1, max = 2000, message = "Message must be between 1 and 2000 characters")
    val message: String,

    @field:NotNull(message = "Start time is required")
    val startTime: Instant,

    @field:NotNull(message = "End time is required")
    val endTime: Instant
) {
    /**
     * Validate that end time is after start time.
     */
    fun validate(): Boolean {
        return endTime.isAfter(startTime)
    }
}
```

### MaintenanceBannerResponse (Read)

```kotlin
package com.secman.dto

import com.secman.domain.BannerStatus
import com.secman.domain.MaintenanceBanner
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

@Serdeable
data class MaintenanceBannerResponse(
    val id: Long,
    val message: String,
    val startTime: Instant,  // Serialized as ISO-8601 string
    val endTime: Instant,    // Frontend converts to local timezone
    val createdAt: Instant,
    val createdByUsername: String?,  // Username of creator (may be null if user deleted)
    val status: BannerStatus,        // UPCOMING, ACTIVE, EXPIRED
    val isActive: Boolean            // True if currently displaying
) {
    companion object {
        /**
         * Convert MaintenanceBanner entity to response DTO.
         */
        fun from(banner: MaintenanceBanner): MaintenanceBannerResponse {
            return MaintenanceBannerResponse(
                id = banner.id!!,
                message = banner.message,
                startTime = banner.startTime,
                endTime = banner.endTime,
                createdAt = banner.createdAt,
                createdByUsername = banner.createdBy?.username,
                status = banner.getStatus(),
                isActive = banner.isActive()
            )
        }
    }
}
```

---

## Database Migration

### Expected DDL (Hibernate Auto-Generated)

```sql
CREATE TABLE maintenance_banner (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message VARCHAR(2000) NOT NULL,
    start_time TIMESTAMP(6) NOT NULL,
    end_time TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    created_by BIGINT NULL,  -- Foreign key to user table

    CONSTRAINT fk_maintenance_banner_created_by
        FOREIGN KEY (created_by)
        REFERENCES user(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_end_after_start
        CHECK (end_time > start_time),

    INDEX idx_start_time (start_time),
    INDEX idx_end_time (end_time),
    INDEX idx_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**Notes**:
- `TIMESTAMP(6)` provides microsecond precision for Instant
- `utf8mb4_unicode_ci` supports full Unicode range
- InnoDB for transaction support and foreign key constraints

### Migration Strategy

1. **Development**: Hibernate auto-migration creates table
2. **Testing**: Verify schema in test database
3. **Production**: Review generated DDL, apply manually or via Flyway (future enhancement)

---

## Sample Data

### Example Records

```sql
-- Active banner (maintenance in progress)
INSERT INTO maintenance_banner (message, start_time, end_time, created_at, created_by)
VALUES (
    'System maintenance in progress. Some features may be temporarily unavailable.',
    '2025-11-15 20:00:00',  -- Started 1 hour ago
    '2025-11-15 23:00:00',  -- Ends in 2 hours
    '2025-11-15 18:00:00',  -- Created 3 hours ago
    1  -- Admin user ID
);

-- Upcoming banner (scheduled for tomorrow)
INSERT INTO maintenance_banner (message, start_time, end_time, created_at, created_by)
VALUES (
    'Scheduled maintenance: Database upgrades on November 16th from 2-4 AM UTC.',
    '2025-11-16 02:00:00',  -- Starts tomorrow
    '2025-11-16 04:00:00',  -- Ends 2 hours later
    '2025-11-15 21:00:00',  -- Created today
    1  -- Admin user ID
);

-- Expired banner (historical)
INSERT INTO maintenance_banner (message, start_time, end_time, created_at, created_by)
VALUES (
    'Emergency maintenance completed. All systems restored.',
    '2025-11-14 10:00:00',  -- Started yesterday
    '2025-11-14 12:00:00',  -- Ended yesterday
    '2025-11-14 09:30:00',  -- Created yesterday
    1  -- Admin user ID
);
```

---

## Validation Rules Summary

| Rule | Validation Point | Error Message |
|------|-----------------|---------------|
| Message not blank | DTO validation | "Message is required" |
| Message length 1-2000 | DTO validation | "Message must be between 1 and 2000 characters" |
| Start time not null | DTO validation | "Start time is required" |
| End time not null | DTO validation | "End time is required" |
| End after start | Service layer | "End time must be after start time" |
| XSS sanitization | Service layer | (Silent sanitization, no error) |

---

## Query Examples

### Find All Active Banners (Public Endpoint)

```kotlin
@Query("""
    SELECT b FROM MaintenanceBanner b
    WHERE :currentTime BETWEEN b.startTime AND b.endTime
    ORDER BY b.createdAt DESC
""")
fun findActiveBanners(currentTime: Instant): List<MaintenanceBanner>
```

**Usage**:
```kotlin
val activeBanners = repository.findActiveBanners(Instant.now())
```

**Expected Performance**: <10ms for 100 total banners

---

### Find All Banners (Admin Endpoint)

```kotlin
@Query("""
    SELECT b FROM MaintenanceBanner b
    ORDER BY b.createdAt DESC
""")
fun findAllOrderByCreatedAtDesc(): List<MaintenanceBanner>
```

**Usage**:
```kotlin
val allBanners = repository.findAllOrderByCreatedAtDesc()
```

**Returns**: All banners (active, upcoming, expired) sorted newest first

---

## Security Considerations

1. **XSS Prevention**: Message sanitized in service layer using OWASP Java HTML Sanitizer
2. **SQL Injection**: Prevented by JPA parameterized queries
3. **RBAC**: Admin-only write access, public read for active banners
4. **Audit Trail**: `createdBy` and `createdAt` provide audit information

---

## Performance Considerations

1. **Index Strategy**:
   - Composite index on (start_time, end_time) for time-range queries
   - Single index on created_at for admin list ordering

2. **Expected Load**:
   - Read queries (active banners): ~1000 requests/minute (every page load)
   - Write queries (admin CRUD): ~10 requests/day (very infrequent)

3. **Optimization Opportunities** (Post-MVP):
   - Add Redis cache for active banners (60-second TTL)
   - Materialized view for frequently-accessed data
   - Connection pooling already handled by Micronaut

---

## Future Enhancements (Not in MVP)

1. **Rich Text Support**: Allow HTML/Markdown in message (requires different sanitization)
2. **Banner Categories**: Add severity levels (INFO, WARNING, CRITICAL)
3. **Recurring Banners**: Support weekly/monthly recurrence patterns
4. **User Dismissal**: Allow users to hide specific banners for their session
5. **Analytics**: Track banner impressions and user engagement
6. **Localization**: Support multi-language banner messages
