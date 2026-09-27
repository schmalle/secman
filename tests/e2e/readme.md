# Running the E2E suite

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

## Guest assessment invitation regression

`assessment-guest-invitation.spec.ts` is automatically discovered by the full
Playwright suite. It creates a SaaS assessment for an unregistered respondent,
sends an invitation through the isolated SMTP sink, and opens the generated link
in a fresh browser context without cookies or stored login. It requires the
questionnaire API to return its scoped requirement, the page to render, and an
answer to save, remain selected after reload, and submit anonymously. Any login navigation fails the test. Token URLs are
excluded from traces/screenshots and sanitized from browser failures.

Run it alone (both configured browsers by default):

```bash
./scripts/test/test-e2e-assessment-guest-invitation.sh
# Or Chrome only:
./scripts/test/test-e2e-assessment-guest-invitation.sh --project=chrome
```

The wrapper provisions the disposable database, restricted database user,
separate frontend/backend and loopback SMTP sink. No real email is sent. Cleanup
removes the runner-owned database even after a failed test; the normal development
stack and its assessments are not used.

## MCP vulnerability query regression

`mcp-vulnerability-query.spec.ts` is automatically included in the Playwright
suite. It creates an explicitly delegated VULN user, a workgroup-granted asset,
an ungranted asset and known HIGH/LOW findings. It checks MCP initialization,
tool discovery and the severity-array schema, then exercises both `/mcp` and
`/api/mcp/tools/call`: default queries, CVE lookup, severity filtering, asset
filtering, pagination and asset-name serialization. Invalid keys, missing user
delegation, missing permissions and ungranted assets must be refused. MCP requests
use a separate HTTP context without the fixture administrator's cookies.

```bash
./scripts/test/test-e2e-mcp-vulnerability-query.sh --project=chrome
```

The same isolated runner owns and removes all fixtures and temporary API keys.
Traces, screenshots and videos are disabled for this credential-bearing spec.
