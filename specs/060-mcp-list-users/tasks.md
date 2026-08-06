# Tasks: MCP List Users Tool

**Input**: Design documents from `/specs/060-mcp-list-users/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Not included (tests only when explicitly requested per Constitution Principle IV)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`

---

## Phase 1: Setup

**Purpose**: No setup tasks needed - extending existing MCP infrastructure

**Checkpoint**: Existing codebase ready for extension

---

## Phase 2: User Story 1 & 2 - MCP List Users Tool (Priority: P1) 🎯 MVP

**Goal**: Implement `list_users` MCP tool that returns all users for admin users, and denies access for non-admins

**Independent Test**: Call the MCP `list_users` tool with an admin user's delegation to verify users are returned; call with non-admin to verify access denied

**Note**: User Stories 1 (List Users) and 2 (Deny Non-Admin) are implemented together as they are both aspects of the same tool with the same authorization check.

### Implementation

- [X] T001 [US1] Create ListUsersTool.kt implementing McpTool interface in `src/backendng/src/main/kotlin/com/secman/mcp/tools/ListUsersTool.kt`
- [X] T002 [US1] Implement delegation check (FR-002, FR-004): return DELEGATION_REQUIRED error if `!context.hasDelegation()` in `ListUsersTool.kt`
- [X] T003 [US2] Implement admin role check (FR-003, FR-005, FR-010): return ADMIN_REQUIRED error if `!context.isAdmin` in `ListUsersTool.kt`
- [X] T004 [US1] Implement user retrieval using `UserRepository.findAll()` in `ListUsersTool.kt`
- [X] T005 [US1] Map User entity to response (FR-006, FR-007, FR-008): id, username, email, roles, authSource, mfaEnabled, createdAt, lastLogin in `ListUsersTool.kt`
- [X] T006 [US1] Add totalCount to response metadata (FR-009) in `ListUsersTool.kt`
- [X] T007 Add ListUsersTool injection to McpToolRegistry constructor in `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`
- [X] T008 Register ListUsersTool in tools list in `McpToolRegistry.kt`
- [X] T009 Add `list_users` authorization mapping to isToolAuthorized() function in `McpToolRegistry.kt`

**Checkpoint**: Tool fully implemented with admin-only access control

---

## Phase 3: Polish & Cross-Cutting Concerns

**Purpose**: Final validation

- [X] T010 Run `./gradlew build` to verify backend compiles and tests pass
- [ ] T011 Manual verification: Test tool with admin delegation via MCP client

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No setup needed - using existing infrastructure
- **User Story (Phase 2)**: Can start immediately - all work in same phase
- **Polish (Phase 3)**: Depends on Phase 2 completion

### Task Dependencies Within Phase 2

```
T001 (Create tool class)
  ↓
T002, T003, T004 (can run sequentially - same file)
  ↓
T005, T006 (response mapping - same file)
  ↓
T007, T008, T009 (registry changes - different file, can start after T001)
```

### Parallel Opportunities

Limited parallelism due to small scope:
- T001 must complete first (creates the file)
- T002-T006 are in same file (ListUsersTool.kt) - sequential
- T007-T009 are in different file (McpToolRegistry.kt) - can run after T001

---

## Implementation Strategy

### MVP (Complete Feature)

This is a small feature - all tasks deliver the complete MVP:

1. Complete Phase 2: Create ListUsersTool with authorization
2. Complete Phase 3: Build verification
3. **DONE**: Feature fully delivered

### Task Grouping for Efficiency

Since T001-T006 are in the same file, they can be implemented in a single editing session:

```bash
# Group 1: Create complete ListUsersTool.kt (T001-T006)
# Group 2: Update McpToolRegistry.kt (T007-T009)
# Group 3: Verify build (T010-T011)
```

---

## Notes

- All tasks in Phase 2 affect only 2 files: ListUsersTool.kt (new) and McpToolRegistry.kt (modify)
- Follow existing patterns from DeleteAllRequirementsTool.kt for admin check
- Follow existing patterns from GetRequirementsTool.kt for response structure
- No database changes required
- Commit after completing all tasks (small feature)
