# Skills and Agents

Project-local Claude Code automation defined under `.claude/` (mirrored to
`.agents/` for Codex).

## Skills — which one to use

**See `docs/SKILLS.md`** for the authoritative routing guide to all ten
E2E/testbed skills (`/finalizer`, `/e2ejs`, `/e2evulnexception`,
`/e2eexception`, `/admin-asset-e2e`, `/importtest`, `/crowdstrike-vuln-match`,
`/aws-account-risk-assessment`, `/aws-account-owner-email`,
`/createtestdata`) — what each one does, which are destructive, and the
pairs that get confused. This file does not duplicate that content.

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

`e2e-backend-fixer` and `e2e-frontend-fixer` (`.claude/agents/`) are
Kotlin/Micronaut and Astro/React specialists for triaging E2E-surfaced
errors in isolation. **No current skill spawns them automatically** — every
skill listed in `docs/SKILLS.md` fixes backend/frontend issues inline
instead. Invoke either agent directly (via the Agent tool) when you want a
focused diagnosis of a single backend or frontend failure without running
a full E2E skill.

| Agent | Handles | Common patterns |
|---|---|---|
| `e2e-backend-fixer` | HTTP 5xx/403/404, Kotlin/Java stack traces | `ClassCastException` (Hibernate native query mismatch), `NullPointerException` (nullable field/relation), `LazyInitializationException` (lazy collection outside transaction), `HttpStatusException(403)` (over-restrictive `@Secured`), `HttpStatusException(404)` (endpoint not registered), `DataAccessException` (entity mapping), `JsonProcessingException` (circular ref) |
| `e2e-frontend-fixer` | JS/render/selector failures | Component crash, API response shape mismatch, renamed selector/`data-testid`, missing route |

Both work from `.e2e-logs/{backend,frontend}.log` (gitignored), make a
minimal fix to application code only (never tests — if a selector changed,
they report that the test needs updating instead of patching app code to
match it), and never restart anything themselves.
