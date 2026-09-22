# Shared authorization model (Option 2)

## Asset and workgroup policy

ADMIN and SECCHAMPION can see all assets and manage workgroups, memberships,
explicit asset/account/domain grants, sharing, and asset deletion. Only ADMIN
manages users, canonical workgroup owners, and personal mappings. Ordinary
users can edit ordinary fields of accessible assets; account, domain, and
workgroup changes are privileged. Ordinary asset creation requires initial
placement in an enabled workgroup they directly belong to. Spreadsheet imports
use the same placement and account/domain checks while retaining the VULN import
action. Invalid rows are skipped and reported without creating assets or grants.

Asset visibility is the union of enabled direct workgroup asset links, whole
AWS-account links, AD-domain links, personal mappings, and directional received
AWS sharing. Account and domain grants include assets discovered later.
Ownership, creator, and uploader are metadata. Workgroup membership does not
expose fellow members' assets. Hierarchy does not propagate membership or
access. Raw scans require visibility of every linked asset. Whole-account
assessment access cannot be inferred from one accessible asset.

## Assessment workflow

Assignments are stable records independent of usernames. Respondents have
only their assigned questions and minimal task context. Assessors review all
questions. An assignment never grants asset/account browsing or editing.
Requestor status is not a grant. USER-only participants can read and answer
assigned tasks through REST and MCP.

- `GET /api/risk-assessments/{id}/workflow/assignments`: manager assignment view.
- `POST .../workflow/assignments`: ADMIN/SECCHAMPION assigns an existing `userId`
  or an accountless respondent `email`, `role` (`RESPONDENT` or `ASSESSOR`),
  and `requirementIds`. An empty requirement set means the whole questionnaire.
- `DELETE .../workflow/assignments/{assignmentId}`: revoke assignment and its links.
- `POST .../workflow/submit`: respondent submits their assigned section. The
  assessment becomes COMPLETED when all active respondent assignments are
  submitted and all questionnaire answers exist.
- `POST .../workflow/reopen`: manager reopens answers, invalidates existing
  acceptance, and invalidates old capability links.
- `POST .../workflow/accept`: authenticated independent human submits
  `answerRevision` and `rationale`. Submission and acceptance are separate.

The existing response endpoints use the same transactional workflow service.
Mixed-scope answer batches are rejected before any answer is saved. Pessimistic
assessment-row locking serializes answer changes, submission, reassignment,
reopening, and acceptance. Questionnaire use cases cannot be changed through
the old generic update endpoint. That endpoint also rejects direct status and
respondent changes; clients must use workflow actions.

Human, evidence-file, delegated, and saved AI contributions retain stable actor
IDs, assignment IDs, API-key identity where applicable, and revisions. AI contribution records include the initiating key owner and
effective delegate. A previous contributor cannot accept, even after rename,
reassignment, or promotion to ADMIN/SECCHAMPION. Marked service accounts cannot
accept. Evaluation-only automation does not write an acceptance decision.
AI does not overwrite manually entered or human-edited answers. A submitted
section also blocks overlapping human assignments, evidence changes, and AI
writes while other sections remain open. Revoking an assignment does not
unfreeze its submitted answers; explicit reopening is required.

Email links require an active respondent assignment and its current version.
Create the accountless assignment before generating a link using the existing
token endpoint. Links expose assigned questions, not full asset/user entities.
Submission closes writes. Links tied to suspended users are rejected. Reassignment, revocation, and reopening invalidate
old links. Unbound historical links must be reissued after assignment review.

## Automation and revocation

MCP keys explicitly bind allowed delegate IDs; the domain allowlist remains an
additional check. Existing keys permit only their owner unless rebound. Key
permissions intersect current delegate roles. `manage_assessment_assignment`
provides LIST/ASSIGN/REVOKE/REOPEN actions to managers in both permission tables.
There is deliberately no MCP final-acceptance action.

AI jobs bind assignment version, effective user, and (for MCP) key/initiator.
They recheck current authorization before research and before saving results.
Live events check the current viewer. Export jobs bind stable user identity and
a SHA-256 fingerprint of authorized asset IDs. Changed scope, removed users,
and disabled users invalidate processing and download. This is conservative:
a changed scope can require a new export even when access grew.

ADMIN sets `enabled` and `serviceAccount` with
`PUT /api/users/{id}/access-state`. Scheduled assessment reminders require an
explicit enabled service account with review authority, configured using
`SECMAN_ASSESSMENT_REMINDER_SERVICE_USER_ID` as described below. Recipient
assignments are checked before notification and again inside asynchronous
delivery and retries. Already delivered mail or downloaded bytes cannot be
recalled.

`notify_risk_assessment_respondent` accepts `respondentEmail` when multiple
sections are open. It counts unanswered questions only in that assignment and
uses a separate 24-hour cooldown per assignment. REST `/{id}/notify` and
`/{id}/remind` use the same delivery service and return `sent` and `reason`;
they no longer report a send for a placeholder operation. Accountless recipients
receive `/respond/{token}` links. A failed or revoked delivery releases its
cooldown claim. Reopening clears assignment cooldowns.

The assessment UI accepts existing participant emails for SECCHAMPION without
requiring access to the ADMIN user directory. Assignment by a registered email
binds the existing stable user ID; an unknown respondent email remains an
accountless assignment. USER-only participants can open My assessments.

Configuration property: `secman.assessment.reminder-service-user-id` (default
`0`, which denies scheduled reminders). Set it through Micronaut configuration,
for example `SECMAN_ASSESSMENT_REMINDER_SERVICE_USER_ID`. Do not use a synthetic
all-powerful system identity.

## AD synchronization and provenance

`sync-workgroup-assets` retains its command name for compatibility but now
reconciles whole-account grants, using server-side canonical owner mappings.
It previews `GET /api/workgroups/{id}/aws-accounts/owner-sync`, then applies
`POST` to the same path with `expectedAccounts`. Changed mappings reject apply.
Only ADMIN may run owner synchronization.

`manualGrant` and `ownerSyncGrant` distinguish independent sources. Removing
obsolete synchronized grants preserves manual account grants and all explicit
asset links. Existing account grants migrate as manual because their provenance
cannot safely be inferred. Existing owner-derived asset links also remain
explicit until an administrator reviews their removal. Never erase them
merely because an owner mapping changed.

## Migration and rollout

Additive migrations V272–V277 introduce delegate bindings, assignment/contribution/
acceptance history, job authorization context, grant provenance, export scope,
and user access state. Existing assessment answers and status remain; historical
authorship is explicitly incomplete. Final acceptance requires a new assessment
with independently collected answers; copying legacy content must not be used
to bypass contributor history. No automatic replacement grants are created.

ADMIN can read the bounded metadata-grant impact preview at
`GET /api/admin/authorization-impact/{userId}?afterId=0&limit=200`. Follow
`nextAfterId` while `hasMore` is true. The response identifies owner/creator/
uploader grants lost under the new asset policy and performs no changes.
This report covers metadata-grant removal; review hierarchy, service identity,
MCP key bindings, and legacy link changes separately.

Before rollout:

1. Rehearse migrations on a disposable copy and inspect legacy assignment/history
   counts. Retain a backup and review access impact before changing live grants.
2. Bind automation delegates explicitly, assign service accounts, and update
   clients that used creator access or generic assessment status updates.
3. Review legacy links and reissue only scoped respondent links.
4. Run the full authorization tests, query/concurrency tests, browser workflows,
   and security review against the final patch.
5. Apply the reviewed migration/replacement plan during deployment. Development
   startup must not silently apply this change to an unreviewed shared database.

The implementation has not been deployed and no migration or replacement grant
has been applied to the development database. Connection-local legacy migration fixtures verify SQL backfills and fail-closed
defaults; this does not replace rehearsal on a representative database copy. See the accompanying verification report for
the actual checks completed and any remaining test-environment limitations.
