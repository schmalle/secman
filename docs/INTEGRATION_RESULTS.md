# Central integration results

SecMan is the system of record for security scanner output. GitHub AI Check
(`secman_ai_github`), Visual Check (`secman_visual_check`), and Web Check
(`secman_web_check`) share the version-1 interface described here.

## Contract

Base path: `/api/integrations/v1`.

| Operation | REST | MCP |
|---|---|---|
| Discover assigned subjects | `GET /scanners/{id}/subjects` | `list_integration_subjects` |
| Submit one terminal snapshot | `POST /runs` | `submit_integration_run` |
| Health summary | `GET /summary` | `get_integration_summary` |
| Current findings | `GET /findings[/{id}]` | `list_integration_findings`, `get_integration_finding` |
| Run history | `GET /runs[/{id}]` | `list_integration_runs`, `get_integration_run` |

Every MCP tool requires delegated identity. Writes require
`INTEGRATIONS_WRITE`; the read tools require `INTEGRATIONS_READ`. REST and MCP
delegate to the same services, so asset access is identical on both transports.

The canonical request fixture is `docs/contracts/integration-run-v1.json`. The
three checker repositories keep an exact copy under `tests/fixtures/`; verify all
clients with:

```bash
./scripts/check-integration-contract.sh --run
```

The mirrored `/integration-contract-test` skill documents the complete gate for
Claude Code and Codex. CI accepts immutable 40-character extension revisions
through `.github/workflows/integration-contract.yml`.

## Lifecycle and retained evidence

A run is an atomic terminal snapshot with status `SUCCESS`, `PARTIAL`, `FAILED`,
or `SKIPPED`. `runKey` makes retries idempotent: identical content replays the
acknowledgement; different content under the same key is rejected. Only a newer
`SUCCESS` run with `completeCoverage=true` resolves findings absent from that
snapshot. Partial, filtered, failed, or skipped scans never close missing
findings.

Findings retain descriptions, recommendations, source locations, URL,
confidence, engine/model, commit, issue/PR links, and validated evidence.
Historical run detail preserves the original finding snapshot after the current
finding changes or resolves. Open findings project into SecMan vulnerabilities,
so ownership, asset access, exceptions, ageing, reporting, and notification
behavior remain central.

Limits are enforced atomically:

- 500 findings per run;
- 10 attachments per finding;
- 1 MiB per attachment and 5 MiB combined evidence per run;
- 100 records per read page;
- URLs are references only; SecMan never fetches finding evidence remotely.

## Administration and rollout

Administrators register a scanner and bind it to existing assets. GitHub
subjects additionally retain GitHub instance and numeric repository identity.
The assigned service user must already have access to every bound asset;
registration does not grant access.

Recommended rollout:

1. Back up MariaDB and apply the normal migration process.
2. Deploy backend and frontend together.
3. Register each scanner in **Integration Results → Scanner settings** and bind
   only intended inventory subjects.
4. Grant the service user `INTEGRATIONS_WRITE`; grant operator/automation keys
   `INTEGRATIONS_READ` when they need central triage.
5. Configure the scanner ID and explicitly enable upload in each checker.
6. Run `/integration-contract-test` before publishing a client revision.

Disable a scanner to stop ingestion without deleting history. A rejected v1
request never falls back to a legacy write path. Do not delete integration
tables as a rollback shortcut; their foreign keys intentionally retain history.

## Security properties

- All reads are scoped through `IntegrationAccessService` and
  `AssetFilterService`.
- Only the assigned service user can submit a subject run, and it must retain
  asset access.
- Evidence is decoded, bounded, validated, and re-encoded before storage.
- Scanner health notifications carry generic status and an authenticated SecMan
  link, not finding or asset detail.
- Relay/iOS exposes only aggregate integration counts under an `ADMIN` policy;
  snapshot schema version remains 2 because the new section is additive.
