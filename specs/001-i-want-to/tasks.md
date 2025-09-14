# Tasks: MCP Server Integration

**Input**: Design documents from `/Users/flake/sources/misc/secman/specs/001-i-want-to/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Tech stack: Kotlin 2.0.21, Micronaut 4.4.3, MariaDB, Astro 5.13.5, React 19.1.1
   → Structure: Web app (backend + frontend)
2. Load design documents:
   → data-model.md: 4 entities (McpApiKey, McpSession, McpAuditLog, McpToolPermission)
   → contracts/: mcp-api.yaml (8 endpoints), mcp-tools.yaml (10 MCP tools)
   → research.md: MCP SDK, authentication, performance targets
3. Generate tasks by category:
   → Setup: MCP SDK dependencies, enum definitions
   → Tests: Contract tests for 8 API endpoints, integration tests
   → Core: 4 entity models, MCP services, tool implementations
   → Integration: Controllers, authentication, session management
   → Polish: Performance tests, documentation, UI components
4. Apply TDD rules: All tests before implementation
5. 48 tasks generated with parallel execution optimization
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- File paths are absolute from repository root

## Phase 3.1: Setup Dependencies
- [ ] T001 Add MCP SDK dependencies to src/backendng/build.gradle.kts
- [ ] T002 [P] Create MCP enum classes in src/backendng/src/main/kotlin/com/secman/domain/McpPermission.kt
- [ ] T003 [P] Create MCP enum classes in src/backendng/src/main/kotlin/com/secman/domain/McpEventType.kt
- [ ] T004 [P] Create MCP enum classes in src/backendng/src/main/kotlin/com/secman/domain/McpOperation.kt
- [ ] T005 [P] Create MCP enum classes in src/backendng/src/main/kotlin/com/secman/domain/McpConnectionType.kt

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### Contract Tests (API Endpoints)
- [ ] T006 [P] Contract test GET /api/mcp/capabilities in src/backendng/src/test/kotlin/com/secman/controller/McpCapabilitiesTest.kt
- [ ] T007 [P] Contract test POST /api/mcp/session in src/backendng/src/test/kotlin/com/secman/controller/McpSessionCreateTest.kt
- [ ] T008 [P] Contract test DELETE /api/mcp/session/{sessionId} in src/backendng/src/test/kotlin/com/secman/controller/McpSessionDeleteTest.kt
- [ ] T009 [P] Contract test POST /api/mcp/tools/list in src/backendng/src/test/kotlin/com/secman/controller/McpToolsListTest.kt
- [ ] T010 [P] Contract test POST /api/mcp/tools/call in src/backendng/src/test/kotlin/com/secman/controller/McpToolsCallTest.kt
- [ ] T011 [P] Contract test GET /api/mcp/sse/{sessionId} in src/backendng/src/test/kotlin/com/secman/controller/McpSseTest.kt
- [ ] T012 [P] Contract test POST /api/mcp/admin/api-keys in src/backendng/src/test/kotlin/com/secman/controller/McpApiKeyCreateTest.kt
- [ ] T013 [P] Contract test GET /api/mcp/admin/api-keys in src/backendng/src/test/kotlin/com/secman/controller/McpApiKeyListTest.kt

### Integration Tests (User Stories)
- [ ] T014 [P] Integration test MCP session lifecycle in src/backendng/src/test/kotlin/com/secman/integration/McpSessionLifecycleTest.kt
- [ ] T015 [P] Integration test MCP tool execution in src/backendng/src/test/kotlin/com/secman/integration/McpToolExecutionTest.kt
- [ ] T016 [P] Integration test API key authentication in src/backendng/src/test/kotlin/com/secman/integration/McpAuthenticationTest.kt
- [ ] T017 [P] Integration test concurrent MCP clients in src/backendng/src/test/kotlin/com/secman/integration/McpConcurrencyTest.kt
- [ ] T018 [P] Integration test MCP audit logging in src/backendng/src/test/kotlin/com/secman/integration/McpAuditLoggingTest.kt

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### Database Entities
- [ ] T019 [P] McpApiKey entity in src/backendng/src/main/kotlin/com/secman/domain/McpApiKey.kt
- [ ] T020 [P] McpSession entity in src/backendng/src/main/kotlin/com/secman/domain/McpSession.kt
- [ ] T021 [P] McpAuditLog entity in src/backendng/src/main/kotlin/com/secman/domain/McpAuditLog.kt
- [ ] T022 [P] McpToolPermission entity in src/backendng/src/main/kotlin/com/secman/domain/McpToolPermission.kt

### Repository Layer
- [ ] T023 [P] McpApiKeyRepository in src/backendng/src/main/kotlin/com/secman/repository/McpApiKeyRepository.kt
- [ ] T024 [P] McpSessionRepository in src/backendng/src/main/kotlin/com/secman/repository/McpSessionRepository.kt
- [ ] T025 [P] McpAuditLogRepository in src/backendng/src/main/kotlin/com/secman/repository/McpAuditLogRepository.kt
- [ ] T026 [P] McpToolPermissionRepository in src/backendng/src/main/kotlin/com/secman/repository/McpToolPermissionRepository.kt

### Core MCP Services
- [ ] T027 McpProtocolService (session management, protocol handling) in src/backendng/src/main/kotlin/com/secman/service/McpProtocolService.kt
- [ ] T028 McpAuthenticationService (API key validation) in src/backendng/src/main/kotlin/com/secman/service/McpAuthenticationService.kt
- [ ] T029 McpSessionManagerService (session lifecycle) in src/backendng/src/main/kotlin/com/secman/service/McpSessionManagerService.kt
- [ ] T030 McpAuditService (audit logging) in src/backendng/src/main/kotlin/com/secman/service/McpAuditService.kt

### MCP Tool Implementations
- [ ] T031 [P] GetRequirementsTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetRequirementsTool.kt
- [ ] T032 [P] CreateRequirementTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateRequirementTool.kt
- [ ] T033 [P] GetRiskAssessmentsTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetRiskAssessmentsTool.kt
- [ ] T034 [P] ExecuteRiskAssessmentTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/ExecuteRiskAssessmentTool.kt
- [ ] T035 [P] DownloadFileTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/DownloadFileTool.kt
- [ ] T036 [P] TranslateRequirementTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/TranslateRequirementTool.kt
- [ ] T037 [P] SearchAllTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/SearchAllTool.kt

### Tool Registry and Management
- [ ] T038 McpToolRegistry (tool discovery and execution) in src/backendng/src/main/kotlin/com/secman/service/McpToolRegistry.kt
- [ ] T039 McpPermissionService (role-based access control) in src/backendng/src/main/kotlin/com/secman/service/McpPermissionService.kt

## Phase 3.4: API Controllers

### MCP Protocol Controllers
- [ ] T040 McpController (capabilities, session, tools) in src/backendng/src/main/kotlin/com/secman/controller/McpController.kt
- [ ] T041 McpSseController (Server-Sent Events) in src/backendng/src/main/kotlin/com/secman/controller/McpSseController.kt
- [ ] T042 McpApiKeyController (API key management) in src/backendng/src/main/kotlin/com/secman/controller/McpApiKeyController.kt

### Request/Response DTOs
- [ ] T043 [P] MCP request/response DTOs in src/backendng/src/main/kotlin/com/secman/dto/McpDtos.kt

## Phase 3.5: Frontend Integration

### API Key Management UI
- [ ] T044 [P] ApiKeyManagement React component in src/frontend/src/components/ApiKeyManagement.tsx
- [ ] T045 ApiKeyManagement page in src/frontend/src/pages/settings/api-keys.astro
- [ ] T046 [P] MCP status dashboard component in src/frontend/src/components/McpDashboard.tsx

## Phase 3.6: Polish & Validation

### Performance and Load Testing
- [ ] T047 [P] MCP performance tests (<100ms response time) in src/backendng/src/test/kotlin/com/secman/performance/McpPerformanceTest.kt
- [ ] T048 [P] MCP load tests (200 concurrent connections) in src/backendng/src/test/kotlin/com/secman/performance/McpLoadTest.kt

## Dependencies
**Critical TDD Dependencies:**
- Tests T006-T018 MUST complete and FAIL before ANY implementation (T019-T048)
- T001-T005 (setup) before all other tasks
- T019-T022 (entities) before T023-T026 (repositories)
- T023-T026 (repositories) before T027-T039 (services)
- T027-T030 (core services) before T031-T039 (tools and registry)
- T031-T039 (tools) before T040-T042 (controllers)
- T040-T043 (backend) before T044-T046 (frontend)

**Service Dependencies:**
- T027 (McpProtocolService) blocks T028, T029
- T028 (McpAuthenticationService) blocks T040, T042
- T029 (McpSessionManagerService) blocks T041
- T038 (McpToolRegistry) blocks T040

## Parallel Execution Examples

### Phase 3.1: Setup (All Parallel)
```bash
# Launch T002-T005 together:
Task: "Create MCP enum classes in src/backendng/src/main/kotlin/com/secman/domain/McpPermission.kt"
Task: "Create MCP enum classes in src/backendng/src/main/kotlin/com/secman/domain/McpEventType.kt"
Task: "Create MCP enum classes in src/backendng/src/main/kotlin/com/secman/domain/McpOperation.kt"
Task: "Create MCP enum classes in src/backendng/src/main/kotlin/com/secman/domain/McpConnectionType.kt"
```

### Phase 3.2: Contract Tests (All Parallel)
```bash
# Launch T006-T013 together (different test files):
Task: "Contract test GET /api/mcp/capabilities in src/backendng/src/test/kotlin/com/secman/controller/McpCapabilitiesTest.kt"
Task: "Contract test POST /api/mcp/session in src/backendng/src/test/kotlin/com/secman/controller/McpSessionCreateTest.kt"
Task: "Contract test DELETE /api/mcp/session/{sessionId} in src/backendng/src/test/kotlin/com/secman/controller/McpSessionDeleteTest.kt"
Task: "Contract test POST /api/mcp/tools/list in src/backendng/src/test/kotlin/com/secman/controller/McpToolsListTest.kt"
Task: "Contract test POST /api/mcp/tools/call in src/backendng/src/test/kotlin/com/secman/controller/McpToolsCallTest.kt"
Task: "Contract test GET /api/mcp/sse/{sessionId} in src/backendng/src/test/kotlin/com/secman/controller/McpSseTest.kt"
Task: "Contract test POST /api/mcp/admin/api-keys in src/backendng/src/test/kotlin/com/secman/controller/McpApiKeyCreateTest.kt"
Task: "Contract test GET /api/mcp/admin/api-keys in src/backendng/src/test/kotlin/com/secman/controller/McpApiKeyListTest.kt"
```

### Phase 3.3: Entity Models (All Parallel)
```bash
# Launch T019-T022 together (different entity files):
Task: "McpApiKey entity in src/backendng/src/main/kotlin/com/secman/domain/McpApiKey.kt"
Task: "McpSession entity in src/backendng/src/main/kotlin/com/secman/domain/McpSession.kt"
Task: "McpAuditLog entity in src/backendng/src/main/kotlin/com/secman/domain/McpAuditLog.kt"
Task: "McpToolPermission entity in src/backendng/src/main/kotlin/com/secman/domain/McpToolPermission.kt"
```

### Phase 3.3: MCP Tools (All Parallel)
```bash
# Launch T031-T037 together (different tool files):
Task: "GetRequirementsTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetRequirementsTool.kt"
Task: "CreateRequirementTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateRequirementTool.kt"
Task: "GetRiskAssessmentsTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetRiskAssessmentsTool.kt"
Task: "ExecuteRiskAssessmentTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/ExecuteRiskAssessmentTool.kt"
Task: "DownloadFileTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/DownloadFileTool.kt"
Task: "TranslateRequirementTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/TranslateRequirementTool.kt"
Task: "SearchAllTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/SearchAllTool.kt"
```

## Validation Checklist
After completing all tasks, verify:

- [ ] All contract tests exist and initially FAILED
- [ ] All 4 entities implemented with JPA annotations
- [ ] All 8 API endpoints implemented per OpenAPI spec
- [ ] All 10 MCP tools implemented with proper input/output schemas
- [ ] API key authentication working end-to-end
- [ ] MCP session management with cleanup
- [ ] Server-Sent Events connection established
- [ ] Performance targets met (200 concurrent connections, <100ms responses)
- [ ] Audit logging captures all MCP activities
- [ ] Frontend API key management UI functional
- [ ] Integration tests covering all user stories from quickstart.md

## Notes
- [P] tasks = different files, can run in parallel
- **TDD ENFORCED**: All tests (T006-T018) MUST be written and failing before implementation
- **Performance Critical**: Session management must support 200 concurrent connections
- **Security Critical**: API key authentication and audit logging mandatory
- Each task should result in a single git commit
- Follow existing Secman code patterns and conventions
- Use MockK for unit tests, real database for integration tests

## Manual Testing Script
After implementation completion, execute quickstart.md scenarios:
1. Generate API key via web UI
2. Test MCP server capabilities endpoint
3. Initialize MCP session and verify SSE connection
4. Execute each MCP tool (get_requirements, create_requirement, etc.)
5. Verify audit logs capture all activities
6. Load test with 10 concurrent sessions
7. Validate Claude/ChatGPT integration documentation