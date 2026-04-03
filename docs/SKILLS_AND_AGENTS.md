# Skills and Agents Reference

This document describes all Claude Code skills (slash commands) and autonomous agents configured for the secman project. These are defined in the `.claude/` directory and provide AI-assisted development workflows.

---

## Table of Contents

- [Overview](#overview)
- [Skills (Slash Commands)](#skills-slash-commands)
  - [E2E Skills](#e2e-skills)
    - [`/e2e-runner`](#e2e-runner)
    - [`/admin-asset-e2e`](#admin-asset-e2e)
  - [SpecKit Commands](#speckit-commands)
    - [`/speckit.constitution`](#speckitconstitution)
    - [`/speckit.specify`](#speckitspecify)
    - [`/speckit.clarify`](#speckitclarify)
    - [`/speckit.plan`](#speckitplan)
    - [`/speckit.checklist`](#speckitchecklist)
    - [`/speckit.tasks`](#speckittasks)
    - [`/speckit.analyze`](#speckitanalyze)
    - [`/speckit.implement`](#speckitimplement)
    - [`/speckit.taskstoissues`](#speckittaskstoissues)
- [Agents (Autonomous Specialists)](#agents-autonomous-specialists)
  - [`e2e-backend-fixer`](#e2e-backend-fixer)
  - [`e2e-frontend-fixer`](#e2e-frontend-fixer)
- [Architecture](#architecture)
  - [E2E Fix Loop](#e2e-fix-loop)
  - [SpecKit Workflow](#speckit-workflow)
- [Configuration](#configuration)
  - [E2E Runner Config](#e2e-runner-config)
  - [Hooks](#hooks)

---

## Overview

The project has two categories of Claude Code automation:

| Category | Count | Purpose |
|----------|-------|---------|
| **Skills** | 11 | User-invoked slash commands (`/skill-name`) that perform structured workflows |
| **Agents** | 2 | Autonomous sub-agents spawned by skills to fix specific error categories |

Skills run in `fork` mode (dedicated context) or inline, and may spawn agents as sub-processes.

---

## Skills (Slash Commands)

### E2E Skills

#### `/e2e-runner`

**File:** Configured via Claude Code settings (skill definition)

End-to-end test orchestrator. Starts the full stack (backend + frontend), runs the E2E test script, and iteratively fixes failures until all tests pass or the retry budget is exhausted.

**Trigger phrases:** "run e2e tests", "run end to end", "test the whole stack", "e2e loop", "get the tests green"

**Workflow:**
1. Start backend (`gradle :backendng:clean :backendng:run`)
2. Start frontend (`npm run dev`)
3. Wait for health checks (backend: `localhost:8080`, frontend: `localhost:4321`)
4. Run E2E test script (`./scripts/e2e-test.sh`)
5. If failures: classify error, apply fix, restart services if needed, re-run
6. Repeat up to `maxRetries` (default: 5) or until green

**Error classification:**

| Pattern | Category | Action |
|---------|----------|--------|
| HTTP 5xx | backend | Fix controller/service code |
| HTTP 403 | backend | Check `@Secured` annotations and roles |
| HTTP 404 on `/api/*` | backend | Missing or misrouted endpoint |
| React/JS stack trace | frontend | Fix component code |
| Hydration error | frontend | SSR/client mismatch |
| "Failed to fetch" | backend | Endpoint unreachable or CORS |
| Timeout | infra | Investigate hangs or infinite loops |

**Restart rules:**
- Backend change -> kill process, rebuild, wait for health
- Frontend change -> Vite hot-reloads automatically (3s wait)
- Test-only change -> no restart needed

---

#### `/admin-asset-e2e`

**File:** `.claude/skills/admin-asset-e2e/SKILL.md`

Runs the admin asset and vulnerability Playwright E2E test that verifies the full asset creation workflow across user roles.

**Trigger phrases:** "run admin asset e2e", "test add system", "admin asset test"

**Test flow:**
1. Login as normal user -> verify DUMMY asset does NOT exist
2. Login as admin -> create asset "DUMMY" at `/admin/add-system` with normal user as owner
3. Admin adds a HIGH criticality vulnerability (60 days old) to DUMMY
4. Login as normal user -> verify DUMMY asset IS now visible (owner-based access)

**Test file:** `tests/e2e/admin-asset-vuln.spec.ts`

**Key application files exercised:**
- `src/frontend/src/pages/admin/add-system.astro` - Admin UI page
- `src/frontend/src/components/admin/AdminAddSystem.tsx` - React component
- `src/backendng/.../controller/AssetController.kt` - Asset REST API
- `src/backendng/.../controller/VulnerabilityManagementController.kt` - Vulnerability API
- `src/backendng/.../service/AssetFilterService.kt` - Access control filtering

**Required environment variables:**
- `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS`
- `SECMAN_USER_USER` / `SECMAN_USER_PASS`

---

### SpecKit Commands

SpecKit is a specification-driven development workflow. Commands form a pipeline from feature ideation through to implementation and issue tracking.

#### `/speckit.constitution`

**File:** `.claude/commands/speckit.constitution.md`

Create or update the project constitution — the governing document that defines project principles, constraints, and quality standards.

**What it does:**
- Loads the existing constitution from `.specify/memory/constitution.md`
- Collects or derives values for principle tokens interactively
- Drafts updated content and checks consistency with all templates
- Produces a Sync Impact Report
- Applies semantic versioning (MAJOR/MINOR/PATCH) to constitution changes

**Handoffs to:** `/speckit.specify`

---

#### `/speckit.specify`

**File:** `.claude/commands/speckit.specify.md`

Create or update a feature specification from a natural language description. This is typically the entry point for new features.

**What it does:**
- Generates a short feature branch name (2-4 words)
- Creates a feature branch via `.specify/scripts/bash/create-new-feature.sh`
- Fills the spec template with feature details
- Generates a Specification Quality Checklist
- Validates against quality criteria (completeness, clarity, consistency, measurability)
- Flags up to 3 `[NEEDS CLARIFICATION]` markers if areas are underspecified

**Handoffs to:** `/speckit.plan`, `/speckit.clarify`

---

#### `/speckit.clarify`

**File:** `.claude/commands/speckit.clarify.md`

Identify underspecified areas in the current feature spec by asking up to 5 targeted clarification questions and encoding the answers back into the spec.

**Coverage taxonomy scanned:**
- Functional Scope & Behavior
- Domain & Data Model
- Interaction & UX Flow
- Non-Functional Quality Attributes
- Integration & External Dependencies
- Edge Cases & Failure Handling
- Constraints & Tradeoffs
- Terminology & Consistency

**Handoffs to:** `/speckit.plan`

---

#### `/speckit.plan`

**File:** `.claude/commands/speckit.plan.md`

Execute the implementation planning workflow to generate design artifacts from the feature spec.

**Phases:**
- **Phase 0 — Outline & Research:** Identify unknowns, resolve via research tasks
- **Phase 1 — Design & Contracts:** Data model, interface contracts, agent context update

**Artifacts generated:**
- `research.md` - Resolved unknowns and clarifications
- `data-model.md` - Entity and relationship definitions
- `contracts/` - Interface contracts (API shapes, component props)
- `quickstart.md` - Getting-started guide for implementers

**Handoffs to:** `/speckit.tasks`, `/speckit.checklist`

---

#### `/speckit.checklist`

**File:** `.claude/commands/speckit.checklist.md`

Generate a custom quality checklist for the current feature. Checklists validate **requirement quality** (completeness, clarity, consistency), NOT implementation behavior.

**Key concept:** These are "unit tests for requirements writing."

**Allowed patterns:**
- "Are [requirement type] defined/specified/documented for [scenario]?"

**Prohibited patterns (implementation testing):**
- "Verify the button clicks correctly"
- "Test error handling works"
- "Confirm the API returns 200"

**Checklist categories:**
- Requirement Completeness
- Requirement Clarity
- Requirement Consistency
- Acceptance Criteria Quality
- Scenario Coverage
- Edge Case Coverage
- Non-Functional Requirements
- Dependencies & Assumptions
- Ambiguities & Conflicts

---

#### `/speckit.tasks`

**File:** `.claude/commands/speckit.tasks.md`

Generate an actionable, dependency-ordered `tasks.md` from the design artifacts (plan, spec, data model, contracts).

**What it produces:**
- Tasks organized by user story, in priority order
- Dependency graph with parallel execution opportunities
- Phase structure: Setup -> Foundational -> Per-story phases -> Polish

**Handoffs to:** `/speckit.analyze`, `/speckit.implement`

---

#### `/speckit.analyze`

**File:** `.claude/commands/speckit.analyze.md`

Perform a **read-only** cross-artifact consistency and quality analysis across `spec.md`, `plan.md`, and `tasks.md`.

**Prerequisite:** Must run after `/speckit.tasks` has produced a complete `tasks.md`.

**Detection passes:**
| Pass | What it finds |
|------|---------------|
| Duplication | Near-duplicate requirements |
| Ambiguity | Vague adjectives without measurable criteria |
| Underspecification | Missing object or measurable outcome |
| Constitution Alignment | Violations of MUST principles (auto-CRITICAL) |
| Coverage Gaps | Requirements with zero tasks, orphaned tasks |
| Inconsistency | Terminology drift, conflicting requirements |

**Severity levels:** CRITICAL > HIGH > MEDIUM > LOW (max 50 findings)

---

#### `/speckit.implement`

**File:** `.claude/commands/speckit.implement.md`

Execute the implementation plan by processing all tasks in `tasks.md` phase-by-phase.

**What it does:**
- Validates all checklists are complete (fails if incomplete)
- Loads full implementation context (tasks, plan, data model, contracts, research)
- Verifies project setup (ignore files, directory structure)
- Executes tasks in dependency order, marking each `[X]` on completion
- Handles errors and tracks progress

**Pre/post hooks:** Checks `.specify/extensions.yml` for `hooks.before_implement` and `hooks.after_implement`.

---

#### `/speckit.taskstoissues`

**File:** `.claude/commands/speckit.taskstoissues.md`

Convert tasks from `tasks.md` into dependency-ordered GitHub issues.

**What it does:**
- Reads the task list from `tasks.md`
- Resolves the Git remote URL to identify the target GitHub repository
- Creates one GitHub issue per task via the GitHub MCP server
- Preserves task dependencies and ordering

**Safety:** Only creates issues in the repository matching the current Git remote. Refuses to proceed if the remote is not a GitHub URL.

**Required tool:** `github/github-mcp-server/issue_write`

---

## Agents (Autonomous Specialists)

Agents are spawned automatically by the E2E skills when errors are detected. They cannot be invoked directly via slash commands.

### `e2e-backend-fixer`

**File:** `.claude/agents/e2e-backend-fixer.md`
**Tools:** Read, Grep, Glob, Bash, Edit, Write

Kotlin/Micronaut backend specialist. Spawned when E2E failures are classified as backend issues (HTTP 5xx, 403, 404, Kotlin/Java exceptions).

**Diagnosis workflow:**
1. Extract HTTP method/URL from error output
2. Find the controller with the matching `@Controller` path
3. Trace into the service layer
4. Check `.e2e-logs/backend.log` for stack traces
5. Apply minimal fix to application code

**Common fix patterns:**

| Exception | Typical Root Cause | Fix |
|-----------|-------------------|-----|
| `ClassCastException` | Hibernate native query type mismatch | Use explicit casts or JPQL |
| `NullPointerException` | Null entity field/relationship | Add null-safety (`?.`, `?: default`) |
| `LazyInitializationException` | Accessing lazy collection outside transaction | Add `@Transactional` or `JOIN FETCH` |
| `HttpStatusException(403)` | Too-restrictive `@Secured` | Add missing role to annotation |
| `HttpStatusException(404)` | Endpoint not registered | Check `@Controller` path and method |
| `DataAccessException` | SQL error (missing column, constraint) | Fix entity mapping |
| `JsonProcessingException` | Circular reference in serialization | Add `@JsonIgnore` or use DTO |

**Constraint:** Never modifies test files. Almost always requires backend restart.

---

### `e2e-frontend-fixer`

**File:** `.claude/agents/e2e-frontend-fixer.md`
**Tools:** Read, Grep, Glob, Bash, Edit, Write

Astro + React frontend specialist. Spawned when E2E failures are classified as frontend issues (JS errors, component render failures, missing elements).

**Diagnosis workflow:**
1. Map the URL path from the failing test to an Astro page in `src/pages/`
2. Trace into React components in `src/components/`
3. Identify root cause: render error, API response shape mismatch, selector issue, or routing issue
4. Apply minimal fix to application code

**Constraint:** Never modifies test files. If a DOM selector changed, reports that the test needs updating rather than altering application code to match an outdated test. Vite typically hot-reloads frontend changes automatically.

---

## Architecture

### E2E Fix Loop

```
User: "/e2e-runner" or "/admin-asset-e2e"
         |
         v
    Skill (fork context)
    |-- Phase 1: Start backend & frontend
    |-- Phase 2: Run E2E test script
    |-- Phase 3: Classify failure
    |   |-- backend error --> spawn e2e-backend-fixer agent
    |   |-- frontend error --> spawn e2e-frontend-fixer agent
    |   |-- infra error --> investigate directly
    |   '-- apply fix, restart if needed
    |-- Phase 4: Re-run tests (loop back to Phase 2)
    '-- Phase 5: Teardown & Report
         |
         '-- Max 5 iterations, then stop with summary
```

**Logs:** Written to `.e2e-logs/` (backend.log, frontend.log)

### SpecKit Workflow

The SpecKit commands form a directed pipeline. Each command produces artifacts consumed by the next:

```
/speckit.constitution
         |
         v
/speckit.specify  <-->  /speckit.clarify
         |
         v
/speckit.plan
         |
    +---------+
    |         |
    v         v
/speckit.   /speckit.
checklist    tasks
              |
              v
         /speckit.analyze
              |
              v
         /speckit.implement
              |
              v
         /speckit.taskstoissues
```

**Artifact flow:**
1. `constitution.md` - Project principles (governs all downstream artifacts)
2. `spec.md` - Feature specification (what to build)
3. `plan.md` - Technical plan (how to build it)
4. `data-model.md`, `contracts/` - Design artifacts (structures and interfaces)
5. `tasks.md` - Implementation tasks (ordered work items)
6. `checklists/` - Quality gates (requirement validation)
7. GitHub Issues - External tracking

---

## Configuration

### E2E Runner Config

Optional file at project root: `e2e-runner.config.json`

| Setting | Default | Description |
|---------|---------|-------------|
| `backend.start` | `gradle :backendng:clean :backendng:run` | Backend start command |
| `backend.healthUrl` | `http://localhost:8080` | Health check endpoint |
| `backend.healthTimeout` | `120` (seconds) | Max wait for backend health |
| `frontend.start` | `npm run dev` | Frontend start command |
| `frontend.healthUrl` | `http://localhost:4321` | Health check endpoint |
| `frontend.healthTimeout` | `60` (seconds) | Max wait for frontend health |
| `e2e.script` | `./scripts/e2e-test.sh` | E2E test script path |
| `e2e.maxRetries` | `5` | Max fix-and-retry iterations |
| `e2e.retryDelay` | `5` (seconds) | Delay between retries |

### Hooks

Configured in `.claude/settings.json`. Key hooks relevant to skills:

| Hook | Trigger | Purpose |
|------|---------|---------|
| `PostToolUse` (Edit/Write) | Any file edit | Runs `on-file-changed.sh` to alert the E2E runner about hot-reload needs |
| `PreToolUse` (Task) | Task tool usage | Runs pre-task hooks |
| `PostToolUse` (Task) | Task tool completion | Runs post-task hooks |

### Helper Scripts

The E2E runner uses the following scripts:

| Script | Purpose |
|--------|---------|
| `scripts/e2e-test.sh` | Main E2E test execution script |
