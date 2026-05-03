# Skills and Agents

Project-local Claude Code automation defined under `.claude/`.

| Type | Count | Purpose |
|---|---|---|
| Skills | user-invoked `/<name>` slash commands | structured workflows (E2E loops, SpecKit pipeline) |
| Agents | autonomous sub-agents | spawned by skills to fix specific error categories — never invoked directly |

Skills run in `fork` mode (dedicated context) or inline; agents are spawned as subprocesses with `Read, Grep, Glob, Bash, Edit, Write` tools.

## E2E skills

### `/e2eexception` — vuln exception workflow

`.claude/skills/e2eexception/SKILL.md`

Triggers: "run exception e2e", "test exception workflow", "e2eexception".

Loop: start backend (`./scriptpp/startbackenddev.sh`) → start frontend (`./scriptpp/startfrontenddev.sh`) → wait for ports `8080`/`4321` → run `scriptpp/test/test-e2e-exception-workflowsupport.sh` → on failure classify, fix, restart if needed, re-run. Up to `maxRetries` (default 5).

Test = 11-step MCP-based exception lifecycle (see `docs/E2E_EXCEPTION_WORKFLOW_TEST.md`).

Classification:

| Pattern | Category | Action |
|---|---|---|
| HTTP 5xx | backend | controller/service code |
| HTTP 403 | backend | check `@Secured` annotations |
| HTTP 404 on `/api/*` | backend | missing/misrouted endpoint |
| React/JS stack trace | frontend | component fix |
| Hydration mismatch | frontend | SSR/client divergence |
| `Failed to fetch` | backend | unreachable / CORS |
| Timeout | infra | hangs/loops |

Restart rules: backend change → kill, rebuild, wait for health; frontend change → Vite hot-reloads (3s wait); test-only change → no restart.

### `/admin-asset-e2e` — admin asset + vuln (Playwright)

Triggers: "run admin asset e2e", "test add system", "admin asset test".

Flow:
1. login normal user → assert no `DUMMY` asset
2. login admin → create asset `DUMMY` at `/admin/add-system` with normal user as owner
3. admin adds HIGH-criticality 60-day vuln to DUMMY
4. login normal user → asset visible (owner-based access)

Test: `tests/e2e/admin-asset-vuln.spec.ts`. Files exercised: `frontend/src/pages/admin/add-system.astro`, `components/admin/AdminAddSystem.tsx`, backend `AssetController`, `VulnerabilityManagementController`, `AssetFilterService`.

Env: `SECMAN_ADMIN_NAME/PASS`, `SECMAN_USER_USER/PASS`.

### `/e2ejs` — JS error scanner

Triggers: "run js error scanner", "scan pages for errors", "e2ejs", "check all pages", "fix js errors".

Loop: start backend + frontend → wait healthy → run `tests/js-error-scanner.sh` → classify (backend first, then frontend) → fix → restart backend if needed → re-run. Exit `2` = fatal (host unreachable / login failed) → stop.

Both runs (admin + normal user) must report **0 JS errors** per `CLAUDE.md` principle 7. Documented empty-state `[HTTP 404]` and RBAC `[HTTP 403]` are not failures.

### `/e2evulnexception` — full lifecycle (MCP + UI)

Combines the MCP workflow above with Playwright-side UI verification: clean testbed → exception lifecycle (approve/reject/cancel) + auth negatives via MCP → same state through Astro/React UI. Cleanup before and after. Required to exit clean per `CLAUDE.md` principle 7.

## SpecKit commands (`/speckit.*`)

Specification-driven development pipeline, defined under `.claude/commands/`.

```
/speckit.constitution
        │
        ▼
/speckit.specify  ↔  /speckit.clarify
        │
        ▼
/speckit.plan
        │
   ┌────┴─────┐
   ▼          ▼
/speckit.    /speckit.
checklist     tasks
              │
              ▼
        /speckit.analyze        (read-only: duplication, ambiguity, gaps)
              │
              ▼
        /speckit.implement
              │
              ▼
        /speckit.taskstoissues  (creates GitHub issues; safe-checks remote)
```

Artifacts:
1. `constitution.md` — principles (governs all downstream)
2. `spec.md` — what (≤3 `[NEEDS CLARIFICATION]` markers allowed)
3. `plan.md` — how (Phase 0 research / Phase 1 design + contracts)
4. `data-model.md`, `contracts/` — entities + interface shapes
5. `tasks.md` — ordered work items, parallel-execution graph
6. `checklists/` — requirement-quality unit tests (allowed: "Are X defined?"; forbidden: "Verify the button works")
7. GitHub issues — one per task, dependency-preserved (only created if remote is GitHub; uses `github/github-mcp-server/issue_write`)

`speckit.analyze` severities: `CRITICAL > HIGH > MEDIUM > LOW`, max 50 findings. Constitution-MUST violations are auto-CRITICAL.

## Agents

### `e2e-backend-fixer`

`.claude/agents/e2e-backend-fixer.md`. Spawned for HTTP 5xx/403/404 or Kotlin/Java stack traces.

Workflow: extract method+URL → find matching `@Controller` → trace into service → check `.e2e-logs/backend.log` → minimal fix to application code (never tests).

Common patterns:
| Exception | Root cause | Fix |
|---|---|---|
| `ClassCastException` | Hibernate native query type mismatch | explicit casts or JPQL |
| `NullPointerException` | nullable entity field/relation | `?.`, `?: default` |
| `LazyInitializationException` | lazy collection outside transaction | `@Transactional` or `JOIN FETCH` |
| `HttpStatusException(403)` | over-restrictive `@Secured` | add role |
| `HttpStatusException(404)` | endpoint not registered | check `@Controller` path/method |
| `DataAccessException` | SQL error (column/constraint) | fix entity mapping |
| `JsonProcessingException` | circular ref | `@JsonIgnore` or DTO |

Backend changes always need a restart.

### `e2e-frontend-fixer`

`.claude/agents/e2e-frontend-fixer.md`. Spawned for JS/render/selector failures.

Workflow: map URL → Astro page in `src/pages/` → React components in `src/components/` → identify root cause (render error, API shape mismatch, selector drift, routing) → minimal fix to application code (never tests). When a selector changed, reports the test needs updating instead of patching app code to an outdated test. Vite typically hot-reloads.

## Architecture

```
User → /e2eexception | /admin-asset-e2e | /e2ejs | /e2evulnexception
        │
        ▼
   Skill (fork context)
   ├── start backend + frontend
   ├── run E2E test
   ├── classify failure
   │     ├── backend  → spawn e2e-backend-fixer
   │     ├── frontend → spawn e2e-frontend-fixer
   │     └── infra    → investigate directly
   ├── apply fix → restart if needed
   ├── re-run (max 5 iterations)
   └── teardown + report
```

Logs: `.e2e-logs/{backend,frontend}.log` (gitignored).

## Configuration

`e2e-runner.config.json` (optional, repo root):

| Key | Default |
|---|---|
| `backend.start` | `gradle :backendng:clean :backendng:run` |
| `backend.healthUrl` | `http://localhost:8080` |
| `backend.healthTimeout` | `120` s |
| `frontend.start` | `npm run dev` |
| `frontend.healthUrl` | `http://localhost:4321` |
| `frontend.healthTimeout` | `60` s |
| `e2e.script` | `./scriptpp/e2e-test.sh` |
| `e2e.maxRetries` | `5` |
| `e2e.retryDelay` | `5` s |

Liveness in the runner is **port-bind** (`lsof -iTCP:8080 -sTCP:LISTEN -n -P`), not HTTP. Functional checks still use `SECMAN_HOST` from `pass-cli`.

Hooks (`.claude/settings.json`):
| Hook | Trigger | Purpose |
|---|---|---|
| `PostToolUse` Edit/Write | any file edit | `on-file-changed.sh` — alerts runner about hot-reload |
| `PreToolUse` / `PostToolUse` Task | task tool boundaries | pre/post-task housekeeping |

E2E helper script: `scriptpp/e2e-test.sh`.
