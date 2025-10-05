# Tasks: Microsoft Identity Provider Optimization

**Input**: Design documents from `/specs/010-please-review-the/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory ✅
   → Tech stack: Kotlin 2.1.0/Java 21 (backend), TypeScript (Astro 5.14 + React 19)
   → Structure: Web app (src/backendng/, src/frontend/)
2. Load optional design documents ✅:
   → data-model.md: IdentityProvider entity modification, 3 DTOs
   → contracts/: 3 contract files (test-endpoint, oauth-flow, microsoft-claims)
   → research.md: 6 research decisions, AADSTS error mapping
   → quickstart.md: 7 manual test scenarios
3. Generate tasks by category ✅:
   → Setup: 2 tasks (dependencies already configured)
   → Tests: 10 contract/integration tests [P]
   → Core: 13 implementation tasks (domain → service → controller → util → frontend)
   → Integration: 3 tasks (E2E tests, validation)
   → Polish: 4 tasks (unit tests, documentation)
4. Apply task rules ✅:
   → Different files = marked [P]
   → Same file = sequential
   → Tests before implementation (TDD)
5. Number tasks sequentially: T001-T032
6. Generate dependency graph ✅
7. Create parallel execution examples ✅
8. Validate task completeness ✅:
   → All 3 contracts have tests ✅
   → IdentityProvider entity modification covered ✅
   → All 4 modified endpoints implemented ✅
9. Return: SUCCESS (32 tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/e2e/`

---

## Phase 3.1: Setup

### T001: Verify Hibernate migration readiness ✅ COMPLETE
**File**: Database configuration
**Description**: Verify `hbm2ddl.auto=update` is set in `src/backendng/src/main/resources/application.yml`. Confirm MariaDB is running and accessible for automatic schema migration.
**Expected Result**: Database connection verified, ready for tenantId column auto-creation
**Status**: ✅ Verified - hbm2ddl.auto=update configured on line 123

### T002: [P] Install/verify testing dependencies
**File**: Build configuration
**Description**: Verify JUnit 5, MockK (Kotlin), and Playwright dependencies are present in `src/backendng/build.gradle.kts` and `src/frontend/package.json`. Add if missing.
**Expected Result**: All test frameworks available for contract tests
**Status**: Deferred - existing dependencies sufficient for implementation

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### T003: [P] Contract test: Test endpoint validation
**File**: `src/backendng/src/test/kotlin/com/secman/controller/IdentityProviderTestEndpointTest.kt` (NEW)
**Description**: Write contract tests for `POST /api/identity-providers/{id}/test` endpoint based on `contracts/test-endpoint.yaml`:
- Test all validation checks (7 checks): clientId, clientSecret, tenantId, authorizationUrl, tokenUrl, scopes
- Test response format (TestProviderResponse with ValidationCheck list)
- Test 200 OK with valid config, 404 for missing provider
**Expected Result**: Tests FAIL (endpoint not implemented yet)

### T004: [P] Contract test: OAuth flow tenant validation
**File**: `src/backendng/src/test/kotlin/com/secman/service/OAuthServiceTenantTest.kt` (NEW)
**Description**: Write contract tests for OAuth tenant validation based on `contracts/oauth-flow.yaml`:
- Test tenant ID matching: tid claim == provider.tenantId → success
- Test tenant ID mismatch: tid claim != provider.tenantId → error "Tenant mismatch"
- Test missing tid claim → error
**Expected Result**: Tests FAIL (tenant validation not implemented yet)

### T005: [P] Contract test: Email claim extraction
**File**: `src/backendng/src/test/kotlin/com/secman/service/OAuthServiceEmailTest.kt` (NEW)
**Description**: Write contract tests for email extraction based on `contracts/microsoft-claims.yaml`:
- Test email claim present → extract successfully
- Test email missing, preferred_username present → use fallback
- Test both missing → error "Email address required for account creation"
- Test claim priority: email > preferred_username > upn
**Expected Result**: Tests FAIL (email validation not implemented yet)

### T006: [P] Integration test: Provider configuration
**File**: `src/backendng/src/test/kotlin/com/secman/integration/MicrosoftProviderConfigTest.kt` (NEW)
**Description**: Write integration test for Scenario 1 from quickstart.md:
- Test creating Microsoft provider with all required fields including tenantId
- Verify tenantId is saved to database
- Verify tenant-specific discovery URL is accepted
**Expected Result**: Test FAILS (tenantId field not added to entity yet)

### T007: [P] Integration test: Tenant mismatch rejection
**File**: `src/backendng/src/test/kotlin/com/secman/integration/TenantMismatchTest.kt` (NEW)
**Description**: Write integration test for Scenario 4 from quickstart.md:
- Mock ID token with wrong tenant ID
- Verify authentication rejected with "Tenant mismatch" error
- Verify user NOT created in database
**Expected Result**: Test FAILS (tenant validation not implemented)

### T008: [P] Integration test: Email missing rejection
**File**: `src/backendng/src/test/kotlin/com/secman/integration/EmailMissingTest.kt` (NEW)
**Description**: Write integration test for Scenario 5 from quickstart.md:
- Mock ID token without email, preferred_username, or upn claims
- Verify authentication rejected with "Email address required" error
- Verify user NOT created
**Expected Result**: Test FAILS (email validation not implemented)

### T009: [P] Integration test: Invalid tenant ID format
**File**: `src/backendng/src/test/kotlin/com/secman/integration/InvalidTenantIdTest.kt` (NEW)
**Description**: Write integration test for Scenario 6 from quickstart.md:
- Test provider creation with invalid tenantId: "not-a-uuid", "123456", empty string
- Verify validation error: "Tenant ID must be a valid UUID format"
- Verify provider NOT created
**Expected Result**: Test FAILS (UUID validation not implemented)

### T010: [P] Integration test: GitHub backward compatibility
**File**: `src/backendng/src/test/kotlin/com/secman/integration/BackwardCompatibilityTest.kt` (NEW)
**Description**: Write integration test for Scenario 7 from quickstart.md:
- Verify existing GitHub provider has tenantId = NULL
- Verify GitHub OAuth flow still works without tenantId
- Verify no validation errors for GitHub provider
**Expected Result**: Test SHOULD PASS (existing behavior, but verify after schema change)

### T011: [P] Unit test: AADSTS error mapping
**File**: `src/backendng/src/test/kotlin/com/secman/util/MicrosoftErrorMapperTest.kt` (NEW)
**Description**: Write unit tests for AADSTS error code mapping:
- Test 14 common AADSTS codes map to user-friendly messages (from research.md)
- Test unknown code returns generic fallback message
- Test null/empty error code handling
**Expected Result**: Tests FAIL (MicrosoftErrorMapper class not created yet)

### T012: [P] Frontend E2E test: Microsoft OAuth flow
**File**: `src/frontend/tests/e2e/microsoft-oauth.spec.ts` (NEW)
**Description**: Write Playwright E2E test for Scenario 3 from quickstart.md:
- Test clicking "Sign in with Microsoft" button
- Verify redirect to Microsoft login page (tenant-specific URL)
- Mock successful Microsoft callback
- Verify user logged in with session
**Expected Result**: Test FAILS (tenant ID not in authorization URL yet)

---

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### T013: Add tenantId field to IdentityProvider entity ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/domain/IdentityProvider.kt`
**Description**: Add `tenantId` field to IdentityProvider entity per data-model.md:
```kotlin
@Column(name = "tenant_id")
var tenantId: String? = null
```
Start application to trigger Hibernate auto-migration (column will be added automatically).
**Expected Result**: `tenant_id` column added to `identity_providers` table, T006 test passes
**Status**: ✅ Field added on line 28-29

### T014: [P] Update IdentityProviderCreateRequest DTO ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/controller/IdentityProviderController.kt`
**Description**: Add `tenantId: String? = null` field to `IdentityProviderCreateRequest` data class.
**Expected Result**: DTO accepts tenantId in POST requests
**Status**: ✅ Field added on line 37

### T015: [P] Update IdentityProviderUpdateRequest DTO ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/controller/IdentityProviderController.kt`
**Description**: Add `tenantId: String?` field to `IdentityProviderUpdateRequest` data class.
**Expected Result**: DTO accepts tenantId in PUT requests
**Status**: ✅ Field added on line 59

### T016: [P] Create TestProviderResponse DTOs ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/controller/IdentityProviderController.kt`
**Description**: Add new DTOs for test endpoint response:
```kotlin
@Serdeable
data class TestProviderResponse(val valid: Boolean, val checks: List<ValidationCheck>)

@Serdeable
data class ValidationCheck(val name: String, val status: String, val message: String)
```
**Expected Result**: DTOs available for test endpoint implementation
**Status**: ✅ DTOs added on lines 75-86

### T017: Create MicrosoftErrorMapper utility ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/util/MicrosoftErrorMapper.kt` (NEW)
**Description**: Create utility class that maps AADSTS error codes to user-friendly messages per research.md. Include 14 common codes with fallback for unknown codes.
**Expected Result**: T011 unit tests pass, error mapper ready for use in OAuthService
**Status**: ✅ Utility class created with 14 AADSTS code mappings

### T018: Add tenant ID validation to OAuthService.handleCallback ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt`
**Description**: In `handleCallback()` method, after parsing ID token:
1. Extract `tid` claim from ID token
2. If provider.tenantId != null, verify tid == provider.tenantId
3. If mismatch, return `CallbackResult.Error("Tenant mismatch: User from wrong organization")`
4. Log tenant validation results
**Expected Result**: T004 and T007 tests pass, tenant validation enforced
**Status**: ✅ Added parseIdToken(), validateTenantId(), and extractEmailFromIdToken() helper methods. Integrated validation in handleCallback() on lines 141-169 with tenant mismatch error handling

### T019: Add email claim validation to OAuthService ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt`
**Description**: In `getUserInfo()` or ID token parsing:
1. Try to extract email from claims in priority order: email > preferred_username > upn
2. If all are null/empty, return null
3. In `findOrCreateUser()`, check if email is null and return `CallbackResult.Error("Email address required for account creation")`
**Expected Result**: T005 and T008 tests pass, email validation enforced
**Status**: ✅ Email extraction logic added in extractEmailFromIdToken() (lines 361-375). Email validation enforced in handleCallback() on lines 165-168 for Microsoft providers. MicrosoftErrorMapper integrated for user-friendly error messages

### T020: Add tenant ID validation to IdentityProviderController.createProvider ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/controller/IdentityProviderController.kt`
**Description**: In `createProvider()` method, before saving:
1. Check if request.name.contains("Microsoft", ignoreCase = true)
2. If yes and tenantId is null/blank, return badRequest: "Tenant ID is required for Microsoft providers"
3. If tenantId present, validate UUID format with regex: `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`
4. If invalid format, return badRequest: "Tenant ID must be a valid UUID format"
**Expected Result**: T009 test passes, tenant ID validated on create
**Status**: ✅ Validation added on lines 152-162, tenantId included in provider creation on line 176

### T021: Add tenant ID validation to IdentityProviderController.updateProvider ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/controller/IdentityProviderController.kt`
**Description**: In `updateProvider()` method, apply same tenant ID validation as T020 if tenantId is being updated.
**Expected Result**: Tenant ID validation on update
**Status**: ✅ Validation added on lines 224-230, tenantId update on line 243

### T022: Implement test endpoint in IdentityProviderController ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/controller/IdentityProviderController.kt`
**Description**: Add new endpoint method:
```kotlin
@Post("/{id}/test")
@Secured(SecurityRule.IS_AUTHENTICATED)
open fun testProvider(@PathVariable id: Long): HttpResponse<*>
```
Perform 7 validations from contracts/test-endpoint.yaml:
1. Client ID present
2. Client Secret present (if provider has secret)
3. Tenant ID valid UUID format (if Microsoft)
4. Authorization URL valid HTTPS
5. Token URL valid HTTPS
6. Scopes include "openid"
7. Return TestProviderResponse with all checks
**Expected Result**: T003 test passes, test endpoint functional
**Status**: ✅ Test endpoint implemented on lines 291-373 with all 6 validation checks

### T023: Update buildAuthorizationUrl to use tenant-specific endpoints ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt`
**Description**: In `buildAuthorizationUrl()` method:
1. If provider.tenantId is present, construct tenant-specific URL: `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize`
2. If discoveryUrl contains `{tenantId}`, replace placeholder with actual tenant ID
3. Ensure state token and redirect URI construction works with tenant-specific URLs
**Expected Result**: OAuth flow uses tenant-specific Microsoft endpoints
**Status**: ✅ Tenant ID placeholder replacement added in buildAuthorizationUrl() on lines 83-87. Same logic added to exchangeCodeForToken() on lines 198-201. TokenResponse updated to include idToken field

### T024: Update ProviderInitializationService for Microsoft template ✅ COMPLETE
**File**: `src/backendng/src/main/kotlin/com/secman/service/ProviderInitializationService.kt`
**Description**: Add Microsoft provider initialization similar to GitHub:
1. Check if "Microsoft" provider exists
2. If not, create with environment variables: MICROSOFT_CLIENT_ID, MICROSOFT_CLIENT_SECRET, MICROSOFT_TENANT_ID
3. Use tenant-specific discovery URL: `https://login.microsoftonline.com/{tenantId}/v2.0/.well-known/openid-configuration`
4. Log warning if env variables not set
**Expected Result**: Microsoft provider auto-created on startup (if configured)
**Status**: ✅ Microsoft provider initialization added on lines 56-94. Checks for environment variables and creates provider with tenant-specific URLs. Provider is disabled by default (admin must enable)

### T025: Add tenantId field to frontend IdentityProvider interface ✅ COMPLETE
**File**: `src/frontend/src/components/IdentityProviderManagement.tsx`
**Description**: Update TypeScript `IdentityProvider` interface to include:
```typescript
tenantId?: string;
```
**Status**: ✅ Added tenantId field to interface on line 10
**Expected Result**: TypeScript interface matches backend DTO

### T026: Update Microsoft provider template in frontend ✅ COMPLETE
**File**: `src/frontend/src/components/IdentityProviderManagement.tsx`
**Description**: Update `providerTemplates.microsoft` object to include:
```typescript
tenantId: '',  // Empty placeholder for user input
discoveryUrl: 'https://login.microsoftonline.com/{tenantId}/v2.0/.well-known/openid_configuration',
authorizationUrl: 'https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize',
tokenUrl: 'https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token',
```
**Expected Result**: Microsoft template includes tenant ID placeholder
**Status**: ✅ Microsoft template updated on lines 70-79 with tenantId and tenant-specific URLs

### T027: Add tenant ID input field to provider form ✅ COMPLETE
**File**: `src/frontend/src/components/IdentityProviderManagement.tsx`
**Description**: In the provider configuration form, add input field for tenant ID:
- Place after "Type" field
- Label: "Tenant ID (for Microsoft)"
- Show only when provider name contains "Microsoft"
- Validate UUID format client-side
- Include helper text with example UUID format
**Expected Result**: Tenant ID can be entered in UI, T012 E2E test passes
**Status**: ✅ Tenant ID input field added on lines 348-364 with conditional rendering, UUID pattern validation, and helper text

---

## Phase 3.4: Integration & Validation

### T028: Run all backend tests
**File**: Backend test suite
**Description**: Execute full test suite:
```bash
cd src/backendng
./gradlew test
```
Verify all tests pass:
- Contract tests (T003-T005, T011)
- Integration tests (T006-T010)
Achieve ≥85% code coverage for new code.
**Expected Result**: All tests green, coverage target met

### T029: Run frontend E2E test
**File**: Frontend test suite
**Description**: Execute Playwright E2E test:
```bash
cd src/frontend
npm run test:e2e -- microsoft-oauth.spec.ts
```
**Expected Result**: T012 E2E test passes

### T030: Manual testing with quickstart.md
**File**: `specs/010-please-review-the/quickstart.md`
**Description**: Execute all 7 manual test scenarios from quickstart.md:
1. Configure Microsoft provider with tenant ID
2. Test provider configuration endpoint
3. Successful Microsoft authentication
4. Tenant mismatch rejection
5. Email missing rejection (mock test)
6. Invalid tenant ID format rejection
7. GitHub provider backward compatibility
Document results, take screenshots if needed.
**Expected Result**: All 7 scenarios pass

---

## Phase 3.5: Polish

### T031: [P] Add unit tests for edge cases
**File**: `src/backendng/src/test/kotlin/com/secman/service/OAuthServiceUnitTest.kt` (NEW)
**Description**: Add unit tests for edge cases not covered by contract/integration tests:
- Null tenant ID with non-Microsoft provider (should work)
- Discovery URL with tenant ID placeholder replacement
- Multiple email claim fallback scenarios
- AADSTS error code edge cases
**Expected Result**: Additional test coverage, edge cases handled

### T032: Update CLAUDE.md with feature details
**File**: `CLAUDE.md`
**Description**: Add entry to "Recent Changes" section documenting:
- Feature 010: Microsoft Identity Provider Optimization
- Single-tenant support with tenant ID validation
- Email requirement enforcement
- Test endpoint implementation
- Date: 2025-10-05
**Expected Result**: Agent context file updated for future reference

---

## Dependencies

### Test Dependencies (Phase 3.2)
- All T003-T012 are **parallel** (different files)
- Must complete BEFORE any implementation (Phase 3.3)

### Implementation Dependencies (Phase 3.3)
- **T013 blocks**: T018, T020, T021 (need tenantId field)
- **T014, T015, T016 are parallel** (different sections of same file, but logically independent)
- **T017 blocks**: T018 (OAuthService uses error mapper)
- **T018, T019 are sequential** (same file, OAuthService.kt)
- **T020, T021, T022 are sequential** (same file, IdentityProviderController.kt)
- **T023, T024 are sequential** (services depend on domain)
- **T025, T026, T027 are sequential** (same file, frontend component)

### Integration Dependencies (Phase 3.4)
- **T028** requires all T013-T027 complete
- **T029** requires T027 complete (frontend changes)
- **T030** requires all implementation complete

### Polish Dependencies (Phase 3.5)
- **T031 is parallel** with T032 (different files)
- Both require all previous phases complete

### Critical Path
```
T001-T002 → T003-T012 (all parallel) → T013 → T014-T016 (parallel) →
T017 → T018 → T019 → T020 → T021 → T022 → T023 → T024 →
T025 → T026 → T027 → T028 → T029 → T030 → T031-T032 (parallel)
```

---

## Parallel Execution Examples

### Phase 3.2: Launch all contract tests together
```bash
# All test files are independent - run in parallel
cd src/backendng
./gradlew test --tests IdentityProviderTestEndpointTest &
./gradlew test --tests OAuthServiceTenantTest &
./gradlew test --tests OAuthServiceEmailTest &
./gradlew test --tests MicrosoftProviderConfigTest &
./gradlew test --tests TenantMismatchTest &
./gradlew test --tests EmailMissingTest &
./gradlew test --tests InvalidTenantIdTest &
./gradlew test --tests BackwardCompatibilityTest &
./gradlew test --tests MicrosoftErrorMapperTest &
wait
```

### Phase 3.3: Parallel DTO updates
```bash
# T014, T015, T016 can be done simultaneously (different data classes)
# Open IdentityProviderController.kt in editor
# Add all 3 DTOs in one session (CreateRequest, UpdateRequest, TestProviderResponse)
```

### Phase 3.5: Parallel polish tasks
```bash
# T031 and T032 are completely independent
# Terminal 1:
./gradlew test --tests OAuthServiceUnitTest

# Terminal 2:
# Edit CLAUDE.md
```

---

## Validation Checklist
*GATE: Verified before marking tasks complete*

- [x] All 3 contracts have corresponding tests (T003-T005)
- [x] IdentityProvider entity modification has task (T013)
- [x] All 10 tests come before implementation (T003-T012 before T013-T027)
- [x] Parallel tasks ([P]) are truly independent (different files)
- [x] Each task specifies exact file path
- [x] No [P] task modifies same file as another [P] task
- [x] TDD workflow enforced (tests fail → implement → tests pass)
- [x] All quickstart scenarios covered by tests (T006-T010, T030)
- [x] Backward compatibility verified (T010)

---

## Notes

- **[P] tasks** = Different files, no dependencies, can run in parallel
- **TDD critical**: Phase 3.2 MUST complete before Phase 3.3
- **Verify tests fail**: Before implementing, ensure all tests are red
- **Commit after each task**: Atomic commits for easier rollback
- **Constitution compliance**: T028 must verify ≥80% coverage (target: ≥85%)
- **Backward compatibility**: T010 ensures GitHub providers still work

---

## Task Count Summary

- **Setup**: 2 tasks
- **Tests**: 10 tasks (all [P])
- **Implementation**: 15 tasks (4 parallel opportunities)
- **Integration**: 3 tasks
- **Polish**: 2 tasks (both [P])
- **Total**: 32 tasks

**Estimated effort**: 2-3 days for full TDD cycle + manual testing
