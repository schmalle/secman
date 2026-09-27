# Testing

Four tiers: backend unit (Mockk), backend integration (**external MariaDB**, no Docker/Testcontainers), CLI (Picocli arg validation), and frontend unit (`node:test`, no test framework dependency).

Integration tests run **unconditionally** — they *fail*, not skip, when no test database is reachable. There is no Docker gate and no `@EnabledIf`; Testcontainers was removed from the build.

Stack:
```
junit-jupiter 6.1.3, junit-platform-launcher 6.1.3,
micronaut-test-junit5 5.1.1,
mockk 1.14.11,
assertj 3.27.7
```

## Run

```bash
./scripts/test/run-isolated-e2e.sh --database-only -- ./gradlew build
./scripts/runbackendtests.sh                                  # backend
./scripts/runbackendtests.sh --tests "*ServiceTest*"
./scripts/runbackendtests.sh --tests "*IntegrationTest*"
./scripts/runbackendtests.sh --tests "VulnerabilityServiceTest"
./gradlew :cli:test
./gradlew :cli:test --tests "AddVulnerabilityCommandTest"

# Frontend unit tests (no framework dependency — Node's own runner)
cd src/frontend && npm test
cd src/frontend && npm run test:watch
cd src/frontend && node --experimental-strip-types --import ./test/register.mjs \
  --test src/utils/permissions.test.ts        # one file

# HTML reports
open src/backendng/build/reports/tests/test/index.html
open src/cli/build/reports/tests/test/index.html
```

`/testsuite` runs all four tiers plus the frontend build gate, the skill-sync
check, and the coverage report in one pass — see `docs/SKILLS.md`.

> HTTP E2E tests use the runner-provided `SECMAN_BACKEND_URL` and `FRONTEND_URL` on loopback. Do not target the persistent 8080/4321 stack.

## Test database

`./scripts/runbackendtests.sh` creates an owned `secman_e2e_*` MariaDB schema,
sets `TEST_DB_URL`, `TEST_DB_USERNAME`, and `TEST_DB_PASSWORD`, and drops the
schema after the run. The Gradle task refuses a direct run without the runner's
ownership marker. Existing SecMan rows and the 8080/4321 stack are untouched.

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

src/frontend/
  test/
    register.mjs           # --import entry point; installs the resolver hook
    resolve-ts.mjs         # extensionless-import resolver (see Frontend below)
  src/**/<module>.test.ts  # test sits next to the module it covers
```

Naming: file `<Class>Test.kt`. Method either `addVulnerabilityFromCli_createsNewAsset` (descriptive) or `@DisplayName("VS-001: …")`. ID prefixes used in DisplayName tags: `VS-*` (VulnerabilityService), `VI-*` (Vuln Integration), `CLI-*` (CLI), `EC-*` (edge cases), `ES-*` (ExcelSanitizer), `AID-*` (AWS instance-id recognition).

> **Do not add a test dependency without checking the classpath.** `junit-jupiter-params` is *not* declared, so `@ParameterizedTest` does not compile — loop inside a plain `@Test` and attach `.as("…")` to each assertion instead. The frontend tier deliberately has **no** test-framework dependency at all.

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

### Frontend (`node:test`, zero dependencies)

Tests run on Node's built-in runner with native TypeScript stripping — there is no
Vitest, no Jest, no jsdom, and nothing to install beyond what `npm ci` already
brings. `npm test` expands to:

```
node --experimental-strip-types --import ./test/register.mjs --test "src/**/*.test.ts"
```

Two rules follow from that toolchain, and both have bitten this repo:

1. **Imports resolve `.ts`, never `.tsx`.** Node's type stripping cannot parse JSX.
   `test/resolve-ts.mjs` completes extensionless specifiers (`../utils/auth`) the
   way Vite does, so tests can import production modules as written; when a path
   exists only as `.tsx` it raises a pointed error instead of a syntax error.
2. **Component logic must be extracted to be unit-testable.** Pure logic living in
   a `.tsx` file can only be asserted against its *source text*. Both styles are in
   use — prefer extraction:

```ts
// Preferred: pure logic in a sibling .ts module (productSuggestions.ts,
// productSearchResults.ts, exceptionReviewDto.ts, …), imported directly.
import { getProductSuggestions } from './productSuggestions.ts';

test('empty filter returns every product', () => {
  assert.deepEqual(getProductSuggestions('', ['a', 'b']), ['a', 'b']);
});

// Fallback for markup that cannot be extracted: assert on the source text.
test('sidebar labels the products link', () => {
  const source = readFileSync(new URL('./Sidebar.tsx', import.meta.url), 'utf8');
  assert.match(source, /href="\/products"[\s\S]*Vulnerable products/);
});
```

Source-text assertions are brittle by nature — they break on reformatting and pass
on logic that never runs. Use them only when the alternative is no test at all.

> `npm run build` is a separate obligation from `npm test` (CLAUDE.md principle 5a).
> The tests do not type-check the app; the build does.

## Coverage

**There is no line-coverage tooling.** No JaCoCo, no Kover, no c8 — nothing in this
build records which lines executed. Adding Kover to `src/backendng/build.gradle.kts`
is the natural next step if a real figure is ever needed.

What exists instead is a name-reference report:

```bash
./scripts/test-coverage-report.sh            # per-area summary + the untested names
./scripts/test-coverage-report.sh --summary  # counts only
./scripts/test-coverage-report.sh --area service
```

For each production unit it asks whether *any* test file mentions its name. That is
a floor, and it is biased in both directions:

- A unit counted as covered may carry a single trivial assertion.
- `controller` and `mcp-tools` are **understated**: the E2E gates and `tests/e2e/`
  exercise controllers over HTTP without naming a Kotlin class, and
  `McpToolPermissionsTest` asserts over all 96 MCP tools in one table.

`service`, `util` and the frontend areas are close to honest, because those units
are normally reached by a unit test or not at all. Never quote the total as "test
coverage" without that caveat.

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

Liveness in the isolated runner is **port-bind**, not HTTP probe: backend
`:18080` (120s budget), frontend `:14321` (60s). Functional checks use the
runner-provided loopback URLs and never target the regular stack.

## CI

**There is no CI pipeline in this repo** — no `.github/workflows/`. Verification is local and
gated by CLAUDE.md's Hard Principles: an isolated `./gradlew build` clean, a clean
`./scripts/startbackenddev.sh` startup, and the two mandatory E2E gates above.

A CI job needs a local MariaDB administrator connection so the isolated runner
can create and remove a marked schema. There is no Docker service or skip flag.

The frontend tier is the exception and would be the cheapest thing to wire up first:
`cd src/frontend && npm ci && npm test && npm run build` needs no database, no
secrets, and no `pass-cli` — only Node ≥ 22 for `--experimental-strip-types`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Integration tests fail at startup with a connection error | Check local MariaDB access, then rerun `./scripts/runbackendtests.sh`; do not point tests at `secman` |
| E2E test port occupied | Inspect 18080/14321/1925; the runner never stops an unrelated listener |
| An old `secman_e2e_*` schema remains | Rerun the isolated runner; only an exact ownership marker with a dead runner PID permits automatic removal |
| Gradle build dies mid-run on a dev machine | IntelliJ's daemon-stop can kill CLI Gradle builds — isolate with `-Dorg.gradle.daemon.registry.base` |
| `verify` fails unexpectedly | check `MockKAnnotations.init(this, relaxed=true/false)` choice; missing `every {}` setup |
| Tests pass alone, fail together | unique test data (`"host-${System.nanoTime()}"`); cleanup in `@AfterEach`; per-test transactions |
| `npm ci` fails with "lock file's X does not satisfy Y" | `package-lock.json` drifted from `package.json`, which makes the whole frontend gate unrunnable. `npm install` to rewrite the lock, then re-run `npm ci` to confirm, and commit the lock |
| `npm test`: `ERR_MODULE_NOT_FOUND` on a component path | the module is `.tsx`; JSX cannot be imported. Extract the logic to a sibling `.ts` module (see [Frontend](#frontend-nodetest-zero-dependencies)) |
| `@ParameterizedTest` unresolved | `junit-jupiter-params` is not on the classpath. Loop inside a plain `@Test` |
