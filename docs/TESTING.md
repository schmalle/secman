# SecMan Testing Guide

**Last Updated:** 2025-12-29
**Version:** 1.0

Comprehensive guide for testing the SecMan security management platform.

---

## Table of Contents

1. [Overview](#overview)
2. [Test Stack](#test-stack)
3. [Running Tests](#running-tests)
4. [Test Structure](#test-structure)
5. [Writing Tests](#writing-tests)
6. [Test Utilities](#test-utilities)
7. [CI/CD Integration](#cicd-integration)
8. [Troubleshooting](#troubleshooting)

---

## Overview

SecMan uses a comprehensive testing strategy with three test tiers:

| Tier | Type | Purpose | Requirements |
|------|------|---------|--------------|
| 1 | Unit Tests | Test business logic in isolation | None |
| 2 | Integration Tests | Test full stack with real database | Docker |
| 3 | CLI Tests | Test command-line parameter validation | None |

**Key principles:**
- Tests are located in `src/{module}/src/test/kotlin/`
- Integration tests auto-skip when Docker is unavailable
- All tests run with `./gradlew build`

---

## Test Stack

### Core Dependencies

```kotlin
// build.gradle.kts
testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
testImplementation("org.junit.jupiter:junit-jupiter-engine:5.11.3")
testImplementation("io.micronaut.test:micronaut-test-junit5:4.8.1")
testImplementation("io.mockk:mockk:1.13.13")
testImplementation("org.testcontainers:testcontainers:1.20.4")
testImplementation("org.testcontainers:mariadb:1.20.4")
testImplementation("org.testcontainers:junit-jupiter:1.20.4")
testImplementation("org.assertj:assertj-core:3.26.3")
```

### Framework Roles

| Framework | Purpose |
|-----------|---------|
| **JUnit 5** | Test framework and lifecycle management |
| **Mockk** | Kotlin-native mocking for unit tests |
| **Testcontainers** | Containerized MariaDB for integration tests |
| **AssertJ** | Fluent assertion library |
| **Micronaut Test** | Dependency injection in tests |

---

## Running Tests

### All Tests

```bash
# Run all tests (unit + integration)
./gradlew build

# Or explicitly
./gradlew test
```

### Backend Tests Only

```bash
# All backend tests
./gradlew :backendng:test

# Unit tests only (service layer)
./gradlew :backendng:test --tests "*ServiceTest*"

# Specific test class
./gradlew :backendng:test --tests "VulnerabilityServiceTest"

# Specific test method
./gradlew :backendng:test --tests "VulnerabilityServiceTest.addVulnerabilityFromCli_createsNewAsset"
```

### Integration Tests Only

```bash
# All integration tests (requires Docker)
./gradlew :backendng:test --tests "*IntegrationTest*"

# Specific integration test
./gradlew :backendng:test --tests "VulnerabilityIntegrationTest"
```

### CLI Tests Only

```bash
# All CLI tests
./gradlew :cli:test

# Specific CLI test class
./gradlew :cli:test --tests "AddVulnerabilityCommandTest"
```

### Test Reports

```bash
# After running tests, open HTML report:
open src/backendng/build/reports/tests/test/index.html

# CLI test report:
open src/cli/build/reports/tests/test/index.html
```

---

## Test Structure

### Directory Layout

```
src/
├── backendng/src/test/kotlin/com/secman/
│   ├── controller/           # Controller unit tests
│   │   └── AuthControllerTest.kt
│   ├── integration/          # Full-stack integration tests
│   │   ├── VulnerabilityIntegrationTest.kt
│   │   └── EdgeCaseTest.kt
│   ├── service/              # Service layer unit tests
│   │   └── VulnerabilityServiceTest.kt
│   └── testutil/             # Shared test utilities
│       ├── BaseIntegrationTest.kt
│       ├── TestDataFactory.kt
│       └── TestAuthHelper.kt
├── cli/src/test/kotlin/com/secman/cli/
│   └── commands/             # CLI command tests
│       └── AddVulnerabilityCommandTest.kt
```

### Test Naming Conventions

```kotlin
// Class naming: {ClassName}Test.kt
VulnerabilityServiceTest.kt
AddVulnerabilityCommandTest.kt

// Method naming: describe what is tested
@Test
fun `addVulnerabilityFromCli_createsNewAsset`() { ... }

// Or use DisplayName for readable output
@Test
@DisplayName("VS-001: Creates new asset when hostname doesn't exist")
fun `addVulnerabilityFromCli_createsNewAsset`() { ... }
```

### Test ID Prefixes

| Prefix | Module |
|--------|--------|
| `VS-*` | VulnerabilityService tests |
| `VI-*` | Vulnerability Integration tests |
| `CLI-*` | CLI command tests |
| `EC-*` | Edge case tests |

---

## Writing Tests

### Unit Tests with Mockk

```kotlin
@DisplayName("VulnerabilityService.addVulnerabilityFromCli")
class VulnerabilityServiceTest {

    @MockK
    private lateinit var assetRepository: AssetRepository

    @MockK
    private lateinit var vulnerabilityRepository: VulnerabilityRepository

    private lateinit var vulnerabilityService: VulnerabilityService

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)

        vulnerabilityService = spyk(
            VulnerabilityService(
                vulnerabilityRepository = vulnerabilityRepository,
                assetRepository = assetRepository
                // ... other dependencies
            )
        )

        // Default mock behaviors
        every { assetRepository.save(any()) } answers {
            firstArg<Asset>().apply { id = 1L }
        }
    }

    @Test
    @DisplayName("VS-001: Creates new asset when hostname doesn't exist")
    fun `addVulnerabilityFromCli_createsNewAsset`() {
        // Given: No existing asset
        every { assetRepository.findByNameIgnoreCase("new-system") } returns null

        val request = AddVulnerabilityRequestDto(
            hostname = "new-system",
            cve = "CVE-2024-001",
            criticality = "HIGH",
            daysOpen = 60
        )

        // When
        val result = vulnerabilityService.addVulnerabilityFromCli(request)

        // Then
        assertThat(result.success).isTrue()
        assertThat(result.assetCreated).isTrue()

        verify {
            assetRepository.save(match { asset ->
                asset.name == "new-system" &&
                asset.type == "SERVER"
            })
        }
    }
}
```

### Integration Tests with Testcontainers

```kotlin
@DisplayName("CLI Add Vulnerability Integration Tests")
@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class VulnerabilityIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    private lateinit var adminUser: User

    @BeforeEach
    fun setupTestUsers() {
        adminUser = userRepository.save(TestDataFactory.createAdminUser(
            username = "integ-admin-${System.nanoTime()}"
        ))
    }

    @Test
    @DisplayName("VI-001: Add vulnerability creates asset and vulnerability")
    fun `cliAddVulnerability_createsAssetAndVuln`() {
        // Given: Admin user authenticated
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)
        val hostname = "system-a-${System.nanoTime()}"

        val request = AddVulnerabilityRequestDto(
            hostname = hostname,
            cve = "CVE-2024-TEST001",
            criticality = "HIGH",
            daysOpen = 60
        )

        // When: POST to cli-add endpoint
        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/vulnerabilities/cli-add", request)
                .bearerAuth(token),
            AddVulnerabilityResponseDto::class.java
        )

        // Then: HTTP 200 and asset in database
        assertThat(response.status).isEqualTo(HttpStatus.OK)

        val asset = assetRepository.findByNameIgnoreCase(hostname)
        assertThat(asset).isNotNull
        assertThat(asset!!.type).isEqualTo("SERVER")
    }
}
```

### CLI Tests with Picocli

```kotlin
@DisplayName("AddVulnerabilityCommand Parameter Validation")
class AddVulnerabilityCommandTest {

    @Test
    @DisplayName("CLI-003: Requires hostname parameter")
    fun `requiresHostname`() {
        val cmd = CommandLine(AddVulnerabilityCommand())

        val optionSpec = cmd.commandSpec.findOption("--hostname")
        assertThat(optionSpec).isNotNull
        assertThat(optionSpec?.required()).isTrue()
    }

    @Test
    @DisplayName("CLI-001: Accepts valid criticality values")
    fun `acceptsValidCriticalityValues`() {
        val validValues = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")

        validValues.forEach { criticality ->
            val cmd = CommandLine(AddVulnerabilityCommand())
            cmd.parseArgs(
                "--hostname", "test-host",
                "--cve", "CVE-2024-001",
                "--criticality", criticality,
                "--username", "admin",
                "--password", "pass"
            )

            val command = cmd.getCommand<AddVulnerabilityCommand>()
            assertThat(command.criticality).isEqualTo(criticality)
        }
    }
}
```

---

## Test Utilities

### BaseIntegrationTest

Provides shared Testcontainers configuration for all integration tests.

```kotlin
abstract class BaseIntegrationTest : TestPropertyProvider {

    companion object {
        @JvmStatic
        val mariadb: MariaDBContainer<*> by lazy {
            MariaDBContainer("mariadb:11.4")
                .withDatabaseName("secman_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true)
                .also { it.start() }
        }
    }

    override fun getProperties(): MutableMap<String, String> {
        return mutableMapOf(
            "datasources.default.url" to mariadb.jdbcUrl,
            "datasources.default.username" to mariadb.username,
            "datasources.default.password" to mariadb.password
        )
    }
}
```

**Features:**
- Singleton container pattern (one MariaDB for all tests)
- Auto-configures datasource for Micronaut
- Container reuse for faster subsequent runs

### TestDataFactory

Factory for creating test entities with sensible defaults.

```kotlin
object TestDataFactory {

    const val DEFAULT_PASSWORD = "testpass123"

    fun createAdminUser(
        username: String = "testadmin",
        email: String = "testadmin@secman.test"
    ): User {
        return User(
            username = username,
            email = email,
            passwordHash = encodedPassword,
            roles = mutableSetOf(Role.USER, Role.ADMIN)
        )
    }

    fun createVulnUser(username: String, email: String): User { ... }
    fun createRegularUser(username: String, email: String): User { ... }
    fun createAsset(name: String, type: String = "SERVER"): Asset { ... }
    fun createVulnerability(asset: Asset, cve: String, severity: String): Vulnerability { ... }
}
```

**Usage:**
```kotlin
val admin = TestDataFactory.createAdminUser()
val asset = TestDataFactory.createAsset(name = "test-server")
val vuln = TestDataFactory.createVulnerability(asset, "CVE-2024-001", "High")
```

### TestAuthHelper

Helper for authentication in integration tests.

```kotlin
object TestAuthHelper {

    fun getAuthToken(client: HttpClient, username: String): String {
        val request = HttpRequest.POST(
            "/api/auth/login",
            LoginRequest(username, TestDataFactory.DEFAULT_PASSWORD)
        )
        val response = client.toBlocking().exchange(request, LoginResponse::class.java)
        return response.body()?.token ?: throw IllegalStateException("No token")
    }

    fun attemptLoginExpectingFailure(
        client: HttpClient,
        username: String,
        password: String
    ): HttpClientResponseException { ... }
}
```

**Usage:**
```kotlin
val token = TestAuthHelper.getAuthToken(client, adminUser.username)

val response = client.toBlocking().exchange(
    HttpRequest.GET<Any>("/api/protected")
        .bearerAuth(token),
    String::class.java
)
```

### DockerAvailable

Helper for conditionally enabling integration tests based on Docker availability.

```kotlin
class DockerAvailable {
    companion object {
        @JvmStatic
        fun isDockerAvailable(): Boolean {
            return try {
                DockerClientFactory.instance().isDockerAvailable
            } catch (e: Exception) {
                false
            }
        }
    }
}
```

**Usage:**
```kotlin
@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class MyIntegrationTest : BaseIntegrationTest() { ... }
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Test Suite

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'corretto'

      - name: Run unit tests
        run: ./gradlew :backendng:test --tests "*ServiceTest*"

      - name: Upload test report
        uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: unit-test-report
          path: src/backendng/build/reports/tests/

  integration-tests:
    runs-on: ubuntu-latest
    services:
      docker:
        image: docker:dind
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'corretto'

      - name: Run integration tests
        run: ./gradlew :backendng:test --tests "*IntegrationTest*"
```

### Test Environment Variables

```bash
# For integration tests with external MariaDB (optional)
TESTCONTAINERS_REUSE_ENABLE=true  # Reuse containers between runs

# Skip integration tests in environments without Docker
SKIP_INTEGRATION_TESTS=true
```

---

## Troubleshooting

### Docker Not Available

**Symptom:** Integration tests are skipped

**Solution:**
```bash
# Verify Docker is running
docker info

# On macOS, ensure Docker Desktop is running
# On Linux, check Docker daemon
systemctl status docker
```

### Testcontainers Slow Start

**Symptom:** First test run is very slow

**Solution:** Enable container reuse:
```bash
# Create ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

### Test Failures After Schema Changes

**Symptom:** Integration tests fail with schema errors

**Solution:**
```bash
# Remove cached containers
docker container prune

# Or remove specific container
docker rm -f $(docker ps -aq --filter "label=org.testcontainers")
```

### Mock Verification Failures

**Symptom:** `verify` calls fail in unit tests

**Solution:** Check mock setup:
```kotlin
// Ensure mock is properly initialized
@BeforeEach
fun setup() {
    MockKAnnotations.init(this, relaxed = true)
}

// Use relaxed = false for strict verification
MockKAnnotations.init(this, relaxed = false)
```

### Test Isolation Issues

**Symptom:** Tests pass individually but fail when run together

**Solution:** Ensure unique test data:
```kotlin
// Use unique identifiers in tests
val hostname = "test-host-${System.nanoTime()}"

// Clean up in @AfterEach if needed
@AfterEach
fun cleanup() {
    assetRepository.deleteAll()
}
```

---

## Related Documentation

- [CLI Reference](./CLI.md) - CLI usage and commands
- [Environment Variables](./ENVIRONMENT.md) - Configuration reference
- [CrowdStrike Import](./CROWDSTRIKE_IMPORT.md) - Import technical details

---

*For questions about testing, see the project repository or contact the maintainers.*
