# Risk Assessment Discovery, Recommendation, and AWS Owner Mail Plan

## Purpose

This document is the implementation plan for three related operator journeys:

1. ask SecMan through MCP which risk assessments are open and what type each one is;
2. ask SecMan to analyze one assessment's submitted answers and recommend whether it is OK, through both MCP and the web UI; and
3. optionally email every owner when an AWS mapping import discovers a new account.

It is deliberately a **gap plan**, not a greenfield design. SecMan already implements
substantial parts of all three journeys. The first implementation step is therefore to
preserve the existing contracts, close only the gaps listed below, and avoid introducing
a second assessment or onboarding workflow.

## Outcome and non-goals

After this plan is complete:

- an authorized MCP client can request open assessments, optionally filter them by
  assessment basis type, and receive an explicit type in every result;
- an authorized reviewer can obtain the same explainable recommendation through MCP
  and the UI;
- an AWS mapping importer can opt in or out of owner mail, receives a per-owner delivery
  outcome, and can safely retry without duplicate mail; and
- operator, MCP, configuration, and troubleshooting documentation describes the actual
  behavior and its authorization boundary.

This work does **not** allow an AI recommendation to approve an assessment, alter an
answer, or replace the existing independent human acceptance step. It does not send mail
for accounts that were known before the import, infer an owner from AWS root-contact
data, or weaken tenant/resource visibility.

## Existing capability inventory

### 1. Open assessment discovery

The MCP tool `list_risk_assessments` already:

- accepts `status = STARTED | COMPLETED` and `useCaseName`;
- pages results;
- applies delegated-user visibility in the repository query and rechecks access in the
  service; and
- returns `basisType` (`DEMAND`, `ASSET`, or `AWS_ACCOUNT`) in each assessment summary.

Consequently, `{"status":"STARTED"}` already answers the basic “which assessments are
open and what type are they?” question. The missing ergonomic and contract pieces are an
explicit `openOnly` vocabulary, a server-side `basisType` filter, an unambiguous
`assessmentType` response field, and focused contract tests/documentation. “Open” must
remain an alias for persisted status `STARTED`; this plan does not add a third lifecycle
state.

### 2. Answer evaluation

The MCP tool `evaluate_risk_assessment` already evaluates a completed questionnaire. It
returns `COMPLIANT` when there is no `NO`, otherwise `NON_COMPLIANT`, plus answer counts
and findings. This is deterministic and read-only, but it is available only through MCP,
does not provide an operator-oriented recommendation/explanation, and is not exposed by
the risk-assessment UI.

The separately shipped AI pre-fill feature answers a different question: it drafts
questionnaire answers before submission. It must not be reused as if its completion were
an approval recommendation.

### 3. New AWS account owner mail

The shared mapping-import path already supports `onboardingMode` (`WELCOME_ONLY`,
`DIRECT`, or `GUIDED`) and `sendWelcomeEmail`. The CLI, REST bulk import, and MCP
`import_user_mappings` converge on `UserMappingBulkImportService`; new-account detection,
dry-run behavior, direct assessment creation, and onboarding mail therefore already have
one implementation.

The important remaining gap is operational confidence. Mail is best-effort, and a
successful import or created assessment is not proof of delivery. The result currently
contains `welcomeEmailSent`, but the long-lived delivery/audit and retry contract needs to
be made explicit and consistent on every import surface.

## Terminology and contract decisions

| Term | Contract |
|---|---|
| Open assessment | `status == STARTED`; overdue assessments are still open. |
| Assessment type | The basis type: `DEMAND`, `ASSET`, or `AWS_ACCOUNT`. Use-case names remain a separate filter and response field. |
| Recommendation | Advisory result derived from the immutable submitted revision; never an approval decision. |
| Owner | Each validated email mapped to the newly introduced account in that import, consistent with the existing import contract. |
| New account | A 12-digit account id absent from all mapping rows before the import begins. |

Retain `basisType` for backward compatibility and add `assessmentType` as its documented
alias. Clients must not infer the type from nullable `asset`, `demand`, or AWS fields.

## Workstream A — MCP open-assessment query

### A1. Extend the read contract

Extend `list_risk_assessments` with:

```json
{
  "status": "STARTED",
  "openOnly": true,
  "assessmentType": "AWS_ACCOUNT",
  "useCaseName": "Cloud workload",
  "page": 0,
  "pageSize": 20
}
```

Rules:

- `openOnly` is optional and defaults to `false`.
- `openOnly: true` is equivalent to `status: "STARTED"`.
- reject `openOnly: true` together with any other status rather than silently choosing
  one;
- `assessmentType` is optional and is a closed enum of `DEMAND`, `ASSET`, and
  `AWS_ACCOUNT`;
- preserve the existing maximum page size of 100; and
- add `filtersApplied` to the response so an agent can report exactly what it queried.

Each summary should expose at least `id`, `status`, `assessmentType`, legacy `basisType`,
basis identity/display data, use cases, start/end dates, respondent, assessor, and locked
release version. Do not expose answers or comments in a list response.

### A2. Keep filtering and authorization in the database boundary

Add the optional basis-type predicate to `RiskAssessmentRepository.findForMcp` and its
count query. Do not fetch every visible assessment and filter in Kotlin: that would make
page counts incorrect and create an avoidable unbounded-query path. Continue the service
access recheck as defense in depth.

Normalize and validate all enum input before repository invocation. An inaccessible id
or assessment must remain indistinguishable from a missing one on detail operations.

### A3. Verification

Add unit/integration coverage for:

- no filter, `openOnly`, each status, and each assessment type;
- the conflicting `openOnly`/status input;
- mixed types across more than one page, including correct `totalElements`;
- ADMIN/SECCHAMPION global visibility and ordinary delegated-user scoping;
- inaccessible assets/accounts and revoked assignments; and
- the MCP `tools/list` schema plus `tools/call` permission registration.

## Workstream B — shared assessment recommendation

### B1. Define one evaluation DTO and service

Extract the existing deterministic MCP evaluation into a shared, read-only
`RiskAssessmentRecommendationService` used by both REST and MCP. A recommended response
shape is:

```json
{
  "assessmentId": 9001,
  "answerRevision": 12,
  "recommendation": "NOT_OK",
  "verdict": "NON_COMPLIANT",
  "summary": "2 requirements are not met; 1 answer needs review.",
  "answerCounts": {"YES": 17, "NO": 2, "N_A": 1, "UNKNOWN": 0},
  "findings": [
    {
      "requirementId": 301,
      "internalId": "ENC-01",
      "answerType": "NO",
      "reason": "Requirement is explicitly not met.",
      "comment": "Rollout is scheduled for Q4."
    }
  ],
  "generatedAt": "2026-09-26T12:00:00Z",
  "advisory": true
}
```

Use a three-valued internal recommendation even if the first UI emphasizes a binary
answer:

- `OK`: every scoped requirement is answered and none is `NO` or `UNKNOWN`;
- `NOT_OK`: at least one answer is `NO`;
- `NEEDS_REVIEW`: an answer is missing/`UNKNOWN`, the questionnaire is not submitted, or
  policy requires review of `N_A`.

Before implementation, product/security owners must make one explicit policy decision:
whether `N_A` is accepted as `OK` or produces `NEEDS_REVIEW`. The current MCP evaluator
lists `N_A` as a finding while still returning `COMPLIANT`; changing that behavior without
an approved rule would be a breaking semantic change. Encode the selected rule once and
cover it with a truth-table test.

### B2. Revision safety and lifecycle

Recommendations are calculated against `answerRevision`. Return the revision and reject
or visibly mark stale results when answers change. Initially calculate on demand and do
not add a persistence table: the deterministic result is cheap and avoids stale stored
decisions. Add persistence only if audit requirements demand historical recommendation
snapshots; if so, use a unique key on `(assessment_id, answer_revision, policy_version)`
and retain prior rows.

Completed assessments are the normal evaluation target. For a started assessment, return
`NEEDS_REVIEW` with completeness details to the UI, but do not label it `OK`. Preserve the
current MCP behavior during rollout by either keeping `evaluate_risk_assessment`
completed-only or versioning its documented behavior before broadening it.

### B3. MCP surface

Keep `evaluate_risk_assessment` as the stable tool name and move its implementation onto
the shared service. Add the new fields without removing `verdict`, `answerCounts`,
`requirementCount`, `findings`, or `assessment`.

Authorization remains review authority: assigned assessor/requestor as permitted today,
ADMIN, or SECCHAMPION, with resource access checked after delegation. The MCP operation
stays `READ`; analysis must not accept, submit, or mutate the assessment.

### B4. REST and UI surface

Add a read endpoint such as:

```text
GET /api/risk-assessments/{id}/recommendation
```

Apply the same access service and review-authority rule as MCP. Avoid duplicating a
controller-specific evaluator.

In `RiskAssessmentManagement`:

- show **Analyze answers** only when the viewer has review authority;
- open a modal or detail panel with the recommendation, answer counts, findings, policy
  explanation, assessment revision, and generation time;
- use distinct accessible styling for `OK`, `NOT_OK`, and `NEEDS_REVIEW` and never rely
  on color alone;
- label the output “Advisory — human approval is still required”;
- link each finding to the corresponding requirement/answer when the viewer may see it;
- provide useful empty, loading, stale, forbidden, and failure states; and
- refresh or invalidate the panel if the assessment revision changes.

### B5. Optional AI narrative — separate and feature-gated

If “analyze” is intended to include free-text reasoning, add it only after the
deterministic recommendation ships. The deterministic policy remains authoritative; an
LLM may summarize findings and propose remediation but may not change `OK`/`NOT_OK`.
Reuse the existing OpenRouter configuration, redaction, outbound-call controls, budget
limits, citations, audit metadata, and feature flag. Never send attachments, identity
fields, or unrestricted comments without applying and testing the existing redaction
boundary. The UI and MCP response must identify generated narrative as AI-produced and
degrade cleanly to deterministic output when AI is disabled or unavailable.

### B6. Verification

Add:

- truth-table tests for every `AnswerType`, incomplete questionnaires, zero requirements,
  and mixed findings;
- authorization tests for assessor, requestor, respondent-only, ADMIN, SECCHAMPION, and
  unrelated users;
- stale-revision and concurrent-read tests;
- REST integration tests asserting byte-compatible policy results with MCP;
- frontend unit tests for all states and keyboard-accessible modal behavior; and
- E2E coverage that creates, answers, submits, analyzes through MCP, then verifies the
  same recommendation and findings in the UI.

## Workstream C — optional owner email in AWS mapping import

### C1. Preserve the shared onboarding path

Do not add mail sending to controllers, CLI commands, or the MCP tool. All surfaces must
continue to pass options into `UserMappingBulkImportService` and
`AccountOnboardingService` so new-account detection and idempotency stay identical.

Keep backward compatibility:

- no onboarding flags means mapping-only import and no owner mail;
- legacy `startRiskAssessment: true` means `DIRECT` with no additional welcome email;
- explicit `onboardingMode` defaults `sendWelcomeEmail` to true; and
- explicit `sendWelcomeEmail: false` suppresses the welcome message without suppressing
  the selected assessment behavior.

Document clearly that `DIRECT` assessment-start mail and onboarding welcome mail are
different notifications. The options must not accidentally send both with confusing or
duplicate calls to action.

### C2. Make delivery outcomes durable and retryable

Introduce or reuse a notification audit record keyed by event type, normalized owner
email, AWS account id, and import/onboarding idempotency key. Record `PENDING`, `SENT`, or
`FAILED`, timestamps, provider message id when available, and a bounded sanitized error
code/message. Do not log tokens, message bodies, or secrets.

Send only after the mapping transaction commits. A mail failure must not roll back a
valid import, but every surface must report it independently of mapping success. Return
per-owner fields such as:

```json
{
  "awsAccountId": "123456789012",
  "ownerEmail": "owner@example.com",
  "welcomeEmail": {
    "requested": true,
    "status": "SENT",
    "notificationId": 42,
    "retryable": false
  }
}
```

Define retry as an explicit ADMIN action against a failed notification record. Enforce an
atomic claim/unique key so concurrent retries or repeated imports cannot double-send.
Never retry an already `SENT` event unless an administrator deliberately requests a new
notification event.

### C3. Surface parity

Verify that CLI JSON/text output, REST `BulkUserMappingResponse`, and MCP tool results all
report the same requested/sent/failed/skipped meanings. CSV and XLSX upload adapters must
use the same request object rather than bypassing onboarding. Dry run must resolve and
validate recipients and show `WOULD_SEND`, but must mint no token, create no audit row,
and contact no mail provider.

### C4. Verification

Extend isolated import/onboarding E2E coverage for:

- one new account with one owner and one with multiple distinct normalized owners;
- known-account re-import, duplicate rows in one file, and concurrent/replayed import;
- opt-in, explicit opt-out, legacy direct mode, all onboarding modes, and dry run;
- unknown/invalid owner addresses and owners without SecMan user rows;
- SMTP success, no active provider, transient failure, durable failure state, retry, and
  exactly-once behavior; and
- matching CLI, REST, and MCP result semantics.

## Documentation deliverables

Update documentation in the same changes that alter a contract:

1. `docs/MCP.md` — tool schemas, examples for open-by-type queries, response fields,
   permissions, and errors.
2. `docs/MCP_RISK_ASSESSMENT_LIFECYCLE.md` — discovery-to-recommendation walkthrough and
   the human-approval boundary.
3. `docs/AI_RISK_ASSESSMENT.md` — distinguish answer pre-fill from post-submission
   analysis and document optional narrative behavior if implemented.
4. `docs/AWS_ACCOUNT_RISK_ASSESSMENT.md` — direct assessment mail versus welcome mail,
   durable delivery outcomes, retry, and troubleshooting.
5. `docs/ACCOUNT_ONBOARDING.md` — exact defaults for every mode and surface, dry-run
   semantics, and idempotency.
6. `docs/CLI.md` and generated/help text — import switches and output meanings.
7. `docs/ENVIRONMENT.md` — only if a new feature flag, retry setting, or provider option
   is introduced.

Include copy-pasteable MCP requests and representative success, validation, forbidden,
and delivery-failure responses. Do not document an option until all advertised surfaces
actually support it.

## Security and privacy review (OWASP Top 10:2021)

| Category | Required control for this plan |
|---|---|
| A01 Broken Access Control | Reuse delegated-user guards and `RiskAssessmentAccessService`; scope repository paging before serialization; require ADMIN for import/retry. |
| A02 Cryptographic Failures | Keep mail and MCP credentials in `pass-cli`/configured secret stores; never return tokens or provider credentials. |
| A03 Injection | Parse status/type through closed enums, bind repository parameters, escape mail/HTML values, and render comments as text in React. |
| A04 Insecure Design | Recommendations are advisory, revision-bound, explainable, and separated from approval; mail is opt-in and idempotent. |
| A05 Security Misconfiguration | Feature flags default safely; missing mail/AI configuration yields explicit unavailable/failed state rather than false success. |
| A06 Vulnerable Components | Prefer existing dependencies; justify and scan any new mail/outbox library. |
| A07 Identification and Authentication Failures | Preserve JWT/MCP delegation and role checks; email links use the existing authenticated or one-time-token flow. |
| A08 Software and Data Integrity Failures | Version recommendation policy and AI prompts; preserve audit records and immutable submitted answers. |
| A09 Security Logging and Monitoring Failures | Audit actor, assessment/revision, import, notification status, and retries with sanitized structured fields. |
| A10 Server-Side Request Forgery | Reuse the existing fixed OpenRouter/mail provider clients; accept no caller-controlled provider URL. |

Authentication/authorization, outbound AI, and mail changes require the repository's
security review gate. Any HIGH-or-above finding blocks rollout.

## Delivery sequence

1. **Contract decision** — approve `N_A` policy, type terminology, response compatibility,
   and owner-mail delivery semantics.
2. **Discovery slice** — implement the MCP type/open filters, repository paging, tests,
   and MCP docs. This is independently releasable.
3. **Shared deterministic evaluator** — extract existing evaluation, add revision-aware
   DTO and tests, while retaining the MCP response's existing fields.
4. **REST/UI slice** — expose the same service and add the accessible analysis panel plus
   cross-surface E2E coverage.
5. **Mail reliability slice** — add durable delivery state and retry around the existing
   onboarding path, then prove surface parity and exactly-once behavior.
6. **Optional AI narrative** — only if requested after deterministic recommendation is
   accepted; reuse existing controls and keep it non-authoritative.
7. **Documentation and rollout** — complete operator docs, run all mandated gates, enable
   any new flag in a test environment, and monitor forbidden/error/delivery metrics.

Prefer small commits in that order. Do not combine a recommendation-policy change with a
mail-delivery migration; each needs a reversible deployment and separately attributable
tests.

## Definition of done

- `list_risk_assessments` can return only open assessments of a requested type with
  correct paging and no visibility leak.
- Existing clients using `status` and `basisType` remain compatible.
- MCP and UI show byte-for-byte equivalent recommendation policy fields for the same
  assessment revision.
- The recommendation explains every non-OK result and cannot mutate or approve the
  assessment.
- New-account owner mail remains opt-in, is post-commit, records a durable per-owner
  outcome, and is safe to retry without duplicate delivery.
- Mapping import behavior is identical across CLI, REST, MCP, CSV, and XLSX adapters.
- Unit, integration, frontend, isolated onboarding, MCP lifecycle, `e2ejs`, and
  `e2evulnexception` checks pass.
- MCP, lifecycle, onboarding, AWS assessment, CLI, environment (when applicable), and
  troubleshooting documentation match the shipped behavior.
- The diff passes `./scripts/owasp-check.sh` and the required semantic/security review
  with no HIGH-or-above findings.

## Open decisions to resolve before coding

1. Does `N_A` mean `OK`, or does it require reviewer attention?
2. Should requestors retain evaluation authority, or only assigned assessors and global
   assessment managers?
3. Is a deterministic recommendation sufficient, or is a feature-gated AI remediation
   narrative also required?
4. What retention period and redaction policy applies to owner-mail delivery records?
5. Should failed-mail retry be UI-only, MCP-accessible for ADMIN automation, or both?

None of these decisions blocks the discovery slice. They do block finalizing the
recommendation truth table or durable mail schema.
