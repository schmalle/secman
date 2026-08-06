# Test Specifications Contract

**Feature**: 056-test-suite
**Date**: 2025-12-29

This document defines the test contracts - the expected behaviors that each test must verify.

## Unit Tests

### VulnerabilityServiceTest

| Test ID | Test Name | Input | Expected Output |
|---------|-----------|-------|-----------------|
| VS-001 | addVulnerabilityFromCli_createsNewAsset | hostname="new-system", cve="CVE-2024-001", criticality="HIGH", daysOpen=60 | assetCreated=true, operation="CREATED", asset.type="SERVER", asset.owner="CLI-IMPORT" |
| VS-002 | addVulnerabilityFromCli_usesExistingAsset | hostname="existing-system" (exists), cve="CVE-2024-002", criticality="MEDIUM", daysOpen=30 | assetCreated=false, operation="CREATED" |
| VS-003 | addVulnerabilityFromCli_updatesExistingVuln | hostname="system-a", cve="CVE-2024-001" (exists), criticality="CRITICAL", daysOpen=90 | assetCreated=false, operation="UPDATED" |
| VS-004 | addVulnerabilityFromCli_mapsCriticality | criticality="HIGH" | cvssSeverity="High" |
| VS-005 | addVulnerabilityFromCli_mapsCriticalityLow | criticality="LOW" | cvssSeverity="Low" |
| VS-006 | addVulnerabilityFromCli_calculatesScanTimestamp | daysOpen=60 | scanTimestamp = now - 60 days |
| VS-007 | addVulnerabilityFromCli_handlesDaysOpenZero | daysOpen=0 | scanTimestamp ≈ now (within 1 second) |
| VS-008 | addVulnerabilityFromCli_formatsDaysOpenText | daysOpen=60 | daysOpen="60 days" |
| VS-009 | addVulnerabilityFromCli_formatsDaysOpenSingular | daysOpen=1 | daysOpen="1 day" |

### AuthControllerTest

| Test ID | Test Name | Input | Expected Output |
|---------|-----------|-------|-----------------|
| AC-001 | login_returnsJwtToken | valid username/password | HTTP 200, token not null, roles included |
| AC-002 | login_rejectsInvalidCredentials | wrong password | HTTP 401, error="Invalid credentials" |
| AC-003 | login_rejectsEmptyUsername | username="" | HTTP 400 |
| AC-004 | login_rejectsEmptyPassword | password="" | HTTP 400 |
| AC-005 | status_returnsUserInfo | valid JWT token | HTTP 200, username, email, roles |
| AC-006 | status_rejectsInvalidToken | invalid/expired token | HTTP 401 |

## Integration Tests

### VulnerabilityIntegrationTest

| Test ID | Test Name | Preconditions | Actions | Verifications |
|---------|-----------|---------------|---------|---------------|
| VI-001 | cliAddVulnerability_systemA_high_60days | Admin user exists | POST /api/vulnerabilities/cli-add with hostname="system-a", cve="CVE-2024-TEST001", criticality="HIGH", daysOpen=60 | HTTP 200, vulnerability in DB with severity="High", scanTimestamp 60 days ago |
| VI-002 | cliAddVulnerability_addsToExistingAsset | Asset "system-a" exists with 1 vuln | POST with same hostname, different CVE | Asset count unchanged, vuln count increased by 1 |
| VI-003 | getCurrentVulnerabilities_returnsAddedVuln | Vulnerability exists for "system-a" | GET /api/vulnerabilities/current | Response contains vulnerability with correct details |
| VI-004 | rbac_adminCanAddVuln | Admin user authenticated | POST /api/vulnerabilities/cli-add | HTTP 200 |
| VI-005 | rbac_vulnRoleCanAddVuln | VULN user authenticated | POST /api/vulnerabilities/cli-add | HTTP 200 |
| VI-006 | rbac_userCannotAddVuln | USER role authenticated | POST /api/vulnerabilities/cli-add | HTTP 403 |
| VI-007 | rbac_unauthenticatedDenied | No authentication | POST /api/vulnerabilities/cli-add | HTTP 401 |

### Edge Case Tests

| Test ID | Test Name | Input | Expected Behavior |
|---------|-----------|-------|-------------------|
| EC-001a | hostname_withDots | hostname="server-01.domain.local" | Successfully creates asset |
| EC-001b | hostname_withUnderscore | hostname="server_name" | Successfully creates asset |
| EC-001c | hostname_maxLength | hostname=(255 chars) | Successfully creates asset |
| EC-002 | concurrent_sameAsset | Two simultaneous requests, same hostname | No duplicate assets, both vulns created |
| EC-003 | daysOpen_zero | daysOpen=0 | scanTimestamp equals current time |
| EC-004 | database_unavailable | MariaDB container stopped | Graceful error, appropriate message |
| EC-005 | cve_lowercase | cve="cve-2024-1234" | Normalized to uppercase in storage |

## CLI Tests

### AddVulnerabilityCommandTest

| Test ID | Test Name | Input | Expected Output |
|---------|-----------|-------|-----------------|
| CLI-001 | validatesCriticalityEnum | criticality="INVALID" | Error: "Criticality must be CRITICAL, HIGH, MEDIUM, or LOW" |
| CLI-002 | rejectsNegativeDaysOpen | daysOpen=-1 | Error: "Days open must be a positive number or zero" |
| CLI-003 | requiresHostname | hostname missing | Picocli required parameter error |
| CLI-004 | requiresCve | cve missing | Picocli required parameter error |
| CLI-005 | requiresCriticality | criticality missing | Picocli required parameter error |
| CLI-006 | acceptsValidInputs | all valid parameters | No validation errors |
| CLI-007 | normalizeCriticalityCase | criticality="high" | Normalized to "HIGH" internally |

## Test Coverage Matrix

| Functional Requirement | Unit Test | Integration Test | Edge Case Test |
|----------------------|-----------|------------------|----------------|
| FR-001: JUnit 5 + Mockk | ✓ (all tests) | ✓ (all tests) | ✓ (all tests) |
| FR-002: Testcontainers | - | ✓ (VI-*) | ✓ (EC-004) |
| FR-003: CLI validation | ✓ (CLI-*) | - | - |
| FR-004: Upsert pattern | ✓ (VS-001,002,003) | ✓ (VI-001,002) | - |
| FR-005: HTTP status codes | ✓ (AC-*) | ✓ (VI-004-007) | - |
| FR-006: JWT auth | ✓ (AC-001,002) | ✓ (VI-*) | - |
| FR-007: RBAC | - | ✓ (VI-004-007) | - |
| FR-008: system-a/HIGH/60 | - | ✓ (VI-001) | - |
| FR-009: Asset defaults | ✓ (VS-001) | ✓ (VI-001) | - |
| FR-010: Criticality mapping | ✓ (VS-004,005) | ✓ (VI-001) | - |
| FR-011: Timestamp calc | ✓ (VS-006,007) | ✓ (VI-001) | ✓ (EC-003) |
| FR-012: gradle build | ✓ (all) | ✓ (all) | ✓ (all) |
| FR-013: Edge cases | - | - | ✓ (EC-*) |
| FR-014: @BeforeEach setup | ✓ (all) | ✓ (all) | ✓ (all) |
