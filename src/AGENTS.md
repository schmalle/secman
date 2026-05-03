# Repository Guidelines (src/)

See repo-root `CLAUDE.md` for the authoritative rules. Quick reference for agents working inside `src/`:

## Layout
- `backendng/` — Micronaut/Kotlin. Code: `src/main/kotlin`. Config: `src/main/resources`. Tests: `src/test/kotlin` (mirror package).
- `frontend/` — Astro + React. Pages: `src/pages`. Components: `src/components`. Helpers: `src/utils`. Playwright: `tests/`.
- `cli/` — Picocli CLI commands + service.
- `shared/` — code shared between backend and CLI.

## Build / test
- Backend: `cd ../scriptpp && ./startbackenddev.sh` (canonical) or from repo root `./gradlew build` / `./gradlew :backendng:test`.
- Frontend: `cd frontend && npm install`, then `npm run dev`, `npm run build`, `npm run preview`, `npm run test` (full Playwright) or `npm run test:checkin` (lite).
- CLI: `./gradlew :cli:shadowJar`, then `./scriptpp/secman <cmd>`.

## Style
- Kotlin: 4-space indent, `UpperCamelCase` types, `lowerCamelCase` members, Micronaut DI, prefer immutable data classes. Keep config near its feature package.
- TS/TSX: 2-space indent, named exports. Import order: std → third-party → local. Shared constants in `frontend/src/utils`.

## Tests
- Backend: `*Test.kt` next to the feature; `@MicronautTest` for container-dependent code; Mockk for unit doubles; **Testcontainers MariaDB** (not H2) via `BaseIntegrationTest` for persistence.
- Frontend: Playwright specs grouped by feature in `frontend/tests`; use `data-testid` selectors; document any `test.skip` in the PR.
- HTTP in tests goes through `SECMAN_HOST` env var (resolved via `pass-cli`). Never hardcode `localhost:8080` / `localhost:4321`.

## Commits & PRs
`type(scope): description` (or short `Type: Summary`). Imperative ≤72 chars. PR covers motivation, verification (`./gradlew build`, `npm run test:checkin`), and screenshots for UI changes. Highlight new dependencies — especially anything touching auth, storage, or crypto — and confirm license compatibility.

## Secrets
Never commit credentials or DB dumps. Use env vars or `application-local.yml` overrides; resolve secrets via `pass-cli` (Proton Pass). Never reintroduce `op run`.
