# Testing

Three tiers: unit (Mockk), integration (**external MariaDB**, no Docker/Testcontainers), CLI (Picocli arg validation).

Integration tests run **unconditionally** — they *fail*, not skip, when no test database is reachable. There is no Docker gate and no `@EnabledIf`; Testcontainers was removed from the build.

Stack:
```
junit-jupiter 6.1.2, junit-platform-launcher 6.1.2,
micronaut-test-junit5 5.1.0,
mockk 1.14.11,
assertj 3.27.7
```

## Run

```bash
./gradlew build                                              # everything
./gradlew :backendng:test                                    # backend
./gradlew :backendng:test --tests "*ServiceTest*"            # unit only
./gradlew :backendng:test --tests "*IntegrationTest*"        # integration (needs TEST_DB_*)
./gradlew :backendng:test --tests "VulnerabilityServiceTest" # one class
./gradlew :backendng:test --tests "VulnerabilityServiceTest.addVulnerabilityFromCli_createsNewAsset"
./gradlew :cli:test
./gradlew :cli:test --tests "AddVulnerabilityCommandTest"

# HTML reports
open src/backendng/build/reports/tests/test/index.html
open src/cli/build/reports/tests/test/index.html
```

> All HTTP traffic in tests goes through `SECMAN_HOST` (resolved via `pass-cli`). Never hardcode `http://localhost:8080` / `:4321`.

## Test database

Integration tests need a reachable MariaDB. The datasource is read by
`src/test/resources/application-test.yml` from three env vars, supplied via `pass-cli`:

| Var | Default |
|---|---|
| `TEST_DB_URL` | `jdbc:mariadb://localhost:3306/secman_test` |
| `TEST_DB_USERNAME` | `secman_test` |
| `TEST_DB_PASSWORD` | `secman_test` |

> ⚠️ **The schema is created and dropped on every run** (Hibernate `hbm2ddl.auto=create-drop`;
> Flyway is off in the `test` environment). Point `TEST_DB_*` **only** at a dedicated, disposable
> database — never at `DB_CONNECT`, which would drop the dev or production tables.

One-time local setup:
```sql
CREATE DATABASE IF NOT EXISTS secman_test;
CREATE USER IF NOT EXISTS 'secman_test'@'localhost' IDENTIFIED BY 'secman_test';
GRANT ALL PRIVILEGES ON secman_test.* TO 'secman_test'@'localhost';
```

Integration tests bind port **8080**, so stop any running dev backend first
(`./scripts/stopbackenddev.sh`) or the suite hangs on a `BindException`.

## Layout

```
src/backendng/src/test/kotlin/com/secman/
  controller/              # AuthControllerTest.kt, ...
  service/                 # *ServiceTest.kt — unit
  integration/             # *IntegrationTest.kt — full stack
  testutil/
    BaseIntegrationTest.kt # @MicronautTest(environments=["test"]) base class
    TestDataFactory.kt     # createAdminUser, createAsset, createVulnerability, ...
    TestAuthHelper.kt      # JWT login → bearer
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

### Integration (external MariaDB via `BaseIntegrationTest`)
```kotlin
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
Starts the Micronaut context against the external test database. It carries no
datasource wiring of its own — that comes from `application-test.yml` and the
`TEST_DB_*` env vars (see [Test database](#test-database)).
```kotlin
@MicronautTest(environments = ["test"])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseIntegrationTest
```
Subclass it and inject what you need; there is nothing to gate or configure per test.

### `TestDataFactory`
`createAdminUser`, `createVulnUser`, `createRegularUser`, `createAsset(name, type="SERVER")`, `createVulnerability(asset, cve, severity)`. `DEFAULT_PASSWORD = "testpass123"`.

### `TestAuthHelper`
`getAuthToken(client, username)` POSTs to `/api/auth/login` and returns the JWT. `attemptLoginExpectingFailure(...)` for negative tests.

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

**There is no CI pipeline in this repo** — no `.github/workflows/`. Verification is local and
gated by CLAUDE.md's Hard Principles: `./gradlew build` clean, a clean
`./scripts/startbackenddev.sh` startup, and the two mandatory E2E gates above.

A CI job would need a reachable MariaDB and the `TEST_DB_*` vars exported; there is no
Docker service to provision and no skip flag to set, because integration tests no longer
gate themselves.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Integration tests fail at startup with a connection error | `TEST_DB_*` unset or DB unreachable. They no longer skip — a missing DB is a failure. Check `mariadb -u secman_test -p secman_test` |
| Integration tests hang, `BindException: 8080` | a dev backend is running — `./scripts/stopbackenddev.sh` |
| Schema-mismatch failures | the run left a partial schema; drop and recreate `secman_test` (it is `create-drop`, nothing of value lives there) |
| Gradle build dies mid-run on a dev machine | IntelliJ's daemon-stop can kill CLI Gradle builds — isolate with `-Dorg.gradle.daemon.registry.base` |
| `verify` fails unexpectedly | check `MockKAnnotations.init(this, relaxed=true/false)` choice; missing `every {}` setup |
| Tests pass alone, fail together | unique test data (`"host-${System.nanoTime()}"`); cleanup in `@AfterEach`; per-test transactions |
