# Research: Test Suite for Secman

**Feature**: 056-test-suite
**Date**: 2025-12-29

## Research Topics

### 1. Micronaut Testing with JUnit 5 and Mockk

**Decision**: Use `@MicronautTest` annotation with JUnit 5 for integration tests, Mockk for unit test mocking.

**Rationale**: Micronaut Test provides seamless integration with JUnit 5, supporting dependency injection in test classes via `@Inject`. The `@MicronautTest` annotation handles application context lifecycle automatically.

**Configuration (build.gradle.kts)**:
```kotlin
dependencies {
    kspTest("io.micronaut:micronaut-inject-java")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.mockk:mockk:1.13.13")
}

tasks.test {
    useJUnitPlatform()
}
```

**Test Pattern**:
```kotlin
@MicronautTest
class VulnerabilityServiceTest {
    @Inject
    lateinit var vulnerabilityService: VulnerabilityService

    @Test
    fun `should add vulnerability to existing asset`() {
        // test implementation
    }
}
```

**Alternatives Considered**:
- Kotest: More idiomatic Kotlin but adds another framework to learn; JUnit 5 is more widely known
- Spock: Groovy-based, would require mixing languages

---

### 2. Testcontainers with MariaDB

**Decision**: Use Testcontainers MariaDB module with JUnit 5 Jupiter extension for integration tests.

**Rationale**: Testcontainers provides throwaway MariaDB instances matching production, ensuring realistic integration tests. The JUnit 5 extension manages container lifecycle automatically.

**Configuration (build.gradle.kts)**:
```kotlin
dependencies {
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:mariadb:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}
```

**Test Pattern**:
```kotlin
@Testcontainers
@MicronautTest
class VulnerabilityIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val mariadb = MariaDBContainer("mariadb:11.4")
            .withDatabaseName("secman_test")
            .withUsername("test")
            .withPassword("test")
    }

    @Test
    fun `should persist vulnerability to database`() {
        // Full integration test with real database
    }
}
```

**Alternatives Considered**:
- H2 in-memory: Faster but doesn't replicate MariaDB-specific behavior
- Shared test database: Violates test isolation principle
- Docker Compose: More complex setup, less integrated with test lifecycle

---

### 3. Test Data Setup Strategy

**Decision**: Programmatic test data creation in `@BeforeEach` methods with repository injection.

**Rationale**: Per-test isolation prevents test pollution. Each test starts with a known state. Repositories are injected via Micronaut DI.

**Pattern**:
```kotlin
@MicronautTest
class VulnerabilityServiceTest {
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var assetRepository: AssetRepository

    private lateinit var testUser: User
    private lateinit var testAsset: Asset

    @BeforeEach
    fun setup() {
        // Clean slate
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()

        // Create test user with ADMIN role
        testUser = userRepository.save(User(
            username = "testadmin",
            passwordHash = BCryptPasswordEncoder().encode("testpass"),
            email = "test@example.com",
            roles = mutableSetOf(Role(name = "ADMIN"))
        ))

        // Create test asset
        testAsset = assetRepository.save(Asset(
            name = "system-a",
            type = "SERVER",
            owner = "TEST"
        ))
    }
}
```

**Alternatives Considered**:
- Flyway test migrations: Good for schema but less flexible for per-test data
- Shared fixtures (@BeforeAll): Faster but risks test pollution
- Factory pattern: Added complexity without significant benefit for this scope

---

### 4. CLI Command Testing

**Decision**: Test CLI parameter validation with unit tests; test end-to-end CLI behavior with integration tests calling the HTTP client.

**Rationale**: Picocli commands can be unit tested for validation logic. Full CLI tests require a running backend, which is covered by integration tests.

**Unit Test Pattern (parameter validation)**:
```kotlin
class AddVulnerabilityCommandTest {
    @Test
    fun `should reject invalid criticality`() {
        val command = AddVulnerabilityCommand()
        command.criticality = "INVALID"
        // Validation logic test
    }

    @Test
    fun `should reject negative days-open`() {
        val command = AddVulnerabilityCommand()
        command.daysOpen = -1
        // Validation logic test
    }
}
```

**Integration Test Pattern**:
```kotlin
@MicronautTest
class CliAddVulnerabilityIntegrationTest {
    @Inject lateinit var httpClient: HttpClient

    @Test
    fun `should add vulnerability via API endpoint`() {
        val request = AddVulnerabilityRequestDto(
            hostname = "system-a",
            cve = "CVE-2024-TEST001",
            criticality = "HIGH",
            daysOpen = 60
        )
        val response = httpClient.toBlocking().exchange(
            HttpRequest.POST("/api/vulnerabilities/cli-add", request)
                .bearerAuth(testToken),
            AddVulnerabilityResponseDto::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }
}
```

---

### 5. Authentication Testing

**Decision**: Test JWT authentication using Micronaut's HTTP client with Bearer tokens.

**Rationale**: Micronaut Test provides an embedded HTTP client that can be injected. JWT tokens are obtained via the login endpoint and used in subsequent requests.

**Pattern**:
```kotlin
@MicronautTest
class AuthControllerTest {
    @Inject
    @Client("/")
    lateinit var client: HttpClient

    @Test
    fun `should return JWT token for valid credentials`() {
        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login",
                mapOf("username" to "testadmin", "password" to "testpass")),
            LoginResponse::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body()?.token)
    }

    @Test
    fun `should reject invalid credentials`() {
        assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login",
                    mapOf("username" to "wrong", "password" to "wrong")),
                Any::class.java
            )
        }
    }
}
```

---

### 6. RBAC Testing

**Decision**: Create test users with different roles and verify access control at controller level.

**Rationale**: @Secured annotations are enforced by Micronaut Security. Integration tests verify that endpoints correctly reject unauthorized access.

**Pattern**:
```kotlin
@MicronautTest
class RbacTest {
    @Inject lateinit var userRepository: UserRepository

    private lateinit var adminToken: String
    private lateinit var userToken: String

    @BeforeEach
    fun setup() {
        // Create ADMIN user
        val admin = createUser("admin", listOf("ADMIN"))
        adminToken = getToken("admin", "password")

        // Create USER (no ADMIN/VULN role)
        val user = createUser("user", listOf("USER"))
        userToken = getToken("user", "password")
    }

    @Test
    fun `admin should access cli-add endpoint`() {
        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/vulnerabilities/cli-add", request)
                .bearerAuth(adminToken),
            Any::class.java
        )
        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `user should be denied access to cli-add endpoint`() {
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST("/api/vulnerabilities/cli-add", request)
                    .bearerAuth(userToken),
                Any::class.java
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }
}
```

---

## Dependencies Summary

### Backend (src/backendng/build.gradle.kts)

```kotlin
dependencies {
    // Test framework
    kspTest("io.micronaut:micronaut-inject-java")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testImplementation("io.micronaut.test:micronaut-test-junit5:4.8.1")

    // Mocking
    testImplementation("io.mockk:mockk:1.13.13")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:mariadb:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")

    // Assertions
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.test {
    useJUnitPlatform()
}
```

### CLI (src/cli/build.gradle.kts)

```kotlin
// Remove the test.enabled = false block
// Add test dependencies similar to backend
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testImplementation("io.mockk:mockk:1.13.13")
}

tasks.test {
    useJUnitPlatform()
}
```

---

## Open Questions Resolved

| Question | Resolution |
|----------|------------|
| Which test framework? | JUnit 5 + Mockk (user specified) |
| Database for integration tests? | Testcontainers MariaDB (user specified) |
| Test data setup? | @BeforeEach programmatic (clarified in spec) |
| Edge cases to test? | All 5 (clarified in spec) |
