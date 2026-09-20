# Supplier and SaaS risk assessments

SecMan models a supplier as a normal asset with `type = SUPPLIER`. The asset's
`name` is the legal or trading name, `uri` is its canonical public HTTPS site,
`owner` is the internal supplier owner, and `description` can carry a short
service and data-scope summary. This deliberately reuses asset ownership,
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
4. An ADMIN or the assessment's SECCHAMPION assessor/requestor may call
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
