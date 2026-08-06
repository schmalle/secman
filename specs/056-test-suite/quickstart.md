# Quickstart: Test Suite for Secman

**Feature**: 056-test-suite
**Date**: 2025-12-29

## Prerequisites

- **Java 21** - Required for Kotlin compilation
- **Docker** - Required for Testcontainers (MariaDB)
- **Gradle 9.2** - Build tool

## Running Tests

### All Tests
```bash
./gradlew build
```

### Backend Tests Only
```bash
./gradlew :backendng:test
```

### CLI Tests Only
```bash
./gradlew :cli:test
```

### Specific Test Class
```bash
./gradlew :backendng:test --tests "com.secman.service.VulnerabilityServiceTest"
```

### Specific Test Method
```bash
./gradlew :backendng:test --tests "com.secman.service.VulnerabilityServiceTest.addVulnerabilityFromCli_systemA_high_60days"
```

### With Verbose Output
```bash
./gradlew test --info
```

## Test Structure

```
src/backendng/src/test/kotlin/com/secman/
├── service/
│   └── VulnerabilityServiceTest.kt      # Unit tests with Mockk
├── controller/
│   ├── AuthControllerTest.kt            # Authentication tests
│   └── VulnerabilityControllerTest.kt   # Controller tests
├── integration/
│   └── VulnerabilityIntegrationTest.kt  # Full flow with Testcontainers
└── testutil/
    ├── TestDataFactory.kt               # Test data creation helpers
    └── BaseIntegrationTest.kt           # Testcontainers base class

src/cli/src/test/kotlin/com/secman/cli/
└── commands/
    └── AddVulnerabilityCommandTest.kt   # CLI parameter validation
```

## Configuration

### Test Application Config
Location: `src/backendng/src/test/resources/application-test.yml`

```yaml
micronaut:
  application:
    name: secman-test
  security:
    enabled: true
    token:
      jwt:
        signatures:
          secret:
            generator:
              secret: test-secret-key-for-jwt-signing-minimum-256-bits

datasources:
  default:
    # Configured dynamically by Testcontainers
    driverClassName: org.mariadb.jdbc.Driver

jpa:
  default:
    properties:
      hibernate:
        hbm2ddl:
          auto: create-drop
```

## Testcontainers Setup

Integration tests automatically start a MariaDB container:

```kotlin
@Testcontainers
@MicronautTest
abstract class BaseIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val mariadb = MariaDBContainer("mariadb:11.4")
            .withDatabaseName("secman_test")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("datasources.default.url") { mariadb.jdbcUrl }
            registry.add("datasources.default.username") { mariadb.username }
            registry.add("datasources.default.password") { mariadb.password }
        }
    }
}
```

## Primary Test Case

The explicitly requested test case (US1, FR-008):

```kotlin
@Test
fun `should add vulnerability for system-a with HIGH criticality and 60 days open`() {
    // Given
    val adminToken = getAuthToken("testadmin", "testpass")

    // When
    val response = client.toBlocking().exchange(
        HttpRequest.POST("/api/vulnerabilities/cli-add",
            AddVulnerabilityRequestDto(
                hostname = "system-a",
                cve = "CVE-2024-TEST001",
                criticality = "HIGH",
                daysOpen = 60
            ))
            .bearerAuth(adminToken),
        AddVulnerabilityResponseDto::class.java
    )

    // Then
    assertEquals(HttpStatus.OK, response.status)
    val body = response.body()!!
    assertTrue(body.success)
    assertEquals("system-a", body.assetName)
    assertEquals("CVE-2024-TEST001", body.vulnerabilityId)

    // Verify in database
    val asset = assetRepository.findByNameIgnoreCase("system-a")
    assertNotNull(asset)
    assertEquals("SERVER", asset!!.type)
    assertEquals("CLI-IMPORT", asset.owner)

    val vuln = vulnerabilityRepository.findByAssetAndVulnerabilityId(asset, "CVE-2024-TEST001")
    assertNotNull(vuln)
    assertEquals("High", vuln!!.cvssSeverity)
    assertEquals("60 days", vuln.daysOpen)

    val expectedTimestamp = LocalDateTime.now().minusDays(60)
    assertTrue(vuln.scanTimestamp!!.isBefore(expectedTimestamp.plusMinutes(1)))
    assertTrue(vuln.scanTimestamp!!.isAfter(expectedTimestamp.minusMinutes(1)))
}
```

## Troubleshooting

### Docker Not Running
```
Error: Could not find a valid Docker environment
```
**Solution**: Start Docker Desktop or Docker daemon.

### Port Conflicts
```
Error: Address already in use
```
**Solution**: Testcontainers uses random ports. Check for orphaned containers:
```bash
docker ps -a | grep testcontainers
docker rm -f <container_id>
```

### Test Timeout
```
Error: Test timed out after 60 seconds
```
**Solution**: First Testcontainers run downloads images. Increase timeout or pre-pull:
```bash
docker pull mariadb:11.4
```

### Hibernate Schema Errors
```
Error: Table 'X' doesn't exist
```
**Solution**: Verify `hbm2ddl.auto: create-drop` in test config. Each test should start fresh.

## Success Criteria Verification

| Criteria | How to Verify |
|----------|---------------|
| SC-001: ./gradlew build passes | Run `./gradlew build` - exit code 0 |
| SC-002: VulnerabilityService coverage | All VS-* tests pass |
| SC-003: system-a/HIGH/60 test | VI-001 test passes |
| SC-004: <2 min integration tests | Check Gradle test report timing |
| SC-005: Auth success/failure | AC-001, AC-002 tests pass |
| SC-006: RBAC 403/401 | VI-005, VI-006, VI-007 tests pass |
| SC-007: All 5 edge cases | EC-001 through EC-005 tests pass |
