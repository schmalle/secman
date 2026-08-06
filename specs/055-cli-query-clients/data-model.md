# Data Model: CLI Query Clients/Workstations

**Feature**: 055-cli-query-clients
**Date**: 2025-12-16

## Overview

This feature introduces no new database entities. It adds a single enum type for device classification in the CLI/shared modules.

## New Types

### DeviceType Enum

**Location**: `src/shared/src/main/kotlin/com/secman/crowdstrike/dto/DeviceType.kt`

```kotlin
enum class DeviceType(val fqlValue: String) {
    SERVER("Server"),
    WORKSTATION("Workstation"),
    ALL("");  // Special case: queries both types

    /**
     * Generate FQL filter string for CrowdStrike API
     * Returns null for ALL (requires special handling)
     */
    fun toFqlFilter(): String? = when (this) {
        SERVER -> "product_type_desc:'Server'"
        WORKSTATION -> "product_type_desc:'Workstation'"
        ALL -> null
    }

    companion object {
        /**
         * Parse from string (case-insensitive)
         * @throws IllegalArgumentException if invalid value
         */
        fun fromString(value: String): DeviceType =
            entries.find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Invalid device type: '$value'. Valid values: ${entries.joinToString { it.name }}"
                )
    }
}
```

**Fields**:

| Field | Type | Description |
|-------|------|-------------|
| fqlValue | String | CrowdStrike FQL filter value |

**Validation Rules**:
- Case-insensitive parsing from CLI input
- Invalid values throw `IllegalArgumentException` with helpful message

## Existing Entities (Unchanged)

### Asset

The existing Asset entity remains unchanged. Both servers and workstations are stored as Asset records with no device type distinction. This is intentional - the vulnerability management system treats all devices equally.

### Vulnerability

The existing Vulnerability entity remains unchanged. Vulnerabilities are associated with Assets regardless of whether the underlying device is a server or workstation.

## Data Flow

```
CLI Input (--device-type WORKSTATION)
    ↓
DeviceType.fromString("WORKSTATION")
    ↓
DeviceType.WORKSTATION.toFqlFilter()
    ↓
"product_type_desc:'Workstation'"
    ↓
CrowdStrike API Query
    ↓
CrowdStrikeVulnerabilityDto (existing)
    ↓
Backend Import (existing endpoint)
    ↓
Asset + Vulnerability records (existing)
```

## Migration

**No migration required** - This feature adds no database schema changes.
