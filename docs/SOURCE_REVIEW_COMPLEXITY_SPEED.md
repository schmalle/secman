# Source Review: Complexity Drivers & Speed Issues

*Full-repository review, 2026-08-27. Scope: `src/backendng` (133k main + 32k test LOC Kotlin), `src/frontend` (80k), `src/cli` (20k), `src/relay` (12k Go), `scripts/` (15k shell), `src/clinotify`, build configuration, and Flyway migrations — ~296k lines total.*

*Every file:line reference below was verified against the source at review time. Recommendations are ranked with **operational robustness as the overriding constraint**: nothing here proposes weakening the `AssetFilterService` auth boundary, the transactional-replace import semantics, the boot-fail validators, the stdlib-only contracts, or any OWASP control. Section 5 lists things that superficially look like problems but are load-bearing and must not be "fixed".*

---

## 1. Executive summary — top 10 findings

| # | Finding | Kind | Impact | Effort |
|---|---|---|---|---|
| 1 | Controller layer bypasses its own central error handlers: ~349 local `catch (e: Exception)` blocks and ~1,000 hand-built `HttpResponse.*` sites against 429 lines of `exception/` handlers | Complexity | High (every controller) | Medium, incremental |
| 2 | `ksp.incremental=false` forces full annotation-processing of 779 Kotlin files on every backend change, and drives the 5.6 GiB build heap | Speed (dev loop) | High (every build) | Blocked upstream — track KSP releases |
| 3 | `AssetFilterService` admin paths call `assetRepository.findAll()` (3 sites) and the scan path fetch-then-filters `scanRepository.findAll()` in memory | Speed (runtime) | High as asset count grows | Low–medium |
| 4 | CLI `SecmanCli.kt` (1,527 lines): a 420-line manual dispatcher, 4 hand-rolled arg-parse loops, and ~930 lines of hand-written help text duplicating Picocli metadata; zero `@Mixin` in the whole CLI, 4 independent `authenticate()` implementations | Complexity | High (CLI maintainability) | Medium |
| 5 | Landing-page dashboard issues up to 9 serial `await`s (`HomeStatisticsDashboard.tsx:134–233`); total latency is the sum, not the max | Speed (user-visible) | High (first page every user sees) | Low |
| 6 | 89× `client:load` vs 2× `client:visible` — every 1,000+-line management island hydrates eagerly on page load | Speed (user-visible) | Medium–high | Low per page |
| 7 | Frontend has no shared data-fetching hook: ~65 files re-implement the same try/ok/catch/finally block around `authenticated*`; 5 components exceed the repo's own 1,000-line limit; `Export.tsx`/`ImportExport.tsx` are drifting forks | Complexity | High (frontend maintainability) | Medium, incremental |
| 8 | `VulnerabilityService` keeps three overlapping "get current vulnerabilities" implementations; the legacy path fetches **unpaged** (`Pageable.UNPAGED`, `VulnerabilityService.kt:370`) then filters/paginates in Kotlin | Complexity + speed | High on the hottest view | Medium |
| 9 | `NormMappingService.suggestAndApplyMappings` (`:435`) holds a DB transaction across a blocking outbound AI-service HTTP call; `WorkgroupService` does per-element SELECT+UPDATE loops inside transactions (`:201–208` et al.) | Speed + robustness | Medium | Low |
| 10 | ~12 near-duplicate `-aws` shell-script forks and 13 copy-pasted helper functions across 66 scripts with only 2 shared libs; `owasp-check.sh --all` and the coverage report do quadratic work | Complexity + speed (tooling) | Medium | Medium |

---

## 2. Complexity drivers

### 2.1 Backend (`src/backendng`)

#### C-B1. Controllers hand-roll error handling instead of using the existing handlers — the largest mechanical duplication in the repo

The `exception/` package already contains five central handlers (`GenericExceptionHandler`, `ValidationExceptionHandler`, `AccessDeniedExceptionHandler`, `HibernateConstraintViolationHandler`, `HttpClientExceptionHandler`, 429 lines total) that implement the A05 contract: generic message to the client, detail to the log. Yet the controller layer contains ~349 local `catch (e: Exception)` blocks and ~1,000 `HttpResponse.badRequest/serverError/notFound(...)` construction sites. Worst offenders: `AlignmentController` (40 catches), `VulnerabilityManagementController` (32), `AssetController` (28), `VulnerabilityExceptionRequestController` (27), `WorkgroupController` (24, and 84 `HttpResponse.` sites), `ImportController` (23), `CrowdStrikeController` (23).

**Suggestion.** Migrate controller-by-controller: let domain exceptions (`IllegalArgumentException`, access-denied, constraint violations) propagate to the central handlers; keep a local catch only where the endpoint genuinely maps a specific failure to a specific response. Add typed exceptions where the local catch exists only to pick a status code. Do this opportunistically — whenever a controller is touched — rather than as one big-bang rewrite.

**Robustness guardrail.** The central handlers are a *security* control (A05: no stack trace / SQL / internal path to the client). Migration must preserve exactly that: audit each removed catch to confirm the replacement handler emits a generic body and logs the detail with actor+target+outcome (A09). Never migrate by loosening — a catch that today swallows-and-genericizes must not become an unhandled 500 with a leaked message.

#### C-B2. `VulnerabilityService` (1,895 lines) carries three parallel "current vulnerabilities" implementations

`getCurrentVulnerabilities` (`:347`, legacy), `getCurrentVulnerabilitiesOptimized` (`:531`), and `getCurrentVulnerabilitiesByStatusSql` (`:740`, ~198 lines) coexist, plus hand-built dynamic JPQL with **twin** StringBuilders (`jpqlBase`+`countBase`, `:1108–1161`, appending identical predicates twice) and a third native-SQL rendering of the same predicates in snake_case (`:1261–1301`).

**Suggestion.** Pick the SQL-status path as canonical (it is the one that reads the materialized `excepted` flag — see `VulnQuerySql.NOT_EXCEPTED`), route the remaining callers to it, and delete the legacy path (which is also a perf bug, see S-B2). Extract the predicate-building into one function that renders both the data and count queries from the same predicate list, so a filter can never exist in one and not the other.

**Robustness guardrail.** All three paths must keep flowing through `AssetFilterService.getAccessibleAssetIds()` for scoped users — consolidation must not inline or "optimize away" the auth boundary. The `excepted`-flag semantics are pinned by `ExceptedFlagSqlAgreementIntegrationTest`; run it after any predicate change.

#### C-B3. `repository/VulnerabilityRepository.kt` (1,942 lines): the query-family explosion is half-fixed — finish it

93 query methods; every statistics query exists as a `ForAll`/`ForAssets` twin. `VulnQuerySql.kt` already demonstrates the fix (shared SELECT/TAIL fragments, scope fragment interpolated, compile-time constants) and its header comment is the best-written perf post-mortem in the repo. But only the statistics families use it; the long tail of derived finders (`findByVulnerabilityIdInAndAssetOsVersionContainingIgnoreCase`, …) grew one-per-filter-combination.

**Suggestion.** Extend the `VulnQuerySql` fragment pattern to the remaining twinned families. For the derived-finder tail, prefer one filtered query with nullable parameters (the pattern `findLatestVulnerabilitiesPerAssetWithFilters` already uses) over adding another name-encoded permutation.

**Robustness guardrail.** Keep everything bound (`:name`) — the fragment constants interpolate *SQL structure* at compile time, never request values (A03). Any new shared fragment must keep that property.

#### C-B4. Repetitive-boilerplate god files

- **`ConfigBundleService.kt` (1,083)** — 18 near-identical per-entity `export*`/`import*` methods. Suggestion: a small `BundleSection<E, DTO>` abstraction (repository accessor + toDto + fromDto) and one generic export/import loop; each entity becomes ~10 declarative lines. Guardrail: keep the current per-entity validation and the "never export secrets" behavior byte-identical; the credentials fields must keep flowing through `EncryptedStringConverter` and never serialize.
- **`RequirementController.kt` (1,755)** — 10 near-identical export handlers across `{docx,xlsx} × {all,usecase} × {plain,translated} × {GET,POST}`. Suggestion: one parameter object (format, scope, language) resolved by thin route methods delegating to a single export function. Guardrail: route paths, response headers and `@Secured` values are a public contract (the `extensions/` clients and the public download endpoints) — the consolidation must not rename any path or change any role set; verify with the CLAUDE.md §Extension Clients grep.
- **`ImportController.kt` (1,059)** — inlines a full Excel parser (`:223–446`) while injecting 13 dedicated import services; `AlignmentController.importReviewsFromExcel` (`:767`, ~236 lines) does the same. Suggestion: move parsing into the corresponding import services beside the other 11 parsers. Guardrail: `ImportController.validateFile` (size/extension/content-type/empty checks, A08) stays in front of every parse path, unchanged.

#### C-B5. Four idioms for one problem: Micronaut self-invocation

The codebase works around AOP self-invocation four different ways: (a) `Provider<Self>` self-proxy (6 services), (b) a deliberately split bean (`AsyncExceptionRecompute`), (c) raw `CompletableFuture.supplyAsync` (`TranslationService.kt:154`), (d) `@Named(TaskExecutors.IO) ExecutorService` (`ExportJobService.kt:60`). Additionally `CrowdStrikeVulnerabilityImportService` implements its **own** `isDeadlockException`/`withDeadlockRetry` (`:90–137`) parallel to the shared `service/DeadlockRetry.kt`, and `VulnerabilityManagementController.kt:705` calls `DeadlockRetry.withRetry` from the web layer.

**Suggestion.** Document one blessed idiom (the split-bean approach is the least magical) in `docs/ARCHITECTURE.md` and converge opportunistically. Consolidate the two deadlock-retry implementations into `DeadlockRetry` — the CrowdStrike variant's jittered backoff is the better algorithm; keep it and its excellent comment as the shared one. Move the controller-level retry into the service it wraps.

**Robustness guardrail.** Each convergence must preserve the transactional boundary semantics the workaround exists for: the retried lambda must still enter through the AOP proxy so every attempt is a fresh transaction (the CrowdStrike comment at `:108–110` states this precisely — keep that comment on the shared method). The differing retry counts/backoffs per call site were tuned against real deadlock storms; unify the mechanism, not necessarily the parameters.

#### C-B6. `AssetFilterService` dual implementation and documented dead code

The central auth boundary carries two parallel scoped-access implementations behind `memoryConfig.lazyLoadingEnabled` (`getAccessibleAssetsUnified` `:126` vs `getAccessibleAssetsMultiQuery` `:138`) — doubling the reasoning surface of every access-control question — plus `getAccessibleVulnerabilities` (`:232`), which its own 10-line warning comment (`:218–227`) declares unused and unsafe, ending "Otherwise this method should simply be deleted."

**Suggestion.** (1) Delete `getAccessibleVulnerabilities` — the comment already authorizes it and grep confirms no callers. (2) Decide the feature flag: if `lazyLoadingEnabled` has been stable in production, remove the multi-query fallback; if not yet trusted, set a review date. A flag without an expiry becomes permanent double-maintenance on the most security-critical file in the repo.

**Robustness guardrail.** This file **is** the auth boundary (CLAUDE.md §Unified Asset Access). Removing the fallback path requires the unified query (`AssetRepository.findAccessibleAssets`) to be proven equivalent for all 10 access rules — add/extend an integration test that seeds one asset per access rule and asserts identical result sets from both paths *before* deleting one. Preserve the documented deliberate asymmetry (`getScopedAccessibleAssetIds` short-circuits for ADMIN only, not SECCHAMPION).

#### C-B7. Copy-pasted role lookups and CVE-list parsing

- `userRepository.findAll().filter { it.hasRole(...) }` appears in at least 8 services (`UserService.kt:188`, `AdminNotificationService.kt:186,:266`, `CrowdStrikeCleanupNotificationService.kt:25`, `ExceptionRequestNotificationService.kt:177,:742`, `ConfigBundleService.kt:322`, `UserDeletionValidator`, …). One `findByRole`/`countByRole` repository query replaces all of them (also a speed fix, S-B4).
- `VulnerabilityExceptionService.kt` parses the same comma-separated CVE list five times (`:441,:453,:465,:477,:518`) — extract one `parseCveList(subjectValue)`.

#### C-B8. Notification/statistics service sprawl

15 notification-related services and 8 statistics/dashboard services with overlapping recipient-resolution and aggregate queries. Not urgent, but when any is next touched, extract a shared `AdminRecipientResolver` (fixing C-B7 at the same time) rather than adding a 16th copy. The `ChatNotificationEvent` pattern (one enum constant + one publish call) is the model to imitate — it is the repo's own proof that this layer can be declarative.

### 2.2 Frontend (`src/frontend`)

#### C-F1. No shared data-fetching layer — 65 files of copy-pasted try/ok/catch/finally

`utils/auth.ts:89–179` returns raw `Response` objects, so every call site re-implements the same ~12-line block (see three consecutive near-identical copies at `WorkgroupManagement.tsx:131–173`). A `services/` layer exists (50 files) but is bypassed at will — `WorkgroupManagement.tsx` calls `/api/workgroups` raw while `services/workgroupApi.ts` exists. Three HTTP conventions coexist: `authenticated*` (65 files), axios (16), raw `fetch` (13).

**Suggestion.** Add one small `useApiResource`/`apiJson<T>` helper that wraps `authenticated*` and returns parsed data or a typed error, then migrate call sites opportunistically. Fold the 6 axios-importing islands onto it so axios can eventually retire from island bundles (it stays in `utils/csrf.ts`-driven services until then). Do **not** attempt a big-bang migration.

**Robustness guardrail.** The `authenticated*` helpers and axios `withCredentials` carry the HttpOnly-cookie auth contract (A02/A07). The wrapper must sit *on top of* `utils/auth.ts`, not replace it, and must not introduce any token handling in JS.

#### C-F2. Monolithic management components — 5 files over the repo's own 1,000-line limit

`RequirementManagement.tsx` (1,491), `CurrentVulnerabilitiesTable.tsx` (1,418), `UserManagement.tsx` (1,411), `WorkgroupManagement.tsx` (1,204), `CrowdStrikeVulnerabilityLookup.tsx` (1,193); `AssetManagement.tsx` (1,027) and `ApplicationRegister.tsx` (1,005) sit at the line. The shape is uniform: data layer + filter state + table + every modal inline (WorkgroupManagement holds three inline modals totaling ~640 lines; the table doesn't start until `:1082`).

**Suggestion.** Extract inline modals into sibling components — `WorkgroupAccountsModal`/`WorkgroupDomainsModal` already prove the pattern works and is low-risk. Target: each management screen = list component + N modal components + one extracted pure-logic `.ts` module (which is also what makes the node:test tier able to test it, per `docs/TESTING.md` §Frontend).

**Robustness guardrail.** UI role checks are UX, not the boundary (Principle 2) — extraction must keep every existing role-gated render intact, and `/e2ejs` (both roles) is the regression gate for any component split.

#### C-F3. Drifting forks and dead code

- **`Export.tsx` (820) vs `ImportExport.tsx` (759)**: four export handlers duplicated with a 33-line drift — one side migrated to `requestWordExport()`, the other still calls `authenticatedFetch` directly (`Export.tsx:175` vs `ImportExport.tsx:171`). Extract the handlers into a shared module before the drift becomes behavioral.
- **`utils/api-config.ts` (261 lines)**: zero importers (verified). Delete it.
- **Layout divergence**: `Layout.astro` bundles Bootstrap from npm; `BaseLayout.astro` loads it from `cdn.jsdelivr.net` (plus the full JS bundle); `login/success.astro` hand-rolls a third copy, CDN again. Both layouts hardcode near-identical CSP `<meta>` tags whitelisting the CDN that only one of them needs. Converge on the npm-bundled path and tighten the CSP accordingly. Guardrail: CSP changes go through the §A05 rule — narrow only, and re-run `/e2ejs` on every page since a too-tight CSP is a runtime-only failure.
- **`Sidebar.tsx` (850)**: ~83 hardcoded nav entries with inlined role predicates, rendered on every page. Convert to a data-driven nav table (entry = {path, label, icon, roles}) — this also makes the role gating auditable at a glance.

### 2.3 CLI (`src/cli`)

#### C-C1. `SecmanCli.kt` (1,527 lines) fights Picocli instead of using it — the largest single reduction available in the repo

Verified: a 420-line manual dispatcher with 4 hand-rolled `while (i < args.size)` argument-parse loops (`:70,:103,:166,:227`) coexisting with `PicocliRunner.run(...)` for other commands (two different `--help`/error behaviors depending on the branch), plus `commandHelpTexts` (`:572` onward, ~930 lines, 61% of the file) hand-duplicating the `@Option(description=…)` text Picocli generates for free — with no mechanism keeping them in sync.

**Suggestion.** Route every command through Picocli's own dispatcher (a top-level `@Command(subcommands=[...])`), delete the manual loops and the help-text map. Net deletion of roughly 1,300 lines with *better* behavior (consistent `--help`, unknown-option errors, completion).

**Robustness guardrail.** The CLI's flags are a user-facing contract exercised by `scripts/` and the E2E drivers — inventory every flag the manual loops accept (including undocumented tolerances like `toIntOrNull() ?: 1000` defaults) and pin them in CLI tests before switching dispatchers. `./scripts/import.sh` and the `/importtest`, `/aws-account-workgroup-import` skills are the acceptance gates.

#### C-C2. No `@Mixin`; 4 `authenticate()` implementations; 6 `@Client` injection points

28 of 50 commands redeclare `--username/--password/--backend-url`; 39 files carry a near-identical env-fallback resolver; `authenticate()` exists in `CliHttpClient.kt:28` (canonical), `UserMappingCliService.kt:108`, `RequirementCliService`, and privately in `DeduplicateVulnerabilitiesCommand.kt:188`.

**Suggestion.** One `@Mixin class BackendAuthOptions` (options + env fallback + a `login(CliHttpClient)` method); collapse the other three `authenticate()` copies onto `CliHttpClient`. Move the nine catch-branch diagnostic block out of `CliHttpClient.authenticate` into a shared connection-error reporter.

**Robustness guardrail.** Credentials keep coming from options/env sourced via `pass-cli` — the mixin must not add any credential caching to disk. Error messages must keep the A07 property of not disclosing whether an account exists.

#### C-C3. `ImportCommand` (560) vs `ImportS3Command` (713) fork

Eight shared option names and duplicated post-import reporting; the only real difference is the file source. Extract the shared options into an `@Mixin` and the reporting into one function; `ImportS3Command` becomes "download via `S3DownloadService`, then delegate".

### 2.4 Relay (`src/relay`)

The relay is the healthiest large module — stdlib-only by contract, small interfaces, bounded caches. Two structural notes, no urgent action:

- **`cmd/secman-relay/main.go:61 run()` (176 lines)** wires everything in one function. Splitting into `buildIngestPlane/buildApiPlane/buildOpsPlane` helpers would localize changes; purely mechanical.
- **`internal/config/config.go` (763 lines)** — 25 env readers + three validators. Fine as-is; resist the temptation to add a config library (the zero-dependency contract is deliberate — see §5).

### 2.5 Scripts and build

#### C-S1. ~12 `-aws` script forks and 13 copy-pasted helpers, with only 2 shared libs

`e2e-test.sh`/`e2e-testaws.sh`, `import.sh`/`importaws.sh`, `startbackenddev.sh`/`startbackenddevaws.sh`, … differ essentially in which secrets they source, yet are full forks. Across `scripts/` + `scripts/test/`, `cleanup()` ×8, `mcp_call()` ×7, `check_prerequisites()` ×7, `setup_testbed()` ×5, `admin_login()` ×4 etc. are copy-pasted.

**Suggestion.** Grow `scripts/lib/` deliberately: `secman-env.sh` (pass-cli sourcing with an `--aws` mode), `secman-http.sh` (`api()`, `admin_login()`, `mcp_call()`, `mcp_payload()`), `secman-backend.sh` (start/stop/liveness). Convert one script pair per change; never all at once.

**Robustness guardrail.** These scripts are the E2E harness — a regression here silently weakens every gate. Convert a pair only when its E2E skill is then run to green (`/e2evulnexception` for the vuln pair, etc.). Keep the canonical entry points (`startbackenddev.sh` etc.) stable in name and behavior — CLAUDE.md and the skills reference them by path.

#### C-S2. `test-e2e-vuln-exception-full.sh` (1,833 lines, 15 functions, 173 `jq` spawns)

Long function bodies make failures hard to localize. When next touched, split phases into functions the way `test-e2e-account-onboarding.sh` does, and batch related assertions into single `jq` programs (also a speed win). Not worth a standalone rewrite.

---

## 3. Speed issues

### 3.1 Backend

#### S-B1. `AssetFilterService`: `findAll()` on the admin path, fetch-then-filter on scans — the highest-leverage runtime fix

Verified sites:
- `:78`, `:86`, `:113` — `assetRepository.findAll()` (twice materializing full entities just to extract ids) on **every** ADMIN/SECCHAMPION request that resolves asset access.
- `:290` — `scanRepository.findAll().filter { accessibleUsernames.contains(it.uploadedBy) }` — live fetch-then-filter for non-admin scan listing, preceded by a per-workgroup user query loop (`:282–284`).

**Suggestion.** (1) For the id-only paths (`:86`, `:113`), add `assetRepository.findAllIds()` (a projection query) — same semantics, no entity materialization. (2) For `:78`, callers that only need ids should call `getAccessibleAssetIds`; audit callers of `getAccessibleAssets` for ones that immediately `.map { it.id }`. (3) Replace `:290` with `scanRepository.findByUploadedByInOrderByScanDateDesc(usernames)` and the `:282` loop with one `findByWorkgroupsIdIn(ids)` query.

**Robustness guardrail.** The *shape* of the boundary must not change: same method names, same 10 access rules, same ADMIN/SECCHAMPION short-circuit semantics and the documented asymmetry. Add the projection queries beside the existing ones; do not alter `findAccessibleAssets`. The SQL pre-filter rule stands: these queries are the auth boundary only because they live inside `AssetFilterService`.

#### S-B2. `VulnerabilityService.getCurrentVulnerabilities` fetches unpaged then filters in memory

Verified at `:370`: `Pageable.UNPAGED` with the comment "Get all for exception status filtering", then per-row exception matching in Kotlin. On the current data scale (~1.8M vulnerability rows, per the `VulnQuerySql` history) any caller of the legacy path pays a full materialization. The fix is C-B2: the `excepted` flag is already materialized precisely so this filter can run in SQL — route callers to the SQL-status path and delete the legacy one. Same pattern at `:1845` and in `VulnerabilityPersistenceHelper.kt:34`, `ExceptionRequestStatisticsService.kt:95`, `VulnerabilityExceptionRequestService.kt:710`, `ExceptionRequestExportService.kt:139` — each needs a per-site look, prioritizing ones reachable from interactive endpoints.

#### S-B3. Transactions held across slow work

- **Confirmed:** `NormMappingService.suggestAndApplyMappings` (`:435`, `@Transactional`) reaches `callOpenRouter` (`:160`, blocking HTTP to an AI service with multi-second latency) — a DB transaction and connection held across an external AI call, plus a per-id `findById` loop (`:442`). Fix: hoist the suggestion phase (HTTP) out of the transaction; make only `applyMappings` transactional — the code already has that method (`:626`). Guardrail: keep apply atomic per request; partial application on AI failure must remain impossible.
- **Pattern risk:** 11 services send email inside `@Transactional` methods (`AlignmentEmailService`, `UserVulnerabilityNotificationService`, `AccountOnboardingService`, …). SMTP timeouts then hold DB connections. The repo already has the right pattern — `ChatNotificationService` dispatch is deliberately non-`@Transactional` — and the right migration is event-publish-then-listener (`@EventListener @Async` post-commit), which several paths already use. Migrate opportunistically, highest-volume senders first (`UserVulnerabilityNotificationService.sendUserVulnerabilityNotifications`, the repo's longest method at ~273 lines, is both a complexity and a speed target).
- **Positive finding, keep as reference:** `OAuthService.handleCallback` is *deliberately* non-transactional with REQUIRES_NEW micro-transactions around DB steps, documented at `:286–288` and `:381` — this is the pattern to copy, and it should be named in `docs/ARCHITECTURE.md` §Patterns.
- `CrowdStrikeVulnerabilityService` mixes Falcon HTTP (`queryByHostname`, non-transactional) and `@Transactional saveToDatabase` with `PESSIMISTIC_WRITE` (`:908`) in one class. Verified: the HTTP round-trip is **not** inside the transaction today. The risk is architectural — nothing but convention prevents a future caller from composing them inside one transaction. Splitting query-side and persistence-side into separate classes (the boundary the 8-service CrowdStrike cluster already gestures at) would make the mistake unrepresentable.

#### S-B4. N+1 and per-row loops (verified samples)

- `WorkgroupService.assignUsersToWorkgroup` (`:201–208`): per-user SELECT (`findByIdWithWorkgroups`) + UPDATE inside one transaction; same shape at `:255`, `:283`, `:337`. For bulk assignment this is 2N statements where 2–3 would do (one membership-table batch insert). Guardrail: keep the not-found error per id — batch first, then report missing ids explicitly rather than silently skipping.
- `userRepository.findAll().filter { role }` in ≥8 services (C-B7) — full user-table scans to find admins, on notification paths that run on schedules. Replace with `findByRole`.
- `AssetImportService.kt:266–271`: one `findByNameIgnoreCase` per workgroup name per imported row — hoist a name→workgroup map before the row loop.
- `ReleaseService.kt:147,:293`: `riskAssessmentRepository.findAll().filter { it.lockedRelease?.id == releaseId }` — in-memory FK filter with lazy-load risk per row; replace with `findByLockedReleaseId`.

#### S-B5. Scheduling load profile is invisible

14 `@Scheduled` methods spread across 5 schedulers and 9 services, including a 15s sweeper (`MaterializedViewRefreshService:499`) and a 30s debounce (`ImportCompletionNotifier:91`). No single place shows what runs when. Suggestion: a one-page table in `docs/ARCHITECTURE.md` (job, interval, tables touched, expected duration) — zero code risk, large debugging payoff when DB contention appears. The `DeadlockRetry` call-site comments about 180s lock-wait windows show contention is a lived reality here.

### 3.2 Frontend

#### S-F1. Landing dashboard: up to 9 serial round-trips (verified, `HomeStatisticsDashboard.tsx:125–240`)

Each card's fetch is `await`ed in sequence; total time is the sum of all enabled cards' latencies. The per-card `try/catch` isolation is deliberate and documented in-code ("either endpoint failing must degrade only its own card") — keep it.

**Suggestion.** Wrap each card's fetch in an async function returning `{key, value|null}` and run them with `Promise.allSettled`, preserving per-card degradation exactly. Latency drops from Σ to max. Lowest-effort, highest-visibility speed fix in the repo.

#### S-F2. Eager hydration: 89 `client:load`, 2 `client:visible` (verified)

Every 1,000+-line management island parses and hydrates before first paint. `Sidebar` is already `client:visible` with a comment explaining why — the same reasoning applies to below-the-fold and modal-heavy islands.

**Suggestion.** Audit per page: tables the user immediately interacts with stay `client:load`; secondary islands (below-the-fold charts, admin panels reached by tab) become `client:visible` or `client:idle`. Verify each page with `/e2ejs` — hydration-timing changes are exactly the class of bug it exists to catch.

#### S-F3. Serial per-row HTTP loops

- `MoveWorkgroupModal.tsx:45–76` (verified): recursive depth-first tree load, one awaited request per workgroup node. Suggestion: a `GET /api/workgroups/tree` (or `?all=true`) endpoint returning the tree in one query — the backend already has ancestor/descendant queries in `WorkgroupRepository`. Guardrail: the endpoint must filter through the caller's workgroup visibility same as the per-node endpoints do.
- `ResponseInterface.tsx:183–200`: one POST per requirement on questionnaire submit; `AssessmentPerformance.tsx:314–320`: one POST per non-compliant requirement; `ApplicationRegister.tsx:416–431`: two serial calls per imported row. Each wants a bulk endpoint or at minimum bounded-concurrency `Promise.all`. Guardrail for bulk endpoints: server-side validation per element with a per-element result list (the `ImportResult` pattern), never all-or-nothing silent failure.

#### S-F4. Whole-table fetches for dropdowns

Full `GET /api/assets` in 6 components (`AssetManagement.tsx:109`, `ApplicationRegister.tsx:200`, `DemandManagement.tsx:157`, `RiskAssessmentManagement.tsx:218`, `RiskManagement.tsx:61`, `WorkgroupManagement.tsx:166`) and full `GET /api/users` in 4 — several only to populate a `<select>`. `RequirementManagement.tsx:167` defeats pagination with `?pageSize=10000`. Suggestion: a typeahead endpoint (`?q=&limit=20`) for pick-an-asset flows — `ProductAutocomplete` already implements the client half (trim its `limit: 1000` while at it). Guardrail: server-side the search must go through `AssetFilterService` scoping like every other asset read.

#### S-F5. Small verified defects

- **`McpDashboard.tsx:50–60` interval leak (verified):** the cleanup closes over the `refreshInterval` state variable, which is still `null` when the `[]`-dep effect mounts, so `clearInterval` never runs; the 30s poll survives unmount. Fix: clear the local `interval` const in the cleanup. One line.
- **`AssetManagement.tsx` recompute-per-render (verified pattern):** full-array `.filter` (`:431`) and `[...new Set(...)].sort()` option-building (`:791`, `:805`) on every render with no `useMemo` in the file. Memoize on `[assets, filters]`.

### 3.3 CLI

- `UserMappingCliService.individualCreateFallback` (`:889–926`): serial POST per entry on the bulk-import fallback — thousands of round-trips — and it swallows HTTP 400 as "duplicate/skipped", masking real validation errors. Fix both: chunked sub-batches (copy the pattern from `VulnerabilityStorageService.kt:304–430`, the best-engineered code in the CLI — chunking, fixed thread pool, adaptive 413 splitting, jittered retry) and classify 400 bodies before counting them as skips.
- `PortScanCliService.kt:143–145`: reads the process's stdout then stderr sequentially with `readText()` — can deadlock when a chatty nmap fills the stderr pipe while stdout is being drained. Drain both concurrently (or redirect stderr to a file).

### 3.4 Relay

- **`store.Section`/`store.Sections`**: full defensive copy of every section payload on every read; `Sections` copies all visible sections per `GET /api/v1/status`, up to 64 sections within a 4 MiB body budget under fleet polling. Suggestion: an immutable versioned snapshot (pointer-swap on `Put`, readers share the frozen buffers) preserves the immutability guarantee without per-request copies. Guardrail: the copy exists to guarantee handlers can never mutate stored state — the replacement must keep that property structurally (freeze-on-publish), not by convention.
- **`registry.persistLocked` (`registry.go:679`)**: whole-registry `json.MarshalIndent` + temp-file + fsync + rename **under the write lock**, reachable from `TouchLastSeen` on every authenticated request (throttled to once/minute/device — verified `:594–600`). With many devices the throttles interleave and reads serialize behind fsyncs. Suggestion: mark-dirty in `TouchLastSeen` and let the existing 1-minute maintenance goroutine do a single persist outside the read path. Guardrail: enrollment/revocation/control changes must keep their synchronous persist — durability of security-relevant state before acknowledging the request is the point; only the `lastSeenAt` timestamp is safe to defer (bounded loss: one minute of liveness metadata).

### 3.5 Build & tooling

- **`ksp.incremental=false`** (verified in `gradle.properties` with its documenting comment): every backend change re-processes all 779 files, and is why the daemon needs 5.6 GiB. The constraint is upstream (KSP `PSI has changed` crash; 2.3.11 fixes it but OOMs). Action: track KSP releases; re-test incremental + the 2.3.11 memory fix on each bump — this single flag is the biggest dev-loop lever. Guardrail: keep the pinning comments; they are what saved days of debugging last time.
- **`owasp-check.sh` `file_rule`** (`:261`): O(files × added_lines) with ~6 process spawns per file per rule — quadratic in `--all` mode. Suggestion: pre-index `added.lines` by file into a temp directory once, then each rule reads only its file's slice; batch the per-file greps into one awk pass. Guardrail: the gate's self-test (`scripts/test/owasp-check-test.sh`) must pass before and after — a faster gate that misses a rule is a security regression, and the macOS-awk comment block (`:84–116`) documents exactly how that happens silently.
- **`test-coverage-report.sh`**: ~270 `grep`s over a 1.35 MB corpus per run. Build one name→hit index with a single `grep -oF` pass. Cosmetic; fix opportunistically.

---

## 4. Quick wins (low risk, do first)

1. Fix the `McpDashboard` interval leak (one line). *(S-F5)*
2. Delete `utils/api-config.ts` (261 dead lines, zero importers). *(C-F3)*
3. Delete `AssetFilterService.getAccessibleVulnerabilities` (its own comment says to). *(C-B6)*
4. `Promise.allSettled` the home dashboard, preserving per-card try/catch. *(S-F1)*
5. Add `findByRole`/`countByRole` and replace the 8 `findAll().filter{role}` sites. *(C-B7/S-B4)*
6. Add `findAllIds()` projection for the `AssetFilterService` id-only admin paths. *(S-B1)*
7. Hoist the OpenRouter call out of `suggestAndApplyMappings`' transaction. *(S-B3)*
8. Extract `parseCveList` in `VulnerabilityExceptionService` (5 copies → 1). *(C-B7)*
9. Consolidate the two deadlock-retry implementations into `DeadlockRetry`. *(C-B5)*
10. Memoize `AssetManagement` filter/options computation. *(S-F5)*

Each is verifiable with the fast tier (`/testsuite`) plus the two mandatory E2E gates for the frontend items.

## 5. Deliberate non-recommendations — load-bearing "complexity" to leave alone

These look like findings but are robustness by design. Documented here so a future cleanup pass does not "fix" them:

- **Boot-fail validators** (`JwtSigningValidator`, `DatabaseCredentialValidator`, `DatasourceUrlValidator`): failing startup on weak config is the feature (A02).
- **Transactional-replace import without JPA cascade**: the manual `deleteByAssetId()` + `saveAll` with `cascade` forbidden on `Asset.vulnerabilities` prevented a real 166,812→1,819 row-loss incident. Any "simplification" toward cascade/orphanRemoval is a regression.
- **Relay's hand-rolled ACME/JWS/JWT/rate-limiter and its 763-line config file**: the zero-dependency contract is a stated supply-chain decision for a DMZ component. Replacing hand-rolled protocol code with libraries would reduce line count and *increase* risk. Same for `src/clinotify` (stdlib-only by contract).
- **`store.Section`'s defensive copy**: the immutability guarantee is correct; only the *mechanism* may change (S-3.4), never the guarantee.
- **`OAuthService`'s non-transactional callback with REQUIRES_NEW micro-transactions**: intentional and documented — it prevents a race with the provider redirect. It is the model, not a finding.
- **`ExportJobService`'s REQUIRES_NEW-per-progress-tick**: each progress update committing independently is what makes job progress observable while the job runs and survives a crash mid-export.
- **Per-card `try/catch` in the home dashboard**: keep under parallelization (S-F1).
- **The `-aws` variants' existence** (as a mode): the two-secret-set split is real; the finding is the *forking*, not the mode. Consolidate into flags, don't delete the AWS path.
- **`McpToolPermissions` LISTING/CALLING as two maps**: the redundancy is the fail-closed design; the residual risk (silent divergence) is best addressed with a test asserting expected membership, not by merging the maps.
- **E2E scripts' verbosity per se**: explicit phase-by-phase assertions are the value; C-S2 targets only structure and process-spawn count.

## 6. Suggested sequencing

**Phase 1 — quick wins (one PR each or small batches).** Items in §4. No architectural risk; every one gated by `/testsuite`, frontend ones additionally by `/e2ejs` + `/e2evulnexception`.

**Phase 2 — hot-path performance.** S-B1 (AssetFilterService projections + scan query), S-B2/C-B2 (retire the legacy vulnerability path), S-B3 (email-out-of-transaction migration, top senders first), S-F2 (hydration audit), S-F3 (workgroup tree endpoint). Each needs its integration-test-first guardrail from the finding text.

**Phase 3 — structural de-duplication (opportunistic, per-touch).** C-B1 (controller error handling, per controller), C-F1 (fetch helper, per component), C-F2 (modal extraction, per screen), C-C1/C-C2 (CLI Picocli consolidation — this one is worth a dedicated PR given the 1,300-line deletion), C-B4 (ConfigBundle/RequirementController), C-S1 (script libs, one pair per change).

**Phase 4 — tooling.** owasp-check indexing (self-test-gated), coverage-report indexing, KSP re-evaluation on each upstream release.

Throughout: a change is complete per CLAUDE.md Principle 5/5a/7 — `./gradlew build` + clean backend start, `npm ci && npm run build` for frontend edits, both E2E gates for anything under `src/`, and `./scripts/owasp-check.sh` on every diff.
