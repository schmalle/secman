# Repository Guidelines

## Project Structure & Module Organization

Core code sits in `src/`: `backendng/` holds the Micronaut service (`src/main/kotlin`, `src/main/resources`), `shared/` exposes reusable clients, and `cli/` ships terminal tooling. The Astro + React UI lives in `src/frontend/` (pages in `src/pages/`, components in `src/components/`, specs in `tests/`), while Python automation resides in `src/helper/` with pytest suites under `tests/`. Root-level `tests/`, `docker/`, `docs/`, and `scripts/` cover integration flows, container manifests, and setup aids. Additional shared code can be found in `src/shared/`, which is needed for both cli and backend development.

## Build, Test, and Development Commands

- `cd src/backendng && ./gradlew run` starts the API; `./gradlew build` compiles all Gradle modules.
- `cd src/frontend && npm install` once, then `npm run dev` for hot reload and `npm run build && npm run preview` to verify production output.
- Verification: `./gradlew test` (when enabled), `npm run lint`, `npm run test:checkin`, `npm run test:e2e`.
- Python helpers: `cd src/helper && pip install -e .[dev]`, `pytest`, `ruff check .`.

## Coding Style & Naming Conventions

Kotlin uses 4-space indentation, `UpperCamelCase` types, constructor injection, and immutable data classes; run ktlint (`./gradlew ktlintCheck` if configured) before pushing. TypeScript/TSX sticks to 2-space indentation, named exports, and ESLint via `npm run lint`, ordering imports external → internal → relative. Python follows PEP 8 with type hints, exposes entry points from `src/cli/`, and wraps scripts in `if __name__ == "__main__":`.

## Testing Guidelines

Do not create any testcase.

## Commit & Pull Request Guidelines

Commits use the `Type: Summary` convention (`Add: Admin User Notification System`); keep subjects imperative and squash noisy WIP steps. Pull requests should include motivation, verification commands (`./gradlew build`, `npm run test:checkin`, `pytest`), and screenshots for UI changes. Flag schema changes, feature toggles, and manual data steps, linking issues or specs before review.

## Security & Configuration Tips

Copy `.env.example` to `.env` and never commit credentials, exports, or database dumps. Update manifests and docs when ports or services change. Surface authentication, encryption, or export-related changes early and confirm third-party licenses before merge. Before completing an implementation, always do a security review.
