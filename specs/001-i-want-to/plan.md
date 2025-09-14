# Implementation Plan: MCP Server Integration


**Branch**: `001-i-want-to` | **Date**: 2025-09-14 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-i-want-to/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from context (web=frontend+backend, mobile=app+api)
   → Set Structure Decision based on project type
3. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
4. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
5. Execute Phase 1 → contracts, data-model.md, quickstart.md, agent-specific template file (e.g., `CLAUDE.md` for Claude Code, `.github/copilot-instructions.md` for GitHub Copilot, or `GEMINI.md` for Gemini CLI).
6. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
7. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
8. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 7. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Implement MCP (Model Context Protocol) server functionality to enable external applications like Claude and ChatGPT to connect and access Secman's security requirements and risk assessment data programmatically. This involves adding API key authentication, MCP protocol handling, role-based access control, and comprehensive documentation for AI assistant integration.

## Technical Context
**Language/Version**: Kotlin 2.0.21 (backend), JavaScript/TypeScript (frontend)
**Primary Dependencies**: Micronaut 4.4.3, Astro 5.13.5, React 19.1.1, MCP protocol libraries (NEEDS CLARIFICATION: specific MCP SDK/library)
**Storage**: MariaDB 11.4 with Hibernate JPA (existing schema extension required)
**Testing**: Micronaut Test with JUnit 5 (backend), Playwright (frontend), MockK for mocking
**Target Platform**: Linux/macOS server deployment, web-based UI
**Project Type**: web - existing frontend+backend architecture
**Performance Goals**: Support 200 concurrent MCP client connections, <500ms response time for data queries
**Constraints**: JWT authentication integration, existing RBAC system compatibility, audit logging requirement
**Scale/Scope**: Integration with existing security management system, API key management UI, Claude/ChatGPT integration documentation

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Simplicity**:
- Projects: 2 (backend MCP integration, frontend API key UI) ✅
- Using framework directly? Yes, extends existing Micronaut controllers ✅
- Single data model? Yes, 4 new entities with clear purpose ✅
- Avoiding patterns? Yes, direct service injection, no Repository pattern ✅

**Architecture**:
- EVERY feature as library? No - integrates with existing monolithic architecture (matches project style) ✅
- Libraries listed: MCP Kotlin SDK (external), existing Secman services ✅
- CLI per library: N/A - web service integration ✅
- Library docs: CLAUDE.md updated with MCP integration details ✅

**Testing (NON-NEGOTIABLE)**:
- RED-GREEN-Refactor cycle enforced? Yes, contract tests fail first, then implementation ✅
- Git commits show tests before implementation? Yes, required by plan ✅
- Order: Contract→Integration→E2E→Unit strictly followed? Yes, specified in task ordering ✅
- Real dependencies used? Yes, actual MariaDB, real MCP protocol, no mocks for integration ✅
- Integration tests for: MCP tool contracts, session management, authentication flow ✅
- FORBIDDEN: Implementation before test, skipping RED phase ✅

**Observability**:
- Structured logging included? Yes, MCP audit logging with JSON format ✅
- Frontend logs → backend? Yes, MCP activities logged centrally ✅
- Error context sufficient? Yes, comprehensive error categorization ✅

**Versioning**:
- Version number assigned? Yes, 0.1.0 (MAJOR.MINOR.BUILD) ✅
- BUILD increments on every change? Yes, follows existing project versioning ✅
- Breaking changes handled? Yes, MCP protocol versioning, backward compatibility ✅

## Project Structure

### Documentation (this feature)
```
specs/[###-feature]/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
# Option 1: Single project (DEFAULT)
src/
├── models/
├── services/
├── cli/
└── lib/

tests/
├── contract/
├── integration/
└── unit/

# Option 2: Web application (when "frontend" + "backend" detected)
backend/
├── src/
│   ├── models/
│   ├── services/
│   └── api/
└── tests/

frontend/
├── src/
│   ├── components/
│   ├── pages/
│   └── services/
└── tests/

# Option 3: Mobile + API (when "iOS/Android" detected)
api/
└── [same as backend above]

ios/ or android/
└── [platform-specific structure]
```

**Structure Decision**: Option 2 (Web application) - extends existing backend/frontend structure

## Phase 0: Outline & Research
1. **Extract unknowns from Technical Context** above:
   - For each NEEDS CLARIFICATION → research task
   - For each dependency → best practices task
   - For each integration → patterns task

2. **Generate and dispatch research agents**:
   ```
   For each unknown in Technical Context:
     Task: "Research {unknown} for {feature context}"
   For each technology choice:
     Task: "Find best practices for {tech} in {domain}"
   ```

3. **Consolidate findings** in `research.md` using format:
   - Decision: [what was chosen]
   - Rationale: [why chosen]
   - Alternatives considered: [what else evaluated]

**Output**: research.md with all NEEDS CLARIFICATION resolved

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Generate API contracts** from functional requirements:
   - For each user action → endpoint
   - Use standard REST/GraphQL patterns
   - Output OpenAPI/GraphQL schema to `/contracts/`

3. **Generate contract tests** from contracts:
   - One test file per endpoint
   - Assert request/response schemas
   - Tests must fail (no implementation yet)

4. **Extract test scenarios** from user stories:
   - Each story → integration test scenario
   - Quickstart test = story validation steps

5. **Update agent file incrementally** (O(1) operation):
   - Run `/scripts/bash/update-agent-context.sh claude` for your AI assistant
   - If exists: Add only NEW tech from current plan
   - Preserve manual additions between markers
   - Update recent changes (keep last 3)
   - Keep under 150 lines for token efficiency
   - Output to repository root

**Output**: data-model.md, /contracts/*, failing tests, quickstart.md, agent-specific file

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `/templates/tasks-template.md` as base
- Generate tasks from Phase 1 design docs (contracts, data model, quickstart)
- Database tasks: Create MCP entities (McpApiKey, McpSession, McpAuditLog, McpToolPermission) [P]
- Contract tests: Each MCP endpoint → failing contract test [P]
- Service implementation: MCP protocol handling, session management, tool registry
- Controller implementation: API endpoints following OpenAPI spec
- Authentication integration: API key validation, session management
- Frontend tasks: API key management UI, MCP status dashboard
- Integration tests: End-to-end MCP tool execution scenarios
- Documentation: User guides for Claude/ChatGPT integration

**Ordering Strategy**:
- TDD order: Contract tests → Integration tests → Implementation → Unit tests
- Dependency order: Database entities → Services → Controllers → Frontend
- Infrastructure: Authentication → Session management → Tool implementation
- Mark [P] for parallel execution (independent database entities, contract tests)
- Sequential: Core MCP service → Tool implementations → UI integration

**Task Categories**:
1. **Database Schema** (4-5 tasks): Entity creation, migrations, indexes [P]
2. **Contract Tests** (8-10 tasks): One per major MCP endpoint [P]
3. **Core Services** (6-8 tasks): MCP protocol, session management, authentication
4. **Tool Implementation** (10-12 tasks): Individual MCP tools (requirements, assessments, etc.)
5. **API Controllers** (4-5 tasks): REST endpoints for MCP protocol
6. **Frontend Integration** (3-4 tasks): API key management UI
7. **Integration Tests** (5-6 tasks): End-to-end scenarios
8. **Documentation** (2-3 tasks): User guides, API documentation

**Estimated Output**: 42-53 numbered, ordered tasks in tasks.md

**Performance Validation Tasks**:
- Load testing with 200 concurrent sessions
- Memory usage profiling
- Response time validation (<100ms for tool calls)
- SSE connection stability testing

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

No constitutional violations detected. All design decisions align with project constitution:
- Stays within 3-project limit (extends existing backend+frontend)
- Uses framework directly without unnecessary abstractions
- Follows existing architectural patterns
- Implements proper TDD workflow
- Includes comprehensive observability
- Maintains proper versioning strategy


## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved
- [ ] Complexity deviations documented

---
*Based on Constitution v2.1.1 - See `/memory/constitution.md`*