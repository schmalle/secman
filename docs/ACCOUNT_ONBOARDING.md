# AWS account onboarding

When a user-mapping import discovers an AWS account SecMan has never seen, the account's
owner is welcomed, and — optionally — a risk assessment is started for them. The scope of
that assessment can be named by the operator, or decided by the owner answering a short
questionnaire whose answers resolve through admin-configured rules.

Reachable from the CLI, MCP and REST, all three dry-runnable, plus a simulate verb that runs
the whole path against an account id and address you make up.

---

## 1. The three modes

| Mode | Welcome mail | Assessment | Who decides the scope |
|---|---|---|---|
| `WELCOME_ONLY` | yes | none | — |
| `DIRECT` | opt-in | immediately | the operator, via `--risk-usecase` |
| `GUIDED` | yes | on submit | **the account owner**, by answering |

`GUIDED` exists because `DIRECT` asks the wrong person. Risk assessments in SecMan are
initiated by one user for another, and the person running a mapping import rarely knows
whether the account they just discovered is production, holds customer data, or faces the
internet. The owner does.

### `--start-risk-assessment` still means exactly what it always meant

The flag predates modes and is what every existing client sends — the `extensions/` clients,
`import-s3`, both existing E2E drivers. On its own it resolves to `DIRECT` **with no welcome
mail**, which is byte-identical to the behaviour before this feature existed.

```kotlin
// domain/AccountOnboardingMode.kt — the single place the two are reconciled
fun resolve(explicit: AccountOnboardingMode?, startRiskAssessment: Boolean) =
    explicit ?: if (startRiskAssessment) DIRECT else null   // null == do nothing
```

The welcome mail defaults on only when a mode is named explicitly. That asymmetry is the
compatibility contract, and `/account-onboarding` has a phase whose only job is to assert it.

**The one incompatible combination is rejected, never guessed.** `--onboarding-mode
WELCOME_ONLY` or `GUIDED` together with `--start-risk-assessment` is a validation error on
every surface, because honouring either half would silently do something the operator did not
ask for.

---

## 2. Model

| Entity | Role |
|---|---|
| `AccountOnboardingQuestion` | One question put to the owner. `questionKey` is the stable identifier; labels are edited freely. |
| `AccountOnboardingChoice` | One selectable answer. `choiceKey` is unique *per question*, so two questions can both offer `yes`. |
| `AccountOnboardingRule` | A combination of choices → the use cases it resolves to. `isDefault` marks the single fallback. |
| `AccountOnboardingInvite` | The one-time link mailed to the owner. Carries the answers, the resolved rules, and a pointer to the assessment it produced. |

### Normalized, not a rule blob

| Option | Verdict | Why |
|---|---|---|
| Normalized tables + two join tables | **chosen** | The rule → `UseCase` link needs real referential integrity: a deleted use case must not leave a rule that silently resolves to nothing. "Which rules reference this choice" has to stay a query — it is what blocks a destructive delete. Matches the existing `requirement_usecase` shape. |
| `ruleJson TEXT`, as `DemandClassificationRule` does | rejected | That precedent exists because its condition is a recursive tree. This condition is a flat AND over choice ids — no recursion, no operators. JSON would cost app-level integrity and a re-parse per evaluation, and buy nothing. |

---

## 3. Matching

### The matcher only ever sees choice ids

A `BOOLEAN` question is a question with exactly two choices. There is no second code path for
it, no special-casing, and nothing that can drift between the two.

### Every matching rule contributes — nothing wins

A rule holds a set of required choices, possibly spanning several questions, and **matches iff
its choices are a subset of the submitted ones**. For `SINGLE_SELECT` questions that is exactly
"AND across questions"; for `MULTI_SELECT` it additionally allows a rule to be satisfied by one
of several answers ticked on the same question.

All matching active rules are unioned and deduplicated into **one** assessment.
`priorityOrder` exists only to give the admin UI a stable display order — it decides nothing.

### The default rule is a fallback, not a participant

Exactly one rule may be flagged `isDefault`, and it is the only rule allowed to name zero
choices. It is consulted **only after every other rule has failed**, so it can never dilute a
real match.

### An empty questionnaire is never created

After resolution, the same check `validateStartRequest` already performs runs again: if the
ACTIVE release has no requirements tagged with any resolved use case, the submission fails with
`EMPTY_QUESTIONNAIRE`. An owner is never handed a questionnaire with zero questions.

### Nothing matched is recorded, not lost

With no fallback rule, a submission that matches nothing gets `409 NO_RULE_MATCHED`. The
answers are **persisted**, the invite stays `PENDING`, and the owner is told a security
champion will follow up. An admin adds the missing rule and the owner's original link still
works. Spending the link on a submission the configuration could not honour would make the
owner redo the work for nothing.

---

## 4. The guided questionnaire

### Every token failure looks the same

Malformed, unknown, expired, already used, cancelled — all five return a byte-identical body:

```json
{"error":"NOT_FOUND","message":"This link is invalid or has expired."}
```

A response that distinguished them would turn the endpoint into an oracle for "does this token
exist". This is a deliberate departure from `ResponseController`'s `NOT_FOUND` /
`TOKEN_EXPIRED` split.

### The controls, and why each is there

| Concern | Control |
|---|---|
| Entropy | 32 `SecureRandom` bytes → 64 hex = **256 bits**. Not `UUID.randomUUID()` (122 bits, what `AssessmentToken` uses) — this token *creates* a risk assessment rather than opening one that already exists. |
| Single use | `AccountOnboardingInviteRepository.claim` — a guarded UPDATE, **claimed before the assessment is created**. Two concurrent submissions cannot both win. |
| Expiry | 14 days by default, range 1–90. Shorter than `AssessmentToken`'s 30, for the same reason as the entropy. |
| Minimum disclosure | The GET returns the account id **masked** to its last four digits, and never the owner email, the assessor, the release, or anything about another account. |
| Rate limiting | 20 GET / 5 POST per client per 10 min, plus a tighter bucket of 10 **failed** lookups per hour — the bucket an enumeration attempt lands in exclusively. |
| Bounded input | ≤ 50 questions, ≤ 50 choices per question, ≤ 200 selections. Unknown keys are refused, never ignored: silently dropping one would resolve a different combination than the owner answered. |
| Logging | `token.take(8) + "…"`, never the full value. A token in a log is a credential in a log. |
| CSRF | Not applicable — the request carries no ambient credential a browser would attach. The token must therefore never be read from a cookie, only from the path. |

`@Secured(SecurityRule.IS_ANONYMOUS)` is declared on the class **and repeated on every
method**. `ResponseController`'s token route omits the annotation entirely; that is a
pre-existing gap, not a pattern to copy.

### The order of the submission is load-bearing

1. Check the token's shape — a malformed value is refused without touching the database.
2. Load the invite, validate the answers.
3. Resolve the rules, and confirm the questionnaire would not be empty.
4. **Claim** the token.
5. Create the assessment, persist the answers and the resolved rules.
6. Send the "assessment started" mail — the same one `DIRECT` sends, reused verbatim.

Steps 3 and 4 in that order are why an unresolvable submission does not consume the link.
Step 4 before step 5 is the single-use control; creating first and marking used afterwards
would let a double-submit create two assessments.

### If the owner never answers

One reminder is sent `secman.account-onboarding.reminder-days-before` (default 3) before the
link expires, then the invite lapses to `EXPIRED` and surfaces as pending work.

**Nothing is ever auto-created.** Falling back to some default use case after a timeout would
produce an assessment scoped by a guess, which looks authoritative and is not — worse than
none.

---

## 5. Access control

| Surface | Who |
|---|---|
| `GET/POST /api/public/account-onboarding/{token}` | anonymous — the token is the capability |
| `/api/account-onboarding/**` (questions, choices, rules, coverage, preview, simulate) | **ADMIN or SECCHAMPION**, reads and writes alike |
| CLI `import --onboarding-mode`, `import-s3`, `simulate-onboarding` | the backend's own checks; the bulk import endpoint is ADMIN |
| MCP `simulate_account_onboarding` | delegation + ADMIN or SECCHAMPION |
| MCP `list_/preview_account_onboarding_rules` | delegation + ADMIN or SECCHAMPION |

**Both roles write, deliberately.** This differs from `DemandClassificationController`, which
lets both read but only ADMIN write. Deciding which security requirements apply to a cloud
account is exactly a security champion's job; routing every rule edit through an ADMIN would
make the feature unusable by the people who own it.

The UI mirrors this (`canAccessAccountOnboarding`), and the sidebar link lives in **RISK
MANAGEMENT**, not the ADMIN block — that block is gated on `isAdmin` alone, so a link placed
there would be invisible to a SECCHAMPION.

---

## 6. Simulating an account

`simulate-onboarding` runs the whole path against an account id and email you supply. It is
**not a mock**: it calls exactly what a real import calls, which is what makes it a genuine
rehearsal — and also why it is guarded like a write.

- ADMIN or SECCHAMPION at the controller **and** in the MCP guard.
- `awsAccountId` must be exactly 12 digits; `ownerEmail` passes the same
  anti-header-injection boundary every other recipient does (`EmailAddressValidator`).
- 20 live simulations per actor per hour; dry runs are limited more loosely.
- **Every simulated mail says so and names the actor** ("This is a test message sent by
  `<actor>` from SecMan"). That single line removes nearly all the phishing value of an
  arbitrary-recipient sender, and tells the recipient who to ask.
- The invite is stamped `simulated = true` and the run is audited with actor, target and
  outcome, so test rows are identifiable and sweepable rather than indistinguishable from
  production.

A live simulation creates real rows and sends real mail **on purpose** — you must be able to
click the link that arrives. Use `--dry-run` to preview.

---

## 7. Dry runs

### A dry run never mints a token

| Mode | What it reports | What it never does |
|---|---|---|
| `WELCOME_ONLY` | recipient, template, subject | no SMTP |
| `DIRECT` | use case, ACTIVE release version, requirement count, assessor, deadline, and the **idempotency verdict** — would-create vs would-skip | no assessment, no tracking row, no `AWS_ACCOUNT` asset, no SMTP |
| `GUIDED` | the questions that would be asked, the invite expiry, and the **full rule matrix** | **no invite row and no token** |

The `DIRECT` idempotency verdict is new capability: before this, a dry run could not tell you
the import would be a no-op.

The `GUIDED` omission is a security property, not an oversight. A minted token would be a live
credential left behind by a command whose whole promise is that it changes nothing — and it
would be printed into the run log.

---

## 8. Operating it

```bash
# Configure the questions and rules first (ADMIN or SECCHAMPION)
#   → /admin/account-onboarding

# Welcome mail only
./scripts/secman manage-user-mappings import -f mappings.csv \
    --onboarding-mode WELCOME_ONLY

# Welcome mail + an assessment you scope yourself
./scripts/secman manage-user-mappings import -f mappings.csv \
    --onboarding-mode DIRECT --risk-usecase "Cloud Onboarding" --risk-deadline-days 14

# Welcome mail + let the owner scope it
./scripts/secman manage-user-mappings import -f mappings.csv \
    --onboarding-mode GUIDED --questionnaire-expiry-days 21

# Preview any of the above
./scripts/secman manage-user-mappings import -f mappings.csv \
    --onboarding-mode GUIDED --dry-run

# Rehearse against an account that does not exist, mailing yourself
./scripts/secman manage-user-mappings simulate-onboarding \
    --aws-account-id 999999999999 --owner-email you@example.com --mode GUIDED
```

### Configuration

| Key | Default | What it does |
|---|---|---|
| `secman.account-onboarding.max-accounts-per-run` | 200 | Ceiling on the pairs one import may onboard. GUIDED sends two mails per pair, so an import introducing hundreds would otherwise fire a mail storm. Exceeding it is **refused up front**, never silently throttled. |
| `secman.account-onboarding.invite-expiry-days` | 14 | Range 1–90. |
| `secman.account-onboarding.reminder-days-before` | 3 | One nudge, then the link lapses. |
| `secman.account-onboarding.welcome-template` | `account-welcome` | Basename under `email-templates/`. |
| `secman.account-onboarding.questionnaire-template` | `account-onboarding-questionnaire` | |
| `secman.account-onboarding.reminder-template` | `account-onboarding-reminder` | |

**Template names go through a closed allowlist** (`EmailTemplateRenderer.ALLOWED_TEMPLATES`),
not a naming convention. These values are operator configuration, and interpolating one into
`getResourceAsStream` would be a classpath-traversal read: `../application` would happily
return the config file, secrets and all. Adding a template means adding it to the allowlist in
the same commit as the file.

### A skip is not a failure

Re-running an import that already onboarded a pair reports `skipped` with a reason, never
`error`. The CLI prints `⏭️` and exits `0`. Callers must not let a skip drive a non-zero exit
status — re-import being a no-op is the feature working, not a problem to act on.

---

## 9. Full worked examples

Three modes × {live, dry run} × {CLI, REST, MCP} = 18, then simulate × 6, then the guided
follow-through. Every command is copy-pasteable; `$SECMAN_BACKEND_URL` and `$TOKEN` come from
your environment.

| Mode | Dry run | CLI | REST | MCP |
|---|---|---|---|---|
| `WELCOME_ONLY` | live | [E1](#e1) | [E2](#e2) | [E3](#e3) |
| `WELCOME_ONLY` | dry | [E4](#e4) | [E5](#e5) | [E6](#e6) |
| `DIRECT` | live | [E7](#e7) | [E8](#e8) | [E9](#e9) |
| `DIRECT` | dry | [E10](#e10) | [E11](#e11) | [E12](#e12) |
| `GUIDED` | live | [E13](#e13) | [E14](#e14) | [E15](#e15) |
| `GUIDED` | dry | [E16](#e16) | [E17](#e17) | [E18](#e18) |

<a id="e1"></a>
### E1 — WELCOME_ONLY · live · CLI

Welcome the owner of every brand-new account, and start nothing.

```bash
./scripts/secman manage-user-mappings import \
    --file mappings.csv --onboarding-mode WELCOME_ONLY
```

```
Onboarding: WELCOME_ONLY (welcome mail to each new account owner)

Onboarding (1):
  ✉️  111111111111  alice@corp.com  ->  welcome mail sent

✓ Import successful
```

Persisted: the mappings. Sent: one welcome mail. No assessment, no invite.

<a id="e2"></a>
### E2 — WELCOME_ONLY · live · REST

```bash
curl -sS -X POST "$SECMAN_BACKEND_URL/api/user-mappings/bulk" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "mappings": [{"email": "alice@corp.com", "awsAccountId": "111111111111"}],
        "onboardingMode": "WELCOME_ONLY"
      }'
```

```json
{
  "totalProcessed": 1, "created": 1, "createdPending": 0, "skipped": 0, "errors": [],
  "newAccounts": [{"awsAccountId": "111111111111", "emails": ["alice@corp.com"]}],
  "riskAssessments": [],
  "onboarding": [{
    "awsAccountId": "111111111111", "ownerEmail": "alice@corp.com", "mode": "WELCOME_ONLY",
    "welcomeEmailSent": true, "questionnaireInviteId": null, "riskAssessmentId": null,
    "dryRun": false, "skipped": false, "skipReason": null, "error": null
  }]
}
```

<a id="e3"></a>
### E3 — WELCOME_ONLY · live · MCP

```json
{"name": "import_user_mappings", "arguments": {
  "mappings": [{"email": "alice@corp.com", "awsAccountId": "111111111111"}],
  "onboardingMode": "WELCOME_ONLY"
}}
```

Result carries the same `onboarding[]` array as E2, alongside the unchanged `newAccounts[]`
and an empty `riskAssessments[]`.

<a id="e4"></a>
### E4 — WELCOME_ONLY · dry · CLI

```bash
./scripts/secman manage-user-mappings import \
    --file mappings.csv --onboarding-mode WELCOME_ONLY --dry-run
```

```
Onboarding: WELCOME_ONLY (welcome mail to each new account owner)
Mode: DRY-RUN (validation only, no changes will be made)

DRY-RUN — nothing persisted, nothing sent, no invite token minted.
Would onboard 1 account/owner pair(s) in WELCOME_ONLY mode:
  ✉️  111111111111  alice@corp.com  ->  would send a welcome mail

✓ Validation successful (dry-run)
```

Persisted: nothing. Sent: nothing.

<a id="e5"></a>
### E5 — WELCOME_ONLY · dry · REST

Same body as E2 plus `"dryRun": true`. The `onboarding[]` entries carry `"dryRun": true` and
`"welcomeEmailSent": false`.

<a id="e6"></a>
### E6 — WELCOME_ONLY · dry · MCP

Same arguments as E3 plus `"dryRun": true`.

<a id="e7"></a>
### E7 — DIRECT · live · CLI

The operator already knows which use case applies.

```bash
./scripts/secman manage-user-mappings import \
    --file mappings.csv --onboarding-mode DIRECT \
    --risk-usecase "Cloud Onboarding" --risk-deadline-days 14
```

```
Onboarding: DIRECT
  Use case:  Cloud Onboarding
  Deadline:  14 day(s)
  Welcome:   yes

Onboarding (1):
  ✅ 111111111111  alice@corp.com  ->  assessment #1042, welcome mail sent

Risk assessments (1):
  ✅ 111111111111  alice@corp.com  ->  assessment #1042, assessor sec@corp.com,
     due 2026-08-25, requirements 2.1.0 (7 requirement(s))

✓ Import successful
```

<a id="e8"></a>
### E8 — DIRECT · live · REST

```bash
curl -sS -X POST "$SECMAN_BACKEND_URL/api/user-mappings/bulk" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "mappings": [{"email": "alice@corp.com", "awsAccountId": "111111111111"}],
        "onboardingMode": "DIRECT",
        "riskAssessmentUseCase": "Cloud Onboarding",
        "riskAssessmentDeadlineDays": 14
      }'
```

```json
{
  "riskAssessments": [{
    "awsAccountId": "111111111111", "ownerEmail": "alice@corp.com",
    "riskAssessmentId": 1042, "assessor": "sec@corp.com", "endDate": "2026-08-25",
    "useCase": "Cloud Onboarding", "useCases": ["Cloud Onboarding"],
    "releaseVersion": "2.1.0", "requirementCount": 7,
    "skipped": false, "skipReason": null, "error": null
  }],
  "onboarding": [{
    "awsAccountId": "111111111111", "ownerEmail": "alice@corp.com", "mode": "DIRECT",
    "welcomeEmailSent": true, "riskAssessmentId": 1042, "dryRun": false, "skipped": false
  }]
}
```

**The legacy form** — `{"startRiskAssessment": true, "riskAssessmentUseCase": "Cloud
Onboarding"}` with no `onboardingMode` — produces the identical `riskAssessments[]` entry and
`"welcomeEmailSent": false`.

<a id="e9"></a>
### E9 — DIRECT · live · MCP

```json
{"name": "import_user_mappings", "arguments": {
  "mappings": [{"email": "alice@corp.com", "awsAccountId": "111111111111"}],
  "onboardingMode": "DIRECT",
  "riskAssessmentUseCase": "Cloud Onboarding",
  "riskAssessmentDeadlineDays": 14
}}
```

<a id="e10"></a>
### E10 — DIRECT · dry · CLI

```bash
./scripts/secman manage-user-mappings import \
    --file mappings.csv --onboarding-mode DIRECT \
    --risk-usecase "Cloud Onboarding" --dry-run
```

```
DRY-RUN — nothing persisted, nothing sent, no invite token minted.
Would onboard 1 account/owner pair(s) in DIRECT mode:
  ✅ 111111111111  alice@corp.com  ->  would start an assessment for 'Cloud Onboarding'
                                       (due in 7 day(s))

✓ Validation successful (dry-run)
```

A pair that already has an open assessment prints `⏭️ … would skip — an open risk assessment
(id=…) already exists`.

<a id="e11"></a>
### E11 — DIRECT · dry · REST

E8's body plus `"dryRun": true`.

<a id="e12"></a>
### E12 — DIRECT · dry · MCP

E9's arguments plus `"dryRun": true`.

<a id="e13"></a>
### E13 — GUIDED · live · CLI

```bash
./scripts/secman manage-user-mappings import \
    --file mappings.csv --onboarding-mode GUIDED --questionnaire-expiry-days 21
```

```
Onboarding: GUIDED (welcome mail + guided assessment)
  Link expiry: 21 day(s)
  Deadline:    7 day(s) after the owner submits
  Welcome:     yes

Onboarding (1):
  🔗 111111111111  alice@corp.com  ->  questionnaire invite #12, expires 2026-09-01 09:14

✓ Import successful
```

Persisted: the mappings and one invite. Sent: a welcome mail and a questionnaire mail. **No
assessment yet** — that happens when the owner submits. The token appears only in the mail;
it is never printed.

<a id="e14"></a>
### E14 — GUIDED · live · REST

```bash
curl -sS -X POST "$SECMAN_BACKEND_URL/api/user-mappings/bulk" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "mappings": [{"email": "alice@corp.com", "awsAccountId": "111111111111"}],
        "onboardingMode": "GUIDED",
        "questionnaireExpiryDays": 21
      }'
```

```json
{
  "riskAssessments": [],
  "onboarding": [{
    "awsAccountId": "111111111111", "ownerEmail": "alice@corp.com", "mode": "GUIDED",
    "welcomeEmailSent": true, "questionnaireInviteId": 12,
    "questionnaireExpiresAt": "2026-09-01 09:14",
    "riskAssessmentId": null, "dryRun": false, "skipped": false, "error": null
  }]
}
```

Note what is absent: the token. No API returns one.

<a id="e15"></a>
### E15 — GUIDED · live · MCP

```json
{"name": "import_user_mappings", "arguments": {
  "mappings": [{"email": "alice@corp.com", "awsAccountId": "111111111111"}],
  "onboardingMode": "GUIDED",
  "questionnaireExpiryDays": 21
}}
```

<a id="e16"></a>
### E16 — GUIDED · dry · CLI

```bash
./scripts/secman manage-user-mappings import \
    --file mappings.csv --onboarding-mode GUIDED --dry-run
```

```
DRY-RUN — nothing persisted, nothing sent, no invite token minted.
Would onboard 1 account/owner pair(s) in GUIDED mode:
  🔗 111111111111  alice@corp.com  ->  would mail a questionnaire link (valid 14 days)

Rule matrix that would apply:
  [1] "Production workload"  environment=production AND customer-data=yes  ->  Cloud Baseline, Data Protection
  [2] "Internet facing"      internet-facing=yes                           ->  Internet Exposure
  [*] default fallback       (no rule matched)                             ->  Cloud Baseline
Reachable use cases: 3  ->  9 requirement(s) in ACTIVE release 2.1.0

✓ Validation successful (dry-run)
```

The rule matrix is what a GUIDED dry run prints **instead of** minting a token.

<a id="e17"></a>
### E17 — GUIDED · dry · REST

E14's body plus `"dryRun": true`. `questionnaireInviteId` stays `null`.

<a id="e18"></a>
### E18 — GUIDED · dry · MCP

E15's arguments plus `"dryRun": true`.

---

### Simulate

| Run | CLI | REST | MCP |
|---|---|---|---|
| live | [S1](#s1) | [S2](#s2) | [S3](#s3) |
| dry | [S4](#s4) | [S5](#s5) | [S6](#s6) |

<a id="s1"></a>
### S1 — simulate · live · CLI

```bash
./scripts/secman manage-user-mappings simulate-onboarding \
    --aws-account-id 999999999999 --owner-email you@example.com --mode GUIDED
```

```
⚠️  SIMULATION — this creates real rows and sends real mail to you@example.com.
    Add --dry-run to preview without sending.

Backend: https://secman.example.com
Account: 999999999999
Owner:   you@example.com
Mode:    GUIDED

  🔗 999999999999  you@example.com  ->  questionnaire invite #13, expires 2026-08-25 09:14
      Check the mailbox — the link is in the mail, never printed here.

✓ Onboarding successful
```

<a id="s2"></a>
### S2 — simulate · live · REST

```bash
curl -sS -X POST "$SECMAN_BACKEND_URL/api/account-onboarding/simulate" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"awsAccountId":"999999999999","ownerEmail":"you@example.com",
       "mode":"GUIDED","dryRun":false}'
```

<a id="s3"></a>
### S3 — simulate · live · MCP

```json
{"name": "simulate_account_onboarding", "arguments": {
  "awsAccountId": "999999999999", "ownerEmail": "you@example.com",
  "mode": "GUIDED", "dryRun": false
}}
```

<a id="s4"></a>
### S4 — simulate · dry · CLI

Add `--dry-run`. Prints the rule matrix and mints nothing.

<a id="s5"></a>
### S5 — simulate · dry · REST

S2's body with `"dryRun": true`.

<a id="s6"></a>
### S6 — simulate · dry · MCP

S3's arguments with `"dryRun": true`.

---

### The guided follow-through

<a id="g1"></a>
### G1 — the owner opens the link

```bash
curl -sS "$SECMAN_BACKEND_URL/api/public/account-onboarding/<64-hex token>"
```

```json
{
  "maskedAccountId": "****9999",
  "expiresAt": "2026-08-25 09:14",
  "questions": [
    {"questionKey": "environment", "label": "Which environment?",
     "inputType": "SINGLE_SELECT", "required": true,
     "choices": [{"choiceKey": "production", "label": "Production"},
                 {"choiceKey": "test", "label": "Test"}]},
    {"questionKey": "customer-data", "label": "Handles customer data?",
     "inputType": "BOOLEAN", "required": true,
     "choices": [{"choiceKey": "yes", "label": "Yes"}, {"choiceKey": "no", "label": "No"}]}
  ]
}
```

No credential is sent, and none is needed. Note what is *not* there: the full account id, the
owner's address, the assessor, the release.

<a id="g2"></a>
### G2 — the owner submits, and two rules match

```bash
curl -sS -X POST "$SECMAN_BACKEND_URL/api/public/account-onboarding/<64-hex token>" \
  -H 'Content-Type: application/json' \
  -d '{"answers":[{"questionKey":"environment","choiceKeys":["production"]},
                  {"questionKey":"customer-data","choiceKeys":["yes"]}]}'
```

```json
{
  "status": "SUBMITTED",
  "riskAssessmentId": 1043,
  "useCases": ["Cloud Baseline", "Data Protection"],
  "requirementCount": 9,
  "deadline": "2026-08-18"
}
```

One assessment, scoped to the **union** of both matching rules' use cases.

<a id="g3"></a>
### G3 — replay is refused

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -X POST "$SECMAN_BACKEND_URL/api/public/account-onboarding/<the same token>" \
  -H 'Content-Type: application/json' -d '{"answers":[]}'
```

```
404
```

with the body from §4 — byte-identical to what an unknown or malformed token returns.

<a id="g4"></a>
### G4 — nothing matches

```json
{"error":"NO_RULE_MATCHED",
 "message":"Your answers have been recorded. They do not yet map to a set of requirements, so a security champion will follow up with you."}
```

`409`. The answers are on the invite, the invite is still `PENDING`, and the same link works
once an admin adds the missing rule.

---

## 10. Testing

```bash
./gradlew :backendng:test --tests "*AccountOnboarding*"
./gradlew :cli:test --tests "*Onboarding*"
cd src/frontend && npm test          # accountOnboardingAnswers, accountOnboardingRules, permissions, Sidebar
```

End to end: **`/account-onboarding`** cold-starts the stack and runs
`scripts/test/test-e2e-account-onboarding.sh`. See `docs/SKILLS.md` for what it covers and how
it differs from `/aws-account-risk-assessment`.

Two gates matter beyond the new one: `/aws-account-risk-assessment` and
`/aws-account-owner-email` must stay **green and unchanged**. They exercise the legacy
`--start-risk-assessment` path, and their passing is the backward-compatibility proof.

---

## 11. Key files

| Concern | File |
|---|---|
| Modes and the compatibility rule | `domain/AccountOnboardingMode.kt` |
| Questions, choices, rules, invites | `domain/AccountOnboarding{Question,Choice,Rule,Invite}.kt` |
| Matching | `service/AccountOnboardingRuleMatcher.kt` |
| Orchestration, mail, invites, reminders | `service/AccountOnboardingService.kt` |
| Assessment creation (shared with `DIRECT`) | `service/AwsAccountRiskAssessmentService.kt` |
| Import wiring | `service/UserMappingBulkImportService.kt` |
| Public questionnaire | `controller/AccountOnboardingPublicController.kt` |
| Rate limiting | `service/AccountOnboardingRateLimiter.kt` |
| Admin API | `controller/AccountOnboardingController.kt` |
| Recipient boundary | `util/EmailAddressValidator.kt` |
| Templates and the allowlist | `service/EmailTemplateRenderer.kt`, `resources/email-templates/account-*` |
| Schema | `resources/db/migration/V253__account_onboarding.sql` |
| CLI | `cli/commands/{ImportCommand,ImportS3Command,SimulateOnboardingCommand}.kt` |
| MCP | `mcp/tools/{Simulate,List,Preview}AccountOnboarding*.kt`, `mcp/McpToolPermissions.kt` |
| Owner UI | `pages/onboarding/[token].astro`, `components/AccountOnboardingQuestionnaire.tsx` |
| Admin UI | `pages/admin/account-onboarding.astro`, `components/admin/AccountOnboarding*.tsx` |

See also: `docs/AWS_ACCOUNT_RISK_ASSESSMENT.md`, `docs/CLI.md`, `docs/MCP.md`.
