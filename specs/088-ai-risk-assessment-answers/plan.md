# Feature 088 — Implementation Plan

**Spec**: `spec.md` (this directory)
**Branch**: `claude/ai-risk-assessment-answers-J77EZ`

## Tech stack

- **Backend**: Kotlin 2.3.21 / Java 21, Micronaut 4.10, Hibernate JPA. New code under `src/backendng/src/main/kotlin/com/secman/`.
- **Frontend**: Astro 6.3 + React 19 islands, Axios, Bootstrap classes. New + extended components under `src/frontend/src/components/`.
- **DB**: MariaDB 11.4 via Flyway. New migration **V215**.
- **External**: OpenRouter (`https://openrouter.ai/api/v1`) — already wired in `application.yml` for `TranslationService`. JDK `HttpClient` pattern (not Micronaut `@Client`).
- **Caching**: Caffeine 24h TTL for AI calls keyed by `hash(systemPrompt + userPrompt + model)`.
- **Async**: Dedicated executor pool (named `ai`, fixed 8 threads, alongside existing `translation` pool). Job lifecycle mirrors `ExportJobService`.
- **SSE**: Same pattern as `materialized-view-refresh/progress` and `exception-badge-updates`. JWT via `?token=` query param.

## Reference implementations to mirror (DO NOT re-invent patterns)

| What | Reference | Why |
|---|---|---|
| OpenRouter HTTP call | `src/backendng/src/main/kotlin/com/secman/service/TranslationService.kt` | JDK HttpClient, executor pool, Caffeine cache, JSON parsing, refusal handling |
| Long-running DB-backed job | `src/backendng/src/main/kotlin/com/secman/service/ExportJobService.kt` | `ExportJob` row, heartbeat, IO executor, stages, cleanup scheduler |
| SSE controller | search controllers under `src/backendng/src/main/kotlin/com/secman/controller/` for `text/event-stream` | event emission, JWT in query, error stream |
| External HTTP config | `application.yml` lines 188-192, 299-334 | Service URL & timeout under `secman.http.services` |

## Architectural decisions

### A1 — One live suggestion per (assessment, requirement)
The composite unique key on `AiAnswerSuggestion (riskAssessmentId, requirementId)` where `status=APPLIED` is enforced via a partial-unique-index workaround (MariaDB lacks partial indices; we enforce in service layer + a regular composite index for query speed, plus a status check before insert). On re-run, prior APPLIED rows are marked `SUPERSEDED` in the same transaction as the new APPLIED row is written.

### A2 — `source` is on `Response`, not the join
Adding `source` (ENUM) + `ai_suggestion_id` (nullable FK) directly to `response` keeps the answer-list query single-join-free. Backfill all rows to `MANUAL` in V215.

### A3 — Confidence band stored, raw float also stored
`raw_confidence DOUBLE` and `confidence_band ENUM('HIGH','MEDIUM','LOW')` both stored. Band is indexed. Raw allows recalibration without re-running the model.

### A4 — Prompt versioning
System prompt lives in `src/backendng/src/main/resources/ai-prompts/compliance-assistant.txt`. First line is `VERSION: NNN` (e.g. `VERSION: 001`). `AiAnswerSuggestion.promptVersion` captures it at call time. Prompt edits bump the version; old suggestions remain comparable.

### A5 — Redaction enforced in builder, not hopeful
A unit test asserts that for an asset with owner=`alice@example.com` and IP=`10.0.0.5`, the built user message contains neither string. Test fails fast on any redaction regression.

### A6 — Cost cap is pre-flight + mid-flight
Pre-flight: project cost = `totalRequirements × avgTokensPerCall × pricePerToken[model]`. Mid-flight: accumulate `costUsd` per completed suggestion; if running total crosses cap, abort remaining futures and mark job `FAILED` with `errorMessage="cost-cap exceeded"`. Partial successes retained.

### A7 — Re-run skips AI_EDITED rows
`AiSuggestionJobService.startJob` filters out requirements whose existing Response has `source=AI_EDITED` unless `force=true` is in the request body. UI shows a confirm dialog when `force=true` is needed.

### A8 — Citations validation
Each citation must: (a) have `https://` scheme, (b) be ≤ 2 KB total JSON-serialized, (c) be deduped by URL. HIGH band requires ≥ 1 valid citation post-validation; if zero remain, downgrade to MEDIUM.

## Data model (V215)

```sql
-- 1) New: ai_suggestion_job
CREATE TABLE ai_suggestion_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    risk_assessment_id BIGINT NOT NULL,
    triggered_by_user_id BIGINT NOT NULL,
    model VARCHAR(128) NOT NULL,
    scope ENUM('WHOLE_ASSESSMENT','SUBSET','SINGLE_REQUIREMENT') NOT NULL,
    status ENUM('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED') NOT NULL DEFAULT 'QUEUED',
    total_count INT NOT NULL DEFAULT 0,
    completed_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    total_cost_usd DECIMAL(10,6) NOT NULL DEFAULT 0,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    last_heartbeat_at TIMESTAMP NULL,
    error_message VARCHAR(2048) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_aijob_assessment (risk_assessment_id, status),
    INDEX idx_aijob_status (status, last_heartbeat_at),
    FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (triggered_by_user_id) REFERENCES user(id)
);

-- 2) New: ai_answer_suggestion
CREATE TABLE ai_answer_suggestion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    risk_assessment_id BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    suggested_answer_type ENUM('YES','NO','N_A','UNKNOWN') NOT NULL,
    suggested_comment TEXT NULL,
    raw_confidence DOUBLE NOT NULL,
    confidence_band ENUM('HIGH','MEDIUM','LOW') NOT NULL,
    rationale TEXT NULL,
    citations JSON NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(16) NOT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    cost_usd DECIMAL(10,6) NULL,
    web_search_used BOOLEAN NOT NULL DEFAULT FALSE,
    status ENUM('APPLIED','SUPERSEDED','FAILED') NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at TIMESTAMP NULL,
    INDEX idx_aisug_assessment_req (risk_assessment_id, requirement_id),
    INDEX idx_aisug_band (confidence_band, status),
    INDEX idx_aisug_job (job_id),
    FOREIGN KEY (job_id) REFERENCES ai_suggestion_job(id) ON DELETE CASCADE,
    FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (requirement_id) REFERENCES requirement(id)
);

-- 3) Extend: response
ALTER TABLE response
    ADD COLUMN source ENUM('MANUAL','AI_GENERATED','AI_EDITED') NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN ai_suggestion_id BIGINT NULL,
    ADD INDEX idx_response_source (source),
    ADD FOREIGN KEY (ai_suggestion_id) REFERENCES ai_answer_suggestion(id) ON DELETE SET NULL;

-- 4) Backfill: existing rows all MANUAL (default handles it, but be explicit)
UPDATE response SET source='MANUAL' WHERE source IS NULL;
```

## API contracts

All paths under `/api/risk-assessments/{id}/ai-suggestions/...`.
All endpoints: `@Secured("ADMIN","SECCHAMPION")` + ownership check (caller is assessor OR requestor of assessment, OR ADMIN).

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/jobs` | `{scope: "WHOLE_ASSESSMENT"\|"SUBSET"\|"SINGLE_REQUIREMENT", requirementIds?: number[], force?: boolean}` | `201 {jobId, totalCount, estimatedCostUsd}` |
| `GET` | `/jobs/{jobId}` | — | `200 {id, status, totalCount, completedCount, failedCount, totalCostUsd, startedAt, finishedAt, errorMessage}` |
| `GET` | `/jobs/{jobId}/events` (SSE) | — | event stream: `{requirementId, band, completedCount, totalCount}` per row; terminal `{status, totalCostUsd}` |
| `DELETE` | `/jobs/{jobId}` | — | `204` (sets `CANCELLED`, interrupts futures) |
| `GET` | `/` (list latest APPLIED) | — | `200 [{requirementId, band, confidence, citations[], rationale, model, suggestedAnswerType}]` |
| `POST` | `/clear-low-confidence` | — | `200 {deletedResponseCount, deletedSuggestionCount}` |

## Configuration delta (`application.yml`)

```yaml
# add or extend
micronaut:
  executors:
    ai:
      type: fixed
      nThreads: 8

secman:
  ai:
    risk-assessment:
      enabled: ${AI_RISK_ASSESSMENT_ENABLED:false}
      model: ${AI_RISK_ASSESSMENT_MODEL:anthropic/claude-sonnet-4.6:online}
      max-cost-per-job-usd: ${AI_RISK_ASSESSMENT_MAX_COST:5.0}
      max-concurrent-jobs-global: ${AI_RISK_ASSESSMENT_MAX_JOBS:2}
      per-request-timeout: 60s
      job-timeout: 30m
      confidence:
        high-threshold: 0.75
        medium-threshold: 0.50
      pricing:
        # USD per 1k tokens; admin can override per model
        "anthropic/claude-sonnet-4.6:online":
          input: 0.003
          output: 0.015
  openrouter:
    api-key: ${OPENROUTER_API_KEY:}
```

`OPENROUTER_API_KEY` resolved via `pass-cli` per CLAUDE.md tooling conventions. Documented in `docs/ENVIRONMENT.md`.

## Frontend integration points

| Component / File | Change |
|---|---|
| `src/frontend/src/components/RiskAssessmentManagement.tsx` | Add **AI Pre-fill** action button per row (gated by `(isAdmin \|\| isSecChampion) && (currentUser is creator)`) |
| **new** `src/frontend/src/components/AiPrefillModal.tsx` | Modal: scope toggle, cost estimate, Start/Cancel; opens SSE to job events |
| `src/frontend/src/components/AssessmentPerformance.tsx` | Per-card AI panel (confidence chip, citations, rationale, provenance badge); header coverage strip; confidence filter chips; Clear LOW button; review-mode badges |
| **new** `src/frontend/src/services/aiSuggestions.ts` | Axios wrappers for the new endpoints |
| `src/frontend/src/utils/permissions.ts` | Reuse `isAdmin`, `isSecChampion` — already exist |

## Test strategy

- **Unit** (`src/backendng/src/test/kotlin/com/secman/service/`):
  - `ComplianceAssistantServiceTest` — prompt builder, redaction, JSON parse (good/refusal/malformed/oversized citations).
  - `ConfidenceScorerTest` — band cutoffs, citation cap, UNKNOWN handling.
- **Integration** (`@MicronautTest` + Testcontainers MariaDB + WireMock-style mocked OpenRouter):
  - `AiSuggestionJobServiceIntegrationTest` — full lifecycle (3 reqs, mocked LLM, cost-cap path, cancel path, re-run skip-AI_EDITED path, clear-LOW path).
  - `AiSuggestionControllerIntegrationTest` — RBAC (ADMIN OK, SECCHAMPION OK as creator, SECCHAMPION 403 as non-creator, RISK 403, USER 403).
- **E2E** (`tests/e2e/ai-risk-assessment.spec.ts`, new Playwright spec):
  - Create assessment → AI Pre-fill → SSE progress → drafts appear → edit one → submit. Asserts source flips MANUAL→AI_GENERATED→AI_EDITED.
- **Mandatory gates** (CLAUDE.md §7): `/e2ejs` (0 errors both roles), `/e2evulnexception` (0 failures).

## Risks & mitigations (carried into tasks)

| Risk | Mitigation task |
|---|---|
| Hallucinated citations | T044 — citation validator + HIGH-band downgrade |
| Sensitive-data leak in prompt | T020 — redaction in prompt builder + unit test T079 |
| Cost runaway | T032 — pre-flight + mid-flight cost cap with abort |
| OpenRouter outage | T040 — retry with exp backoff, FAILED on persistent error |
| Re-run clobbers human edits | T053 — re-run guard, force flag, UI confirm dialog |
| AI rows confused with manual ones in audits | T038 — `source` column + review-mode badges (US4) |

## Out-of-band notes

- Branch enforcement in `.specify/scripts/bash/check-prerequisites.sh` rejected our designated branch `claude/ai-risk-assessment-answers-J77EZ`; artifacts written manually to `specs/088-ai-risk-assessment-answers/`. Follow-up `/speckit.implement` would also need a workaround.
