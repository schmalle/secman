
# Implementation Plan: Email Functionality Implementation

**Branch**: `003-correct-and-implement` | **Date**: 2025-09-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-correct-and-implement/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from context (web=frontend+backend, mobile=app+api)
   → Set Structure Decision based on project type
3. Fill the Constitution Check section based on the content of the constitution document.
4. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, agent-specific template file (e.g., `CLAUDE.md` for Claude Code, `.github/copilot-instructions.md` for GitHub Copilot, `GEMINI.md` for Gemini CLI, `QWEN.md` for Qwen Code or `AGENTS.md` for opencode).
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 7. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Implement and correct email functionality for SecMan, including:
- Fix existing admin UI email configuration accessibility
- Implement automatic email notifications for new risk assessments
- Create test email accounts for validation
- Ensure secure email configuration storage with encryption
- Support SMTP and IMAP protocols

Technical approach: Enhance existing Micronaut EmailService with automatic notification triggers, improve admin UI accessibility, add test account management, and implement proper encryption for email configurations.

## Technical Context
**Language/Version**: Kotlin 2.0.21 with Java 21 target
**Primary Dependencies**: Micronaut 4.4.3, Micronaut Email JavaMail 2.9.0, MariaDB 11.4, Astro 5.12.3, React 19.1.0
**Storage**: MariaDB with JPA/Hibernate, encrypted email config storage
**Testing**: JUnit 5 + Mockk for backend, Playwright for frontend E2E testing
**Target Platform**: Linux server backend (port 8080), web frontend (port 4321)
**Project Type**: web - frontend + backend architecture
**Performance Goals**: <200ms API response times, email delivery within 30 seconds
**Constraints**: Security-first development, JWT authentication required, CORS restrictions
**Scale/Scope**: Enterprise security tool, ~1000 users, existing codebase enhancement
**Existing Infrastructure**: EmailService, EmailConfig entity, admin UI components already implemented

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Security-First Development**: ✅ PASS
- Email configuration encryption required (FR-010)
- JWT authentication maintained for admin access
- Input validation for email addresses and configurations
- Secure storage of SMTP credentials

**Test-Driven Quality**: ✅ PASS
- Contract tests required for email notification endpoints
- Integration tests for email delivery
- TDD cycle: Write tests → Fail → Implement → Pass

**MCP Integration Standards**: ✅ PASS
- No new MCP endpoints required, existing standards maintained

**API-First Architecture**: ✅ PASS
- Email notification endpoints follow REST conventions
- Proper error handling for email delivery failures
- Authentication required for admin email config endpoints

**Configuration Over Hardcoding**: ✅ PASS
- Email server settings externalized to EmailConfig entity
- No hardcoded SMTP credentials or server details
- Environment-specific email configurations supported

**Post-Design Re-evaluation**: ✅ PASS
- Event-driven architecture maintains loose coupling
- Encryption implementation follows security best practices
- API contracts define clear interfaces and error handling
- Test accounts isolated from production data
- Async email processing prevents blocking operations
- No new constitutional violations introduced

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

**Structure Decision**: Option 2 - Web application (frontend + backend detected)

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
   - Run `.specify/scripts/bash/update-agent-context.sh claude` for your AI assistant
   - If exists: Add only NEW tech from current plan
   - Preserve manual additions between markers
   - Update recent changes (keep last 3)
   - Keep under 150 lines for token efficiency
   - Output to repository root

**Output**: data-model.md, /contracts/*, failing tests, quickstart.md, agent-specific file

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `.specify/templates/tasks-template.md` as base
- Generate tasks from Phase 1 design docs (contracts, data model, quickstart)
- Contract tests for email notification and test account APIs [P]
- Entity creation tasks for new data models [P]
- Service enhancement tasks for EmailService
- Event handling implementation for automatic notifications
- UI fixes for admin email configuration accessibility
- Integration tests following quickstart scenarios

**Detailed Task Categories**:

1. **Database & Entities** (4-5 tasks, mostly parallel):
   - Create EncryptedStringConverter for sensitive data
   - Create TestEmailAccount entity and repository
   - Create EmailNotificationLog entity and repository
   - Create RiskAssessmentNotificationConfig entity
   - Enhance EmailConfig entity with encryption

2. **Contract Tests** (6-8 tasks, parallel):
   - Email notification API contract tests
   - Test email accounts API contract tests
   - Each endpoint gets dedicated test file
   - Tests must fail initially (TDD)

3. **Event System** (3-4 tasks, sequential):
   - Create RiskAssessmentCreatedEvent
   - Implement event publishing in risk assessment service
   - Create email notification event listener
   - Test event flow end-to-end

4. **Service Layer** (5-6 tasks, some parallel):
   - Enhance EmailService with notification support
   - Create TestEmailAccountService [P]
   - Create NotificationConfigService [P]
   - Implement retry mechanism for failed emails
   - Add IMAP support for inbox monitoring

5. **API Controllers** (4-5 tasks, parallel after services):
   - Create NotificationController
   - Create TestEmailAccountController
   - Enhance EmailConfigController with encryption
   - Add notification endpoints to existing controllers

6. **UI Fixes** (3-4 tasks, parallel):
   - Fix admin route authentication in email-config.astro
   - Enhance EmailConfigManagement.tsx component
   - Add test account management UI components
   - Add notification configuration UI

7. **Integration & Testing** (3-4 tasks, sequential):
   - Implement quickstart test scenarios
   - Add performance tests for email sending
   - Add security tests for encryption
   - End-to-end notification flow testing

**Ordering Strategy**:
- TDD order: Contract tests → Failing tests → Implementation
- Dependency order: Entities → Services → Controllers → UI
- Event system after entities but before full integration
- Mark [P] for parallel execution (independent files)
- Security/encryption tasks prioritized early

**Parallel Execution Groups**:
- Group 1: All entity creation tasks
- Group 2: All contract test creation tasks
- Group 3: Service layer implementations
- Group 4: Controller implementations
- Group 5: UI component fixes

**Estimated Output**: 28-32 numbered, ordered tasks in tasks.md

**Risk Mitigation**:
- Encryption tasks completed early to prevent security gaps
- Event system tested independently before integration
- UI fixes isolated to prevent breaking existing admin functionality
- Test account management separate from production user system

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |


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
- [x] Complexity deviations documented (none required)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
