# Feature 088 — AI-Assisted Risk Assessment Answers

**Branch**: `claude/ai-risk-assessment-answers-J77EZ`
**Status**: Specified — ready for implementation
**Owner roles touched**: ADMIN, SECCHAMPION (writers); RISK (read-only of AI-marked answers)

## Problem

Risk assessments today require a human to manually answer every compliance question in an assessment, even when the answer is reasonably inferrable from the underlying asset / demand context plus public regulatory text. For long assessments (hundreds of requirements) this is a major bottleneck for SECCHAMPIONs.

## Goal

Let ADMIN and SECCHAMPION users — who created a given risk assessment — trigger an LLM (OpenRouter, with web search via the `:online` model suffix) to pre-fill every (or selected) compliance answer as a **draft Response**, attaching a per-answer confidence score and citations. The human reviews, edits where needed, and submits via the existing finalization endpoint. The AI never finalizes anything.

## Non-goals

- No new role. RISK is intentionally excluded from triggering AI.
- No change to `Risk` derivation from non-compliant answers.
- No change to assessment basis semantics (demand vs asset).
- No per-row "Accept" UI — generated answers auto-apply as drafts (decision locked).

## User stories

### US1 (P1, MVP) — Pre-fill an assessment with AI

As a SECCHAMPION who has just created a risk assessment with ~80 requirements, I want to click **"AI Pre-fill"** and have the system produce draft Responses for each requirement, each with a confidence band and at least one citation, so that I can spend my time reviewing and editing rather than authoring from scratch. When I'm done editing, I submit the assessment via the existing submit flow.

**Independent test**: Create an assessment with 3+ requirements as a SECCHAMPION; click AI Pre-fill / Whole assessment; wait for completion; verify three draft Responses exist with `source=AI_GENERATED`, confidence and citations populated; edit one; submit; verify edited Response carries `source=AI_EDITED`.

### US2 (P2) — Watch progress and cancel

As an ADMIN running a large assessment, I want a live progress indicator (X of Y completed, breakdown by HIGH/MED/LOW band) and a cancel button so I'm not blind to long-running jobs and can stop if the cost estimate was wrong.

**Independent test**: Start an AI job on a 10-requirement assessment with mocked OpenRouter latency of 1s per call; observe the SSE-driven counter updating; cancel mid-run; verify partial results retained and `AiSuggestionJob.status=CANCELLED`.

### US3 (P3) — Bulk clear and safe re-run

As a SECCHAMPION reviewing a completed AI run, I want to bulk-delete LOW-confidence drafts in one click (so I can answer those myself from blank) and re-run AI on remaining unanswered or AI_GENERATED requirements without clobbering any rows I've already edited.

**Independent test**: After an AI run, edit one Response (becomes AI_EDITED); click Clear LOW (removes only AI_GENERATED LOW rows); click Re-run AI; verify AI_EDITED row is untouched, AI_GENERATED non-LOW rows are replaced, and previously LOW slots are re-attempted.

### US4 (P3) — Audit transparency in review mode

As an ADMIN reviewing a submitted assessment, I want to see for each answer whether it was authored by a human, AI-generated and accepted unchanged, or AI-generated then edited — with the model, confidence and citations visible — so I can audit AI-assisted assessments.

**Independent test**: Open a submitted assessment in review mode; verify each answered requirement shows the provenance badge (Manual / AI-generated / AI-edited), and AI-touched answers expand to show model, confidence band, and clickable citations.

## Locked design decisions (from clarification round)

1. **Roles**: ADMIN + SECCHAMPION only can trigger AI. RISK can read provenance but not trigger.
2. **Apply flow**: AI writes directly to `Response` as drafts. Assessment status `STARTED` is the draft container; existing `POST /api/responses/assessment/{id}/submit` finalizes.
3. **Model**: Single admin-configured default (`secman.ai.risk-assessment.model`, default `anthropic/claude-sonnet-4.6:online`). No per-run dropdown.
4. **Cost**: Per-job cap (default $5 USD) + global concurrent-job limit (default 2). No per-user daily cap in v1.
5. **Feature flag**: `secman.ai.risk-assessment.enabled` defaults OFF.

## Functional requirements

- FR-1: Only the assessment's `assessor` or `requestor` (or any ADMIN) may trigger AI for that assessment.
- FR-2: Job rejects if a RUNNING job exists for the same assessment, OR global concurrent jobs ≥ `max-concurrent-jobs-global`, OR projected cost > `max-cost-per-job-usd`.
- FR-3: Each per-requirement call produces a strict JSON: `{answer ∈ YES|NO|N_A|UNKNOWN, confidence ∈ [0,1], rationale, citations[]}`. Malformed → suggestion `status=FAILED`, no Response written for that requirement.
- FR-4: A successful suggestion writes both an `AiAnswerSuggestion` (status=APPLIED) and a `Response` (source=AI_GENERATED, aiSuggestionId set). One live (APPLIED) suggestion per (assessment, requirement).
- FR-5: User edits via the existing bulk-save endpoint on an AI_GENERATED row flip `source` to `AI_EDITED`.
- FR-6: Re-run AI skips rows where `source=AI_EDITED` unless `force=true` (which prompts UI confirm).
- FR-7: Clear-LOW endpoint deletes only Responses where `source=AI_GENERATED` AND linked suggestion's confidence band is LOW. AI_EDITED and MANUAL rows are never touched.
- FR-8: SSE stream emits one event per completed requirement, with `{requirementId, band, completedCount, totalCount}`, plus a terminal `{status: COMPLETED|FAILED|CANCELLED, totalCostUsd}` event.
- FR-9: Confidence band derivation is server-side from the combined score; clients display the band, not the raw float.
- FR-10: Prompt builder redacts: owner emails, IP addresses, internal URLs, asset descriptions matching configured patterns. Allowed context: asset name/type/groups/cloudAccountId/osVersion, OR demand description.

## Non-functional requirements

- NFR-1: Feature flag default OFF; toggling flag does not require restart of frontend, only backend.
- NFR-2: Per-request OpenRouter timeout = 60s (configurable). Job-level: 30 minutes total.
- NFR-3: Audit logs (`ai-suggestion`) capture: jobId, assessmentId, triggeredBy, requirementId, model, promptVersion, confidence, citationCount, costUsd.
- NFR-4: No PII or asset owner data leaves the backend; verified by a unit test on the prompt builder.
- NFR-5: All AI endpoints return 403 to RISK-only users.

## Out of scope (v1)

- Multi-vendor LLM routing (only OpenRouter).
- Streaming per-token responses to the UI (only per-requirement events).
- Cross-assessment learning / fine-tuning.
- Per-user spend dashboards (a future feature when we have usage data).

## Acceptance gates (from CLAUDE.md §7)

- `./gradlew build` clean
- `./scripts/startbackenddev.sh` clean startup
- `/e2ejs` → 0 JS errors for admin AND normal-user runs
- `/e2evulnexception` → 0 failures (regression check)
- New Playwright spec `tests/e2e/ai-risk-assessment.spec.ts` passes the US1 happy-path.
