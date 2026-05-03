# Testing

Three tiers: unit (Mockk), integration (Testcontainers MariaDB 11.4), CLI (Picocli arg validation). Integration auto-skips when Docker is unavailable.

Stack:
```
junit-jupiter 6.0.3, micronaut-test-junit5,
mockk 1.14.9,
testcontainers 2.0.4, testcontainers:mariadb 1.21.4, testcontainers:junit-jupiter 1.21.4,
assertj 3.27.7
```

## Run

```bash
./gradlew build                                              # everything
./gradlew :backendng:test                                    # backend
./gradlew :backendng:test --tests "*ServiceTest*"            # unit only
./gradlew :backendng:test --tests "*IntegrationTest*"        # integration (Docker)
./gradlew :backendng:test --tests "VulnerabilityServiceTest" # one class
./gradlew :backendng:test --tests "VulnerabilityServiceTest.addVulnerabilityFromCli_createsNewAsset"
./gradlew :cli:test
./gradlew :cli:test --tests "AddVulnerabilityCommandTest"

# HTML reports
open src/backendng/build/reports/tests/test/index.html
open src/cli/build/reports/tests/test/index.html
```

> All HTTP traffic in tests goes through `SECMAN_HOST` (resolved via `pass-cli`). Never hardcode `http://localhost:8080` / `:4321`.

## Layout

```
src/backendng/src/test/kotlin/com/secman/
  controller/              # AuthControllerTest.kt, ...
  service/                 # *ServiceTest.kt — unit
  integration/             # *IntegrationTest.kt — full stack
  testutil/
    BaseIntegrationTest.kt # Testcontainers setup, skip-if-no-docker
    TestDataFactory.kt     # createAdminUser, createAsset, createVulnerability, ...
    TestAuthHelper.kt      # JWT login → bearer
    DockerAvailable.kt     # @EnabledIf gate
src/cli/src/test/kotlin/com/secman/cli/commands/  # *CommandTest.kt
```

Naming: file `<Class>Test.kt`. Method either `addVulnerabilityFromCli_createsNewAsset` (descriptive) or `@DisplayName("VS-001: …")`. ID prefixes used in DisplayName tags: `VS-*` (VulnerabilityService), `VI-*` (Vuln Integration), `CLI-*` (CLI), `EC-*` (edge cases).

## Patterns

### Unit (Mockk)
```kotlin
class VulnerabilityServiceTest {
    @MockK lateinit var assetRepository: AssetRepository
    @MockK lateinit var vulnerabilityRepository: VulnerabilityRepository
    private lateinit var service: VulnerabilityService

    @BeforeEach fun setup() {
        MockKAnnotations.init(this, relaxed = true)
        service = spyk(VulnerabilityService(vulnerabilityRepository, assetRepository, /* … */))
        every { assetRepository.save(any()) } answers { firstArg<Asset>().apply { id = 1L } }
    }

    @Test @DisplayName("VS-001: Creates new asset when hostname doesn't exist")
    fun createsNewAsset() {
        every { assetRepository.findByNameIgnoreCase("new-system") } returns null
        val req = AddVulnerabilityRequestDto(hostname="new-system", cve="CVE-2024-001", criticality="HIGH", daysOpen=60)

        val result = service.addVulnerabilityFromCli(req)

        assertThat(result.success).isTrue()
        assertThat(result.assetCreated).isTrue()
        verify { assetRepository.save(match { it.name == "new-system" && it.type == "SERVER" }) }
    }
}
```

### Integration (Testcontainers via `BaseIntegrationTest`)
```kotlin
@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class VulnerabilityIntegrationTest : BaseIntegrationTest() {
    @Inject @field:Client("/") lateinit var client: HttpClient
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var assetRepository: AssetRepository

    private lateinit var admin: User
    @BeforeEach fun setup() {
        admin = userRepository.save(TestDataFactory.createAdminUser(username = "integ-${System.nanoTime()}"))
    }

    @Test @DisplayName("VI-001: Add vulnerability creates asset and vulnerability")
    fun createsAssetAndVuln() {
        val token = TestAuthHelper.getAuthToken(client, admin.username)
        val hostname = "asset-${System.nanoTime()}"
        val req = AddVulnerabilityRequestDto(hostname=hostname, cve="CVE-2024-TEST", criticality="HIGH", daysOpen=60)

        val resp = client.toBlocking().exchange(
            HttpRequest.POST("/api/vulnerabilities/cli-add", req).bearerAuth(token),
            AddVulnerabilityResponseDto::class.java
        )

        assertThat(resp.status).isEqualTo(HttpStatus.OK)
        val asset = assetRepository.findByNameIgnoreCase(hostname)
        assertThat(asset?.type).isEqualTo("SERVER")
    }
}
```

### CLI (Picocli arg validation)
```kotlin
class AddVulnerabilityCommandTest {
    @Test @DisplayName("CLI-003: Requires --hostname")
    fun requiresHostname() {
        val cmd = CommandLine(AddVulnerabilityCommand())
        val opt = cmd.commandSpec.findOption("--hostname")
        assertThat(opt).isNotNull
        assertThat(opt!!.required()).isTrue()
    }

    @Test @DisplayName("CLI-001: Accepts CRITICAL|HIGH|MEDIUM|LOW")
    fun acceptsValidCriticality() {
        listOf("CRITICAL","HIGH","MEDIUM","LOW").forEach { c ->
            val cmd = CommandLine(AddVulnerabilityCommand())
            cmd.parseArgs("--hostname","h","--cve","CVE-X","--criticality",c,"--username","u","--password","p")
            assertThat(cmd.getCommand<AddVulnerabilityCommand>().criticality).isEqualTo(c)
        }
    }
}
```

## Helpers

### `BaseIntegrationTest`
Singleton MariaDB via `withReuse(true)`; auto-injects datasource into Micronaut.
```kotlin
abstract class BaseIntegrationTest : TestPropertyProvider {
    companion object {
        @JvmStatic val mariadb: MariaDBContainer<*> by lazy {
            MariaDBContainer("mariadb:11.4")
                .withDatabaseName("secman_test").withUsername("test").withPassword("test")
                .withReuse(true).also { it.start() }
        }
    }
    override fun getProperties() = mutableMapOf(
        "datasources.default.url"      to mariadb.jdbcUrl,
        "datasources.default.username" to mariadb.username,
        "datasources.default.password" to mariadb.password)
}
```

### `TestDataFactory`
`createAdminUser`, `createVulnUser`, `createRegularUser`, `createAsset(name, type="SERVER")`, `createVulnerability(asset, cve, severity)`. `DEFAULT_PASSWORD = "testpass123"`.

### `TestAuthHelper`
`getAuthToken(client, username)` POSTs to `/api/auth/login` and returns the JWT. `attemptLoginExpectingFailure(...)` for negative tests.

### `DockerAvailable.isDockerAvailable()`
Used as `@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")` to gate integration suites.

## E2E

Two mandatory gates after **every** code change (per `CLAUDE.md` principle 7):

- `/e2ejs` — JS error scanner across all pages, twice (admin + normal user). Must report 0 `[UNCAUGHT EXCEPTION]` and 0 `[CONSOLE ERROR]`. RBAC 403 and documented empty-state 404 are not failures; a page that throws or logs `console.error` is.
- `/e2evulnexception` — full vuln + exception lifecycle (MCP + UI), 0 failures.

Plus Playwright suites under `tests/e2e/` (Chrome + msedge):
```bash
cd tests/e2e && npm install && npx playwright install chrome msedge
./tests/e2e/run-e2e.sh                                        # canonical (pass-cli secrets)
# manual:
SECMAN_BASE_URL="$SECMAN_HOST" \
  SECMAN_ADMIN_NAME=… SECMAN_ADMIN_PASS=… \
  SECMAN_USER_USER=… SECMAN_USER_PASS=… \
  npx playwright test
```

Liveness in the runner is **port-bind**, not HTTP probe: backend `:8080` (120s budget), frontend `:4321` (60s). Functional checks still flow through `SECMAN_HOST`.

## CI

```yaml
unit-tests:
  steps:
    - uses: actions/setup-java@v4
      with: { java-version: '21', distribution: 'corretto' }
    - run: ./gradlew :backendng:test --tests "*ServiceTest*"
    - if: failure()
      uses: actions/upload-artifact@v4
      with: { name: unit-test-report, path: src/backendng/build/reports/tests/ }

integration-tests:
  services: { docker: { image: docker:dind } }
  steps:
    - uses: actions/setup-java@v4
      with: { java-version: '21', distribution: 'corretto' }
    - run: ./gradlew :backendng:test --tests "*IntegrationTest*"
```

Useful env: `TESTCONTAINERS_REUSE_ENABLE=true` (faster reruns), `SKIP_INTEGRATION_TESTS=true` (CI without Docker).

## Troubleshooting

| Symptom | Fix |
|---|---|
| Integration tests skipped | `docker info` (start Docker Desktop / `systemctl status docker`) |
| First run very slow | `~/.testcontainers.properties`: `testcontainers.reuse.enable=true` |
| Schema-mismatch failures | `docker rm -f $(docker ps -aq --filter "label=org.testcontainers")` |
| `verify` fails unexpectedly | check `MockKAnnotations.init(this, relaxed=true/false)` choice; missing `every {}` setup |
| Tests pass alone, fail together | unique test data (`"host-${System.nanoTime()}"`); cleanup in `@AfterEach`; per-test transactions |
