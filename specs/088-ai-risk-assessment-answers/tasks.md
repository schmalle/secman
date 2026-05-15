# Tasks: AI-Assisted Risk Assessment Answers

**Input**: Design documents from `/specs/088-ai-risk-assessment-answers/`
**Prerequisites**: `plan.md`, `spec.md` (both in this directory)
**Branch**: `claude/ai-risk-assessment-answers-J77EZ`

**Tests**: Test tasks are INCLUDED. CLAUDE.md §7 makes `./gradlew build` + `/e2ejs` + `/e2evulnexception` non-negotiable gates, and the design explicitly relies on unit-test enforcement of redaction (NFR-4) and confidence-band cutoffs (A3).

**Organization**: Tasks are grouped by user story. US1 is the MVP. US2–US4 can be implemented in parallel by separate developers after Phase 2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no incomplete dependencies)
- **[Story]**: User story (US1, US2, US3, US4); omitted for Setup / Foundational / Polish
- Each task carries an exact file path.

## Path conventions (this repo)

- Backend Kotlin: `src/backendng/src/main/kotlin/com/secman/{domain,controller,service,repository,config,dto,event}/`
- Backend resources: `src/backendng/src/main/resources/{application.yml,db/migration/,ai-prompts/}`
- Backend tests: `src/backendng/src/test/kotlin/com/secman/`
- Frontend: `src/frontend/src/{components,services,utils,pages}/`
- E2E: `tests/e2e/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Wire up configuration, feature flag, executor pool, and document the new env var. No business logic yet.

- [x] T001 Add `micronaut.executors.ai` (type=fixed, nThreads=8) to `src/backendng/src/main/resources/application.yml` (insert next to existing `translation` executor)
- [x] T002 Add `secman.ai.risk-assessment.*` block (enabled, model, max-cost-per-job-usd, max-concurrent-jobs-global, per-request-timeout, job-timeout, confidence.high-threshold, confidence.medium-threshold, pricing map) to `src/backendng/src/main/resources/application.yml`
- [x] T003 Add `secman.openrouter.api-key: ${OPENROUTER_API_KEY:}` block to `src/backendng/src/main/resources/application.yml` (top-level under `secman`)
- [x] T004 [P] Create `AiRiskAssessmentConfig` `@ConfigurationProperties("secman.ai.risk-assessment")` in `src/backendng/src/main/kotlin/com/secman/config/AiRiskAssessmentConfig.kt` (fields mirror application.yml, plus nested `Confidence` and `Pricing` records)
- [x] T005 [P] Document `AI_RISK_ASSESSMENT_*` env vars and `OPENROUTER_API_KEY` (resolved via `pass-cli` per CLAUDE.md) in `docs/ENVIRONMENT.md`
- [x] T006 [P] Create system prompt file `src/backendng/src/main/resources/ai-prompts/compliance-assistant.txt` — first line `VERSION: 001`; body instructs strict JSON output `{answer ∈ YES|NO|N_A|UNKNOWN, confidence ∈ [0..1], rationale, citations: [{title,url,snippet}]}`; explains web-search usage and "use UNKNOWN if not enough evidence"

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: DB schema, entities, repositories, and the shared `ComplianceAssistantService` skeleton. Every user story depends on this phase.

**⚠️ CRITICAL**: No user-story work can begin until Phase 2 is complete.

### Database migration

- [x] T007 Create Flyway migration `src/backendng/src/main/resources/db/migration/V215__ai_risk_assessment_answers.sql` containing: (a) `ai_suggestion_job` table, (b) `ai_answer_suggestion` table, (c) `ALTER TABLE response ADD COLUMN source ENUM(...) NOT NULL DEFAULT 'MANUAL'`, (d) `ALTER TABLE response ADD COLUMN ai_suggestion_id BIGINT NULL` + FK + indexes per plan.md §"Data model"

### Domain entities

- [x] T008 [P] Create `ResponseSource` enum (`MANUAL, AI_GENERATED, AI_EDITED`) in `src/backendng/src/main/kotlin/com/secman/domain/ResponseSource.kt`
- [x] T009 [P] Create `ConfidenceBand` enum (`HIGH, MEDIUM, LOW`) in `src/backendng/src/main/kotlin/com/secman/domain/ConfidenceBand.kt`
- [x] T010 [P] Create `SuggestedAnswerType` enum (`YES, NO, N_A, UNKNOWN`) in `src/backendng/src/main/kotlin/com/secman/domain/SuggestedAnswerType.kt` (separate from existing `AnswerType` because UNKNOWN is AI-only)
- [x] T011 [P] Create `AiSuggestionJobStatus` enum (`QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED`) in `src/backendng/src/main/kotlin/com/secman/domain/AiSuggestionJobStatus.kt`
- [x] T012 [P] Create `AiSuggestionScope` enum (`WHOLE_ASSESSMENT, SUBSET, SINGLE_REQUIREMENT`) in `src/backendng/src/main/kotlin/com/secman/domain/AiSuggestionScope.kt`
- [x] T013 [P] Create `AiAnswerSuggestionStatus` enum (`APPLIED, SUPERSEDED, FAILED`) in `src/backendng/src/main/kotlin/com/secman/domain/AiAnswerSuggestionStatus.kt`
- [x] T014 Create JPA entity `AiSuggestionJob` in `src/backendng/src/main/kotlin/com/secman/domain/AiSuggestionJob.kt` (fields per plan.md, `@PrePersist` sets createdAt; relationships: ManyToOne RiskAssessment, ManyToOne User triggeredBy)
- [x] T015 Create JPA entity `AiAnswerSuggestion` in `src/backendng/src/main/kotlin/com/secman/domain/AiAnswerSuggestion.kt` (ManyToOne AiSuggestionJob, RiskAssessment, Requirement; `@Convert(JsonConverter)` for citations JSON column)
- [x] T016 Extend `src/backendng/src/main/kotlin/com/secman/domain/Response.kt` with `var source: ResponseSource = ResponseSource.MANUAL` and `var aiSuggestionId: Long? = null` (ManyToOne is overkill; raw FK is enough)

### Repositories

- [x] T017 [P] Create `AiSuggestionJobRepository` (`CrudRepository<AiSuggestionJob, Long>`) in `src/backendng/src/main/kotlin/com/secman/repository/AiSuggestionJobRepository.kt` with query methods: `findByStatus`, `findByRiskAssessmentIdAndStatus`, `countByStatusIn(statuses)` (for global concurrency check)
- [x] T018 [P] Create `AiAnswerSuggestionRepository` (`CrudRepository<AiAnswerSuggestion, Long>`) in `src/backendng/src/main/kotlin/com/secman/repository/AiAnswerSuggestionRepository.kt` with: `findByRiskAssessmentIdAndStatus`, `findByJobId`, `findLatestAppliedByAssessmentAndRequirement(assessmentId, requirementId)`, `markSupersededByAssessmentAndRequirement(assessmentId, requirementId)` as `@Modifying @Query` UPDATE
- [x] T019 Add to `src/backendng/src/main/kotlin/com/secman/repository/ResponseRepository.kt`: `findByRiskAssessmentIdAndSourceAndAiSuggestion_ConfidenceBand` (for clear-LOW), `existsByRiskAssessmentIdAndRequirementIdAndSource` (for re-run guard)

### Shared service skeleton

- [x] T020 Create `src/backendng/src/main/kotlin/com/secman/service/ComplianceAssistantService.kt` skeleton: `@Singleton`, constructor injects `AiRiskAssessmentConfig`, `@Named("ai") ExecutorService`, `ObjectMapper`, optional Caffeine cache bean. JDK `HttpClient` field built lazily. Expose `fun suggest(requirement: Requirement, ctx: AssessmentContext): CompletableFuture<SuggestionResult>` — body throws `NotImplementedError` for now (Phase 3 fills it).
- [x] T021 [P] Define DTOs `AssessmentContext`, `SuggestionResult`, `Citation` in `src/backendng/src/main/kotlin/com/secman/dto/AiSuggestionDtos.kt` (data classes, `@Serdeable` on the request/response ones).

### Ownership/RBAC helper

- [x] T022 Create `AssessmentOwnershipGuard` `@Singleton` in `src/backendng/src/main/kotlin/com/secman/service/AssessmentOwnershipGuard.kt` exposing `fun check(assessmentId: Long, authentication: Authentication): RiskAssessment` — throws `AuthorizationException` if caller is not ADMIN AND not assessor/requestor of the assessment. Reused by all AI endpoints.

**Checkpoint**: Schema migrated, entities compile, repositories wired, service skeleton present. User-story phases can begin in parallel.

---

## Phase 3: User Story 1 — Pre-fill an assessment with AI (Priority: P1) 🎯 MVP

**Goal**: SECCHAMPION/ADMIN creator clicks "AI Pre-fill" → drafts appear with confidence + citations → user edits → submits.

**Independent test**: Create an assessment with 3+ requirements as a SECCHAMPION; trigger AI on Whole assessment; wait for completion; verify three draft Responses exist with `source=AI_GENERATED`, raw_confidence and citations populated on the linked suggestion; edit one Response via the existing bulk-save endpoint; verify `source=AI_EDITED`; submit; assessment finalizes via existing submit flow.

### Tests for User Story 1 (write first, expect to fail)

- [x] T023 [P] [US1] Write `ComplianceAssistantServicePromptBuilderTest` in `src/backendng/src/test/kotlin/com/secman/service/ComplianceAssistantServicePromptBuilderTest.kt`: asserts (a) prompt includes requirement shortreq/details/motivation/example/norm, (b) for asset basis, prompt does NOT contain owner email (`alice@example.com`), IP address (`10.0.0.5`), or any internal URL pattern (`*.internal.*`) — NFR-4 redaction.
- [x] T024 [P] [US1] Write `ConfidenceScorerTest` in `src/backendng/src/test/kotlin/com/secman/service/ConfidenceScorerTest.kt`: parameterised cases for HIGH≥0.75, MEDIUM 0.5–0.75, LOW<0.5; citation-count cap at 3; UNKNOWN-answer downweight; HIGH-without-citation downgrade to MEDIUM.
- [x] T025 [P] [US1] Write `CitationValidatorTest` in `src/backendng/src/test/kotlin/com/secman/service/CitationValidatorTest.kt`: rejects `http://`, oversized (>2KB), duplicate URLs; accepts well-formed https citations.
- [ ] T026 [P] [US1] Write `AiSuggestionJobServiceIntegrationTest` skeleton in `src/backendng/src/test/kotlin/com/secman/service/AiSuggestionJobServiceIntegrationTest.kt` extending `BaseIntegrationTest`, gated on `DockerAvailable.isDockerAvailable`. Single test method `happyPath_3Requirements_writesDraftsAndAppliedSuggestions` with WireMock-style mocked OpenRouter returning canned JSON.
- [ ] T027 [P] [US1] Write `AiSuggestionControllerRbacTest` in `src/backendng/src/test/kotlin/com/secman/controller/AiSuggestionControllerRbacTest.kt`: ADMIN OK, SECCHAMPION-as-creator OK, SECCHAMPION-as-other-user 403, RISK 403, USER 403.

### Implementation — backend service layer

- [x] T028 [US1] Implement `ComplianceAssistantService.buildPrompt(requirement, ctx)` in `src/backendng/src/main/kotlin/com/secman/service/ComplianceAssistantService.kt`: loads system prompt from `classpath:ai-prompts/compliance-assistant.txt` (cached), extracts `promptVersion` from first line, builds user message from requirement + redacted asset/demand context (asset: name/type/groups/cloudAccountId/osVersion only; demand: description only) + up to 3 few-shot already-answered siblings.
- [x] T029 [US1] Implement `ComplianceAssistantService.callOpenRouter(systemPrompt, userPrompt)`: JDK `HttpClient.sendAsync()` POST to `https://openrouter.ai/api/v1/chat/completions` with body `{model, messages:[{role:"system",content:...},{role:"user",content:...}], response_format:{type:"json_schema",json_schema:{...strict schema...}}}`, Authorization bearer `secman.openrouter.api-key`. Reads `message.content` and `message.annotations[].url_citation` (OpenRouter `:online` returns citations there).
- [x] T030 [US1] Implement `ComplianceAssistantService.parseAndScore(rawJson, annotations)` returning `SuggestionResult`: strict JSON parse → `CitationValidator` → `ConfidenceScorer` (which implements 0.5×self + 0.3×citation-grounding + 0.2×determinism) → derive band.
- [x] T031 [US1] Create `ConfidenceScorer` `@Singleton` in `src/backendng/src/main/kotlin/com/secman/service/ConfidenceScorer.kt`: pure function `score(modelSelfConfidence: Double, validCitationCount: Int, isUnknown: Boolean): Pair<Double, ConfidenceBand>`. HIGH-without-citation downgrade rule lives here.
- [x] T032 [US1] Create `CitationValidator` `@Singleton` in `src/backendng/src/main/kotlin/com/secman/service/CitationValidator.kt`: `validate(raw: List<Citation>): List<Citation>` (https only, ≤2KB JSON serialized, dedupe by URL).
- [x] T033 [US1] Wire `ComplianceAssistantService.suggest()` to: `buildPrompt` → `callOpenRouter` (on the `ai` executor via `CompletableFuture.supplyAsync`) → `parseAndScore` → return `SuggestionResult`. Wrap in `CompletableFuture`, do NOT block.

### Implementation — job orchestration (core path only; SSE in US2)

- [x] T034 [US1] Create `AiSuggestionJobService` `@Singleton` in `src/backendng/src/main/kotlin/com/secman/service/AiSuggestionJobService.kt`: `startJob(assessment, scope, requirementIds, force, triggeredBy): AiSuggestionJob`. Validates: feature-flag on, no other RUNNING job for this assessment, `countByStatusIn([QUEUED, RUNNING]) < max-concurrent-jobs-global`, pre-flight projected cost ≤ `max-cost-per-job-usd` (uses `pricing` map + token estimate of 400 in / 200 out per requirement). Persists job row, transitions to RUNNING, dispatches per-requirement futures onto IO executor (mirrors `ExportJobService.kt`).
- [x] T035 [US1] Add to `AiSuggestionJobService` an internal `processOne(job, requirement, ctx)` (`@Transactional open fun`): calls `complianceAssistantService.suggest(...)`; on success writes `AiAnswerSuggestion (status=APPLIED)`, marks prior APPLIED for same (assessment, requirement) as `SUPERSEDED`, **upserts** Response (`source=AI_GENERATED`, `aiSuggestionId=<new>`); on failure writes `AiAnswerSuggestion (status=FAILED)` with rationale=error; increments `job.completedCount` or `failedCount`; checks mid-flight cost cap; updates `lastHeartbeatAt`.
- [x] T036 [US1] Add `AiSuggestionJobService.finalize(job)`: when `completedCount + failedCount == totalCount`, set `status=COMPLETED`, `finishedAt=now`. Idempotent.
- [x] T037 [US1] Add re-run guard inside `startJob`: when computing target requirements, exclude any with `responseRepository.existsByRiskAssessmentIdAndRequirementIdAndSource(..., AI_EDITED)` unless `force=true`.

### Implementation — controller

- [x] T038 [US1] Create `AiSuggestionController` in `src/backendng/src/main/kotlin/com/secman/controller/AiSuggestionController.kt` annotated `@Controller("/api/risk-assessments/{id}/ai-suggestions")` `@Secured("ADMIN","SECCHAMPION")`. Endpoints to implement here (skip SSE/DELETE — US2):
  - `POST /jobs` body `StartJobRequest{scope, requirementIds?, force?}` → calls `ownershipGuard.check(id, auth)` → `jobService.startJob(...)` → 201 `{jobId, totalCount, estimatedCostUsd}`
  - `GET /jobs/{jobId}` → returns job status DTO
  - `GET /` → returns list of latest APPLIED suggestions for assessment (DTO with band, confidence, citations, model, rationale, suggestedAnswerType)
- [x] T039 [US1] In `AiSuggestionController`, define request/response DTOs as nested `@Serdeable` data classes (`StartJobRequest`, `JobStatusResponse`, `SuggestionListItem`).

### Implementation — provenance flip on edit

- [x] T040 [US1] Modify `ResponseController.bulkSaveResponses` in `src/backendng/src/main/kotlin/com/secman/controller/ResponseController.kt`: for each incoming response, if the existing row has `source=AI_GENERATED` AND any of `answerType`/`comment` changed, set `source=AI_EDITED` on save. Keep `aiSuggestionId` for audit trail.
- [x] T041 [US1] Add unit test `ResponseSourceTransitionTest` in `src/backendng/src/test/kotlin/com/secman/controller/ResponseSourceTransitionTest.kt`: covers MANUAL→stays-MANUAL on save, AI_GENERATED→AI_EDITED on field change, AI_GENERATED→AI_GENERATED when payload identical.

### Implementation — frontend

- [x] T042 [P] [US1] Create `src/frontend/src/services/aiSuggestions.ts`: axios wrappers `startAiJob(assessmentId, payload)`, `getJob(assessmentId, jobId)`, `listLatestSuggestions(assessmentId)`. All include `Authorization: Bearer <token>` via existing axios interceptor.
- [x] T043 [P] [US1] Create `src/frontend/src/components/AiPrefillModal.tsx`: Bootstrap modal with scope toggle (Whole / Unanswered / By norm), cost estimate field (server-supplied), Start/Cancel buttons. On Start calls `startAiJob` then closes after jobId obtained — live counter lives in parent (US2 polishes via SSE).
- [x] T044 [US1] In `src/frontend/src/components/RiskAssessmentManagement.tsx`, add a per-row "AI Pre-fill" action button next to existing "Perform"/"Check answers" actions. Gate visibility: `(isAdmin(roles) || isSecChampion(roles)) && (assessment.assessor.id === currentUser.id || assessment.requestor?.id === currentUser.id || isAdmin(roles))`. Clicking opens `AiPrefillModal`.
- [x] T045 [US1] Extend `src/frontend/src/components/AssessmentPerformance.tsx`: fetch `listLatestSuggestions(id)` on mount; build a `suggestionByRequirementId` map; render per-card a collapsible AI panel above the answer inputs containing: confidence chip (color-coded HIGH/MEDIUM/LOW with raw% on hover), rationale, citation links (target=_blank rel=noopener noreferrer), model name. Show a provenance badge next to the answer fields based on `response.source` (✦ AI-generated / ✦ AI-edited / no badge for MANUAL).
- [x] T046 [US1] In `src/frontend/src/components/AssessmentPerformance.tsx`, when user edits any AI_GENERATED card's answerType/comment, optimistically flip the badge to "✦ AI-edited" locally; server side confirms on bulk save (T040).

**Checkpoint US1**: SECCHAMPION can create assessment → click AI Pre-fill → drafts written → see confidence + citations → edit → submit via existing endpoint. MVP complete and demoable.

---

## Phase 4: User Story 2 — Watch progress and cancel (Priority: P2)

**Goal**: Live progress visible during AI run; user can cancel.

**Independent test**: Start an AI job on a 10-requirement assessment with mocked OpenRouter latency 1s/call; observe SSE-driven counter ticking; click Cancel; verify backend sets `status=CANCELLED`, partial results retained.

### Tests for User Story 2

- [ ] T047 [P] [US2] Write `AiSuggestionJobCancelIntegrationTest` in `src/backendng/src/test/kotlin/com/secman/service/AiSuggestionJobCancelIntegrationTest.kt`: starts a job, cancels mid-flight, asserts status=CANCELLED, some APPLIED rows present, no new APPLIED rows after cancel.
- [ ] T048 [P] [US2] Write `AiSuggestionSseTest` in `src/backendng/src/test/kotlin/com/secman/controller/AiSuggestionSseTest.kt`: connects to SSE endpoint with valid JWT, asserts at least one per-requirement event arrives and a terminal event with final status.

### Implementation

- [x] T049 [US2] Add `DELETE /api/risk-assessments/{id}/ai-suggestions/jobs/{jobId}` to `AiSuggestionController` → `jobService.cancelJob(jobId, auth)` → 204.
- [x] T050 [US2] Implement `AiSuggestionJobService.cancelJob(jobId, auth)` in `src/backendng/src/main/kotlin/com/secman/service/AiSuggestionJobService.kt`: ownership check, set `status=CANCELLED`, `finishedAt=now`, interrupt pending futures (track them in a `ConcurrentHashMap<jobId, List<Future<*>>>` field), emit terminal SSE event.
- [x] T051 [US2] Add `GET /api/risk-assessments/{id}/ai-suggestions/jobs/{jobId}/events` (returns `Publisher<Event<JobProgressDto>>` / Micronaut `Flux<Event<...>>`) to `AiSuggestionController`. Reads JWT from `?token=` query param (mirrors existing SSE endpoints like `/api/materialized-view-refresh/progress`). Reuses existing `SseEmitter` style if present; otherwise builds a `Sinks.Many` per jobId.
- [x] T052 [US2] In `AiSuggestionJobService.processOne` (T035), after each completed requirement publish an event `{requirementId, band, completedCount, totalCount, failedCount}` to the job's SSE sink. On `finalize` and `cancel`, publish terminal event `{status, totalCostUsd}` and complete the sink.
- [x] T053 [US2] Add `JobEventSinkRegistry` `@Singleton` (`src/backendng/src/main/kotlin/com/secman/service/JobEventSinkRegistry.kt`) holding per-job `Sinks.Many<JobProgressDto>` with cleanup after terminal event + 60s grace.
- [x] T054 [US2] Extend `AiPrefillModal.tsx` (`src/frontend/src/components/AiPrefillModal.tsx`): after job starts, open `EventSource` to `/api/risk-assessments/{id}/ai-suggestions/jobs/{jobId}/events?token=<jwt>`; show counter `12 / 87 · 3 HIGH · 6 MEDIUM · 3 LOW`; replace Start button with Cancel that calls DELETE; on terminal event close EventSource, refresh suggestion list in parent, close modal.
- [x] T055 [US2] Extend `src/frontend/src/services/aiSuggestions.ts` with `cancelJob(assessmentId, jobId)` and an `openJobEventStream(assessmentId, jobId, jwt): EventSource` helper.

**Checkpoint US2**: SECCHAMPION can watch live progress, cancel safely.

---

## Phase 5: User Story 3 — Bulk clear and safe re-run (Priority: P3)

**Goal**: Quickly remove LOW-confidence AI drafts; re-run AI without clobbering human edits.

**Independent test**: After an AI run produces some LOW rows and the user has edited two rows (now AI_EDITED), click "Clear LOW" → only AI_GENERATED LOW rows are deleted (their Responses removed, Suggestions remain APPLIED but with no Response); click "Re-run AI" → defaults exclude AI_EDITED rows; with `force=true` confirm dialog, AI_EDITED rows are also retargeted.

### Tests for User Story 3

- [ ] T056 [P] [US3] Write `ClearLowConfidenceTest` in `src/backendng/src/test/kotlin/com/secman/service/ClearLowConfidenceTest.kt`: seeds 3 LOW AI_GENERATED, 2 MEDIUM AI_GENERATED, 1 LOW AI_EDITED, 1 MANUAL; asserts only the 3 LOW AI_GENERATED Responses are deleted.
- [ ] T057 [P] [US3] Write `ReRunSkipEditedTest` in `src/backendng/src/test/kotlin/com/secman/service/ReRunSkipEditedTest.kt`: seeds an AI_EDITED row; runs `startJob(WHOLE_ASSESSMENT, force=false)`; asserts that requirement is not in target list; reruns with `force=true`; asserts it IS in target list.

### Implementation

- [x] T058 [US3] Add `POST /api/risk-assessments/{id}/ai-suggestions/clear-low-confidence` to `AiSuggestionController` → `clearLowConfidenceService.clear(assessmentId, auth)` → 200 `{deletedResponseCount, deletedSuggestionCount}`.
- [x] T059 [US3] Implement `AiSuggestionJobService.clearLowConfidence(assessmentId, auth)` (`@Transactional`): finds all Responses with `source=AI_GENERATED` AND linked `AiAnswerSuggestion.confidenceBand=LOW`; deletes those Responses; counts and returns. Suggestions remain for audit (status still APPLIED, but no Response — that's fine for history).
- [x] T060 [US3] Add "Clear LOW" button to header of `src/frontend/src/components/AssessmentPerformance.tsx` (only visible when any LOW AI_GENERATED row exists). Opens confirm dialog "Delete N low-confidence AI drafts? Your edits are not affected." → calls service → refetches.
- [x] T061 [US3] Extend `AiPrefillModal.tsx` (`src/frontend/src/components/AiPrefillModal.tsx`): when opening for re-run on an assessment with any AI_EDITED rows, show a checkbox "Force re-run on rows I've edited (default off)" → passes `force: true` in request body.
- [x] T062 [US3] Add segmented coverage strip at top of `src/frontend/src/components/AssessmentPerformance.tsx`: progress bar split into 4 segments (HIGH green / MEDIUM amber / LOW red / no suggestion grey) — count derived from suggestion list + total requirement count. Hover tooltip lists requirement IDs in each segment.
- [x] T063 [US3] Add confidence filter chips ("All / HIGH / MEDIUM / LOW / No AI") next to the existing compliant/non-compliant filter in `src/frontend/src/components/AssessmentPerformance.tsx`. Filters the visible requirement cards.

**Checkpoint US3**: SECCHAMPION can bulk-clear LOW, safely re-run, see coverage at a glance.

---

## Phase 6: User Story 4 — Audit transparency in review mode (Priority: P3)

**Goal**: Reviewer can see AI provenance, confidence, and citations on every answered requirement.

**Independent test**: Open a submitted assessment in `review` mode (`AssessmentPerformance.tsx` `mode="review"`); each answered requirement shows provenance badge (Manual / AI-generated / AI-edited); AI-touched answers expand to show model + confidence band + clickable citations.

### Tests for User Story 4

- [ ] T064 [P] [US4] Write `ReviewModePayloadTest` in `src/backendng/src/test/kotlin/com/secman/controller/ReviewModePayloadTest.kt`: asserts `GET /api/responses/assessment/{id}/requirements-with-responses` returns `source`, `aiSuggestionId`, and (when set) nested `aiSuggestion{model, band, confidence, citations, rationale}` for each requirement.

### Implementation

- [x] T065 [US4] Update `ResponseController.requirementsWithResponses` in `src/backendng/src/main/kotlin/com/secman/controller/ResponseController.kt`: enrich the returned DTO with `source`, `aiSuggestionId`, and nested `aiSuggestion` payload (model, band, confidence as displayed %, citations, rationale) joined from `AiAnswerSuggestionRepository.findLatestAppliedByAssessmentAndRequirement`.
- [x] T066 [US4] Update `RequirementWithResponse` DTO in `src/backendng/src/main/kotlin/com/secman/dto/` (or wherever defined) to include the new fields.
- [x] T067 [US4] In `src/frontend/src/components/AssessmentPerformance.tsx` `mode="review"` branch, render the provenance badge (✦ Manual / ✦ AI-generated / ✦ AI-edited) and, for AI-touched rows, an inline expandable "Audit details" block with model + confidence band + citations list.
- [x] T068 [US4] Create `docs/AI_RISK_ASSESSMENT.md`: explains the feature, configuration, role gating, audit trail, prompt versioning, redaction guarantees. Links to spec.md and plan.md in this directory.

**Checkpoint US4**: Audit transparency complete; reviewer can defend AI-assisted answers.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Hardening, caching, retries, prompt versioning, mandatory test gates.

- [x] T069 [P] Wire Caffeine cache into `ComplianceAssistantService` (`src/backendng/src/main/kotlin/com/secman/service/ComplianceAssistantService.kt`): cache `suggest` results by `hash(systemPrompt + userPrompt + model)` with 24h TTL, max 10_000 entries. Mirror `TranslationService` cache idiom. Add a "cacheHit" boolean to `SuggestionResult` for observability.
- [x] T070 [P] Add retry-with-exp-backoff to `ComplianceAssistantService.callOpenRouter`: 3 attempts, 1s/2s/4s delays, only on HTTP 5xx or `IOException`. Fail-through to `SuggestionResult.failed(...)` after final attempt; job records FAILED suggestion, continues with remaining requirements.
- [x] T071 [P] Add per-prompt-version handling to `ComplianceAssistantService.buildPrompt`: extract `VERSION:` line at startup, hold in a field, write into every `AiAnswerSuggestion.promptVersion`. Verified by existing `PromptBuilderTest`.
- [ ] T072 [P] Add cost-cap abort path test `CostCapAbortIntegrationTest` in `src/backendng/src/test/kotlin/com/secman/service/CostCapAbortIntegrationTest.kt`: configures `max-cost-per-job-usd=0.001`, runs 5-requirement assessment, asserts job status=FAILED with `errorMessage` mentioning cost-cap, partial APPLIED rows present, future requirements not processed.
- [ ] T073 [P] Add concurrency limit test `GlobalConcurrencyTest` in `src/backendng/src/test/kotlin/com/secman/service/GlobalConcurrencyTest.kt`: configures `max-concurrent-jobs-global=2`, starts 2 long-running jobs (mocked OpenRouter with artificial latency), attempts 3rd → expects HTTP 429 (or 409) with clear message.
- [x] T074 [P] Add scheduled cleanup `@Scheduled(fixedRate="1h")` to `AiSuggestionJobService` (`src/backendng/src/main/kotlin/com/secman/service/AiSuggestionJobService.kt`): marks any RUNNING job with `lastHeartbeatAt > 30 min ago` as FAILED ("heartbeat stale"). Mirrors ExportJobService pattern.
- [x] T075 Run `./gradlew :backendng:test` — all unit + integration tests must pass.
- [x] T076 Run `./gradlew build` — must complete clean (CLAUDE.md §5).
- [ ] T077 Run `./scripts/startbackenddev.sh`, verify clean startup (no Micronaut bean errors, no Flyway errors). Stop after verification. (CLAUDE.md §5.)
- [x] T078 Create Playwright spec `tests/e2e/ai-risk-assessment.spec.ts` covering US1 happy path: login as SECCHAMPION → create assessment → click AI Pre-fill → wait for SSE complete → verify 3 cards show ✦ AI-generated badge + confidence chip + ≥1 citation → edit one card → save → verify badge flips to ✦ AI-edited → submit → verify status COMPLETED.
- [ ] T079 Run `tests/e2e/run-e2e.sh tests/e2e/ai-risk-assessment.spec.ts` — must pass.
- [ ] T080 Run `/e2ejs` (admin AND normal-user) — must report **0 JS errors** for both roles. (CLAUDE.md §7 mandatory gate.)
- [ ] T081 Run `/e2evulnexception` — must remain 0 failures (regression gate). (CLAUDE.md §7 mandatory gate.)
- [x] T082 Update CLAUDE.md "Recent Changes" section with a bullet describing feature 088 (entities, config keys, flag default OFF, redaction guarantee, audit trail).

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: No dependencies. Can start immediately.
- **Foundational (Phase 2)**: Depends on Setup. Blocks every user story.
- **US1 (Phase 3) — MVP**: Depends on Foundational only.
- **US2 (Phase 4) — Progress + cancel**: Depends on Foundational. Independent of US1 in theory; in practice T054 modifies `AiPrefillModal.tsx` which US1 created — schedule after US1 to avoid merge churn, OR split modal scaffolding into Foundational (recommended for parallel teams).
- **US3 (Phase 5) — Clear LOW + safe re-run**: Depends on US1. Independent of US2.
- **US4 (Phase 6) — Audit transparency**: Depends on US1. Independent of US2/US3.
- **Polish (Phase 7)**: Depends on all user stories whose tasks it hardens.

### Within each user story

- Tests written before implementation; expected to fail until the implementation tasks land.
- Models → Repositories → Services → Controllers → Frontend.
- Backend changes restart-required (CLAUDE.md "E2E Runner" section).

### Parallel opportunities

- All [P] tasks in Phase 1 (T004, T005, T006).
- All [P] tasks in Phase 2 (T008–T013, T017–T018, T021).
- All [P] test-writing tasks in any user story (T023–T027 for US1; T047–T048 for US2; T056–T057 for US3; T064 for US4).
- US2, US3, US4 can be developed concurrently by separate developers after US1 ships, since they touch different controller methods and different frontend regions.

---

## Parallel Example: User Story 1 (MVP)

```text
# Phase 2 (Foundational), launch in parallel after T007 migration is in place:
T008 [P] Create ResponseSource enum
T009 [P] Create ConfidenceBand enum
T010 [P] Create SuggestedAnswerType enum
T011 [P] Create AiSuggestionJobStatus enum
T012 [P] Create AiSuggestionScope enum
T013 [P] Create AiAnswerSuggestionStatus enum
T017 [P] Create AiSuggestionJobRepository
T018 [P] Create AiAnswerSuggestionRepository
T021 [P] Define AiSuggestionDtos.kt

# US1 tests in parallel (after Phase 2):
T023 [P] [US1] PromptBuilderTest
T024 [P] [US1] ConfidenceScorerTest
T025 [P] [US1] CitationValidatorTest
T026 [P] [US1] AiSuggestionJobServiceIntegrationTest skeleton
T027 [P] [US1] AiSuggestionControllerRbacTest

# US1 frontend in parallel with US1 service-layer (after Phase 2):
T042 [P] [US1] services/aiSuggestions.ts
T043 [P] [US1] AiPrefillModal.tsx
```

---

## Implementation Strategy

### MVP First (US1 only)

1. Complete Phase 1 (Setup) — config, prompt file, env var documented.
2. Complete Phase 2 (Foundational) — schema, entities, repositories, service skeleton.
3. Complete Phase 3 (US1) — pre-fill, drafts, edit, submit.
4. **STOP and VALIDATE**: trigger AI on a real assessment with 3 requirements; verify drafts + provenance flip; submit.
5. Run Phase 7 mandatory gates (T076, T077, T080, T081). Demo / deploy if green.

### Incremental delivery

1. Ship MVP (US1) behind flag default OFF.
2. Add US2 (progress + cancel) — usability improvement; ship.
3. Add US3 (clear LOW + safe re-run) — power-user features; ship.
4. Add US4 (audit transparency) — compliance-team requirement; ship.
5. Final polish + hardening (caching, retries, scheduled cleanup) — Phase 7.

### Parallel team strategy

With three developers:

- Dev A finishes Phase 1 + 2.
- After T022 (Phase 2 complete):
  - Dev A → US1 (MVP).
  - Dev B → US2 prep work (SSE infra T053, controller cancel endpoint T049, frontend modal extensions T054 once US1's modal exists).
  - Dev C → US4 audit-mode work + docs T068.
- US3 picked up by whoever finishes first after US1 lands.

---

## Notes

- [P] tasks = different files, no incomplete dependencies.
- Story labels US1–US4 trace each task to a user story.
- Backend changes require backend restart (`./scripts/startbackenddev.sh`); frontend changes are Vite hot-reloaded.
- After every backend code change, verify: `./gradlew build` clean, then `./scripts/startbackenddev.sh` boots clean (Micronaut bean wiring is only checked at startup — CLAUDE.md §5).
- Never hardcode `http://localhost:*` in tests; route through `SECMAN_HOST` from `pass-cli`.
- Never log the OpenRouter API key. Never include real owner emails / IPs in test fixtures used by the prompt builder (it would invalidate redaction tests).
- Per CLAUDE.md §7, doc-only changes (e.g. T068 alone) can skip the E2E gates; everything else must pass `/e2ejs` and `/e2evulnexception` before merge.
