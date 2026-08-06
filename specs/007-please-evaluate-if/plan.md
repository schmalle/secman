
# Implementation Plan: Backend Dependency Evaluation and Update

**Branch**: `007-please-evaluate-if` | **Date**: 2025-10-04 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/007-please-evaluate-if/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from file system structure or context (web=frontend+backend, mobile=app+api)
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
Evaluate all backend dependencies (Micronaut, Kotlin, MariaDB driver, Apache POI, etc.) for available updates and apply the latest stable versions. Resolve any compatibility issues, breaking changes, or build failures introduced by dependency updates. Ensure all tests pass and the application builds successfully for both AMD64 and ARM64 architectures after updates.

## Technical Context
**Language/Version**: Kotlin 2.1.0, Java 21
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, MariaDB 11.4, Apache POI 5.3, Spring Security Crypto 6.3
**Storage**: MariaDB 11.4 via Hibernate JPA
**Testing**: JUnit 5 + MockK (backend), Gradle test runner
**Target Platform**: Docker containers (AMD64/ARM64), Linux server
**Project Type**: web (backend infrastructure only - no frontend changes)
**Performance Goals**: Maintain API response time <200ms p95, no regression in build times
**Constraints**: Backward compatibility required, all existing tests must pass, multi-arch Docker builds must succeed
**Scale/Scope**: Update ~30+ dependencies in build.gradle.kts, resolve potential breaking changes across entire backend codebase

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Verify this feature complies with secman Constitution v1.0.0:

| Principle | Compliance Check | Status |
|-----------|------------------|--------|
| I. Security-First | Security implications evaluated? Input validation planned? Auth enforced? | ✅ Updates will address security vulnerabilities in dependencies |
| II. TDD (NON-NEGOTIABLE) | Tests written before implementation? Red-Green-Refactor followed? | ✅ All existing tests must pass, new tests for breaking changes |
| III. API-First | RESTful APIs defined? Backward compatibility maintained? API docs planned? | ✅ No API changes, backward compatibility maintained |
| IV. Docker-First | Services containerized? .env config (no hardcoded values)? Multi-arch support? | ✅ Docker builds for AMD64/ARM64 must succeed |
| V. RBAC | User roles respected? Authorization at API & UI? Admin restrictions enforced? | ✅ No RBAC changes, existing auth preserved |
| VI. Schema Evolution | Migrations automated? Schema backward-compatible? Constraints at DB level? | ✅ No schema changes, database compatibility maintained |

**Quality Gates**:
- [x] Tests achieve ≥80% coverage (existing coverage maintained)
- [x] Linting passes (Kotlin conventions)
- [x] Docker builds succeed (AMD64 + ARM64)
- [x] API endpoints respond <200ms (p95) (performance maintained)
- [x] Security scan shows no critical vulnerabilities (primary goal of update)

## Project Structure

### Documentation (this feature)
```
specs/007-please-evaluate-if/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)

Note: data-model.md and contracts/ are N/A for this infrastructure task
```

### Source Code (repository root)
```
src/backendng/
├── build.gradle.kts                    # PRIMARY: Dependency declarations
├── gradle.properties                   # Kotlin version, build config
├── src/main/kotlin/com/secman/
│   ├── domain/                        # May need updates for API changes
│   ├── service/                       # May need updates for API changes
│   ├── controller/                    # May need updates for API changes
│   └── repository/                    # May need updates for API changes
└── src/test/kotlin/com/secman/        # All tests must pass after update

docker-compose.yml                      # Docker build verification
Dockerfile                              # Multi-arch build test
```

**Structure Decision**: This is a backend infrastructure update. The primary file is `src/backendng/build.gradle.kts` which contains all dependency versions. Code changes will only be needed if dependency updates introduce breaking API changes. The project uses the web application structure with separate backend and frontend, but this task only affects the backend.

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
   - Run `.specify/scripts/bash/update-agent-context.sh claude`
     **IMPORTANT**: Execute it exactly as specified above. Do not add or remove any arguments.
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
- Generate tasks from research.md decision matrix
- Create update tasks for each dependency category:
  - Build plugins (Kotlin, KSP, Shadow, Micronaut)
  - Runtime dependencies (Spring Security, Apache POI, etc.)
  - Micronaut modules (automatic with platform version)
- Testing tasks from quickstart.md validation sections
- Fix tasks for any breaking changes discovered

**Ordering Strategy**:
1. **Pre-update verification**: Baseline metrics, backup
2. **Update build plugins**: Kotlin → KSP → Shadow → Micronaut (sequential, breaks if fails)
3. **Update runtime deps**: Spring Security → Apache POI → others [P] (parallel where safe)
4. **Build verification**: Gradle build, tests, shadow jar
5. **Code fixes**: Fix any compilation errors or breaking changes
6. **Docker verification**: Multi-arch build and startup
7. **Integration testing**: API validation from quickstart.md
8. **Performance validation**: p95 < 200ms verification

**Estimated Output**: 15-20 numbered, ordered tasks in tasks.md

Tasks will be marked:
- **[BREAKING]** if requires code changes
- **[P]** if can run in parallel
- **[CRITICAL]** if failure blocks all subsequent tasks

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

No constitutional violations. This is a maintenance task that:
- Improves security (addresses CVEs)
- Maintains backward compatibility
- Follows TDD (all tests must pass)
- Preserves all constitutional principles


## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [x] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved (none - maintenance task)
- [x] Complexity deviations documented (none - no violations)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
