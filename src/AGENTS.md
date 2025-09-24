# Repository Guidelines

## Project Structure & Module Organization
`backendng/` hosts the Micronaut Kotlin service; production code sits in `src/main/kotlin`, configuration in `src/main/resources`, and tests mirror packages under `src/test/kotlin`. `frontend/` contains the Astro + React client with routes in `src/pages`, reusable pieces in `src/components`, helpers in `src/utils`, and Playwright specs in `tests/`. Python tooling lives in `misc/` alongside its `requirements.txt`.

## Build, Test, and Development Commands
Backend: `cd backendng && ./gradlew run` starts the API, `./gradlew build` assembles it, and `./gradlew test` runs the Micronaut JUnit suite. Frontend: `cd frontend && npm install` once per checkout, then `npm run dev` for live reload, `npm run build` for production assets, and `npm run preview` to validate the bundle. Execute `npm run test` for the full Playwright pass or `npm run test:checkin` for the lighter gate.

## Coding Style & Naming Conventions
Kotlin follows the standard 4-space style with `UpperCamelCase` classes, `lowerCamelCase` members, and dependency injection via Micronaut annotations. Keep configuration near its feature package and prefer immutable data objects. Frontend files use TypeScript/TSX with two-space indentation; order imports as std → third-party → local, export named components, and keep shared constants in `src/utils`. Python utilities should follow PEP 8 and guard runnable code with `if __name__ == "__main__":`.

## Testing Guidelines
Add backend tests alongside the feature and name them `*Test.kt`. Use `@MicronautTest` for components needing the container, MockK for doubles, and provide seed data via H2 when persistence is involved. Frontend scenarios belong in `frontend/tests`; group them by feature, rely on `data-testid` selectors, and document any skipped paths in the PR. Regenerate Playwright artefacts only when the runner prompts you.

## Commit & Pull Request Guidelines
Commits follow the existing short form `Type: concise summary` (e.g., `Add: audit trail`, `Fix: password policy`). Keep the subject imperative and under 72 characters, adding context in the body if needed. PRs should cover motivation, implementation notes, and verification (`./gradlew test`, `npm run test:checkin`, or deeper suites). Link issues, attach UI evidence for visual changes, and call out new dependencies or manual steps before requesting review.

## Security & Configuration Tips
Never commit secrets or database dumps. Use environment variables or `application-local.yml` overrides for credentials, and update sample config files instead. Highlight new third-party libraries when they touch authentication, storage, or crypto, and confirm licence compatibility before merging.
