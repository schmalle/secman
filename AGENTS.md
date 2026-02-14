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

Do not create any unit testcase. However, every code change MUST include a runnable test script in `scripts/test/` that validates the change end-to-end (see Mandatory Change Requirements below).

## Commit & Pull Request Guidelines

Commits use the `Type: Summary` convention (`Add: Admin User Notification System`); keep subjects imperative and squash noisy WIP steps. Pull requests should include motivation, verification commands (`./gradlew build`, `npm run test:checkin`, `pytest`), and screenshots for UI changes. Flag schema changes, feature toggles, and manual data steps, linking issues or specs before review.

## Security & Configuration Tips

Copy `.env.example` to `.env` and never commit credentials, exports, or database dumps. Update manifests and docs when ports or services change. Surface authentication, encryption, or export-related changes early and confirm third-party licenses before merge. Before completing an implementation, always do a security review.

## Mandatory Change Requirements

Every code change MUST satisfy ALL of the following before it is considered complete:

### 1. Security Review (MANDATORY)
- Review every change against OWASP Top 10 vulnerabilities
- Check input validation, authentication/authorization, data exposure, injection vectors
- Document security findings in the PR description under a "Security Review" section
- Changes to auth, authorization, or data access require explicit security sign-off
- New API endpoints must document their security model

### 2. Documentation (MANDATORY)
- Update CLAUDE.md when adding/changing entities, endpoints, patterns, or configuration
- Update relevant docs/ files for user-facing changes (MCP.md, TESTING.md, DEPLOYMENT.md, ENVIRONMENT.md)
- New API endpoints must be documented with method, path, auth, request/response, and roles
- A code change without documentation is incomplete

### 3. Test Script (MANDATORY)
- Include a runnable test script in `scripts/test/` that validates the changed functionality
- Test scripts must be executable standalone and document expected inputs/outputs
- Cover the happy path and at least one error case
- Naming convention: `test-<feature-name>.sh`
- Exit code 0 on success, non-zero on failure

### 4. MCP Availability (MANDATORY)
- Every new or changed backend function that exposes data or performs actions MUST be available as an MCP tool
- Register in the appropriate MCP controller with proper permissions
- Update docs/MCP.md with tool documentation (parameters, permissions, examples)
- MCP tool permissions must align with REST API @Secured annotations
