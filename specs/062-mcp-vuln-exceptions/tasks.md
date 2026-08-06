# Tasks: MCP Tools for Overdue Vulnerabilities and Exception Handling

**Input**: Design documents from `/specs/062-mcp-vuln-exceptions/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Not requested for this feature.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization - using existing project structure

**Note**: No setup tasks required. Project already has:
- MCP infrastructure in `src/backendng/src/main/kotlin/com/secman/mcp/`
- Existing services: `OutdatedAssetService`, `VulnerabilityExceptionRequestService`
- Existing DTOs and entities

**Checkpoint**: Project ready - proceed to implementation

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**Note**: No foundational tasks required. All infrastructure exists:
- `McpTool` interface and `McpToolResult` classes
- `McpExecutionContext` for access control
- `McpToolRegistry` for tool registration
- Existing services with required methods

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - View Overdue Assets (Priority: P1) MVP

**Goal**: Security analysts can query overdue assets via MCP with filtering options

**Independent Test**: Call `get_overdue_assets` tool with pagination and filtering parameters

### Implementation for User Story 1

- [x] T001 [US1] Create GetOverdueAssetsTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetOverdueAssetsTool.kt`
  - Implement McpTool interface with name `get_overdue_assets`
  - Add inputSchema for page, size, minSeverity, searchTerm parameters
  - Check delegation and ADMIN/VULN role requirement
  - Create Authentication adapter from McpExecutionContext
  - Call OutdatedAssetService.getOutdatedAssets()
  - Return paginated response with OutdatedAssetDto mapping

**Checkpoint**: User Story 1 complete - overdue assets queryable via MCP

---

## Phase 4: User Story 2 - Create Exception Request (Priority: P1) MVP

**Goal**: Users can create exception requests via MCP with auto-approval for privileged roles

**Independent Test**: Call `create_exception_request` tool with vulnerability ID, reason, and expiration date

### Implementation for User Story 2

- [x] T002 [US2] Create CreateExceptionRequestTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateExceptionRequestTool.kt`
  - Implement McpTool interface with name `create_exception_request`
  - Add inputSchema with required fields: vulnerabilityId, reason, expirationDate
  - Add optional scope field (defaults to SINGLE_VULNERABILITY)
  - Check delegation requirement
  - Validate reason length (50-2048 characters)
  - Call VulnerabilityExceptionRequestService.createRequest()
  - Return appropriate message based on autoApproved status

**Checkpoint**: User Story 2 complete - exception requests can be created via MCP

---

## Phase 5: User Story 3 - View My Exception Requests (Priority: P2)

**Goal**: Users can view their own exception requests with status filtering

**Independent Test**: Call `get_my_exception_requests` tool with status filter parameter

### Implementation for User Story 3

- [x] T003 [US3] Create GetMyExceptionRequestsTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetMyExceptionRequestsTool.kt`
  - Implement McpTool interface with name `get_my_exception_requests`
  - Add inputSchema for page, size, status filter parameters
  - Check delegation requirement
  - Call VulnerabilityExceptionRequestService.getUserRequests() with delegatedUserId
  - Return paginated response with VulnerabilityExceptionRequestDto mapping

**Checkpoint**: User Story 3 complete - users can view their exception requests via MCP

---

## Phase 6: User Story 4 - View Pending Requests (Priority: P2)

**Goal**: Approvers can see pending exception requests awaiting review

**Independent Test**: Call `get_pending_exception_requests` tool with ADMIN/SECCHAMPION role

### Implementation for User Story 4

- [x] T004 [US4] Create GetPendingExceptionRequestsTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetPendingExceptionRequestsTool.kt`
  - Implement McpTool interface with name `get_pending_exception_requests`
  - Add inputSchema for page, size parameters
  - Check delegation and ADMIN/SECCHAMPION role requirement
  - Call VulnerabilityExceptionRequestService.getPendingRequests()
  - Include pendingCount in response for badge display
  - Sort by createdAt ascending (FIFO processing)

**Checkpoint**: User Story 4 complete - approvers can view pending requests via MCP

---

## Phase 7: User Story 5 - Approve Exception Request (Priority: P2)

**Goal**: Approvers can approve pending requests, creating corresponding VulnerabilityException

**Independent Test**: Call `approve_exception_request` tool with request ID and optional comment

### Implementation for User Story 5

- [x] T005 [US5] Create ApproveExceptionRequestTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/ApproveExceptionRequestTool.kt`
  - Implement McpTool interface with name `approve_exception_request`
  - Add inputSchema with required requestId and optional comment (max 1024 chars)
  - Check delegation and ADMIN/SECCHAMPION role requirement
  - Call VulnerabilityExceptionRequestService.approveRequest()
  - Handle NOT_FOUND, INVALID_STATE, CONCURRENT_MODIFICATION errors
  - Return updated request with success message

**Checkpoint**: User Story 5 complete - approvers can approve requests via MCP

---

## Phase 8: User Story 6 - Reject Exception Request (Priority: P2)

**Goal**: Approvers can reject pending requests with required justification

**Independent Test**: Call `reject_exception_request` tool with request ID and comment

### Implementation for User Story 6

- [x] T006 [US6] Create RejectExceptionRequestTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/RejectExceptionRequestTool.kt`
  - Implement McpTool interface with name `reject_exception_request`
  - Add inputSchema with required requestId and comment (10-1024 chars)
  - Check delegation and ADMIN/SECCHAMPION role requirement
  - Validate comment minimum length (10 characters required)
  - Call VulnerabilityExceptionRequestService.rejectRequest()
  - Handle NOT_FOUND, INVALID_STATE, CONCURRENT_MODIFICATION errors
  - Return updated request with rejection message

**Checkpoint**: User Story 6 complete - approvers can reject requests via MCP

---

## Phase 9: User Story 7 - Cancel Exception Request (Priority: P3)

**Goal**: Users can cancel their own pending exception requests

**Independent Test**: Call `cancel_exception_request` tool with request ID as original requester

### Implementation for User Story 7

- [x] T007 [US7] Create CancelExceptionRequestTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/CancelExceptionRequestTool.kt`
  - Implement McpTool interface with name `cancel_exception_request`
  - Add inputSchema with required requestId
  - Check delegation requirement
  - Call VulnerabilityExceptionRequestService.cancelRequest() with delegatedUserId
  - Handle NOT_FOUND, FORBIDDEN (ownership), INVALID_STATE errors
  - Return updated request with cancellation message

**Checkpoint**: User Story 7 complete - users can cancel their own requests via MCP

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: Integration and documentation tasks that affect all user stories

### Tool Registration

- [x] T008 Register all 7 new tools in `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`
  - Add constructor injections for all tools
  - Add tools to the tools list
  - Add authorization mappings in isToolAuthorized() method

### Documentation

- [x] T009 [P] Update MCP documentation in `docs/MCP.md`
  - Add new tools to Permission Types table
  - Add "Vulnerability Management" section with all 7 tools
  - Document parameters, required roles, and response formats
  - Add usage examples for common workflows

### Verification

- [x] T010 Run `./gradlew build` to verify compilation and no errors
- [x] T011 Verify tools appear in MCP tool listing

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No tasks - existing infrastructure
- **Foundational (Phase 2)**: No tasks - existing infrastructure
- **User Stories (Phase 3-9)**: Can proceed in parallel - all tools are independent
- **Polish (Phase 10)**: Depends on all tool implementations being complete

### User Story Dependencies

- **User Story 1 (P1)**: No dependencies - can start immediately
- **User Story 2 (P1)**: No dependencies - can start immediately
- **User Story 3 (P2)**: No dependencies - can start immediately
- **User Story 4 (P2)**: No dependencies - can start immediately
- **User Story 5 (P2)**: No dependencies - can start immediately
- **User Story 6 (P2)**: No dependencies - can start immediately
- **User Story 7 (P3)**: No dependencies - can start immediately

### Within Each User Story

- Single task per story (one tool per story)
- Tool implementation is self-contained

### Parallel Opportunities

All 7 tool implementations (T001-T007) can run in parallel since they:
- Create different files
- Use existing services (no service modifications needed)
- Have no dependencies on each other

---

## Parallel Example: All Tool Implementations

```bash
# All tools can be implemented in parallel:
Task: "Create GetOverdueAssetsTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetOverdueAssetsTool.kt"
Task: "Create CreateExceptionRequestTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateExceptionRequestTool.kt"
Task: "Create GetMyExceptionRequestsTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetMyExceptionRequestsTool.kt"
Task: "Create GetPendingExceptionRequestsTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetPendingExceptionRequestsTool.kt"
Task: "Create ApproveExceptionRequestTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/ApproveExceptionRequestTool.kt"
Task: "Create RejectExceptionRequestTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/RejectExceptionRequestTool.kt"
Task: "Create CancelExceptionRequestTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/CancelExceptionRequestTool.kt"
```

---

## Implementation Strategy

### MVP First (User Stories 1 & 2)

1. Implement T001: GetOverdueAssetsTool
2. Implement T002: CreateExceptionRequestTool
3. Complete T008: Register tools in McpToolRegistry
4. Run T010: Build verification
5. **STOP and VALIDATE**: Test overdue asset query and exception creation

### Incremental Delivery

1. MVP (T001, T002, T008, T010) → Query overdue assets, create requests
2. Add T003, T004 → Users can track requests, approvers can see queue
3. Add T005, T006 → Complete approval workflow
4. Add T007 → Users can cancel pending requests
5. T009, T011 → Documentation and final verification

### Parallel Team Strategy

With multiple developers:

1. **Developer A**: T001, T002 (MVP tools)
2. **Developer B**: T003, T004 (read tools)
3. **Developer C**: T005, T006, T007 (write tools)
4. **Any developer**: T008, T009, T010, T011 (integration)

---

## Notes

- All tools follow existing MCP tool pattern (see ListUsersTool, ListProductsTool)
- Use McpExecutionContext for delegation and role checks
- Delegate to existing services - no new service methods needed
- Error codes defined in contracts/ directory
- No database schema changes required
