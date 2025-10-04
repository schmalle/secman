# Research: Backend Dependency Updates

**Feature**: 007-please-evaluate-if
**Date**: 2025-10-04
**Status**: Complete

## Executive Summary

Current backend uses Micronaut 4.4.3 and various dependencies with versions from 2024-2025. Latest stable versions are available for most dependencies, with notable updates for Kotlin (2.2.20), Micronaut (4.9.12), Shadow plugin (9.2.2), and Apache POI (5.4.1). MariaDB driver and Spring Security are already at latest versions.

## Current vs Latest Versions

### Build Plugins

| Dependency | Current | Latest | Update? | Breaking Changes? |
|------------|---------|--------|---------|-------------------|
| Kotlin JVM | 2.1.0 | 2.2.20 | Yes | Minor - check for deprecations |
| Kotlin AllOpen | 2.1.0 | 2.2.20 | Yes | Minor - check for deprecations |
| Kotlin JPA | 2.1.0 | 2.2.20 | Yes | Minor - check for deprecations |
| KSP | 2.1.0-1.0.29 | 2.2.20-1.0.30 | Yes | Minor - annotation processing |
| Shadow Plugin | 8.3.8 | 9.2.2 | Yes | **Major** - Plugin ID changed to com.gradleup.shadow |
| Micronaut Application | 4.4.3 | 4.9.12 | Yes | Minor - framework updates |
| Micronaut AOT | 4.5.4 | 4.9.12 | Yes | Minor - optimization updates |

### Runtime Dependencies

| Dependency | Current | Latest | Update? | Notes |
|------------|---------|--------|---------|-------|
| MariaDB JDBC | 3.5.3 | 3.5.3 | No | ✅ Already latest |
| Spring Security Crypto | 6.3.5 | 6.4.4 | Yes | Security fixes (CVE-2025-22223, CVE-2025-22228) |
| Apache POI | 5.3.0 | 5.4.1 | Yes | Security fixes and improvements |
| jsoup | 1.18.3 | Latest | Check | HTML parser for email |
| log4j-to-slf4j | 2.24.3 | Latest | Check | Logging bridge |
| commons-logging | 1.3.4 | Latest | Check | Commons dependency |
| kotlinx-serialization-json | 1.6.0 | Latest | Check | JSON serialization |
| mockk | 1.13.14 | Latest | Check | Test framework |
| h2 | 2.2.224 | Latest | Check | Test database |

### Micronaut Modules

All Micronaut modules will automatically update with the platform version:
- Micronaut framework: 4.4.3 → 4.9.12
- Includes: Core, HTTP, Security, Data, Validation, Email, Reactor, Serde
- **Key improvement**: Netty 4.2 support, virtual threads on event loop (experimental)

## Decision Matrix

### 1. Kotlin Update (2.1.0 → 2.2.20)

**Decision**: Update to 2.2.20
**Rationale**:
- Improved compiler performance
- Better IDE support and tooling
- Bug fixes and stability improvements
- Micronaut 4.9 tested with Kotlin 2.2.x

**Alternatives Considered**:
- Stay on 2.1.0: Rejected due to missing improvements and potential compatibility issues with newer libraries
- Update to 2.3.x (if available): Rejected - 2.2.20 is latest stable

**Migration Steps**:
1. Update all kotlin plugins to 2.2.20
2. Update KSP to 2.2.20-1.0.30
3. Run tests to verify no compilation issues
4. Check for deprecation warnings

### 2. Shadow Plugin Update (8.3.8 → 9.2.2)

**Decision**: Update to 9.2.2
**Rationale**:
- Complete rewrite in Kotlin (better maintainability)
- Performance improvements
- Bug fixes
- New maintainer (GradleUp) actively developing

**Alternatives Considered**:
- Stay on 8.3.8: Rejected due to old unmaintained version
- Use alternative fat jar plugin: Rejected - Shadow is standard

**Breaking Changes**:
- Plugin ID changed: `com.github.johnrengelman.shadow` → `com.gradleup.shadow`
- Must update build.gradle.kts plugin declaration

**Migration Steps**:
1. Update plugin ID in build.gradle.kts
2. Update version to 9.2.2
3. Test shadow jar creation
4. Verify Docker build with new jar

### 3. Micronaut Update (4.4.3 → 4.9.12)

**Decision**: Update to 4.9.12
**Rationale**:
- Netty 4.2 updates (performance, security)
- Virtual threads support (experimental)
- KSP 2 support
- Jakarta Data 1.0 specification support
- Bug fixes and security patches

**Alternatives Considered**:
- Stay on 4.4.3: Rejected due to missing security fixes
- Update to 5.x (if available): Rejected - 4.9.12 is latest stable in 4.x line

**Migration Steps**:
1. Update io.micronaut.application plugin to 4.9.12
2. Update io.micronaut.aot plugin to 4.9.12
3. Run full test suite
4. Check for API deprecations
5. Verify all endpoints still work

### 4. Apache POI Update (5.3.0 → 5.4.1)

**Decision**: Update to 5.4.1
**Rationale**:
- Security fixes
- Bug fixes for Excel processing
- Updated dependencies
- Better compatibility with modern Excel files

**Alternatives Considered**:
- Stay on 5.3.0: Rejected due to security vulnerabilities
- Use alternative library: Rejected - POI is standard for Excel

**Migration Steps**:
1. Update poi-ooxml and poi-scratchpad to 5.4.1
2. Test Excel import functionality (Requirements, Vulnerabilities, Nmap, Masscan)
3. Verify Word export functionality
4. Check for any API changes in parsing logic

### 5. Spring Security Crypto Update (6.3.5 → 6.4.4)

**Decision**: Update to 6.4.4
**Rationale**:
- **Critical**: Addresses CVE-2025-22223 and CVE-2025-22228
- Security fixes for password encoding
- Bug fixes

**Alternatives Considered**:
- Stay on 6.3.5: Rejected due to security vulnerabilities
- Use different crypto library: Rejected - Spring Security is standard and well-tested

**Migration Steps**:
1. Update spring-security-crypto to 6.4.4
2. Test password encoding/verification
3. Verify login functionality
4. Check OAuth2 flows

### 6. Other Dependencies

**jsoup (1.18.3)**: Research shows 1.18.3 is recent, likely latest. Verify and update if needed.
**log4j-to-slf4j (2.24.3)**: Check for latest, update if security fixes available.
**commons-logging (1.3.4)**: Check for latest, update if available.
**kotlinx-serialization-json (1.6.0)**: Check for latest Kotlin-compatible version.
**mockk (1.13.14)**: Test framework, update if compatible with Kotlin 2.2.20.
**h2 (2.2.224)**: Test database, check for latest version.

## Risk Assessment

### High Risk
- **Shadow Plugin**: Plugin ID change is a breaking change, requires build.gradle.kts modification
- **Spring Security Crypto**: Password encoding changes could affect authentication

### Medium Risk
- **Micronaut**: Major version jump (4.4 → 4.9), potential API changes
- **Apache POI**: Excel parsing logic may have subtle changes
- **Kotlin**: Compiler changes may affect code generation

### Low Risk
- **MariaDB JDBC**: Already at latest version
- **Test dependencies**: Isolated to test environment

## Testing Strategy

### Phase 1: Build Verification
1. Update build.gradle.kts
2. Run `./gradlew clean build`
3. Verify compilation succeeds
4. Check for deprecation warnings

### Phase 2: Unit Tests
1. Run `./gradlew test`
2. Fix any failing tests
3. Verify test coverage maintained

### Phase 3: Integration Tests
1. Start Docker containers
2. Run E2E tests
3. Manual testing of critical flows:
   - Login/authentication
   - Excel import (Requirements, Vulnerabilities, Nmap, Masscan)
   - Word export
   - Asset management
   - Vulnerability tracking

### Phase 4: Docker Verification
1. Build Docker images (AMD64 + ARM64)
2. Test multi-arch deployment
3. Verify shadow jar creation
4. Test container startup and health

## Constitutional Compliance

✅ **Security-First**: Updates address known CVEs (Spring Security)
✅ **TDD**: All existing tests must pass
✅ **API-First**: No API changes expected
✅ **Docker-First**: Multi-arch builds will be tested
✅ **RBAC**: No changes to authorization
✅ **Schema Evolution**: No database changes

## Rollback Plan

If critical issues arise:
1. Revert to previous commit
2. Document specific dependency causing issue
3. Update one dependency at a time to isolate problem
4. Consider partial update (critical security fixes only)

## Success Criteria

- [ ] All dependencies updated to latest stable versions
- [ ] Build succeeds without errors
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Docker builds succeed for AMD64 and ARM64
- [ ] No performance regression (<200ms p95)
- [ ] No security vulnerabilities reported
- [ ] Application starts and serves requests correctly

---

**Next Steps**: Proceed to Phase 1 (Design & Contracts) - Since this is an infrastructure task, contracts are N/A. Create quickstart.md for testing strategy.
