# Repository Guidelines (src/)

See repo-root `CLAUDE.md` for the authoritative rules. Quick reference for agents working inside `src/`:

## Layout
- `backendng/` — Micronaut/Kotlin. Code: `src/main/kotlin`. Config: `src/main/resources`. Tests: `src/test/kotlin` (mirror package).
- `frontend/` — Astro + React. Pages: `src/pages`. Components: `src/components`. Helpers: `src/utils`. Playwright: `tests/`.
- `cli/` — Picocli CLI commands + service.
- `shared/` — code shared between backend and CLI.

## Build / test
- Backend: `cd ../scripts && ./startbackenddev.sh` (canonical) or from repo root `./gradlew build` / `./gradlew :backendng:test`.
- Frontend: `cd frontend && npm install`, then `../scripts/startfrontenddev.sh` (canonical dev start — never `npm run dev` directly), `npm run build`, `npm run preview`. Playwright E2E: `./tests/e2e/run-e2e.sh` from repo root (or `npx playwright test` manually with `SECMAN_ADMIN_NAME`/`SECMAN_ADMIN_PASS` etc. set) — there is no `npm run test`/`test:checkin` script.
- CLI: `./gradlew :cli:shadowJar`, then `./scripts/secman <cmd>`.

## Style
- Kotlin: 4-space indent, `UpperCamelCase` types, `lowerCamelCase` members, Micronaut DI, prefer immutable data classes. Keep config near its feature package.
- TS/TSX: 2-space indent, named exports. Import order: std → third-party → local. Shared constants in `frontend/src/utils`.

## Tests
- Backend: `*Test.kt` next to the feature; `@MicronautTest` for DB-backed code; Mockk for unit doubles; persistence tests run against an **external MariaDB** (not H2, no Docker) via `BaseIntegrationTest`. Datasource comes from `TEST_DB_URL`/`TEST_DB_USERNAME`/`TEST_DB_PASSWORD` (set via `pass-cli`; defaults to a local `secman_test`). Schema is Hibernate `create-drop`, so point it only at a disposable test DB — never `DB_CONNECT`.
- Frontend: Playwright specs grouped by feature in `frontend/tests`; use `data-testid` selectors; document any `test.skip` in the PR.
- HTTP in tests goes through `SECMAN_HOST` env var (resolved via `pass-cli`). Never hardcode `localhost:8080` / `localhost:4321`.

## Commits & PRs
`type(scope): description` (or short `Type: Summary`). Imperative ≤72 chars. PR covers motivation, verification (`./gradlew build`, `./tests/e2e/run-e2e.sh`), and screenshots for UI changes. Highlight new dependencies — especially anything touching auth, storage, or crypto — and confirm license compatibility.

## Secrets
Never commit credentials or DB dumps. Use env vars or `application-local.yml` overrides; resolve secrets via `pass-cli` (Proton Pass).
