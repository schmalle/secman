# Repository Guidelines

`CLAUDE.md` is authoritative. This file is a short summary of the rules other AI agents (Codex, Gemini, etc.) need to know when contributing here.

## Layout
- `src/backendng/` — Micronaut/Kotlin (`src/main/kotlin`, `src/main/resources`).
- `src/frontend/` — Astro + React; pages in `src/pages/`, components in `src/components/`, Playwright in `tests/`.
- `src/cli/` — Picocli CLI.
- `src/shared/` — code shared between backend and CLI.
- `scripts/` — **all** scripts (`./scripts/` is deprecated). `tests/`, `docker/`, `docs/`.

## Build, run, test
- Backend dev: `./scripts/startbackenddev.sh` (canonical — wraps `gradle run` with `pass-cli`-resolved env).
- Build everything (incl. tests): `./gradlew build`.
- Frontend: `cd src/frontend && npm install && npm run dev`; production check `npm run build && npm run preview`; lint `npm run lint`.
- CLI: build once `./gradlew :cli:shadowJar`, then `./scripts/secman <cmd>`.

## Style
- Kotlin: 4-space indent, `UpperCamelCase` types, constructor injection, immutable data classes; ktlint if configured.
- TS/TSX: 2-space indent, named exports, ESLint via `npm run lint`. Import order: external → internal → relative.

## Tests (mandatory)
Always write tests for new code. JUnit 6 + Mockk for unit; Testcontainers (MariaDB) for integration via `BaseIntegrationTest`. Tests must route HTTP through `SECMAN_HOST` (sourced from `pass-cli`) — never hardcode `http://localhost:*`. After **every** change, `/e2ejs` and `/e2evulnexception` must exit clean (see `CLAUDE.md` principle 7).

## Commits / PRs
- `type(scope): description` (Conventional Commits) or short `Type: Summary` form.
- PR body: motivation, verification (`./gradlew build`, `npm run lint`), screenshots for UI changes.
- Flag schema changes, feature toggles, manual data steps, new dependencies.

## Security & secrets
- Secrets via `pass-cli` only — never commit.
- Copy `.env.example` → `.env` for local overrides (gitignored).
- Update sample configs and docs when ports/env vars change.
- Authentication, encryption, RBAC, or export changes require a security review before merge.
