# Paperclip risk-assessment automation

This blueprint lets a Paperclip company operate SecMan risk assessments whose
questionnaires are defined by explicitly selected use cases. SecMan remains the
system of record; Paperclip supplies the company structure, agent duties,
issues, routines, heartbeats, and audit trail.

Paperclip supports companies with scoped agents, scheduled routines, injected
secrets, MCP servers, and heartbeat-driven work. Configure one SecMan MCP server
for the company and store its API key as a company secret. Every call must send
the verified key plus `X-MCP-User-Email` for the SecMan user the agent is acting
as. Never put the key, passwords, or assessment tokens in an issue or prompt.

## Authorization setup

Use a dedicated enabled SecMan service account for automation. ADMIN must bind
its MCP key to each intended delegate's stable user ID; a matching email domain
alone does not authorize impersonation. Ordinary respondents need USER plus an
explicit assessment assignment, not general asset visibility. Use
`manage_assessment_assignment` to assign scoped requirement IDs, revoke tasks,
or reopen answers (ADMIN/SECCHAMPION only).

Submission freezes a respondent's assigned answers. `evaluate_risk_assessment`
is advisory. Final acceptance is a separate authenticated human action against
the submitted revision, and is unavailable to MCP, AI, and service accounts.
Anyone who contributed answers, evidence, or saved AI content is ineligible to
accept, including both the automation initiator and its effective delegate.

On authorization failure, stop and obtain a reviewed assignment or key binding;
do not retry under a broader identity. Reopening invalidates prior acceptance
and email links. See [the shared authorization model](AUTHORIZATION_REWORK.md)
for migration, export revocation, and scheduler identity configuration.

## Recommended company

| Agent | Delegated SecMan identity | MCP permissions | Responsibility |
|---|---|---|---|
| Assessment coordinator | ADMIN or SECCHAMPION | `ASSESSMENTS_READ`, `ASSESSMENTS_WRITE`, `REQUIREMENTS_READ`, `NOTIFICATIONS_SEND` | Select use cases, create assessments, run approved AI research, monitor progress, notify respondents |
| Respondent agent | Assigned respondent | `ASSESSMENTS_READ`, `ASSESSMENTS_EXECUTE` | Read the questionnaire, save supported answers, submit when complete |
| Assessor agent | Assigned assessor | `ASSESSMENTS_READ` | Read completed answers and evaluate the result |

Human respondents can replace the respondent agent without changing the
coordinator or assessor flow. Keep `ASSESSMENTS_EXECUTE` away from the
coordinator unless it is intentionally also assigned as respondent.

## Create from selected use cases

The coordinator calls `create_risk_assessment` with one to 50 unique use-case
IDs. Requirements from all selected use cases form the questionnaire union:

```json
{
  "awsAccountId":"123456789012",
  "useCaseIds":[42,57],
  "assessorEmail":"assessor@example.org",
  "respondentEmail":"owner@example.org",
  "endDate":"2026-10-01",
  "notes":"Paperclip issue RA-104"
}
```

Persist the returned SecMan assessment ID in the Paperclip issue. Do not infer
success from HTTP 200 alone: a JSON-RPC `error` or `result.isError:true` is a
failed tool call.

For a SaaS or general supplier, first call `create_asset` with
`type:"SUPPLIER"` and its public HTTPS `uri`, then replace `awsAccountId` in
the request above with the returned `assetId`. See
[Supplier and SaaS risk assessments](SUPPLIER_RISK_ASSESSMENT.md).

## Optional OpenRouter research

Where the organization has enabled and approved AI processing, the coordinator
may call `start_ai_risk_assessment` with the assessment ID. Poll
`get_ai_risk_assessment_job` with both assessment and job IDs until it reaches a
terminal state. The online model's answers remain drafts: Paperclip must route
them to the respondent for review and submission, then to the assessor for the
existing deterministic evaluation. Never treat job completion as security
acceptance.

## Coordinator heartbeat

Create a scheduled Paperclip routine for the coordinator. The routine should:

1. Call `get_risk_assessment_statistics`, with the exact `useCaseName` when the
   project is use-case-specific. If its `byStatus.STARTED` is zero, stop without
   retrieving individual assessments.
2. Call `list_risk_assessments` with `status:"STARTED"` and, when a project is
   use-case-specific, its exact `useCaseName`.
3. For every returned assessment, call
   `get_risk_assessment_questionnaire`. Read `requirementCount`,
   `answeredCount`, and `isComplete` from SecMan instead of maintaining a
   second progress counter in Paperclip.
4. If `isComplete` is false and the Paperclip issue's reminder policy says a
   reminder is due, call `notify_risk_assessment_respondent` first with
   `dryRun:true`.
5. Send with `dryRun:false` only when the preview still reports
   `unansweredCount > 0`. Record the returned `reason` in the issue.
6. If the questionnaire is complete, do not notify. Ask the respondent agent to
   call `submit_risk_assessment` instead.

Example notification call:

```json
{"assessmentId":9001,"dryRun":false}
```

SecMan recomputes outstanding answers for the selected active respondent
assignment. Only an assigned assessor, ADMIN, or SECCHAMPION may notify;
requestor metadata grants nothing. Supply `respondentEmail` when multiple
sections are open. Registered respondents receive an authenticated deep link;
accountless respondents receive a capability bound to their assignment and
version. A durable atomic cooldown permits one reminder per assignment in
24 hours. Failed deliveries release the claim, and access is rechecked inside
asynchronous delivery and retries.

Paperclip may keep its preferred reminder cadence in issue or routine state,
while SecMan remains authoritative for outstanding answers and enforces the
24-hour delivery floor even if Paperclip state is lost or concurrent routines run.

## Respondent and assessor heartbeats

The respondent agent uses a key explicitly bound to the assigned respondent and sends that identity in `X-MCP-User-Email`,
calls `get_risk_assessment_questionnaire`, and saves only evidence-backed
answers with `save_risk_assessment_answers`. It calls
`submit_risk_assessment` only when SecMan reports every requirement answered.

After status becomes `COMPLETED`, the assessor agent calls
`get_risk_assessment_answers` and `evaluate_risk_assessment`. Preserve the
returned verdict, answer counts, and finding requirement IDs in the Paperclip
issue, but keep sensitive comments in SecMan and link to the assessment.

## Failure and retry rules

- Retry transport failures with bounded backoff.
- Do not retry `FORBIDDEN`, `NOT_FOUND`, `VALIDATION_ERROR`, or `CONFLICT`
  unchanged; route them to the coordinator.
- `EMAIL_DELIVERY_FAILED` means no successful delivery was observed and may be
  retried according to the issue's reminder policy.
- `NO_OUTSTANDING_ANSWERS` is a successful no-op.
- Never close the Paperclip issue until SecMan reports `COMPLETED` and the
  independent human acceptance succeeds. MCP evaluation does not create an acceptance decision.

## MCP JSON-RPC envelope

```json
{
  "jsonrpc":"2.0",
  "id":"paperclip-ra-104-reminder",
  "method":"tools/call",
  "params":{
    "name":"notify_risk_assessment_respondent",
    "arguments":{"assessmentId":9001,"dryRun":false}
  }
}
```

See [MCP risk-assessment lifecycle](MCP_RISK_ASSESSMENT_LIFECYCLE.md) for all
tool schemas, [MCP statistics for automation](MCP_STATISTICS.md) for aggregate
heartbeat calls, and [Paperclip security-work management](PAPERCLIP.md) for the
broader SecMan/Paperclip operating model. Paperclip's current architecture and
heartbeat/routine model are described in its
[official repository](https://github.com/paperclipai/paperclip).
