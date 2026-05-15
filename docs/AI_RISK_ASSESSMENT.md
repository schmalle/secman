# AI-Assisted Risk Assessment Answers (Feature 088)

> **Status**: shipped behind feature flag `secman.ai.risk-assessment.enabled` (default OFF).
> **Branch**: `claude/ai-risk-assessment-answers-J77EZ`. Spec / plan / tasks in `specs/088-ai-risk-assessment-answers/`.

## What it does

ADMIN and SECCHAMPION users who created a risk assessment (assessor or requestor) can trigger an LLM (OpenRouter, with web search enabled via the `:online` model suffix) to pre-fill the compliance answers. Each generated answer is written as a draft `Response` row with `source = AI_GENERATED`, plus an `AiAnswerSuggestion` audit row carrying:

- the suggested answer (`YES | NO | N_A | UNKNOWN`),
- a numeric confidence `0..1`,
- a derived band (`HIGH | MEDIUM | LOW`),
- a free-text rationale,
- 0..N citations (each with `title`, `url`, optional `snippet`),
- the model id and prompt version,
- token usage and computed USD cost.

The human reviews, edits where needed, and submits via the existing `POST /api/responses/assessment/{id}/submit` finalization endpoint. The AI never finalizes anything.

## Roles & authorization

- **ADMIN** — full access. Can trigger AI on any assessment.
- **SECCHAMPION** — can trigger AI *only* on assessments they created (assessor or requestor).
- **RISK / USER / others** — `403`. Cannot start jobs; the AI button is hidden in the UI.

All endpoints (`/api/risk-assessments/{id}/ai-suggestions/...`) are gated by `@Secured("ADMIN","SECCHAMPION")` *and* `AssessmentOwnershipGuard.check(assessmentId, auth)`.

## Configuration

See `docs/ENVIRONMENT.md` for the env-var table. Quick recap:

| Var | Default | Notes |
|---|---|---|
| `AI_RISK_ASSESSMENT_ENABLED` | `false` | Master flag. Must be `true` and an API key must be present for any endpoint to do anything. |
| `AI_RISK_ASSESSMENT_MODEL` | `anthropic/claude-sonnet-4.6:online` | The `:online` suffix turns on OpenRouter's built-in web search; citations come back as `message.annotations[].url_citation`. |
| `AI_RISK_ASSESSMENT_MAX_COST` | `5.0` | Per-job hard cap (USD). Pre-flight rejects over-budget runs; mid-flight aborts and marks the job `FAILED`, retaining partial successes. |
| `AI_RISK_ASSESSMENT_MAX_JOBS` | `2` | Global concurrent-job limit. Prevents thundering herd if multiple SECCHAMPIONs trigger whole-assessment runs at once. |
| `OPENROUTER_API_KEY` | unset | Provided via `pass-cli`. Without it, the feature stays off regardless of the flag. |

Token-cost estimates per model live under `secman.ai.risk-assessment.pricing-per-1k-tokens` in `application.yml` and feed pre-flight cost projection. Update them when OpenRouter pricing changes.

## Data flow

```
[UI] "AI Pre-fill" button
   │
   ▼
POST /api/risk-assessments/{id}/ai-suggestions/jobs
   │  (concurrency + cost-cap check, ownership check)
   ▼
AiSuggestionJob row inserted, IO-executor dispatch
   │
   ▼
for each target requirement:
   ComplianceAssistantService.suggest(req, ctx)   // on 'ai' executor
      │
      ├─ PromptBuilder (redaction)
      ├─ HTTP POST → OpenRouter /chat/completions (JSON-strict, web search)
      ├─ parse + extract citations from message.annotations[]
      ├─ CitationValidator (https, dedup, ≤2KiB JSON)
      └─ ConfidenceScorer (0.5·self + 0.3·citations + 0.2·determinism)
   │
   ▼
applySuccess:
   - markAppliedAsSuperseded(assessment, requirement)
   - persist new AiAnswerSuggestion (APPLIED)
   - upsert Response { source = AI_GENERATED, aiSuggestionId }
   - emit SSE PROGRESS event
   - accumulate cost; check mid-flight cap
   │
   ▼
finalize → emit SSE COMPLETED event, close sink.
```

## SSE progress

`GET /api/risk-assessments/{id}/ai-suggestions/jobs/{jobId}/events` returns `text/event-stream`. One event per completed requirement (type `PROGRESS`, with `requirementId`, `band`, running counters) plus a terminal event (`COMPLETED | FAILED | CANCELLED`) before the stream closes. EventSource clients pass the JWT in `?token=` (browsers can't set headers on EventSource — same pattern as `/api/exception-badge-updates`).

The frontend (`AiPrefillModal`) tries SSE first and falls back to polling `GET /jobs/{jobId}` if EventSource errors.

## Audit trail

For every requirement an AI touched, you can trace:

| What | Where |
|---|---|
| Who triggered the job | `ai_suggestion_job.triggered_by_user_id`, `created_at` |
| Which model and which prompt | `ai_answer_suggestion.model`, `prompt_version` |
| What citations were returned | `ai_answer_suggestion.citations` (JSON) |
| Whether the human edited the AI answer | `response.source = AI_EDITED` |
| Spend | `ai_answer_suggestion.input_tokens` / `.output_tokens` / `.cost_usd`; aggregate on `ai_suggestion_job.total_cost_usd` |
| Re-run history | older suggestions are `status = SUPERSEDED`, never deleted |

The system prompt is version-stamped at the top of `src/backendng/src/main/resources/ai-prompts/compliance-assistant.txt`. Any change to the wording bumps `VERSION:` so old suggestions remain comparable.

## Redaction guarantee (NFR-4)

`PromptBuilder.redact()` scrubs three classes of PII before the prompt is sent, regardless of how the caller assembled the context:

- Email addresses (RFC-5322-ish pattern) → `[REDACTED]`
- IPv4 addresses → `[REDACTED]`
- Internal URLs with TLDs `.internal`, `.local`, `.corp`, `.lan`, `.intranet` → `[REDACTED]`

`PromptBuilderTest` asserts these even when the inputs deliberately leak them. Adding to that list? Update both the regex set and the test.

## Confidence scoring

```
score = 0.5 × self_reported
      + 0.3 × min(valid_citations, 3) / 3
      + 0.2 × (answer is not UNKNOWN ? 1 : 0)
```

Band derivation (thresholds in `application.yml`):

- `HIGH` requires `score ≥ 0.75` **and** at least one valid citation. Without citations, HIGH is downgraded to MEDIUM (anti-hallucination guard).
- `MEDIUM` if `score ≥ 0.50`.
- `LOW` otherwise.

The raw float **and** the band are persisted, so the thresholds can be retuned without re-running the model.

## Re-run safety

`AiSuggestionJobService.startJob` excludes any requirement whose existing `Response.source = AI_EDITED` unless the caller passes `force = true`. The UI surfaces the force toggle as a checkbox when the assessment has any edited rows, and the description makes the consequence explicit. `MANUAL` answers are never overwritten by the AI even with `force = true`.

## Clearing low-confidence drafts

`POST /api/risk-assessments/{id}/ai-suggestions/clear-low-confidence` deletes only `Response` rows where `source = AI_GENERATED` AND the linked suggestion's `confidenceBand = LOW`. AI_EDITED and MANUAL rows are not touched. The corresponding `AiAnswerSuggestion` rows stay (status `APPLIED`) for audit; only the user-facing `Response` is removed so the human starts that question from blank.

## Operational notes

- The IO executor handles per-requirement HTTP calls; pool sizing is `micronaut.executors.ai.number-of-threads` (default 8).
- A `@Scheduled` watchdog reclaims jobs with no heartbeat for 30 minutes by transitioning them to `FAILED`.
- The Caffeine cache in `ComplianceAssistantService` (24h TTL, 5_000 entries) is keyed on `SHA-256(systemPrompt || userPrompt || model)` — a re-run with no prompt or requirement changes returns instantly and incurs no spend.

## Related artifacts

- Spec: `specs/088-ai-risk-assessment-answers/spec.md`
- Plan: `specs/088-ai-risk-assessment-answers/plan.md`
- Tasks: `specs/088-ai-risk-assessment-answers/tasks.md`
- Flyway migration: `src/backendng/src/main/resources/db/migration/V215__ai_risk_assessment_answers.sql`
- Prompt: `src/backendng/src/main/resources/ai-prompts/compliance-assistant.txt`
