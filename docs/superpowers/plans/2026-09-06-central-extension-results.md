# Central Extension Results Implementation Plan

> **For agentic workers:** Use executing-plans task-by-task; independent client
> repositories may use dispatching-parallel-agents after the wire contract is fixed.

**Goal:** Implement all five approved central-result recommendations.
**Architecture:** Inventory-bound scanner runs and retained rich findings project
open findings into existing vulnerability workflows. REST/MCP share a service.
**Tech Stack:** Kotlin/Micronaut/JPA/MariaDB, Astro/React, Python clients, Go relay.
**Spec:** ../specs/2026-09-06-central-extension-results-design.md

**Status:** Implementation delivered locally. User explicitly prohibited further
test execution because the database is not disposable; build/runtime/E2E gates
remain open. Final evidence and extension commits: `docs/INTEGRATION_RESULTS.md`.

## Global Constraints

- Preserve production data and legacy cli-add contracts; no production writes.
- New entities use IDENTITY. Admin config, assigned-user writes, asset-scoped reads.
- Run snapshots are atomic, max 500 findings, evidence 1 MiB/file and 5 MiB/run.
- Only complete successful snapshots resolve within one scanner and subject.
- Do not start the destructive E2E driver without a confirmed disposable database.
- No new dependencies; secrets stay in existing credential paths.

## Task 1: Backend persistence and ingestion

Ownership: backend domain/dto/repository/service/controller/mcp, V261 migration,
GithubRepository instance identity/import matching, focused backend tests.
Produce the exact version-1 API in the spec. Use IntegrationScanner,
IntegrationSubject, IntegrationRun, IntegrationFinding, IntegrationAttachment
entities and focused services; avoid one oversized service. HTTP and MCP call
the same validated transaction. Lock the subject while applying runs. Separate
request validation, scoped authorization, persistence and read DTO mapping.

- [x] Write unit tests for lifecycle decisions and request validation.
- [x] Implement bounded validators and lifecycle transitions.
- [x] Add repository/integration tests for replay, scopes, evidence and projection.
- [x] Implement schema, admin binding, reads, REST/MCP and projection lifecycle.
- [ ] Run focused Gradle tests and compile; review source migration and API shape.

## Task 2: GitHub extension

Ownership: extensions/secman_ai_github only. Consume the version-1 API in spec.
Add scanner ID configuration without breaking existing calls. Discover subjects
and match instance/numeric repo ID where available; do not confuse enterprise
hosts. Persist needed scan identity, send details, evidence, commit and issue/PR
links, zero and failed outcomes. Keep High/Critical-only scans incomplete.

- [x] Tests written: rich body, zero findings, failed scan, stable IDs under severity/title
  changes, enterprise mismatch, idempotent run key, dry run has no writes.
- [x] Implement client/mapping/config/state changes using existing auth/redaction.
- [ ] Run full Python suite, update docs, path-scoped extension commit, no push.

## Task 3: Visual extension

Ownership: extensions/secman_visual_check only. Consume same API and MCP tools.
Send all report outcomes and evidence, including image evidence within bounds.
Keep subject scope incomplete when partial page coverage cannot be proven.

- [x] Tests: full evidence/INFO, failed/skipped/zero targets, REST/MCP shape,
  missing subject, no fallback on rejection, stable retry identity, dry run.
- [x] Implement client/mapping/config with existing secrets and report classes.
- [x] Run full Python suite, update docs, path-scoped extension commit, no push.

## Task 4: Central UI, workflow and relay integration

Ownership: frontend new integrations service/components/page, Sidebar,
GithubRepoManagement, relay snapshot builder, chat event/catalog publisher and
its tests. Consume API in spec; no backend ingestion edits owned by Task 1.

- [x] Add pure frontend filter/display tests and authenticated API service.
- [x] Build coverage/scanners/subjects/runs/findings views using existing styles.
- [x] Link finding details to assets/exceptions; add GitHub AI panel.
- [x] Add aggregate integrations relay section and scanner-health events.
- [ ] Test role policies and notification transitions; frontend build/lint.

## Task 5: Contract and release verification

Ownership: docs/contracts, contract CI, docs integration guide and test evidence.

- [x] Add shared request fixture and backend/client contract checks.
- [x] Independently review security and cross-component shape; repair findings.
- [ ] Run all scoped suites, build, OWASP, startup and permitted E2E checks.
- [x] Update review document with implementation status, deployment/migration
  instructions and remaining environment blockers. No push or deployment.
