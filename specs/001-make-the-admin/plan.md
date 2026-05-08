# Implementation Plan: Admin Sidebar Visibility Control

**Branch**: `001-make-the-admin` | **Date**: 2025-10-02 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-make-the-admin/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   ✓ Loaded successfully
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   ✓ Project Type detected: Web application (frontend + backend)
   ✓ Structure Decision: Option 2 (Web application structure)
3. Fill the Constitution Check section
   ✓ Constitution template found (not customized for this project)
4. Evaluate Constitution Check section
   ✓ No constitutional violations - simple UI change
5. Execute Phase 0 → research.md
   ✓ No NEEDS CLARIFICATION items
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, AGENTS.md
   ✓ Ready to generate
7. Re-evaluate Constitution Check section
   ✓ No violations after design
8. Plan Phase 2 → Describe task generation approach
   ✓ Ready to document
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 9. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Implement role-based conditional rendering for the Admin sidebar menu item in both Sidebar.astro and Sidebar.tsx components. The feature leverages the existing authentication infrastructure (window.currentUser global variable and userLoaded event) to check if the authenticated user has ROLE_ADMIN before displaying the Admin navigation entry. This is a frontend-only change with no backend API modifications required.

## Technical Context
**Language/Version**:
- Frontend: TypeScript with Astro 5.13.5, React 19.1.1
- Backend: Kotlin 2.1.0 with Micronaut 4.4.3

**Primary Dependencies**:
- Frontend: Astro, React, Bootstrap 5.3.8, Playwright (testing)
- Backend: Micronaut, Micronaut Security JWT, MariaDB

**Storage**: MariaDB (for user/role data), localStorage (client-side auth token/user data)

**Testing**: Playwright E2E tests (following existing patterns in `/src/frontend/tests/*.spec.ts`)

**Target Platform**: Web browsers (responsive design for desktop/mobile)

**Project Type**: Web application (backend + frontend)

**Performance Goals**: Instant UI response (<16ms for sidebar render), no API calls required for role check

**Constraints**:
- Must use existing authentication infrastructure (no new APIs)
- Must work with both Sidebar.astro and Sidebar.tsx components
- Must handle edge cases (missing user data, role changes)

**Scale/Scope**: Single feature touching 2 UI components + 1 test file

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Note**: Project uses template constitution (not customized). Applying general software engineering principles:

- ✅ **Simplicity**: Leverages existing `window.currentUser` and `userLoaded` event - no new infrastructure
- ✅ **Testability**: E2E tests will verify behavior for admin/non-admin users
- ✅ **Security**: Frontend-only visibility control (backend already enforces authorization)
- ✅ **Consistency**: Follows existing auth patterns from `auth.ts` and Layout.astro
- ✅ **Maintainability**: Changes isolated to sidebar components

**Initial Constitution Check**: PASS

## Project Structure

### Documentation (this feature)
```
specs/001-make-the-admin/
├── spec.md              # Feature specification (complete)
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
│   └── sidebar-behavior.contract.md
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/
├── backendng/          # Kotlin backend (Micronaut)
│   ├── src/main/kotlin/com/secman/
│   │   ├── domain/User.kt          # User entity with roles (existing)
│   │   ├── controller/AuthController.kt  # Auth endpoints (existing)
│   │   └── security/               # JWT security (existing)
│   └── tests/
│
└── frontend/           # Astro + React frontend
    ├── src/
    │   ├── components/
    │   │   ├── Sidebar.astro       # PRIMARY: Add role check (line 17)
    │   │   └── Sidebar.tsx         # PRIMARY: Add role check (line 127-131)
    │   ├── layouts/Layout.astro    # Sets window.currentUser (existing)
    │   └── utils/auth.ts           # Auth utilities (existing)
    └── tests/
        ├── admin-sidebar-visibility.spec.ts  # NEW: E2E tests
        ├── admin-ui-access.spec.ts          # REFERENCE: Similar test pattern
        └── test-helpers.ts                   # Existing helpers

tests/
└── [root level e2e tests if needed]
```

**Structure Decision**: Web application structure (Option 2). Frontend and backend are separated under `src/backendng` and `src/frontend`. This feature only touches frontend components and tests - no backend changes required as role-based authorization is already enforced server-side.

## Phase 0: Outline & Research

**Unknowns Analysis**: No NEEDS CLARIFICATION items in Technical Context. All required information is available from codebase analysis:
- ✅ User authentication mechanism: JWT tokens in localStorage
- ✅ User role structure: `User.roles: MutableSet<Role>` with `Role.ADMIN` enum
- ✅ Frontend access pattern: `window.currentUser` set by Layout.astro
- ✅ Event mechanism: `userLoaded` event dispatched on auth success
- ✅ Testing framework: Playwright with existing patterns

**Research Tasks**: None required - using existing patterns.

**Output**: See research.md for consolidated findings

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

### 1. Extract entities from feature spec → `data-model.md`
- **User** (existing entity - no changes needed)
  - Roles collection includes ROLE_ADMIN
  - Accessible via `window.currentUser.roles`

### 2. Generate API contracts → `/contracts/`
- **No new API endpoints required** - using existing auth infrastructure
- Document **UI Behavior Contract** for sidebar component interactions
  - Input: `window.currentUser.roles`
  - Output: Filtered sidebar items based on role
  - Edge cases: null user, empty roles, role changes

### 3. Generate contract tests
- Playwright E2E tests in `admin-sidebar-visibility.spec.ts`
- Test scenarios from spec acceptance criteria
- Must fail initially (no implementation yet)

### 4. Extract test scenarios → `quickstart.md`
- Manual testing steps for verification
- Automated E2E test execution commands
- Validation criteria from functional requirements

### 5. Update AGENTS.md (agent-specific file)
- Run `.specify/scripts/bash/update-agent-context.sh claude`
- Add context about sidebar role-based rendering
- Keep incremental (O(1) operation)

**Output**: data-model.md, /contracts/sidebar-behavior.contract.md, failing tests, quickstart.md, updated AGENTS.md

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
1. Load `.specify/templates/tasks-template.md` as base
2. Generate tasks in TDD order:
   - Phase 1: Write failing E2E tests (3 test scenarios)
   - Phase 2: Implement Sidebar.astro role check
   - Phase 3: Implement Sidebar.tsx role check
   - Phase 4: Verify tests pass
   - Phase 5: Manual QA with quickstart.md

**Task Dependencies**:
```
[1] Write E2E test: Admin user sees Admin menu
[2] Write E2E test: Non-admin doesn't see Admin menu [parallel with 1]
[3] Write E2E test: Role change updates visibility [parallel with 1,2]
[4] Implement role check in Sidebar.astro [depends on 1,2,3]
[5] Implement role check in Sidebar.tsx [parallel with 4]
[6] Run E2E tests and fix failures [depends on 4,5]
[7] Manual QA following quickstart.md [depends on 6]
```

**Ordering Strategy**:
- Tests first (TDD)
- Independent components in parallel (Sidebar.astro and Sidebar.tsx)
- Validation last

**Estimated Output**: 7-8 numbered tasks in tasks.md

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)
**Phase 4**: Implementation (execute tasks.md following TDD)
**Phase 5**: Validation (run E2E tests, manual testing with quickstart.md)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

No violations - table empty.

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [x] Phase 3: Tasks generated (/tasks command)
- [x] Phase 4: Implementation complete (/implement command)
- [ ] Phase 5: Validation passed (requires manual testing)

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved
- [x] Complexity deviations documented (none)

---
*Based on project constitution principles - See `.specify/memory/constitution.md`*
