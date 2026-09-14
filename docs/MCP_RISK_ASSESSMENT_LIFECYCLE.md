# MCP risk-assessment lifecycle

SecMan exposes the complete authenticated risk-assessment lifecycle through MCP:
creation, discovery, questionnaire retrieval, answering, submission, and evaluation.
The lifecycle is designed for agent clients such as PaperclipAI and always uses
verified user delegation.

## Transport and identity

Send JSON-RPC 2.0 requests to `POST /mcp` with both headers:

```http
X-MCP-API-Key: <key from the SecMan MCP admin page>
X-MCP-User-Email: <the human identity on whose behalf the agent acts>
```

`X-MCP-User-Email` is an identity, not a credential. SecMan verifies the API key,
resolves the delegated user, and intersects the key permissions with that user's
role permissions. PaperclipAI must change the delegation header when it changes
from administrator, to respondent, to evaluator.

The key needs the following permissions for the holistic workflow:

| Activity | API-key permission | Delegated roles |
|---|---|---|
| Create user / import account mapping | `USER_ACTIVITY` | `ADMIN` |
| Create use case / linked requirement | `REQUIREMENTS_WRITE` | `ADMIN` or `REQ` |
| Start assessment for an AWS account | `ASSESSMENTS_WRITE` | `ADMIN` or `SECCHAMPION` |
| Notify about outstanding answers | `NOTIFICATIONS_SEND` | Assessor, requestor, `ADMIN`, or `SECCHAMPION` |
| List / view / evaluate | `ASSESSMENTS_READ` | `ADMIN`, `RISK`, or `SECCHAMPION` |
| Save / submit answers | `ASSESSMENTS_EXECUTE` | `ADMIN` or `RISK`; the delegated user must also be the assigned respondent |

The manually created respondent should therefore have roles `USER` and `RISK`.
Its email domain must also be included in the MCP API key's delegation-domain
allowlist; otherwise calls delegated as that user fail before tool execution.

## SecMan UI and REST

The Risk Assessments page offers **AWS Account (Direct Assessment)** as a basis.
Enter the account number as exactly 12 digits, select the assessor, respondent,
deadline and use case, then submit. The backend creates the account reference row
when it does not exist; it does not create an asset.

The equivalent authenticated REST request is:

```http
POST /api/risk-assessments
Content-Type: application/json

{
  "awsAccountId":"123456789012",
  "assessorRef":{"email":"champion@example.org"},
  "respondentRef":{"email":"owner@example.org"},
  "endDate":"2026-10-01",
  "useCaseIds":[42],
  "notes":"Account onboarding review"
}
```

Exactly one of `demandId`, `assetId`, or `awsAccountId` is accepted. AWS-account
creation requires ADMIN or SECCHAMPION. Retrieve assessments for one account
with `GET /api/risk-assessments/aws-account/123456789012`; RISK users see an
account assessment only when they are its assessor, requestor, or respondent.
ADMIN and SECCHAMPION retain universal assessment visibility.

## Tool contract

### Setup and start

`create_use_case`

```json
{"name":"Cloud workload"}
```

Returns `id` and `name`. Names are unique without regard to case.

`add_requirement` accepts the existing requirement fields plus `useCaseIds`:

```json
{
  "shortreq":"Data at rest must be encrypted",
  "details":"Use a managed encryption mechanism.",
  "chapter":"Data protection",
  "useCaseIds":[42]
}
```

Every supplied use-case id must exist. The operation is atomic: an invalid id
does not create an unlinked requirement.

Create the AWS account mapping with `import_user_mappings`. For a direct,
reversible assessment fixture, do not request auto-start on the mapping import:

```json
{
  "mappings":[{"email":"owner@example.org","awsAccountId":"123456789012"}],
  "startRiskAssessment":false
}
```

Start the assessment with `create_risk_assessment`:

```json
{
  "awsAccountId":"123456789012",
  "useCaseIds":[42,57],
  "assessorEmail":"champion@example.org",
  "respondentEmail":"owner@example.org",
  "endDate":"2026-10-01",
  "notes":"Paperclip change request 4711"
}
```

The AWS account id must contain exactly 12 digits. `useCaseIds` accepts 1–50
unique IDs; requirements from all selected use cases are combined and
de-duplicated. The deprecated singular `useCaseId` remains accepted for older
clients, but the two forms cannot be combined. SecMan resolves or creates its
`aws_account` reference row and stores `AWS_ACCOUNT` as the assessment basis; it
does not create an asset. The users and use case must exist, the deadline cannot
be in the past, and the use case must contain at least one requirement. The
delegated creator becomes the requestor.

### Notify the respondent about outstanding answers

Delegate as the assessor, requestor, an ADMIN, or a SECCHAMPION and preview:

```json
{"assessmentId":9001,"dryRun":true}
```

The result includes `requirementCount`, `unansweredCount`, `respondent`, and a
machine-readable `reason`. Repeat with `dryRun:false` only when answers remain.
Successful delivery returns `sent:true` and `reason:"SENT"`; a questionnaire
with no missing answers returns `sent:false` and
`reason:"NO_OUTSTANDING_ANSWERS"`. Completed assessments are rejected. The
email links to the authenticated SecMan UI and contains no capability token.

## Operator provisioning script

`scripts/mcp-create-aws-risk-assessment.sh` provides the setup portion as one
reusable command. It creates the user, use case, linked requirement, AWS account
mapping and account-native open risk assessment through MCP. The password is always
prompted twice without terminal echo; there is deliberately no `--password`
argument, so it cannot appear in shell history or the process list.

The script invokes `pass-cli run --env-file ./secmanpp.env` itself when the MCP
environment is not already resolved. The Proton Pass environment must provide
`SECMAN_BACKEND_URL`, `SECMAN_MCP_KEY`, and `SECMAN_ADMIN_EMAIL`. The key needs
`USER_ACTIVITY`, `REQUIREMENTS_WRITE`, `ASSESSMENTS_WRITE`, `NOTIFICATIONS_SEND`, and
`ASSESSMENTS_READ`, and its delegation policy must allow the administrator's
email domain. To later answer through MCP as the created respondent, it must also
grant `ASSESSMENTS_EXECUTE` and allow the new user's email domain.

Minimal interactive example:

```bash
./scripts/mcp-create-aws-risk-assessment.sh
```

The script prompts for the email, AWS account id, password, and password
confirmation. The username defaults to the part of the email before `@`; names
default to account-specific values and the deadline defaults to seven days.

Provide the non-secret values explicitly for a repeatable operator run:

```bash
./scripts/mcp-create-aws-risk-assessment.sh \
  --email owner@example.org \
  --username aws-owner-4711 \
  --aws-account-id 123456789012 \
  --use-case-name "Internet-facing production account" \
  --requirement-text "Public workloads must use an approved ingress path" \
  --deadline-days 14
```

If Proton Pass has already populated the current environment, the same command
uses those resolved values and does not start a nested `pass-cli` process:

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/mcp-create-aws-risk-assessment.sh \
  --email owner@example.org \
  --aws-account-id 123456789012
```

On success, stdout is one JSON document containing every created id and the
assessment's verified `STARTED` status. Progress and errors go to stderr; neither
output contains the password. Treat a non-zero exit as a partial-provisioning
failure: successful MCP calls are committed individually and are not deleted by
the script.

This command intentionally starts a direct assessment after importing the
mapping with `startRiskAssessment:false`. It does not activate a new requirements
release, because activation would archive the globally ACTIVE release. The
direct assessment uses the new use case's live requirement. Use
`import_user_mappings` with `startRiskAssessment:true` only when the intended use
case is already present in the ACTIVE release; that release-pinned workflow is
documented in `docs/AWS_ACCOUNT_RISK_ASSESSMENT.md`.

The offline shell contract test verifies the MCP call order, account validation,
result JSON, automatic open-assessment check, and that the entered password is
absent from stdout and stderr:

```bash
./scripts/test/test-mcp-create-aws-risk-assessment-script.sh
```

### Discover assessments

`list_risk_assessments` is database-paged and returns at most 100 rows per page.
All filters are optional.

To list open assessments for one use case:

```json
{"status":"STARTED","useCaseName":"Cloud workload","page":0,"pageSize":20}
```

To list completed assessments for later evaluation:

```json
{"status":"COMPLETED","useCaseName":"Cloud workload"}
```

The result contains `assessments`, `page`, `pageSize`, `totalPages`, and
`totalElements`. ADMIN and SECCHAMPION can list all matching assessments. Other
callers see only assessments where they are assessor, requestor, or respondent.

### Read and answer the questionnaire

Call `get_risk_assessment_questionnaire`:

```json
{"assessmentId":9001}
```

The response contains assessment metadata, the exact requirement list, each
saved response, `answeredCount`, `requirementCount`, and `isComplete`. A pinned
assessment uses its frozen release snapshots; a direct assessment uses the live
requirements linked to its use case.

To retrieve only the saved answers for a dedicated assessment, call
`get_risk_assessment_answers`:

```json
{"assessmentId":9001}
```

The result contains `assessment`, `answers`, `answeredCount`, and
`requirementCount`. Each answer includes `requirementId`, `internalId`,
`shortreq`, `answerType`, `comment`, `source`, `respondentEmail`, and `updatedAt`.
The same visibility boundary as questionnaire retrieval applies; an inaccessible
id is returned as not found.

Save one or more answers with `save_risk_assessment_answers`, delegated as the
assigned respondent:

```json
{
  "assessmentId":9001,
  "answers":[
    {
      "requirementId":301,
      "answerType":"NO",
      "comment":"Encryption rollout is scheduled for Q4."
    }
  ]
}
```

`answerType` is `YES`, `NO`, or `N_A`. A batch has at most 200 unique requirement
ids and comments are capped at 4000 characters. Every id must belong to this
assessment's questionnaire. The batch is transactional; one invalid row rejects
the whole batch.

Complete it with `submit_risk_assessment`:

```json
{"assessmentId":9001}
```

Submission succeeds only for the assigned respondent, while status is `STARTED`,
and after every questionnaire requirement has an answer. It changes the status
to `COMPLETED`; later answer changes are rejected.

### Evaluate a completed questionnaire

Delegate as the assigned assessor or requestor, an ADMIN, or a SECCHAMPION and
call `evaluate_risk_assessment`:

```json
{"assessmentId":9001}
```

The deterministic result contains:

- `verdict`: `COMPLIANT` when there is no `NO`, otherwise `NON_COMPLIANT`;
- `answerCounts`: counts for `YES`, `NO`, and `N_A`;
- `requirementCount`;
- `findings`: every `NO` or `N_A` answer with requirement identity and comment;
- `assessment`: the assessment metadata used for the evaluation.

This operation evaluates the submitted questionnaire without mutating it. Risk
creation remains an explicit, separate business decision.

## JSON-RPC envelope for PaperclipAI

Every argument object above is placed in the same envelope:

```json
{
  "jsonrpc":"2.0",
  "id":"paperclip-4711-step-4",
  "method":"tools/call",
  "params":{
    "name":"list_risk_assessments",
    "arguments":{"status":"STARTED","useCaseName":"Cloud workload"}
  }
}
```

Treat either a top-level JSON-RPC `error` or `result.isError: true` as a failed
tool call even when HTTP returned 200. The streamable `/mcp` transport currently
maps tool failures to JSON-RPC internal-error code `-32603` while preserving the
safe tool message; clients should branch on success versus failure, not on that
numeric code. Do not retry an authorization or validation message unchanged. An
inaccessible assessment deliberately reports “Risk assessment not found” so
callers cannot enumerate ids.

## Holistic E2E test and cleanup

The driver is `scripts/test/test-e2e-mcp-risk-assessment-lifecycle.sh`. It takes
an unused mailbox explicitly:

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-mcp-risk-assessment-lifecycle.sh \
  --user-email owner-test@company.example --verbose
```

It creates two users, one use case, exactly one linked requirement, one AWS
account mapping, and one account-native assessment. It then proves respondent-scoped
listing, exact questionnaire scope, a non-respondent denial, answer saving,
submission, evaluator access, the derived finding, and completed-by-use-case
listing. Cleanup runs before and after.

Use `--keep-data` only when the fixture must remain available for manual
inspection. Remove it later with:

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-mcp-risk-assessment-lifecycle.sh --cleanup-only
```

Cleanup identifies the assessment by an exact notes marker, derives the exact
account number from that assessment before deleting it, and removes the account
row only after it is nameless and unreferenced. All other rows use exact fixture
names. It never uses a broad substring and is safe to repeat.
