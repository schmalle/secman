```
  # With Proton Pass:
  ./tests/e2e/run-e2e.sh

  # Manually:
  cd tests/e2e
  SECMAN_ADMIN_NAME=admin SECMAN_ADMIN_PASS=pass \
  SECMAN_USER_USER=user SECMAN_USER_PASS=pass \
  npx playwright test
```

## Risk assessment lifecycle

`risk-assessment.spec.ts` covers the full asset-backed risk assessment path:
admin bootstrap, generated ADMIN/SECCHAMPION/respondent/viewer users, asset,
use case, scoped requirements, assessment creation, UI completion, response
persistence, risk raising, RBAC denial for a non-risk user, and before/after
cleanup. The spec deletes every user whose username starts with
`e2e-risk-assessment-` before setup and after teardown; do not use that prefix
for manual accounts.

Run only this suite with Proton Pass:

```bash
./tests/e2e/run-e2e.sh risk-assessment.spec.ts
```
