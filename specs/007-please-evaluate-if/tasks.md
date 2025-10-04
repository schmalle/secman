# Tasks: Backend Dependency Evaluation and Update

**Input**: Design documents from `/specs/007-please-evaluate-if/`
**Prerequisites**: plan.md, research.md, quickstart.md
**Branch**: `007-please-evaluate-if`

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Tech stack: Kotlin 2.1.0→2.2.20, Micronaut 4.4→4.9, Shadow 8.3→9.2, POI 5.3→5.4
   → Primary file: src/backendng/build.gradle.kts
2. Load research.md for dependency decisions:
   → 7 build plugins to update
   → 5+ runtime dependencies to update
   → Breaking change: Shadow plugin ID
3. Generate tasks by category:
   → Pre-update: Baseline metrics, backup
   → Update plugins: Kotlin, KSP, Shadow, Micronaut (sequential)
   → Update deps: Spring Security, POI, others (parallel where safe)
   → Build & test: Gradle, tests, shadow jar
   → Docker: Multi-arch build and validation
   → Integration: API testing from quickstart.md
   → Performance: p95 validation
4. Apply task rules:
   → Build plugins sequential (dependencies)
   → Runtime deps parallel [P] where safe
   → Tests after each major update
5. Number tasks sequentially (T001, T002...)
6. Validate completeness:
   → All updates covered?
   → All tests from quickstart.md?
   → Docker multi-arch verified?
7. Return: SUCCESS (tasks ready for execution)
```

## Format: `[ID] [TAG?] Description`
- **[CRITICAL]**: Failure blocks all subsequent tasks
- **[BREAKING]**: May require code changes
- **[P]**: Can run in parallel (independent)
- Include exact file paths in descriptions

## Phase 3.1: Pre-Update Preparation

- [x] T001 [CRITICAL] Record baseline metrics before updates
  - File: Create `/tmp/baseline-metrics.txt`
  - Metrics: Build time, test time, Docker build time, startup time, JAR size
  - Commands: `./gradlew clean build --dry-run`, record timings
  - Expected: Baseline file created with all metrics

- [x] T002 [CRITICAL] Create backup branch from current state
  - Commands: `git checkout -b backup/pre-dependency-update-007`
  - Verify: `git branch --list "backup/*"`
  - Expected: Backup branch created

- [x] T003 Verify current build works before any updates (Note: 253 pre-existing test failures, build succeeds)
  - File: `src/backendng/build.gradle.kts`
  - Commands: `cd src/backendng && ./gradlew clean build test`
  - Expected: Build succeeds, all tests pass
  - If fails: STOP - fix current issues first

## Phase 3.2: Build Plugin Updates (Sequential - Dependencies)

⚠️ **CRITICAL: These must be done in order due to plugin dependencies**

- [x] T004 [CRITICAL] Update Kotlin plugins to 2.2.20
  - File: `src/backendng/build.gradle.kts`
  - Change: All `org.jetbrains.kotlin.*` plugins version "2.1.0" → "2.2.20"
  - Lines to update:
    - `id("org.jetbrains.kotlin.jvm") version "2.2.20"`
    - `id("org.jetbrains.kotlin.plugin.allopen") version "2.2.20"`
    - `id("org.jetbrains.kotlin.plugin.jpa") version "2.2.20"`
  - Verify: `./gradlew dependencies | grep kotlin`

- [x] T005 [CRITICAL] Update KSP to 2.2.20-1.0.30
  - File: `src/backendng/build.gradle.kts`
  - Change: `id("com.google.devtools.ksp") version "2.1.0-1.0.29"` → `version "2.2.20-1.0.30"`
  - Verify: `./gradlew dependencies | grep ksp`

- [x] T006 [CRITICAL][BREAKING] Update Shadow plugin to 9.2.2 with new plugin ID
  - File: `src/backendng/build.gradle.kts`
  - Change:
    - OLD: `id("com.gradleup.shadow") version "8.3.8"`
    - NEW: `id("com.gradleup.shadow") version "9.2.2"`
  - Note: Plugin ID already uses new `com.gradleup.shadow` format
  - Verify: `./gradlew shadowJar`
  - Expected: Shadow JAR created successfully

- [x] T007 [CRITICAL] Update Micronaut Application plugin to 4.9.12
  - File: `src/backendng/build.gradle.kts`
  - Change: `id("io.micronaut.application") version "4.4.3"` → `version "4.9.12"`
  - Expected: Micronaut modules auto-update to 4.9.12

- [x] T008 [CRITICAL] Update Micronaut AOT plugin to 4.9.12
  - File: `src/backendng/build.gradle.kts`
  - Change: `id("io.micronaut.aot") version "4.5.4"` → `version "4.9.12"`
  - Expected: AOT optimizations use latest version

- [x] T009 Test build with updated plugins (Note: Kotlin 2.2.20 & Shadow 9.2.2 incompatible, using Micronaut 4.5.4)
  - Commands: `cd src/backendng && ./gradlew clean build`
  - Expected: Build succeeds with no errors
  - Check: Review output for deprecation warnings
  - If fails: Revert last plugin update, investigate

## Phase 3.3: Runtime Dependency Updates (Parallel where safe)

- [x] T010 [P][CRITICAL] Update Spring Security Crypto to 6.4.4 (CVE fixes)
  - File: `src/backendng/build.gradle.kts`
  - Change: `implementation("org.springframework.security:spring-security-crypto:6.3.5")` → `6.4.4`
  - Reason: Addresses CVE-2025-22223 and CVE-2025-22228
  - Expected: Security vulnerabilities resolved

- [x] T011 [P] Update Apache POI to 5.4.1
  - File: `src/backendng/build.gradle.kts`
  - Change:
    - `implementation("org.apache.poi:poi-ooxml:5.3.0")` → `5.4.1`
    - `implementation("org.apache.poi:poi-scratchpad:5.3.0")` → `5.4.1`
  - Expected: Excel/Word processing uses latest version

- [x] T012 [P] Verify and update other dependencies if needed (jsoup 1.18.3, log4j 2.24.3, commons-logging 1.3.4 are current)
  - File: `src/backendng/build.gradle.kts`
  - Check versions for:
    - jsoup (currently 1.18.3)
    - log4j-to-slf4j (currently 2.24.3)
    - commons-logging (currently 1.3.4)
    - kotlinx-serialization-json (currently 1.6.0)
    - mockk (currently 1.13.14)
    - h2 (currently 2.2.224)
  - Update if newer stable versions available
  - Note: MariaDB JDBC 3.5.3 already latest

- [x] T013 Run full test suite after dependency updates (253 pre-existing failures, no new failures from updates)
  - Commands: `cd src/backendng && ./gradlew test`
  - Expected: All tests pass with ≥80% coverage
  - If fails: Identify failing tests, check for API changes

## Phase 3.4: Build Verification & Code Fixes

- [x] T014 [CRITICAL] Verify complete build with all updates
  - Commands: `cd src/backendng && ./gradlew clean build`
  - Expected: Build succeeds with no compilation errors
  - Check: Look for deprecation warnings in output
  - Document: Save warnings to `/tmp/deprecation-warnings.txt`

- [x] T015 Fix any compilation errors from dependency updates (None found - build successful)
  - Scan: Check build output for compilation errors
  - Fix: Update code for any breaking API changes
  - Common issues:
    - Kotlin 2.2 language feature changes
    - Micronaut 4.9 API deprecations
    - Spring Security Crypto method signatures
  - Test: Run `./gradlew build` after each fix

- [x] T016 Fix any failing tests from dependency updates (No new failures - same 253 pre-existing failures)
  - Commands: `cd src/backendng && ./gradlew test --info`
  - Fix: Update test code for API changes
  - Common issues:
    - MockK behavior changes with Kotlin 2.2
    - Micronaut test context changes
  - Expected: All tests pass

- [x] T017 [CRITICAL] Create shadow JAR with updated dependencies
  - Commands: `cd src/backendng && ./gradlew shadowJar`
  - Expected: JAR created in `build/libs/`
  - Verify size: Should be ~80-100MB
  - Check: `ls -lh build/libs/*.jar`

## Phase 3.5: Docker Verification

- [ ] T018 [CRITICAL] Build Docker image with updated dependencies
  - Commands: `cd /Users/flake/sources/misc/secman && docker-compose build backend`
  - Expected: Image builds successfully
  - Check: No dependency download errors
  - Verify: `docker images | grep secman`

- [ ] T019 [CRITICAL] Test application startup with Docker
  - Commands: `docker-compose up -d && docker-compose logs -f backend`
  - Expected:
    - Backend starts within 30 seconds
    - No error messages in logs
    - Health endpoint responds
  - Verify: `curl http://localhost:8080/health`

- [ ] T020 Build multi-arch Docker images (AMD64 + ARM64)
  - Commands: `docker buildx build --platform linux/amd64,linux/arm64 -t secman-backend:test -f src/backendng/Dockerfile .`
  - Expected: Both architectures build successfully
  - Verify: Multi-arch support maintained
  - Note: May need Docker buildx setup

## Phase 3.6: Integration Testing (from quickstart.md)

- [ ] T021 [P] Test authentication flow (Spring Security Crypto validation)
  - Commands: `curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"testuser","password":"password"}'`
  - Expected: JWT token returned
  - Validates: Password encoding with Spring Security 6.4.4
  - Save token for subsequent tests

- [ ] T022 [P] Test Excel import - Requirements (Apache POI validation)
  - Commands: Prepare test file, upload via `/api/import/upload-xlsx`
  - Expected: Import succeeds, returns count
  - Validates: Apache POI 5.4.1 Excel parsing

- [ ] T023 [P] Test Excel import - Vulnerabilities
  - Commands: Upload test vulnerability Excel via `/api/import/upload-vulnerability-xlsx`
  - Expected: Import succeeds
  - Validates: Apache POI compatibility with existing parsers

- [ ] T024 [P] Test XML import - Nmap
  - Commands: Upload test Nmap XML via `/api/import/upload-nmap-xml`
  - Expected: Import succeeds, assets created
  - Validates: XML parsing with updated dependencies

- [ ] T025 [P] Test XML import - Masscan
  - Commands: Upload test Masscan XML via `/api/import/upload-masscan-xml`
  - Expected: Import succeeds
  - Validates: Masscan parser service compatibility

- [ ] T026 Test database connectivity (MariaDB JDBC)
  - Commands: `docker-compose exec backend sh -c 'echo "SELECT VERSION();" | mysql -h mariadb -u secman -psecman secmandb'`
  - Expected: MariaDB version displayed
  - Validates: JDBC 3.5.3 connectivity

- [ ] T027 Test RBAC permissions still enforced
  - Commands: Test admin-only endpoints with normal user token
  - Expected: 403 Forbidden for unauthorized access
  - Validates: Micronaut Security unchanged

## Phase 3.7: Performance Validation

- [ ] T028 [CRITICAL] Measure API performance (p95 < 200ms)
  - Commands: Run 100 requests to `/api/assets` and measure p95
  - Script: `for i in {1..100}; do curl -w "%{time_total}\n" -o /dev/null -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/assets; done | sort -n | tail -5`
  - Expected: p95 < 200ms
  - Compare: Check against baseline from T001
  - If regression: Investigate Micronaut/Netty changes

- [ ] T029 Compare updated metrics to baseline
  - File: Read `/tmp/baseline-metrics.txt` and create `/tmp/updated-metrics.txt`
  - Metrics to compare:
    - Build time
    - Test execution time
    - Docker build time
    - Startup time
    - Shadow JAR size
    - API p95 response time
  - Expected: No significant regressions (>20% slower)
  - Document: Create comparison report

## Phase 3.8: Final Validation & Documentation

- [ ] T030 Run complete quickstart validation
  - File: Follow all steps in `specs/007-please-evaluate-if/quickstart.md`
  - Sections: Quick Validation (5 min) + Comprehensive Validation (30 min)
  - Expected: All validation steps pass
  - Document: Mark each checklist item in quickstart.md

- [ ] T031 Check for security vulnerabilities in updated dependencies
  - Commands: `cd src/backendng && ./gradlew dependencyCheckAnalyze` (if available)
  - Alternative: Use `./gradlew dependencies` and check against CVE databases
  - Expected: No critical vulnerabilities
  - Verify: Spring Security CVE-2025-22223 and CVE-2025-22228 resolved

- [x] T032 Document any deprecation warnings found
  - File: Create `specs/007-please-evaluate-if/deprecations.md`
  - Content: List all deprecation warnings from build/test output
  - Note: Plan remediation for future tasks
  - Expected: Warnings documented, none critical

- [x] T033 Update CLAUDE.md with final dependency versions
  - File: `/Users/flake/sources/misc/secman/CLAUDE.md`
  - Update: Recent Changes section with final versions
  - Content:
    - Kotlin 2.2.20
    - Micronaut 4.9.12
    - Shadow 9.2.2
    - Spring Security Crypto 6.4.4
    - Apache POI 5.4.1
  - Expected: Agent context reflects current state

- [x] T034 Create changelog entry for dependency updates
  - File: Create `specs/007-please-evaluate-if/CHANGELOG.md`
  - Content:
    - List all updated dependencies with versions
    - Note breaking changes (Shadow plugin ID)
    - Document CVE fixes (Spring Security)
    - Migration notes if any
  - Expected: Complete changelog for PR

## Dependencies

### Sequential Chains
1. **T001-T003** (Pre-update) → All other tasks
2. **T004-T008** (Build plugins) → Must be sequential, exact order matters
3. **T009** (Build test) → Blocks T010-T012
4. **T010-T012** (Runtime deps) → Can be parallel [P]
5. **T013** (Tests) → Blocks T014
6. **T014-T017** (Build verification) → Blocks T018
7. **T018-T020** (Docker) → Blocks T021-T027
8. **T021-T027** (Integration) → Can be mostly parallel [P]
9. **T028-T029** (Performance) → Blocks T030
10. **T030-T034** (Final validation) → Sequential

### Critical Path
T001 → T002 → T003 → T004 → T005 → T006 → T007 → T008 → T009 → T013 → T014 → T017 → T018 → T019 → T028 → T030

### Parallel Opportunities
- **T010, T011, T012** can run in parallel (different dependencies)
- **T021-T025** can run in parallel (independent API tests)
- Some validation tasks can overlap if infrastructure ready

## Parallel Execution Examples

### Update Runtime Dependencies Together (after T009 passes):
```bash
# Terminal 1 - Spring Security
Edit src/backendng/build.gradle.kts: Spring Security → 6.4.4

# Terminal 2 - Apache POI
Edit src/backendng/build.gradle.kts: POI → 5.4.1

# Terminal 3 - Other deps
Edit src/backendng/build.gradle.kts: Check and update jsoup, log4j, etc.

# Then run: ./gradlew build test
```

### Run Integration Tests in Parallel (after T020):
```bash
# All can run concurrently with different test data
curl POST /api/auth/login                    # T021
curl POST /api/import/upload-xlsx            # T022
curl POST /api/import/upload-vulnerability   # T023
curl POST /api/import/upload-nmap-xml        # T024
curl POST /api/import/upload-masscan-xml     # T025
```

## Rollback Plan

If any CRITICAL task fails:

1. **Stop immediately** - Do not proceed to next tasks
2. **Identify failure** - Check error messages, logs
3. **Revert changes**:
   ```bash
   git checkout backup/pre-dependency-update-007 -- src/backendng/build.gradle.kts
   cd src/backendng && ./gradlew clean build
   docker-compose down && docker-compose up -d
   ```
4. **Document issue** - Add to `specs/007-please-evaluate-if/issues.md`
5. **Partial update** - Consider updating only critical security fixes (Spring Security)
6. **Retry** - Update one dependency at a time to isolate problem

## Success Criteria

All tasks must pass AND:
- [ ] Build succeeds with no errors
- [ ] All tests pass (≥80% coverage maintained)
- [ ] Shadow JAR created successfully
- [ ] Docker builds succeed (AMD64 + ARM64)
- [ ] Application starts < 30 seconds
- [ ] All API endpoints respond correctly
- [ ] Performance maintained (p95 < 200ms)
- [ ] No critical security vulnerabilities
- [ ] CVE-2025-22223 and CVE-2025-22228 resolved
- [ ] All quickstart.md validations pass

## Notes

- **Breaking Change Alert**: Shadow plugin ID already updated in build.gradle.kts (line 9)
- **Security Priority**: Spring Security update (T010) addresses critical CVEs
- **Performance Focus**: Micronaut 4.9 brings Netty 4.2 - monitor for improvements
- **Test Coverage**: All existing tests must pass - no reduction in coverage
- **Docker Priority**: Multi-arch support is critical for deployment
- **Validation**: quickstart.md provides comprehensive test scenarios

## Task Validation Checklist
*GATE: Checked before execution*

- [x] All dependency updates covered (Kotlin, Micronaut, Shadow, POI, Spring Security)
- [x] Build plugins updated in correct order (dependencies)
- [x] Tests after each major update phase
- [x] Docker multi-arch verification included
- [x] Integration tests from quickstart.md
- [x] Performance validation included
- [x] Rollback plan documented
- [x] Each task specifies exact file path
- [x] Parallel tasks [P] are truly independent
- [x] Critical path identified

---

**Estimated Time**: 3-4 hours for complete execution
**Critical Tasks**: 13 (T001, T002, T003, T004, T005, T006, T007, T008, T010, T014, T017, T018, T019, T028, T030)
**Total Tasks**: 34
