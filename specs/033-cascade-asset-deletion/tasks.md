# Implementation Tasks: Cascade Asset Deletion with Related Data

**Feature Branch**: `033-cascade-asset-deletion`
**Created**: 2025-10-24
**Status**: Ready for Implementation

## Overview

This document provides actionable implementation tasks organized by user story to enable independent, incremental delivery. Each user story represents a complete, independently testable increment of functionality.

**Tech Stack**: Kotlin 2.1.0/Java 21 (backend), TypeScript/React 19 (frontend), Micronaut 4.4, Hibernate JPA, MariaDB 11.4

**TDD Approach**: Per Constitution Principle II, all tasks follow Test-Driven Development. Tests are written first (contract → unit → integration), then implementation makes tests pass.

## Implementation Strategy

**MVP (Minimum Viable Product)**: User Story 1 only
- Delivers core cascade deletion functionality
- Ensures data integrity (zero orphaned records)
- Provides immediate business value
- Can be deployed independently

**Incremental Delivery**:
- Phase 3 (US1): Core cascade deletion + audit logging
- Phase 4 (US2): Verified audit trail preservation (validation of US1)
- Phase 5 (US3): Bulk operations with real-time progress
- Phase 6 (US4): UI warnings and pre-flight validation

**Parallel Execution**: Tasks marked [P] can run concurrently (different files, no blocking dependencies)

---

## Phase 1: Setup & Prerequisites

**Goal**: Prepare development environment and verify existing infrastructure

- [ ] T001 Verify Kotlin 2.1.0/Java 21 and Micronaut 4.4 versions in build.gradle.kts
- [ ] T002 Verify Hibernate JSON type dependency (com.vladmihalcea:hibernate-types-60:2.21.1) in build.gradle.kts
- [ ] T003 Verify Micronaut Reactor dependency for SSE support in build.gradle.kts
- [ ] T004 Verify JUnit 5, MockK, and Playwright test dependencies in build configuration
- [ ] T005 Review existing Asset.kt entity to understand current cascade behavior in src/backendng/src/main/kotlin/com/secman/domain/Asset.kt
- [ ] T006 Review existing AssetController.kt and AssetBulkDeleteService.kt implementations in src/backendng/src/main/kotlin/com/secman/{controller,service}/
- [ ] T007 Review VulnerabilityException.kt to confirm assetId field (non-FK Long) in src/backendng/src/main/kotlin/com/secman/domain/VulnerabilityException.kt
- [ ] T008 Review VulnerabilityExceptionRequest.kt to confirm vulnerability FK relationship in src/backendng/src/main/kotlin/com/secman/domain/VulnerabilityExceptionRequest.kt

**Verification**: All dependencies present, existing code understood

---

## Phase 2: Foundational Components

**Goal**: Build shared infrastructure required by all user stories (blocking prerequisites)

### Audit Infrastructure

- [ ] T009 [P] Create AssetDeletionAuditLog entity with JSON columns in src/backendng/src/main/kotlin/com/secman/domain/AssetDeletionAuditLog.kt
- [ ] T010 [P] Create AssetDeletionAuditLogRepository interface in src/backendng/src/main/kotlin/com/secman/repository/AssetDeletionAuditLogRepository.kt
- [ ] T011 [P] Write unit test for AssetDeletionAuditLog entity creation and JSON serialization in src/backendng/src/test/kotlin/com/secman/domain/AssetDeletionAuditLogTest.kt
- [ ] T012 Create AssetDeletionAuditService with async logging methods in src/backendng/src/main/kotlin/com/secman/service/AssetDeletionAuditService.kt
- [ ] T013 Write unit tests for AssetDeletionAuditService async operations in src/backendng/src/test/kotlin/com/secman/service/AssetDeletionAuditServiceTest.kt

### DTOs

- [ ] T014 [P] Create CascadeDeleteSummaryDto in src/backendng/src/main/kotlin/com/secman/dto/CascadeDeleteSummaryDto.kt
- [ ] T015 [P] Create DeletionErrorDto in src/backendng/src/main/kotlin/com/secman/dto/DeletionErrorDto.kt
- [ ] T016 [P] Create CascadeDeletionResultDto in src/backendng/src/main/kotlin/com/secman/dto/CascadeDeletionResultDto.kt
- [ ] T017 [P] Create BulkDeleteProgressDto in src/backendng/src/main/kotlin/com/secman/dto/BulkDeleteProgressDto.kt

### Repository Query Methods

- [ ] T018 Add countByAssetId() to VulnerabilityRepository in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt
- [ ] T019 Add findIdsByAssetId() to VulnerabilityRepository in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt
- [ ] T020 Add countByAssetIdAndExceptionType() to VulnerabilityExceptionRepository in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityExceptionRepository.kt
- [ ] T021 Add findIdsByAssetIdAndType() to VulnerabilityExceptionRepository in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityExceptionRepository.kt
- [ ] T022 Add countByVulnerabilityAssetId() with @Query to VulnerabilityExceptionRequestRepository in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityExceptionRequestRepository.kt
- [ ] T023 Add findIdsByVulnerabilityAssetId() with @Query to VulnerabilityExceptionRequestRepository in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityExceptionRequestRepository.kt
- [ ] T024 Add deleteByIdIn(List<Long>) to all three repositories (Vulnerability, VulnerabilityException, VulnerabilityExceptionRequest)

**Verification**: All foundational components compile, unit tests pass

---

## Phase 3: User Story 1 - Delete Asset with All Related Data (P1 - MVP)

**Story Goal**: Core cascade deletion functionality with pessimistic locking and audit logging

**Independent Test**: Create asset with 5 vulnerabilities + 3 ASSET-type exceptions + 2 exception requests → Delete asset → Verify all 10 related records deleted + audit log created

### Backend - Core Service

- [ ] T025 [US1] Write contract test: DELETE /api/assets/{id} deletes asset with all related data in src/backendng/src/test/kotlin/com/secman/contract/AssetCascadeDeleteContractTest.kt
- [ ] T026 [US1] Write contract test: DELETE returns 409 when asset locked in src/backendng/src/test/kotlin/com/secman/contract/AssetCascadeDeleteContractTest.kt
- [ ] T027 [US1] Write contract test: DELETE returns 404 when asset not found in src/backendng/src/test/kotlin/com/secman/contract/AssetCascadeDeleteContractTest.kt
- [ ] T028 [US1] Write contract test: DELETE preserves IP and PRODUCT exceptions in src/backendng/src/test/kotlin/com/secman/contract/AssetCascadeDeleteContractTest.kt
- [ ] T029 [US1] Create AssetCascadeDeleteService with @Transactional and @Singleton in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T030 [US1] Implement pessimistic locking (LockModeType.PESSIMISTIC_WRITE) in deleteAsset() method in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T031 [US1] Implement ID collection logic (collect vuln/exception/request IDs before deletion) in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T032 [US1] Implement cascade deletion in dependency order (requests → exceptions → vulnerabilities → asset) in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T033 [US1] Integrate AssetDeletionAuditService async logging call in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T034 [US1] Write unit tests for AssetCascadeDeleteService covering all deletion paths in src/backendng/src/test/kotlin/com/secman/service/AssetCascadeDeleteServiceTest.kt
- [ ] T035 [US1] Write integration test for transaction rollback on constraint violation in src/backendng/src/test/kotlin/com/secman/integration/AssetCascadeTransactionTest.kt
- [ ] T036 [US1] Write integration test for concurrent deletion with pessimistic locking in src/backendng/src/test/kotlin/com/secman/integration/AssetConcurrentDeletionTest.kt

### Backend - Controller Enhancement

- [ ] T037 [US1] Enhance DELETE /api/assets/{id} endpoint in AssetController to call AssetCascadeDeleteService in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T038 [US1] Add exception handling for PessimisticLockException → 409 DeletionErrorDto in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T039 [US1] Add exception handling for AssetNotFoundException → 404 error in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T040 [US1] Add exception handling for generic exceptions → 500 DeletionErrorDto in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T041 [US1] Update Swagger/OpenAPI annotations for enhanced DELETE endpoint in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt

### Frontend - Basic Integration

- [ ] T042 [P] [US1] Update assetService.deleteAsset() to handle CascadeDeletionResultDto response in src/frontend/src/services/assetService.ts
- [ ] T043 [P] [US1] Update assetService to handle DeletionErrorDto for 409/500 errors in src/frontend/src/services/assetService.ts
- [ ] T044 [US1] Update asset detail page delete button handler to show success message with counts in src/frontend/src/pages/assets/[id].astro
- [ ] T045 [P] [US1] Create DeletionErrorAlert component for displaying structured errors in src/frontend/src/components/DeletionErrorAlert.tsx
- [ ] T046 [US1] Integrate DeletionErrorAlert into asset detail page error handling in src/frontend/src/pages/assets/[id].astro

### Testing

- [ ] T047 [P] [US1] Write Playwright E2E test: Delete asset with related data → verify all deleted in src/frontend/tests/e2e/asset-cascade-delete.spec.ts
- [ ] T048 [P] [US1] Write Playwright E2E test: Concurrent deletion attempt → verify 409 error shown in src/frontend/tests/e2e/asset-cascade-delete.spec.ts

**Phase 3 Completion Criteria**:
- ✅ Single asset deletion cascades to all related records
- ✅ Pessimistic locking prevents concurrent deletions
- ✅ Audit log created for every deletion
- ✅ Contract tests pass (4 scenarios)
- ✅ Integration tests pass (transaction rollback, concurrent locking)
- ✅ E2E tests pass (successful deletion, error handling)
- ✅ Zero orphaned records after deletion (database integrity check)

**MVP Delivery**: After Phase 3, core functionality is complete and deployable

---

## Phase 4: User Story 2 - Complete Deletion of Exception Requests (P2)

**Story Goal**: Verify audit trail preservation while operational data is deleted

**Independent Test**: Create asset with exception requests + audit log entries → Delete asset → Verify requests deleted but audit logs preserved

### Backend - Audit Verification

- [ ] T049 [US2] Write contract test: DELETE asset removes exception requests but preserves ExceptionRequestAuditLog in src/backendng/src/test/kotlin/com/secman/contract/AssetCascadeDeleteContractTest.kt
- [ ] T050 [US2] Write integration test: Verify ExceptionRequestAuditLog entries intact after cascade deletion in src/backendng/src/test/kotlin/com/secman/integration/AssetAuditPreservationTest.kt
- [ ] T051 [US2] Add verification to AssetCascadeDeleteService ensuring ExceptionRequestAuditLog not touched in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt

### Testing

- [ ] T052 [US2] Write Playwright E2E test: Delete asset → verify audit logs accessible via admin panel in src/frontend/tests/e2e/asset-audit-preservation.spec.ts

**Phase 4 Completion Criteria**:
- ✅ Exception requests deleted (verified)
- ✅ ExceptionRequestAuditLog preserved (verified)
- ✅ Audit log query endpoints return historical data

---

## Phase 5: User Story 3 - Transactional Bulk Asset Deletion (P2)

**Story Goal**: Bulk operations with real-time progress streaming via SSE

**Independent Test**: Bulk delete 10 assets → Verify all deleted with progress updates OR one fails → verify complete rollback

### Backend - Bulk Delete Enhancement

- [ ] T053 [US3] Write contract test: DELETE /api/assets/bulk deletes all assets in single transaction in src/backendng/src/test/kotlin/com/secman/contract/BulkAssetDeleteContractTest.kt
- [ ] T054 [US3] Write contract test: DELETE /api/assets/bulk rolls back on single failure in src/backendng/src/test/kotlin/com/secman/contract/BulkAssetDeleteContractTest.kt
- [ ] T055 [US3] Write contract test: DELETE /api/assets/bulk/stream returns SSE events in src/backendng/src/test/kotlin/com/secman/contract/BulkAssetDeleteContractTest.kt
- [ ] T056 [US3] Enhance AssetBulkDeleteService to use AssetCascadeDeleteService for each asset in src/backendng/src/main/kotlin/com/secman/service/AssetBulkDeleteService.kt
- [ ] T057 [US3] Implement sequential deletion with single transaction wrapper in AssetBulkDeleteService in src/backendng/src/main/kotlin/com/secman/service/AssetBulkDeleteService.kt
- [ ] T058 [US3] Generate UUID for bulkOperationId and pass to audit service in src/backendng/src/main/kotlin/com/secman/service/AssetBulkDeleteService.kt
- [ ] T059 [US3] Add DELETE /api/assets/bulk/stream endpoint with Flux<ServerSentEvent> in AssetController in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T060 [US3] Implement SSE progress streaming (emit BulkDeleteProgressDto after each asset) in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T061 [US3] Add error handling in SSE stream (emit FAILED status, close stream on error) in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T062 [US3] Write integration test: Bulk delete transaction rolls back completely on failure in src/backendng/src/test/kotlin/com/secman/integration/BulkDeleteTransactionTest.kt

### Frontend - Progress Streaming

- [ ] T063 [P] [US3] Create bulkDeleteProgressService with EventSource SSE client in src/frontend/src/services/bulkDeleteProgressService.ts
- [ ] T064 [P] [US3] Implement streamBulkDelete() function with progress/complete/error callbacks in src/frontend/src/services/bulkDeleteProgressService.ts
- [ ] T065 [P] [US3] Create BulkDeleteProgressModal component with progress bar in src/frontend/src/components/BulkDeleteProgressModal.tsx
- [ ] T066 [US3] Integrate EventSource connection lifecycle (open, message, error, close) in BulkDeleteProgressModal in src/frontend/src/components/BulkDeleteProgressModal.tsx
- [ ] T067 [US3] Add progress bar rendering (completed/total percentage) in src/frontend/src/components/BulkDeleteProgressModal.tsx
- [ ] T068 [US3] Add current asset name display during deletion in src/frontend/src/components/BulkDeleteProgressModal.tsx
- [ ] T069 [US3] Add error display for failed deletions in src/frontend/src/components/BulkDeleteProgressModal.tsx
- [ ] T070 [US3] Update asset list page to open BulkDeleteProgressModal on bulk delete action in src/frontend/src/pages/assets/index.astro

### Testing

- [ ] T071 [US3] Write Playwright E2E test: Bulk delete shows real-time progress updates in src/frontend/tests/e2e/bulk-delete-progress.spec.ts
- [ ] T072 [US3] Write Playwright E2E test: Bulk delete failure shows error and rolls back in src/frontend/tests/e2e/bulk-delete-progress.spec.ts

**Phase 5 Completion Criteria**:
- ✅ Bulk deletion is atomic (all-or-nothing)
- ✅ SSE streams progress updates in real-time
- ✅ Progress modal shows completed/total count
- ✅ Transaction rollback works on single asset failure
- ✅ Bulk operation ID correlates all audit logs

---

## Phase 6: User Story 4 - UI Warning Before Cascade Deletion (P3)

**Story Goal**: Pre-flight validation with cascade count warnings and timeout estimates

**Independent Test**: Initiate delete on asset with 15 vulns + 3 exceptions + 2 requests → Modal shows "20 related records will be deleted" + timeout warning if applicable

### Backend - Pre-Flight Endpoint

- [ ] T073 [US4] Write contract test: GET /api/assets/{id}/cascade-summary returns count summary in src/backendng/src/test/kotlin/com/secman/contract/AssetPreFlightContractTest.kt
- [ ] T074 [US4] Write contract test: GET returns exceedsTimeout=true for large datasets in src/backendng/src/test/kotlin/com/secman/contract/AssetPreFlightContractTest.kt
- [ ] T075 [US4] Add estimateCascadeDeletion() method to AssetCascadeDeleteService in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T076 [US4] Implement pre-flight COUNT queries for vuln/exception/request in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T077 [US4] Implement timeout estimation formula (totalRecords / 100 + 1) in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T078 [US4] Set exceedsTimeout flag when estimatedSeconds > 60 in src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt
- [ ] T079 [US4] Add GET /api/assets/{id}/cascade-summary endpoint to AssetController in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T080 [US4] Add forceTimeout query parameter to DELETE /api/assets/{id} endpoint in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T081 [US4] Return 422 Unprocessable if exceedsTimeout and forceTimeout=false in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T082 [US4] Write unit tests for pre-flight validation logic in src/backendng/src/test/kotlin/com/secman/service/AssetPreFlightValidationTest.kt

### Frontend - Confirmation Modal Enhancement

- [ ] T083 [P] [US4] Add getCascadeSummary() method to assetService in src/frontend/src/services/assetService.ts
- [ ] T084 [P] [US4] Update deleteAsset() to accept forceTimeout parameter in src/frontend/src/services/assetService.ts
- [ ] T085 [US4] Create AssetDeleteConfirmModal component (if not exists) in src/frontend/src/components/AssetDeleteConfirmModal.tsx
- [ ] T086 [US4] Add useEffect to fetch cascade summary on modal open in src/frontend/src/components/AssetDeleteConfirmModal.tsx
- [ ] T087 [US4] Display vulnerability/exception/request counts in modal in src/frontend/src/components/AssetDeleteConfirmModal.tsx
- [ ] T088 [US4] Display timeout warning alert when exceedsTimeout=true in src/frontend/src/components/AssetDeleteConfirmModal.tsx
- [ ] T089 [US4] Add "Force Delete" checkbox for timeout scenarios in src/frontend/src/components/AssetDeleteConfirmModal.tsx
- [ ] T090 [US4] Integrate modal into asset detail page delete flow in src/frontend/src/pages/assets/[id].astro

### Testing

- [ ] T091 [US4] Write Playwright E2E test: Delete shows cascade counts in confirmation modal in src/frontend/tests/e2e/asset-delete-warning.spec.ts
- [ ] T092 [US4] Write Playwright E2E test: Timeout warning displayed for large datasets in src/frontend/tests/e2e/asset-delete-warning.spec.ts
- [ ] T093 [US4] Write Playwright E2E test: Force delete checkbox enables deletion despite timeout warning in src/frontend/tests/e2e/asset-delete-warning.spec.ts

**Phase 6 Completion Criteria**:
- ✅ Confirmation modal shows cascade counts before deletion
- ✅ Timeout warning appears for >60s estimated operations
- ✅ Force delete option available for timeout scenarios
- ✅ Pre-flight queries complete in <100ms

---

## Phase 7: Polish & Cross-Cutting Concerns

**Goal**: Production readiness, performance optimization, documentation

- [ ] T094 [P] Update CLAUDE.md with new entities, services, and endpoints
- [ ] T095 [P] Update OpenAPI/Swagger documentation for all new endpoints
- [ ] T096 [P] Add logging statements to AssetCascadeDeleteService (INFO for successful deletions, WARN for timeouts)
- [ ] T097 [P] Add performance benchmarking tests (100 assets deletion in <30s) in src/backendng/src/test/kotlin/com/secman/performance/AssetDeletionPerformanceTest.kt
- [ ] T098 [P] Review and optimize database indexes (verify asset_id, exception_type indexes present)
- [ ] T099 Verify lock timeout configuration in application.yml (javax.persistence.lock.timeout)
- [ ] T100 Verify SSE timeout configuration in application.yml (micronaut.server.netty.idle-timeout)
- [ ] T101 Manual QA: Delete single asset via UI, verify cascade and audit log
- [ ] T102 Manual QA: Bulk delete 10 assets via UI, verify progress streaming
- [ ] T103 Manual QA: Attempt concurrent deletions, verify pessimistic locking
- [ ] T104 Manual QA: Delete asset with >6000 records, verify timeout warning
- [ ] T105 Run full test suite: ./gradlew test && npm test
- [ ] T106 Verify code coverage ≥80% for new services (./gradlew jacocoTestReport)
- [ ] T107 Create PR with conventional commit message: "feat(assets): add cascade deletion with audit logging"

**Phase 7 Completion Criteria**:
- ✅ All tests pass (unit, integration, contract, E2E)
- ✅ Code coverage ≥80%
- ✅ Manual QA scenarios validated
- ✅ Documentation updated
- ✅ Performance benchmarks met

---

## Dependencies & Execution Order

### User Story Dependencies

```
Phase 1 (Setup)
      ↓
Phase 2 (Foundational)
      ↓
Phase 3 (US1) ←── MVP (can deploy independently)
      ↓
Phase 4 (US2) ←── Builds on US1 (validation only, no new code)
      ↓
Phase 5 (US3) ←── Builds on US1 (adds bulk operations)
      ↓
Phase 6 (US4) ←── Builds on US1 (adds pre-flight validation)
      ↓
Phase 7 (Polish)
```

**Independent Stories**: US2, US3, US4 can be implemented in any order after US1 completes

**Blocking Dependencies**:
- Phase 2 must complete before any user story
- Phase 3 (US1) must complete before US2, US3, US4

### Parallel Execution Opportunities

**Within Phase 2** (can run concurrently):
- T009-T010: Audit log entity + repository
- T011: Audit log tests
- T014-T017: All DTOs (independent files)
- T018-T024: Repository method additions (independent per repository)

**Within Phase 3 - US1** (can run concurrently after tests written):
- T042-T043: Frontend assetService updates
- T045: DeletionErrorAlert component
- T047-T048: E2E tests (after backend complete)

**Within Phase 5 - US3** (can run concurrently after backend SSE endpoint ready):
- T063-T064: SSE service
- T065-T069: Progress modal component

**Within Phase 6 - US4** (can run concurrently):
- T083-T084: assetService updates
- T091-T093: E2E tests (after modal complete)

**Within Phase 7** (can run all in parallel):
- T094-T098: Documentation and optimization tasks

---

## Summary

**Total Tasks**: 107
- Phase 1 (Setup): 8 tasks
- Phase 2 (Foundational): 16 tasks
- Phase 3 (US1 - MVP): 24 tasks
- Phase 4 (US2): 4 tasks
- Phase 5 (US3): 20 tasks
- Phase 6 (US4): 21 tasks
- Phase 7 (Polish): 14 tasks

**Parallel Tasks**: 26 tasks marked [P] can run concurrently

**Story Breakdown**:
- US1 (P1 - MVP): 24 tasks → Delivers core cascade deletion
- US2 (P2): 4 tasks → Validates audit preservation
- US3 (P2): 20 tasks → Adds bulk operations + SSE
- US4 (P3): 21 tasks → Adds pre-flight warnings

**MVP Scope**: Phases 1-3 (48 tasks total)
- Delivers complete cascade deletion with audit logging
- Pessimistic locking for concurrency
- Contract + integration + E2E tests
- Deployable independently

**Estimated Effort**:
- MVP (Phases 1-3): ~3-4 days (single developer)
- Full Feature (All Phases): ~5-7 days (single developer)
- With Parallel Execution: ~4-5 days (2 developers)

**Next Steps**:
1. Start with Phase 1 (Setup) to verify environment
2. Complete Phase 2 (Foundational) to build shared infrastructure
3. Implement Phase 3 (US1) following TDD workflow
4. Deploy MVP after Phase 3 completion
5. Incrementally add US2, US3, US4 based on business priority
