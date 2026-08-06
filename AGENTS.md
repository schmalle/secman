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

## Skills (two-way sync — mandatory)

Every skill in this repo exists twice, once per agent harness:

- `.agents/skills/` — what **Codex** (and other `AGENTS.md`-driven agents) load.
- `.claude/skills/` — what **Claude Code** loads.

They are two renderings of the *same* skill, not two skills. **Whichever tree you edit, the same change must land in the counterpart file in the same commit** — Codex editing `.agents/skills/` ports to `.claude/skills/` exactly as Claude Code editing `.claude/skills/` ports to `.agents/skills/`. A commit that touches one tree only is incomplete. This covers every `*.md` under the trees (`SKILL.md`, `_shared/`, `references/`), not just `SKILL.md`.

- **Translate, don't copy.** Swap harness-specific mechanics for their equivalent: `sandbox_permissions: "require_escalated"` ↔ Bash tool `dangerouslyDisableSandbox: true`; "ask the user directly" ↔ `AskUserQuestion`; `.agents/skills/…` ↔ `.claude/skills/…` paths. Everything else — steps, commands, thresholds, credential handling — stays word-for-word identical.
- **Tie-breaker, not sole writer.** If the two copies already disagree and neither is clearly newer, `.claude/skills/` wins. That resolves existing drift; it does not make a Codex-side edit second-class and never excuses leaving `.claude/skills/` stale.
- **New skill → create both; delete → delete both.** If you *find* an entry that exists in only one tree, report it rather than synthesizing the missing side.
- **Gate**: `./scripts/check-skill-sync.sh` must exit 0 before a skill change is done (`--verbose` shows differing lines). It is report-only and never edits either tree.
- Each skill file carries a `> **Sync policy (two-way, mandatory)**` banner naming its counterpart. The checker strips banners before diffing, so their wording may differ.

## Style
- Kotlin: 4-space indent, `UpperCamelCase` types, constructor injection, immutable data classes; ktlint if configured.
- TS/TSX: 2-space indent, named exports, ESLint via `npm run lint`. Import order: external → internal → relative.

## Tests (mandatory)
Always write tests for new code. JUnit 6 + Mockk for unit; integration tests run against an **external MariaDB** (no Docker/Testcontainers — removed from the build) via `BaseIntegrationTest`. Tests must route HTTP through `SECMAN_HOST` (sourced from `pass-cli`) — never hardcode `http://localhost:*`. After **every** change, `/e2ejs` and `/e2evulnexception` must exit clean (see `CLAUDE.md` principle 7).

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
