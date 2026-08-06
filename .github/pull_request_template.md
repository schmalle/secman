## Summary

<!-- What does this PR change, and why? 1-3 bullet points. -->

-

## Changes

<!-- Notable files/areas touched. Call out schema changes, new endpoints, new roles, etc. -->

-

## Testing

<!-- Check what you actually ran. Delete rows that don't apply (e.g. doc-only changes). -->

- [ ] `./gradlew build` is clean
- [ ] `./scripts/startbackenddev.sh` starts cleanly (backend changes)
- [ ] `cd src/frontend && npm ci && npm run build` exits 0 (frontend changes)
- [ ] New/updated unit or integration tests cover the change
- [ ] `/e2ejs` reports 0 JS errors (admin + normal user)
- [ ] `/e2evulnexception` passes with 0 failures
- [ ] Doc-only change outside `src/`, `tests/`, `scripts/` — E2E gates skipped

## Backend contract changes

<!-- If you renamed/retyped a request or response field, changed a path/method, or altered @Secured/auth,
     the extensions/ clients (secman_ai_github, secman_visual_check) may break silently. -->

- [ ] N/A — no backend contract changes
- [ ] Contract changed — `extensions/` clients updated and verified (path, method, request/response fields, `@Secured` roles/headers)

## Security

- [ ] RBAC enforced at controller (`@Secured`) and in the UI where applicable
- [ ] Input validation / file handling reviewed for new attack surface
- [ ] No secrets hardcoded (uses `pass-cli`)

## Related issues

<!-- Link issues this PR closes, e.g. Closes #123 -->
