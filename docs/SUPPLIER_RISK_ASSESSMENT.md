# Supplier and SaaS risk assessments

SecMan models a supplier as a normal asset with `type = SUPPLIER`. The asset's
`name` is the legal or trading name, `uri` is its canonical public HTTPS site,
`owner` is the internal supplier owner, and `description` can carry a short
service and data-scope summary. Ownership remains descriptive metadata; access uses workgroup grants. This reuses asset inventory,
workgroups and risk-assessment access controls instead of introducing a second
supplier authorization model.

## End-to-end workflow

1. Create the supplier with the REST asset form or MCP `create_asset`:

   ```json
   {
     "name": "Example Cloud Ltd",
     "type": "SUPPLIER",
     "owner": "procurement-owner",
     "uri": "https://example-cloud.test",
     "description": "SaaS processor for customer contact data"
   }
   ```

2. Create an asset-based questionnaire with `create_risk_assessment`, passing
   the returned `assetId` (not `awsAccountId`) and the SaaS/supplier use-case
   IDs. Exactly one basis is accepted. The delegated caller must be able to
   access the asset.
3. The assigned respondent supplies contractual and non-public facts. Use
   `notify_risk_assessment_respondent` for bounded, audited reminders.
4. An ADMIN or SECCHAMPION may call
   `start_ai_risk_assessment`. It starts the same cost-capped OpenRouter job as
   the Web UI. With an `:online` model, the model researches public evidence
   and stores citations and draft answers; it does **not** submit or approve the
   assessment.
5. Poll `get_ai_risk_assessment_job`. After `COMPLETED`, inspect drafts and
   citations in SecMan or through `get_risk_assessment_answers`. A human or
   explicitly delegated respondent corrects unsupported answers and calls
   `submit_risk_assessment`.
6. The assessor calls `evaluate_risk_assessment`. The deterministic result
   highlights every non-YES answer; the assessor, not the LLM, owns acceptance.

## Supply-chain evidence checklist

Online evidence is useful but incomplete. Configure the supplier/SaaS use case
to cover at least: ownership and jurisdiction; sub-processors and data
locations; SOC 2/ISO 27001 scope and currency; encryption and key ownership;
identity, MFA and privileged access; vulnerability disclosure and breach
history; secure development and dependency controls; tenant isolation;
backup, resilience, RTO/RPO and exit/portability; incident notification;
contractual audit rights; sanctions and concentration risk; and end-of-service
data deletion.

Prefer the supplier's security/trust centre, certification registries,
regulators and advisories over marketing pages. Treat absent, stale,
contradictory or scope-mismatched evidence as `UNKNOWN`/a finding rather than a
positive answer. Citations are evidence pointers, not proof that a control is
implemented for the contracted service.

## Security and operating boundaries

- OpenRouter is outbound processing. Enable it only after the organization has
  approved the provider/model and the data sent to it. SecMan redacts direct
  owner identifiers from AI context, but questionnaire comments may still be
  sensitive and must be reviewed before use.
- Public web research cannot validate private contracts, penetration tests,
  architecture or control operation. Keep a human acceptance gate.
- `OPENROUTER_API_KEY` remains server-side; Paperclip receives only the SecMan
  MCP key and must send `X-MCP-User-Email` for every delegated call.
- The model, prompt version, token usage, cost, citations and whether online
  search was used remain in the AI suggestion audit trail.

See [AI-assisted answers](AI_RISK_ASSESSMENT.md), [MCP](MCP.md), and
[Paperclip automation](PAPERCLIP_RISK_ASSESSMENT_AUTOMATION.md).

## Assignment and acceptance boundary

Supplier and SaaS assessments use the [shared authorization workflow](AUTHORIZATION_REWORK.md).
A respondent assignment grants only its questionnaire section and minimal task
context. It does not expose the supplier asset inventory. Final acceptance
requires an independent authenticated human who never contributed to the
assessment. Historical assessments with incomplete authorship require a new
assessment before final acceptance.

## External supplier collection foundation

Main SecMan can prepare an invitation for an unused asset-based supplier
assessment with no active respondent. An ADMIN or SECCHAMPION creates it with
`POST /api/risk-assessments/{id}/supplier-invitation`. Supply the recipient
email, requester label, service scope, UTC deadline, and one approved public
question text per scoped requirement. SecMan pins the assessment's selected
release, or the active release when none has been pinned. Only approved
question text and opaque question keys appear in the questionnaire projection.
The release stays protected from deletion while a supplier invitation references it.

Use `GET` at the same path to read the latest invitation and
`DELETE /api/risk-assessments/{id}/supplier-invitation/{invitationId}` to revoke
it before import. A replacement gets a new ID and generation. Once supplier
collection is selected, the assessment uses that channel for its answers.
Corrections after import require a new assessment until a separate revision
workflow is available.

The internal import service binds the submission to the invitation main SecMan
selected. It records supplier provenance and completes the questionnaire in the
same transaction as the import receipt. Replays of the same submission preserve
the existing result. Conflicting submissions preserve the imported answers,
and submissions first observed at or after the deadline are held for review.
Final acceptance follows the existing independent human review rules.

## Deliver an invitation

Configure and start the [standalone supplier portal](../src/supplier-portal/README.md).
An ADMIN or SECCHAMPION queues a prepared invitation using
`POST /api/risk-assessments/{id}/supplier-invitation/{invitationId}/delivery`.
SecMan first provisions the questionnaire over mutual TLS. It then sends the
link through the configured SMTP provider using verified TLS. The link expires
after 14 days or at the assessment deadline, whichever comes first.

Read `deliveryState` and `linkExpiresAt` from the latest invitation. `SENT`
means the SMTP provider accepted the email. If delivery is `UNCERTAIN`, check
with the contact before revoking and issuing a new invitation. The outbox
retries provisioning with the same verifier. It does not automatically resend
an email when SMTP acceptance is uncertain.

To resend, revoke the existing invitation, wait for `REVOKED`, create the next
generation, and queue it. The portal carries forward a draft only for the same
contact and exactly the same questionnaire. For a replacement contact, it starts
a new draft. An assessment with a live delivery can be deleted after portal
revocation or link expiry.

## Retrieve and review a submission

Main SecMan starts checking after the portal acknowledges provisioning.
The worker looks up each invitation over the existing mutual-TLS connection.
It records the first time it sees a validated submission. If an import is
interrupted, the same payload can use that observation time on retry. Responses
first observed at or after the assessment deadline, or claiming completion
outside the invitation's usable time, are held for review.

Open **Check Answers** on the assessment to see the supplier collection panel.
Assigned reviewers and assessment managers see email delivery, the latest
portal check, the first observation time, and the imported revision. An
ADMIN or SECCHAMPION can use **Check for submission** to queue another check.
Use **Load imported answers** to refresh a review already open when the import
finished. The supplier sees their portal receipt.

Ordinary pending submissions are checked again after a minute. Portal
outages, missing invitations, and transient import failures retry after five
minutes. Automatic checks stop seven days after the link expires. Invalid,
held, or conflicting responses require operator attention. If the portal
returns conflicting answers for an invitation, SecMan preserves the existing
record, flags the conflict, and blocks new acceptance of that revision. Use a
trusted follow-up channel and a new assessment for corrected answers.

See the [deployment and recovery runbook](SUPPLIER_PORTAL_OPERATIONS.md) for
network isolation, pilot verification, intake pause, and incident holds.
