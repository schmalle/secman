# Risk assessments and AWS owner onboarding

## SaaS and COTS assessments

Open **Risk Management → Risk Assessment → Add New Risk Assessment**.
Choose **SaaS** or **COTS** as the assessment basis and enter the solution name
(required, maximum 255 characters). This creates an assessment of the named
solution without creating an asset, demand, or AWS account. Choose the applicable
use cases to define the questionnaire and set the deadline.

The assessor must be an existing enabled SecMan user. Search by username or email,
an exact unique username or email selects that user automatically. Partial searches
narrow the dropdown so you can select the intended match.

For the respondent, select an existing user using the same search, or select the
external-email option and enter a valid single email address. The external person
does **not** need a SecMan login, and creating the assessment does not register a
user. Their explicit respondent assignment is scoped to the assessment. Send the
questionnaire using the assessment's notification action; the recipient uses the
existing expiring token link to answer and submit. Revoking their assignment also
revokes its access. Merely knowing the email address does not grant access.

The REST create request for a solution uses `assessmentBasisType: "SAAS"` or
`"COTS"` and `solutionName`, instead of a demand/asset/AWS account identifier.
`assessorRef` accepts an existing user's `id` or `email`; `respondentRef` accepts
an existing user's `id` or an email address that need not exist in SecMan.

## Configure new AWS account notifications

Only ADMIN and SECCHAMPION users can access Account Onboarding.

Open **Admin → Users & Access → Account Onboarding**. An ADMIN can save:

- **Welcome email**: the default; sends the configured subject and HTML body to
  each new account owner. It requires an active email provider, but no requirements
  release, questions, or assessment rules.
- **Risk assessment**: starts a direct assessment using the configured use case
  and deadline. It requires an ACTIVE release with matching requirements and an
  available SECCHAMPION assessor. The owner receives the assessment invitation.

The welcome content uses the existing requirements HTML editor and is sanitized
on the server. The saved policy is used by both file and S3 imports:

```bash
./scripts/secman manage-user-mappings import --file mappings.csv --createnotify
./scripts/secman manage-user-mappings import-s3 --createnotify
```

The S3 command uses the configured bucket and key. `--notify-new-accounts` is an
alias for `--createnotify`. Add `--notify-address ops@example.com` for an additional
operator summary. With neither notification flag nor an explicit onboarding mode,
an import does not send owner mail. Explicit legacy assessment/mode flags retain
precedence, so existing automation does not change modes unexpectedly.

New-account detection is database-wide and occurs before mapping inserts. Repeated
imports do not welcome an already-known account again. Mapping persistence commits
before notifications. Inspect the onboarding outcome and **Owner mail delivery**
audit; a successful import does not establish successful delivery. SMTP acceptance
is `SENT`, not a guarantee that a recipient's mailbox accepted the message.

## Test by supplying one email address

With the updated backend running and `pass-cli` signed in, run:

```bash
./scripts/test-account-onboarding.sh you@example.com
```

The script builds the current CLI without tests, creates a random simulated
12-digit account ID, and calls the existing simulation endpoint with
`useDefaultSettings: true`. Credentials and the backend URL come from the normal
Proton Pass configuration. It does not contact AWS or import mappings. It uses the
policy currently saved in the web UI and sends a real message to the supplied
address. No other script argument is needed.

In welcome mode, check that the message contains the saved subject/body and is
marked as a simulation. No assessment should be created. In assessment mode,
check the returned assessment ID and open the emailed questionnaire link without
a SecMan account; complete and submit the answers. Then inspect the assessment as
the assessor. Configure the mode in the UI before rerunning the same command to
exercise the other path. The script does not create a release or invent requirements.

Simulation retains its audit log and, in assessment mode, the assessment for
inspection. Simulated DIRECT assessments do not receive automatic deadline reminders.
Delete unwanted simulated assessments through normal administration;
the script does not delete records. This is a manual delivery rehearsal, not an
automatic E2E suite. No message is sent merely by installing or compiling it.

For a preview without delivery, use the CLI directly:

```bash
./scripts/secman manage-user-mappings simulate-onboarding \
  --aws-account-id 999999999999 --owner-email you@example.com \
  --use-default-settings --dry-run
```

## Deployment

Apply the normal Flyway migrations, including V280 (solution assessments and
external respondent email) and V281 (saved onboarding settings and simulation tracking), and rebuild the
backend, frontend, and CLI. Existing assessment bases and explicit onboarding modes
remain supported. No new dependency is required for these features.

Imports determine newness by AWS account ID across all existing user mappings, including pending users. Re-importing an account or adding another owner to an existing account does not trigger a welcome email or assessment. Only newly discovered account IDs are passed to onboarding.

Participant search displays clickable username/email matches directly beneath the search field. Type part of a name (for example, `schmall`), then select a match; the corresponding assessor or respondent dropdown updates. Loading, empty results, and search failures are shown next to the field.
