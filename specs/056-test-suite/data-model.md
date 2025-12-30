# Data Model: Test Suite for Secman

**Feature**: 056-test-suite
**Date**: 2025-12-29

## Overview

This test suite does not introduce new domain entities. It tests existing entities:
- **Asset**: System/server being tracked
- **Vulnerability**: Security vulnerability on an asset
- **User**: Authenticated user with roles

## Test Data Entities

### TestUser

Used to create test users with specific roles for RBAC testing.

| Field | Type | Description |
|-------|------|-------------|
| username | String | Test username (e.g., "testadmin", "testuser") |
| password | String | Plain text password (hashed before storage) |
| email | String | Test email |
| roles | List<String> | Role names: ADMIN, VULN, USER, SECCHAMPION |

**Test Instances**:
- `testadmin` / `testpass` with roles [ADMIN]
- `testvuln` / `testpass` with roles [VULN]
- `testuser` / `testpass` with roles [USER]

### TestAsset

Used to create test assets for vulnerability testing.

| Field | Type | Description |
|-------|------|-------------|
| name | String | Hostname (e.g., "system-a") |
| type | String | Asset type (default: "SERVER") |
| owner | String | Asset owner (default: "TEST" or "CLI-IMPORT") |

**Test Instances**:
- `system-a` - Primary test asset for US1
- `server-01.domain.local` - Edge case EC-001 (special characters)
- `server_with_underscore` - Edge case EC-001 (underscore)
- Long hostname (255 chars) - Edge case EC-001 (max length)

### TestVulnerability

Used to create test vulnerabilities.

| Field | Type | Description |
|-------|------|-------------|
| cve | String | CVE identifier (e.g., "CVE-2024-TEST001") |
| criticality | String | CRITICAL, HIGH, MEDIUM, LOW |
| daysOpen | Int | Number of days vulnerability has been open |

**Test Instances**:
- CVE-2024-TEST001 / HIGH / 60 days - Primary test case (US1)
- CVE-2024-TEST002 / CRITICAL / 0 days - Edge case EC-003 (days-open=0)
- cve-2024-test003 / MEDIUM / 30 days - Edge case EC-005 (lowercase)

## Test Data Factory

```kotlin
object TestDataFactory {
    fun createAdminUser(userRepository: UserRepository): User {
        return userRepository.save(User(
            username = "testadmin",
            passwordHash = BCryptPasswordEncoder().encode("testpass"),
            email = "testadmin@example.com",
            roles = mutableSetOf(Role(name = "ADMIN"))
        ))
    }

    fun createVulnUser(userRepository: UserRepository): User {
        return userRepository.save(User(
            username = "testvuln",
            passwordHash = BCryptPasswordEncoder().encode("testpass"),
            email = "testvuln@example.com",
            roles = mutableSetOf(Role(name = "VULN"))
        ))
    }

    fun createRegularUser(userRepository: UserRepository): User {
        return userRepository.save(User(
            username = "testuser",
            passwordHash = BCryptPasswordEncoder().encode("testpass"),
            email = "testuser@example.com",
            roles = mutableSetOf(Role(name = "USER"))
        ))
    }

    fun createAsset(assetRepository: AssetRepository, name: String = "system-a"): Asset {
        return assetRepository.save(Asset(
            name = name,
            type = "SERVER",
            owner = "TEST"
        ))
    }

    fun createVulnerability(
        vulnerabilityRepository: VulnerabilityRepository,
        asset: Asset,
        cve: String = "CVE-2024-TEST001",
        severity: String = "High",
        daysOpen: Int = 60
    ): Vulnerability {
        return vulnerabilityRepository.save(Vulnerability(
            asset = asset,
            vulnerabilityId = cve,
            cvssSeverity = severity,
            daysOpen = if (daysOpen == 1) "1 day" else "$daysOpen days",
            scanTimestamp = LocalDateTime.now().minusDays(daysOpen.toLong())
        ))
    }
}
```

## State Transitions

### Vulnerability Upsert States

```
┌─────────────────┐     POST /api/vulnerabilities/cli-add
│   No Asset      │ ──────────────────────────────────────┐
└─────────────────┘                                       │
        │                                                 │
        │ Asset not found                                 │
        ▼                                                 │
┌─────────────────┐                                       │
│ Create Asset    │ ← type=SERVER, owner=CLI-IMPORT       │
│ assetCreated=   │                                       │
│    true         │                                       │
└─────────────────┘                                       │
        │                                                 │
        │ Asset exists                                    │
        ▼                                                 ▼
┌─────────────────┐     CVE not found     ┌─────────────────┐
│   Asset Found   │ ────────────────────► │ Create Vuln     │
│ assetCreated=   │                       │ operation=      │
│    false        │                       │   CREATED       │
└─────────────────┘                       └─────────────────┘
        │
        │ CVE exists
        ▼
┌─────────────────┐
│ Update Vuln     │
│ operation=      │
│   UPDATED       │
└─────────────────┘
```

## Validation Rules

### Hostname Validation
- Maximum length: 255 characters
- Allowed characters: alphanumeric, hyphen, underscore, period
- Case-insensitive lookup

### CVE Validation
- Format: CVE-YYYY-NNNNN (flexible on number of digits)
- Case-insensitive (normalized to uppercase)

### Criticality Validation
- Enum values: CRITICAL, HIGH, MEDIUM, LOW
- Case-insensitive input
- Mapped to title case for storage: "Critical", "High", "Medium", "Low"

### Days-Open Validation
- Must be >= 0
- 0 means newly discovered (scanTimestamp = now)
- Stored as text: "N days" or "1 day"
