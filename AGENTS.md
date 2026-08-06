# Repository Guidelines

`CLAUDE.md` is authoritative. This file is a short summary of the rules other AI agents (Codex, Gemini, etc.) need to know when contributing here.

## Layout
- `src/backendng/` — Micronaut/Kotlin (`src/main/kotlin`, `src/main/resources`).
- `src/frontend/` — Astro + React; pages in `src/pages/`, components in `src/components/`, Playwright in `tests/`.
- `src/cli/` — Picocli CLI.
- `src/shared/` — code shared between backend and CLI.
- `scripts/` — **all** scripts, invoked as `./scripts/<name>.sh` (canonical — do not call `gradlew`/`npm` directly for dev start). `tests/`, `docker/`, `docs/`.

## Build, run, test
- Backend dev: `./scripts/startbackenddev.sh` (canonical — wraps `gradle run` with `pass-cli`-resolved env).
- Build everything (incl. tests): `./gradlew build`.
- Frontend: `cd src/frontend && npm install`, then `./scripts/startfrontenddev.sh` (canonical dev start — never `npm run dev` directly); production check `npm run build && npm run preview`; lint `npm run lint`.
- CLI: build once `./gradlew :cli:shadowJar`, then `./scripts/secman <cmd>`.

## Skills
Eleven project skills live in **`.agents/skills/`** — the Codex mirror of
`.claude/skills/`, which is the authoritative copy (`CLAUDE.md` §Tooling
Conventions). They are plain Markdown: there is no slash command here, so read
the matching `.agents/skills/<name>/SKILL.md` **in full** and follow it. Editing
either tree obliges you to port the same change to the other in the same commit;
`./scripts/check-skill-sync.sh` reports drift and never fixes it.

| Skill | Use it to | Writes data? |
|---|---|---|
| `finalizer` | Pre-merge pass: version/doc drift, `extensions/` contract drift, HIGH/CRITICAL security review, skill sync | docs only |
| `testsuite` | Fast test tier (backend, CLI, frontend) + name-reference coverage gaps | no |
| `e2ejs` | Scan every page for JS errors as admin *and* normal user | no |
| `e2evulnexception` | Full vuln + exception lifecycle over MCP and the UI | ⚠️ **wipes the DB** |
| `e2eexception` | Fast MCP-only exception smoke test | ⚠️ **deletes all assets** |
| `admin-asset-e2e` | Admin adds a system + vulnerability, normal user sees it | adds one asset |
| `importtest` | Run and debug the CrowdStrike import | ⚠️ **imports live data** |
| `crowdstrike-vuln-match` | Compare stored rows against a fresh Falcon query | no |
| `aws-account-risk-assessment` | New AWS account starts a correctly scoped assessment | seeds + removes a testbed |
| `aws-account-owner-email` | The account owner actually receives the mail | testbed, ⚠️ **sends real mail** |
| `createtestdata` | Seed a fixture to click through | adds a fixture |

The three destructive ones are unsafe against a shared instance — resolve
`SECMAN_HOST` before running them. `aws-account-risk-assessment` carries a
quieter one: it activates its own requirements release, archiving the current
`ACTIVE` one, and `ARCHIVED` is terminal.

`.agents/skills/_shared/stack-lifecycle.md` is **mandatory reading** before any
skill that touches the running stack: it defines the unconditional cold start
(both stop scripts first, even when the ports look free), port-bind liveness
(`lsof -iTCP:8080`, 120s; `:4321`, 60s), the `pass-cli` credentials, the log
paths and the 5-iteration fix budget. The start scripts need `pass-cli`, so run
them with `sandbox_permissions: "require_escalated"` — a sandboxed shell cannot
reach the vault and the process fails to start.

`docs/SKILLS.md` is the longer routing guide, including the pairs that get
confused (`e2eexception` vs `e2evulnexception`, `importtest` vs
`crowdstrike-vuln-match`, the two AWS-account skills).

Not mirrored, Claude Code only: the `speckit.*` commands in `.claude/commands/`
(spec-driven-development pipeline, see `docs/SKILLS_AND_AGENTS.md`) and the two
subagents in `.claude/agents/`, which no skill spawns.

## Style
- Kotlin: 4-space indent, `UpperCamelCase` types, constructor injection, immutable data classes; ktlint if configured.
- TS/TSX: 2-space indent, named exports, ESLint via `npm run lint`. Import order: external → internal → relative.

## Tests (mandatory)
Always write tests for new code. JUnit 6 + Mockk for unit; integration tests run against an **external MariaDB** (no Docker/Testcontainers — removed from the build) via `BaseIntegrationTest`. Tests must route HTTP through `SECMAN_HOST` (sourced from `pass-cli`) — never hardcode `http://localhost:*`. After **every** change, the `e2ejs` and `e2evulnexception` skills (§Skills) must exit clean (see `CLAUDE.md` principle 7).

## Commits / PRs
- `type(scope): description` (Conventional Commits) or short `Type: Summary` form.
- PR body: motivation, verification (`./gradlew build`, `npm run lint`), screenshots for UI changes.
- Flag schema changes, feature toggles, manual data steps, new dependencies.

## Security & secrets
- **All code you generate must comply with the OWASP Top 10 checklist in `CLAUDE.md` §OWASP Top 10 Compliance** (A01–A10, pinned to the 2021 list). It is binding, names the existing control to reuse for each category, and requires you to state the A01–A10 result for your diff before calling a change complete. A HIGH-or-above finding blocks the change.
- Secrets via `pass-cli` only — never commit.
- Copy `.env.example` → `.env` for local overrides (gitignored).
- Update sample configs and docs when ports/env vars change.
- Authentication, encryption, RBAC, or export changes require a security review before merge.
