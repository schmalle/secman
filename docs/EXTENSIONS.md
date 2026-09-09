# Secman extensions

Secman supports independent scanner extensions that retain their own runtime,
dependencies and release cadence while publishing normalized scan results into
Secman's central integration-results API.

## Current extensions

| Extension | Repository | Scanner source | Purpose |
|---|---|---|---|
| GitHub AI scanner | `schmalle/secman_ai_github` | `GITHUB_AI` | Reviews GitHub repositories with pluggable AI/security-review engines and submits retained findings to Secman. |
| Visual checker | `schmalle/secman_visual_check` | `VISUAL` | Checks web targets, HTTP behavior and screenshots/vision findings and submits retained results to Secman. |

The extensions are deliberately separate repositories. They are clients of the
Secman integration contract, not source-code modules that are compiled into the
Secman backend.

## Integration contract

The canonical API is versioned under:

```text
/api/integrations/v1
```

Important endpoints are:

```text
/scanners
/scanners/{id}/subjects
/runs
/findings
/summary
```

Scanner configuration is administrative. A scanner is registered with a source,
a dedicated existing Secman service user and one or more bound subjects. The
assigned service user submits runs and must also have access to the bound asset.
Normal readers continue to use Secman's existing asset-scoped authorization.

A run is an atomic snapshot with an explicit lifecycle (`SUCCESS`, `PARTIAL`,
`FAILED`, `SKIPPED`). Submissions are idempotent. Missing findings are only closed
when a newer successful snapshot explicitly declares complete coverage; scanners
that intentionally inspect only a subset of a subject must not claim complete
coverage.

For the full API, evidence limits, lifecycle rules, authorization model and
migration/rollout notes, see [INTEGRATION_RESULTS.md](INTEGRATION_RESULTS.md).

## Extension configuration

Both current clients preserve their legacy upload behavior unless a registered
scanner ID is configured. Setting the scanner ID selects the v1 integration
contract; it does not by itself enable uploads.

### GitHub AI scanner

Repository: <https://github.com/schmalle/secman_ai_github>

Typical configuration:

```bash
export SECMAN_URL=https://secman.example.com
export SECMAN_USERNAME=secman-github-scanner
export SECMAN_PASSWORD=...
export SECMAN_SCANNER_ID=17

uv run secscan scan owner/repository --push-to-secman
```

The scanner preserves repository identity and retained finding evidence. Its
GitHub-specific inventory binding is distinct from Secman's native Dependabot
findings.

### Visual checker

Repository: <https://github.com/schmalle/secman_visual_check>

Configure the Secman URL, service-user credentials and registered scanner ID,
then use the client's existing Secman upload option. Visual scans intentionally
report incomplete coverage because checking one URL/page is not proof of complete
coverage for the bound asset.

## Adding another extension

New extensions should follow the same boundary:

1. Keep scanner-specific collection and analysis in its own repository/runtime.
2. Register a stable scanner source and dedicated Secman service user.
3. Bind scanner subjects to existing Secman inventory rather than creating a
   parallel authorization model.
4. Submit versioned `/api/integrations/v1/runs` snapshots with stable external
   finding identities, explicit coverage semantics and bounded evidence.
5. Treat Secman as the system of record for normalized findings, lifecycle,
   ownership, exceptions, ageing and central visibility.
6. Preserve idempotency: retries of the same snapshot must not duplicate state.
7. Never fetch remote evidence from Secman; send validated evidence or safe
   references according to the integration contract.
8. Keep credentials out of finding payloads, logs and replay identifiers.

## Release status

The central extension-results implementation was merged into `schmalle/secman`
through PR #491 (`feat/central-extension-results`). The corresponding extension
implementations are also present on their `main` branches:

- `schmalle/secman_ai_github`: `558fb12263739238db2a7e59a124dd8a945f3ec1`
- `schmalle/secman_visual_check`: `bbdaa93a20c8a1d0d6ba5f1cfb1d55eb3164c7fe`

These commit references describe repository state only; deployment and production
enablement remain separate operational steps.