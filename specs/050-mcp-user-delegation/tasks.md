# Tasks: MCP User Delegation

**Input**: Design documents from `/specs/050-mcp-user-delegation/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Not included (per Constitution Principle IV - tests only when explicitly requested)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4, US5)

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Resources**: `src/backendng/src/main/resources/`
- **Docs**: `docs/`

---

## Phase 1: Setup

**Purpose**: Schema migration and configuration

- [ ] T001 Create Flyway migration V050__mcp_user_delegation.sql in src/backendng/src/main/resources/db/migration/V050__mcp_user_delegation.sql
- [ ] T002 [P] Add delegation configuration to src/backendng/src/main/resources/application.yml (mcp.delegation.alert.threshold, window-minutes)

---

## Phase 2: Foundational (Entity Extensions)

**Purpose**: Core entity extensions that ALL user stories depend on - MUST complete before any story

**CRITICAL**: No user story work can begin until this phase is complete

- [ ] T003 Extend McpApiKey entity with delegationEnabled and allowedDelegationDomains in src/backendng/src/main/kotlin/com/secman/domain/McpApiKey.kt
- [ ] T004 Add isDelegationAllowedForEmail() and getDelegationDomainsList() methods to McpApiKey in src/backendng/src/main/kotlin/com/secman/domain/McpApiKey.kt
- [ ] T005 [P] Extend McpAuditLog entity with delegatedUserEmail and delegatedUserId in src/backendng/src/main/kotlin/com/secman/domain/McpAuditLog.kt
- [ ] T006 Create McpDelegationDtos (DelegationResult, DelegationFailureRecord) in src/backendng/src/main/kotlin/com/secman/dto/mcp/McpDelegationDtos.kt
- [ ] T007 Create McpDelegationService skeleton with role-to-permission mapping in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt
- [ ] T008 Implement computeEffectivePermissions() in McpDelegationService in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt

**Checkpoint**: Foundation ready - entity extensions complete, delegation service skeleton in place

---

## Phase 3: User Story 1 - External Tool Delegation (Priority: P1)

**Goal**: External tools can pass user email via X-MCP-User-Email header and get intersection permissions applied

**Independent Test**: Send MCP request with delegation-enabled key + user email, verify response reflects user's permissions

### Implementation for User Story 1

- [ ] T009 [US1] Implement validateDelegation() in McpDelegationService (domain check, user lookup, status check) in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt
- [ ] T010 [US1] Implement validateAndGetPermissions() in McpDelegationService in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt
- [ ] T011 [US1] Update McpController.callTool() to extract X-MCP-User-Email header in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T012 [US1] Update McpController.callTool() to call delegationService when header present and key has delegation enabled in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T013 [US1] Update McpController.getCapabilities() to handle delegation header in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T014 [US1] Update McpController.createSession() to handle delegation header in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T015 [US1] Add delegation error codes (DELEGATION_FAILED, DELEGATION_DOMAIN_REJECTED, DELEGATION_USER_INACTIVE) to error responses in src/backendng/src/main/kotlin/com/secman/dto/mcp/McpDelegationDtos.kt

**Checkpoint**: External tools can now delegate requests to users with proper permission intersection

---

## Phase 4: User Story 2 - Admin Configures Delegation Keys (Priority: P1)

**Goal**: Administrators can enable delegation mode and configure domain restrictions on API keys

**Independent Test**: Create API key via admin UI with delegation enabled, verify domains are required and saved

### Implementation for User Story 2

- [ ] T016 [P] [US2] Update McpApiKeyController create endpoint with delegation fields in src/backendng/src/main/kotlin/com/secman/controller/McpApiKeyController.kt
- [ ] T017 [P] [US2] Update McpApiKeyController update endpoint with delegation fields in src/backendng/src/main/kotlin/com/secman/controller/McpApiKeyController.kt
- [ ] T018 [US2] Add validation: delegation requires at least one domain in McpApiKeyController in src/backendng/src/main/kotlin/com/secman/controller/McpApiKeyController.kt
- [ ] T019 [US2] Add domain format validation (must start with @, contain TLD) in McpApiKeyController in src/backendng/src/main/kotlin/com/secman/controller/McpApiKeyController.kt
- [ ] T020 [US2] Update API key response DTOs to include delegationEnabled and allowedDelegationDomains in src/backendng/src/main/kotlin/com/secman/dto/mcp/
- [ ] T021 [P] [US2] Add delegation toggle and domains input to McpApiKeyForm component in src/frontend/src/components/McpApiKeyForm.tsx
- [ ] T022 [US2] Add client-side validation (domains required when delegation enabled) in src/frontend/src/components/McpApiKeyForm.tsx
- [ ] T023 [US2] Update API key list to show delegation status badge in src/frontend/src/pages/admin/mcp-api-keys.astro
- [ ] T024 [US2] Update API key detail view to show allowed domains in src/frontend/src/pages/admin/mcp-api-keys.astro

**Checkpoint**: Admins can configure delegation-enabled API keys through the UI

---

## Phase 5: User Story 3 - Audit Trail for Delegated Requests (Priority: P2)

**Goal**: Delegated requests are logged with both API key and delegated user email for compliance

**Independent Test**: Make delegated request, verify audit log contains delegatedUserEmail field

### Implementation for User Story 3

- [ ] T025 [US3] Update McpAuditService logging methods to accept delegatedUserEmail and delegatedUserId in src/backendng/src/main/kotlin/com/secman/service/McpAuditService.kt
- [ ] T026 [US3] Update McpAuditService.logToolCall() to include delegation info in src/backendng/src/main/kotlin/com/secman/service/McpAuditService.kt
- [ ] T027 [US3] Update McpController to pass delegation info to audit logging in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T028 [US3] Add delegatedUserEmail filter to audit log query endpoint in src/backendng/src/main/kotlin/com/secman/controller/McpAuditLogController.kt
- [ ] T029 [P] [US3] Update McpAuditLogRepository with findByDelegatedUserEmail query in src/backendng/src/main/kotlin/com/secman/repository/McpAuditLogRepository.kt

**Checkpoint**: Security teams can track and filter delegated requests in audit logs

---

## Phase 6: User Story 4 - Fallback to API Key Permissions (Priority: P2)

**Goal**: When no email header provided (or empty), system falls back to API key permissions for backward compatibility

**Independent Test**: Use delegation-enabled key without email header, verify API key's base permissions are used

### Implementation for User Story 4

- [ ] T030 [US4] Handle empty/blank email header as fallback case in McpController.callTool() in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T031 [US4] Ensure legacy (non-delegation) keys ignore X-MCP-User-Email header in McpController in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T032 [US4] Add logging for fallback cases (delegation disabled or no email) in McpController in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt

**Checkpoint**: Existing MCP integrations continue working without modification

---

## Phase 7: User Story 5 - Domain Restriction Enforcement (Priority: P3)

**Goal**: Requests with email outside allowed domains are rejected with clear error

**Independent Test**: Configure key with @company.com domain, try to delegate for @other.com user, verify rejection

### Implementation for User Story 5

- [ ] T033 [US5] Implement domain validation logic with case-insensitive matching in McpDelegationService in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt
- [ ] T034 [US5] Return DELEGATION_DOMAIN_REJECTED error with clear message in McpController in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T035 [US5] Log domain rejection attempts for security monitoring in McpDelegationService in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt

**Checkpoint**: Domain restrictions prevent unauthorized delegation attempts

---

## Phase 8: Security Alerting (FR-013)

**Goal**: Alert administrators when failed delegation attempts exceed threshold

**Implementation**

- [ ] T036 Implement trackDelegationFailure() with in-memory sliding window in McpDelegationService in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt
- [ ] T037 Implement checkAlertThreshold() that triggers alert when threshold exceeded in McpDelegationService in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt
- [ ] T038 Call trackDelegationFailure() on all delegation failures (domain, user not found, inactive) in McpDelegationService in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt
- [ ] T039 Log security alert with WARN level for monitoring pickup in McpDelegationService in src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt

**Checkpoint**: Abuse patterns are detected and alerted

---

## Phase 9: Polish & Documentation

**Purpose**: Documentation updates and final verification

- [ ] T040 [P] Add User Delegation section to docs/MCP_INTEGRATION.md
- [ ] T041 [P] Document X-MCP-User-Email header usage in docs/MCP_INTEGRATION.md
- [ ] T042 [P] Document domain restriction configuration in docs/MCP_INTEGRATION.md
- [ ] T043 [P] Add example delegation configurations to docs/MCP_INTEGRATION.md
- [ ] T044 Run ./gradlew build to verify all changes compile in src/backendng/
- [ ] T045 Verify database migration applies cleanly with Flyway

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup - BLOCKS all user stories
- **User Stories (Phase 3-7)**: All depend on Foundational completion
  - US1 and US2 are both P1 and can proceed in parallel after foundational
  - US3 and US4 are P2, can proceed after US1/US2 core is in place
  - US5 is P3, lowest priority
- **Security Alerting (Phase 8)**: Can proceed after Foundational (independent of stories)
- **Polish (Phase 9)**: Depends on all implementation phases

### User Story Dependencies

| Story | Depends On | Can Start After |
|-------|------------|-----------------|
| US1 (Delegation Core) | Foundational | Phase 2 complete |
| US2 (Admin Config) | Foundational | Phase 2 complete |
| US3 (Audit Trail) | US1 (needs delegation flow) | T012 complete |
| US4 (Fallback) | US1 (modifies same controller) | T012 complete |
| US5 (Domain Enforcement) | US1 (uses validation logic) | T009 complete |

### Parallel Opportunities

**Phase 2 (Foundational)**:
```
T003 McpApiKey entity ─┬─▶ T004 McpApiKey methods ─▶ T007 Service skeleton ─▶ T008 computeEffectivePermissions
T005 McpAuditLog entity ┘
T006 DTOs (parallel with T003-T005)
```

**Phase 4 (US2 - Admin Config)**:
```
T016 Create endpoint ─┐
T017 Update endpoint ─┼─▶ T018-T019 Validation ─▶ T020 DTOs
T021 Frontend form ───┘
```

**Phase 9 (Polish)**:
```
T040 + T041 + T042 + T043 can all run in parallel (different doc sections)
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Complete Phase 1: Setup (migration, config)
2. Complete Phase 2: Foundational (entity extensions)
3. Complete Phase 3: US1 (delegation core logic)
4. Complete Phase 4: US2 (admin configuration)
5. **STOP and VALIDATE**: Test delegation end-to-end
6. Deploy MVP

### Incremental Delivery

1. MVP (US1 + US2) → Core delegation working
2. Add US3 (Audit) → Compliance ready
3. Add US4 (Fallback) → Backward compatible
4. Add US5 (Domain) → Security hardened
5. Add Phase 8 (Alerting) → Monitoring ready
6. Complete Polish → Documentation complete

---

## Summary

| Phase | Tasks | Parallelizable |
|-------|-------|----------------|
| Setup | 2 | 1 |
| Foundational | 6 | 1 |
| US1 (Delegation Core) | 7 | 0 |
| US2 (Admin Config) | 9 | 4 |
| US3 (Audit Trail) | 5 | 1 |
| US4 (Fallback) | 3 | 0 |
| US5 (Domain Enforcement) | 3 | 0 |
| Security Alerting | 4 | 0 |
| Polish | 6 | 4 |
| **Total** | **45** | **11** |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story
- Tests not included per Constitution Principle IV
- Commit after each task or logical group
- Stop at any checkpoint to validate independently
