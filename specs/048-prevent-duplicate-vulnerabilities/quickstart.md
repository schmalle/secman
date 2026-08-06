# Quickstart: Prevent Duplicate Vulnerabilities in CrowdStrike Import

**Feature**: 048-prevent-duplicate-vulnerabilities
**Purpose**: Verify and document the existing duplicate prevention mechanism in CrowdStrike vulnerability imports

## Overview

This feature focuses on **verification and testing** of the existing transactional replace pattern that prevents duplicate vulnerabilities. The implementation already exists and works correctly - this quickstart guides you through understanding, testing, and documenting it.

## What Already Works

The system already prevents duplicates through this mechanism:

```kotlin
@Transactional
fun importVulnerabilitiesForServer(batch: CrowdStrikeVulnerabilityBatchDto): ServerImportResult {
    val (asset, isNewAsset) = findOrCreateAsset(batch)

    // 1. Delete ALL existing vulnerabilities
    vulnerabilityRepository.deleteByAssetId(asset.id!!)

    // 2. Insert NEW vulnerabilities from import
    val vulnerabilities = createVulnerabilities(batch)
    vulnerabilityRepository.saveAll(vulnerabilities)

    return ServerImportResult(...)
}
```

**Result**: Running the same import multiple times produces identical database state (idempotent).

## Prerequisites

- ✅ Kotlin 2.2.21 / Java 21 installed
- ✅ Gradle 9.2 installed
- ✅ MariaDB 12 running (or H2 for tests)
- ✅ Existing secman project checked out
- ✅ Branch: `048-prevent-duplicate-vulnerabilities`

## Quick Verification (5 minutes)

### Step 1: Examine Current Implementation

```bash
# View the transactional replace pattern
cat src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt | grep -A 20 "importVulnerabilitiesForServer"
```

**What to look for**:
- `@Transactional` annotation (line 161)
- `deleteByAssetId()` call (line 169)
- `saveAll()` call (line 239)

### Step 2: Run Existing Tests (if any)

```bash
cd src/backendng
./gradlew test --tests "*CrowdStrike*Import*"
```

**Expected**: Existing tests pass (if they exist)

### Step 3: Manual Verification with CLI

```bash
# Run import twice with same data
cd src/cli
./gradlew run --args='servers import --file test-data.json'
./gradlew run --args='servers import --file test-data.json'

# Check vulnerability count in database
mysql -u secman -p secman -e "SELECT COUNT(*) FROM vulnerability;"
```

**Expected**: Count is same after both imports (not doubled)

## Understanding the Mechanism

### Why It Prevents Duplicates

```
Import Run 1:
  Asset server01: 0 vulnerabilities
  → DELETE 0 rows
  → INSERT 5 rows
  Result: 5 vulnerabilities

Import Run 2 (same data):
  Asset server01: 5 vulnerabilities
  → DELETE 5 rows (removes all)
  → INSERT 5 rows (recreates from import data)
  Result: 5 vulnerabilities (same final state)
```

### Transaction Atomicity

```
Success Case:
  DELETE vulnerabilities ✅
  INSERT vulnerabilities ✅
  COMMIT → Changes persist

Failure Case:
  DELETE vulnerabilities ✅
  INSERT vulnerabilities ❌ (constraint violation)
  ROLLBACK → DELETE reverted, original data restored
```

## Development Workflow

### Phase 1: Add Integration Tests

**File**: `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServiceTest.kt`

**Test 1: Idempotent Import**

```kotlin
@Test
fun `importing same vulnerabilities twice should not create duplicates`() {
    // Given: Initial import with 5 vulnerabilities
    val batch = createTestBatch(hostname = "server01", vulnCount = 5)
    val result1 = service.importVulnerabilitiesForServer(batch)

    // When: Import same data again
    val result2 = service.importVulnerabilitiesForServer(batch)

    // Then: Same number imported both times
    assertEquals(5, result1.vulnerabilitiesImported)
    assertEquals(5, result2.vulnerabilitiesImported)

    // And: Database has exactly 5 vulnerabilities (not 10)
    val asset = assetRepository.findByNameIgnoreCase("server01")!!
    val vulnCount = vulnerabilityRepository.countByAssetId(asset.id!!)
    assertEquals(5, vulnCount)
}
```

**Test 2: Remediation (Removing Vulnerabilities)**

```kotlin
@Test
fun `importing fewer vulnerabilities should remove remediated ones`() {
    // Given: Initial import with 10 vulnerabilities
    val batch1 = createTestBatch(hostname = "server01", vulnCount = 10)
    service.importVulnerabilitiesForServer(batch1)

    // When: Import with only 7 vulnerabilities
    val batch2 = createTestBatch(hostname = "server01", vulnCount = 7)
    service.importVulnerabilitiesForServer(batch2)

    // Then: Database has exactly 7 vulnerabilities (3 removed)
    val asset = assetRepository.findByNameIgnoreCase("server01")!!
    val vulnCount = vulnerabilityRepository.countByAssetId(asset.id!!)
    assertEquals(7, vulnCount)
}
```

**Test 3: Transaction Rollback**

```kotlin
@Test
fun `failed import should rollback and preserve original data`() {
    // Given: Asset with 5 vulnerabilities
    val batch1 = createTestBatch(hostname = "server01", vulnCount = 5)
    service.importVulnerabilitiesForServer(batch1)

    // When: Import with invalid data (e.g., null CVE ID)
    val batch2 = createInvalidBatch(hostname = "server01")
    assertThrows<ConstraintViolationException> {
        service.importVulnerabilitiesForServer(batch2)
    }

    // Then: Original 5 vulnerabilities still exist (rollback successful)
    val asset = assetRepository.findByNameIgnoreCase("server01")!!
    val vulnCount = vulnerabilityRepository.countByAssetId(asset.id!!)
    assertEquals(5, vulnCount)
}
```

### Phase 2: Add Documentation

**File**: `docs/CROWDSTRIKE_IMPORT.md`

```markdown
# CrowdStrike Import Architecture

## Duplicate Prevention

### Mechanism: Transactional Replace Pattern

The import process uses a "transactional replace" pattern:
1. Find or create asset by hostname
2. DELETE all existing vulnerabilities for the asset
3. INSERT new vulnerabilities from import data
4. COMMIT transaction (or ROLLBACK on failure)

This ensures:
- No duplicate vulnerabilities (old data completely replaced)
- Idempotent imports (same input → same output)
- Atomic operations (all succeed or all fail)

### Why Not Upsert?

Considered alternatives:
- Upsert (update if exists, insert if not): ❌ Doesn't handle remediated vulnerabilities
- Soft delete (mark as deleted): ❌ Adds query complexity
- Differential import (compare and apply delta): ❌ Complex algorithm, risk of drift

Transactional replace is simpler and guarantees consistency.
```

### Phase 3: Add Code Comments

**File**: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt`

```kotlin
/**
 * Import vulnerabilities for a single server with transactional replace pattern
 *
 * DUPLICATE PREVENTION:
 * This method prevents duplicate vulnerabilities by deleting ALL existing
 * vulnerabilities for the asset before inserting new ones. This ensures:
 * - Idempotent imports (running same import multiple times produces same result)
 * - No accumulation of duplicate records
 * - Remediated vulnerabilities are properly removed
 *
 * TRANSACTION BEHAVIOR:
 * The @Transactional annotation ensures atomicity:
 * - If delete succeeds but insert fails → ROLLBACK (original data restored)
 * - If both succeed → COMMIT (new data persists)
 * - No partial states possible
 *
 * @param batch Server vulnerability batch with metadata
 * @return ServerImportResult with statistics
 */
@Transactional
open fun importVulnerabilitiesForServer(
    batch: CrowdStrikeVulnerabilityBatchDto
): ServerImportResult {
    // Implementation...
}
```

## Testing Commands

### Run Integration Tests

```bash
cd src/backendng
./gradlew test --tests "CrowdStrikeVulnerabilityImportServiceTest"
```

### Run with Coverage

```bash
./gradlew test --tests "CrowdStrikeVulnerabilityImportServiceTest" jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

### Run Full Test Suite

```bash
./gradlew test
```

## Manual Testing Scenarios

### Scenario 1: Verify Idempotency

```bash
# 1. Create test data file
cat > test-import.json <<EOF
[
  {
    "hostname": "test-server-01",
    "ip": "10.0.0.1",
    "vulnerabilities": [
      {"cveId": "CVE-2023-0001", "severity": "HIGH", "daysOpen": 10},
      {"cveId": "CVE-2023-0002", "severity": "MEDIUM", "daysOpen": 5}
    ]
  }
]
EOF

# 2. Run import
curl -X POST http://localhost:8080/api/crowdstrike/servers/import \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @test-import.json

# 3. Check database count
mysql -u secman -p secman -e "
  SELECT a.name, COUNT(v.id) AS vuln_count
  FROM asset a
  LEFT JOIN vulnerability v ON v.asset_id = a.id
  WHERE a.name = 'test-server-01'
  GROUP BY a.name;
"
# Expected: vuln_count = 2

# 4. Run import again
curl -X POST http://localhost:8080/api/crowdstrike/servers/import \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @test-import.json

# 5. Check database count again
mysql -u secman -p secman -e "
  SELECT a.name, COUNT(v.id) AS vuln_count
  FROM asset a
  LEFT JOIN vulnerability v ON v.asset_id = a.id
  WHERE a.name = 'test-server-01'
  GROUP BY a.name;
"
# Expected: vuln_count = 2 (not 4)
```

### Scenario 2: Verify Remediation Handling

```bash
# 1. Import with 5 vulnerabilities
curl -X POST http://localhost:8080/api/crowdstrike/servers/import \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '[
    {
      "hostname": "test-server-02",
      "vulnerabilities": [
        {"cveId": "CVE-2023-0001", "severity": "HIGH", "daysOpen": 10},
        {"cveId": "CVE-2023-0002", "severity": "HIGH", "daysOpen": 10},
        {"cveId": "CVE-2023-0003", "severity": "MEDIUM", "daysOpen": 5},
        {"cveId": "CVE-2023-0004", "severity": "MEDIUM", "daysOpen": 5},
        {"cveId": "CVE-2023-0005", "severity": "LOW", "daysOpen": 2}
      ]
    }
  ]'

# 2. Import with 3 vulnerabilities (2 remediated)
curl -X POST http://localhost:8080/api/crowdstrike/servers/import \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '[
    {
      "hostname": "test-server-02",
      "vulnerabilities": [
        {"cveId": "CVE-2023-0001", "severity": "HIGH", "daysOpen": 12},
        {"cveId": "CVE-2023-0003", "severity": "MEDIUM", "daysOpen": 7},
        {"cveId": "CVE-2023-0005", "severity": "LOW", "daysOpen": 4}
      ]
    }
  ]'

# 3. Verify count
mysql -u secman -p secman -e "
  SELECT COUNT(*) FROM vulnerability v
  JOIN asset a ON v.asset_id = a.id
  WHERE a.name = 'test-server-02';
"
# Expected: 3 (CVE-2023-0002 and CVE-2023-0004 removed)
```

## Common Issues & Solutions

### Issue 1: Tests Fail with "Transaction already active"

**Cause**: Test not properly cleaning up transactions

**Solution**: Add `@Rollback` annotation to test class
```kotlin
@MicronautTest
@Rollback
class CrowdStrikeVulnerabilityImportServiceTest {
    // Tests...
}
```

### Issue 2: Duplicate Vulnerabilities Still Created

**Cause**: Import not using the transactional replace pattern

**Solution**: Verify `deleteByAssetId()` is called BEFORE `saveAll()`
```kotlin
// Correct order:
vulnerabilityRepository.deleteByAssetId(asset.id!!)  // First
vulnerabilityRepository.saveAll(vulnerabilities)      // Second
```

### Issue 3: Rollback Not Working

**Cause**: Missing `@Transactional` annotation or non-open method

**Solution**: Ensure method is `open` and has `@Transactional`
```kotlin
@Transactional
open fun importVulnerabilitiesForServer(...) {  // Must be 'open' for Micronaut proxy
    // Implementation
}
```

## Success Criteria Checklist

- ✅ Import same data twice → database has same count (not doubled)
- ✅ Import fewer vulnerabilities → remediated ones removed
- ✅ Import failure → rollback preserves original data
- ✅ Concurrent imports for different assets → no conflicts
- ✅ Concurrent imports for same asset → sequential execution
- ✅ Documentation explains transactional replace pattern
- ✅ Code comments describe duplicate prevention strategy

## Next Steps

After completing this quickstart:

1. **Run Tests**: Execute integration tests to verify behavior
2. **Review Documentation**: Read generated `docs/CROWDSTRIKE_IMPORT.md`
3. **Code Review**: Examine implementation with fresh understanding
4. **Performance Test**: Verify large dataset imports complete within 5 minutes
5. **Deploy**: No code changes needed - tests and docs are the deliverables

## Key Takeaways

- ✅ Duplicate prevention **already works** via transactional replace
- ✅ No new implementation needed - verification and documentation only
- ✅ Idempotency is guaranteed by delete-then-insert pattern
- ✅ Transaction rollback ensures atomicity and consistency
- ✅ Tests will validate behavior across all edge cases

## Resources

- [spec.md](spec.md) - Feature specification
- [research.md](research.md) - Implementation research and analysis
- [data-model.md](data-model.md) - Entity relationships and data flow
- [contracts/existing-import-api.md](contracts/existing-import-api.md) - API contract documentation
- [plan.md](plan.md) - Implementation plan (this document)
