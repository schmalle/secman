---
name: aws-account-owner-email
description: >
  Run the end-to-end test proving the owner of a newly discovered AWS account
  actually receives the risk-assessment start email. Asks for the mailbox the
  test mail should be delivered to, seeds the testbed, imports a brand-new
  12-digit account via the CLI and again via MCP, asserts in the backend log
  that SMTP accepted a send to that exact address for that exact account, then
  pauses so a human can confirm the mail arrived. Cleans up before and after
  and never creates or deletes a user for the recipient address. Use this skill
  whenever the user mentions testing the AWS account owner notification, "does
  the account owner get an email", "risk assessment email", "new account email
  test", "aws-account-owner-email", or wants to verify mail delivery for
  auto-started AWS account risk assessments — even when they only say "check the
  owner gets notified".
---

> **Sync policy (two-way, mandatory)**: This file and `.agents/skills/aws-
> account-owner-email/SKILL.md` are one skill kept in two harness trees —
> Claude Code reads this copy, Codex reads the other. Whichever copy an agent
> edits, the same change is ported to the other **in the same commit**;
> translate harness-specific mechanics rather than copying verbatim (e.g. Bash
> tool `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions:
> "require_escalated"`). `.claude/skills/` is the tie-breaker when the two
> disagree — that is a conflict rule, not a licence to edit one side only.
> Verify with `./scripts/check-skill-sync.sh` (exit 0) before calling the
> change done. See `CLAUDE.md` §"Tooling Conventions" and `AGENTS.md` §Skills.

# AWS Account Owner Email — Iterative Fix Loop

You are an orchestration agent. Get the recipient address, bring up the stack,
run the driver, and **iteratively fix every failure** until it passes or the
retry budget is spent.

This skill runs **inline, not in a detached context**: it has to ask the user for
a mailbox and later ask them what landed in it, and a detached context cannot
reach the user.

**Start tool calls immediately.** Do not pre-read `docs/`; load
`docs/AWS_ACCOUNT_RISK_ASSESSMENT.md` only when a specific failure needs it.

## What is actually being tested

The import → assessment path is covered by `/aws-account-risk-assessment`. This
skill covers only the **notification**, because that send is best-effort and
fails quietly: `AwsAccountRiskAssessmentService` swallows a throwing notification
into a `log.warn`, and `EmailService.sendEmail` returns `false` without throwing
when no SMTP config is active. An import can therefore report complete success
while no mail is sent. "The assessment exists" is not evidence of delivery.

The start notification also writes **no** row to `email_notification_logs` — only
`sendNotificationEmail()` does that, and this path does not use it — so
`/api/notification-logs` cannot see these mails either. The only machine-checkable
evidence is the backend log line `EmailService` emits at INFO:

```
Successfully sent email with inline images to <address> with subject: Risk assessment started for your AWS account <id>
```

Note the `with inline images` clause: both owner mails render from
`email-templates/aws-account-risk-assessment-*` and carry the SecMan logo as a CID
image, so they go through `sendEmailWithInlineImages`, which words its success line
differently from plain `sendEmail`. The driver matches several fixed substrings that
must share one line rather than the whole sentence, so it accepts either wording —
worth preserving if you touch that assertion.

The search is scoped to the bytes appended after each import starts, so a stale line
from an earlier run cannot make a broken send look healthy.

## Phase 0 — Get the recipient address

The address the user gives here becomes the `To:` of a real message: the driver
writes it as the mapping owner, the backend mails that owner, and the assertion
looks for a send to that exact address. Getting it wrong mails someone who never
asked, and that cannot be taken back — so settle it before anything starts.

- **Not supplied** → ask the user (in Claude Code, `AskUserQuestion`) and wait
  for the answer. Do not proceed on an assumption.
- **Supplied as an argument** → read it back and confirm before Phase 1. An
  argument is a value, not consent to mail it; a typo in the domain reaches a
  stranger.

Be explicit that **real mail is really delivered** — the whole value of this test
is that a person opens the mailbox afterwards. A mailbox the user reads is right;
a distribution list or a colleague's address is not.

Do not offer to invent an address. The driver rejects reserved placeholder
domains (`example.com`, `*.test`, `*.invalid`, `*.local`, …) for the same reason
the skill exists: a relay usually accepts them, the log then reads
`Successfully sent`, and the run goes green having proved nothing.
`ALLOW_PLACEHOLDER_RECIPIENT=true` overrides it, but only makes sense for a local
mail sink (MailHog, Mailpit) and forfeits the inbox check that Phase 4 depends on.

## Phase 1 — Start services

```
0. ./scripts/stopbackenddev.sh and ./scripts/stopfrontenddev.sh — always,
   even if ports look free (never `kill`).
1. mkdir -p .e2e-logs
2. nohup ./scripts/startbackenddev.sh  > .e2e-logs/backend.log  2>&1 &
3. nohup ./scripts/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &
4. Wait for ports to be BOUND (port binding, not HTTP)
```

| Setting            | Value                                       |
|--------------------|---------------------------------------------|
| Backend port wait  | `lsof -iTCP:8080 -sTCP:LISTEN -n -P`, 120s   |
| Frontend port wait | `lsof -iTCP:4321 -sTCP:LISTEN -n -P`, 60s    |

**Outside-sandbox requirement:** both start scripts source secrets through
`pass-cli`, which a sandboxed shell cannot reach.
Run them with Bash tool `dangerouslyDisableSandbox: true`.

Redirecting the backend to `.e2e-logs/backend.log` is not optional here: it *is*
the assertion surface. The driver refuses to run without a readable log.

The frontend is not exercised by this test; it is started only to honour the
repo's cold-start contract, so the stack matches every other E2E skill.

## Phase 2 — Run the driver

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-aws-account-owner-email.sh --email <recipient> --verbose \
  2>&1 | tee .e2e-logs/e2e-aws-owner-email-run-1.log
```

Required env (all via `pass-cli`): `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`,
`SECMAN_ADMIN_EMAIL`, `SECMAN_MCP_KEY`, and `BASE_URL` / `SECMAN_BACKEND_URL`.

Useful flags: `--skip-mcp` (CLI only), `--skip-cli` (MCP only), `--backend-log
<path>` when the backend logs somewhere else.

Success is `Failed: 0`. `[WARN]` lines are not failures.

## Phase 2.5 — Classify the failure

| Symptom | Likely cause | Where to look |
|---|---|---|
| `No active email configuration` (preflight, exits before seeding) | SMTP not configured in this environment | Admin → Email Configuration. Not a code bug; the test cannot run |
| `no successful send … within 40s` + `Cause: SMTP rejected the message` | credentials, TLS/SSL flags, port, or the recipient domain | `service/EmailService.kt` `createMailProperties`; the `Failed to send email` line names the exception |
| `… Cause: sendStartNotification threw` | template or field rendering blew up | `AwsAccountRiskAssessmentService.sendStartNotification` |
| `… Cause: assessment started but no send was attempted` | the `info.error == null && info.riskAssessmentId != null` guard skipped it | `startAssessmentsForNewAccounts` — a skip also means "already notified", check idempotency |
| `… Cause: no assessment was started at all` | this is an assessment bug, not a mail bug | switch to `/aws-account-risk-assessment`, which diagnoses that path properly |
| `expected 1 tracked assessment, found 0` | import rejected up front | validation table in `docs/AWS_ACCOUNT_RISK_ASSESSMENT.md`; check the CLI/MCP output in the run log |
| `assessment owner is '…', expected …` | the mapping did not carry the recipient | the generated CSV / MCP `mappings[]` payload |
| `ACTIVE release … has no use-case-tagged requirements` | environment state, not a bug | tag requirements with a use case, or set `ALLOW_RELEASE_ACTIVATION=true` **only** if archiving that release is acceptable |
| MCP JSON-RPC `error` on `import_user_mappings` | tool not authorized on the streamable transport | `mcp/McpToolPermissions.kt` `CALLING` — separate from the `LISTING` map used by `tools/list`, and a missing entry denies silently |

Backend stack traces: `tail -200 .e2e-logs/backend.log`, inside the run window.

## Phase 3 — Stop, Fix, Restart

1. `./scripts/stopbackenddev.sh` and `./scripts/stopfrontenddev.sh`.
2. Fix in source. **Never edit while services are running.** For a frontend edit,
   `cd src/frontend && npm ci && npm run build` must exit 0 first.
3. Restart both via the canonical start scripts, outside the sandbox,
   redirecting the backend to `.e2e-logs/backend.log` again.
4. Re-run the driver, incrementing the log suffix.
5. Guard rails: max **5** iterations; if the same assertion fails twice with the
   same message, stop and report rather than trying a third variation.

Note that each re-run mails the recipient again — say so if the loop runs long,
so nobody is surprised by a pile of test mail.

## Phase 4 — Confirm the inbox

A green log assertion means SMTP *accepted* the message. Only the recipient can
confirm it arrived, so do not report success until they have looked.

Show the checklist the driver printed (subject, use case, requirements version,
assessor, deadline — read from the tracking rows, so they are accurate even when
round-robin picked a different assessor). Then ask the user (in Claude Code, `AskUserQuestion`)
whether the mail arrived, arrived with wrong content, or did not arrive. Wait for
the answer.

- **Arrived, content correct** → done.
- **Wrong content** → the defect is in `sendStartNotification`'s body, not the
  transport. The log assertion cannot see this, which is exactly why we ask.
- **Did not arrive** → the message left SecMan; suspect spam filtering, the
  `fromEmail` domain, or a relay rule. Report it as an environment finding, not a
  code bug, unless the log window says otherwise.

## Phase 5 — Teardown & Report

The driver cleans up on `EXIT`: assessments, mappings, the `AWS Account <id>`
assets, and anything prefixed `e2e-awsmail-`. Stop both services when done.

Cleanup deletes the two mapping rows through `DELETE /api/user-mappings/{id}` and
reports any it could not remove with a `[WARN]`. Treat such a warning as a product
finding, not a driver bug to work around: it means that admin surface has regressed
again. It used to fail for every recipient other than the admin running the test,
because the controller passed the *caller's* id into the user-scoped
`deleteMapping(userId, …)` whose ownership check then rejected the row (HTTP 500,
fixed 2026-08-03 — see `UserMappingServiceDeleteTest`).

Report a table: phase, assertion, result, plus the human's inbox verdict and,
for anything still failing, the file and the reason.

## Constraints

- Never commit or push.
- Never `curl localhost` — go through `BASE_URL` from `pass-cli`.
- Never `kill` a dev server; use the stop scripts.
- **Never create or delete a user for the recipient address.** It usually belongs
  to a real account — often the tester's own. None is needed: the mail is sent
  whether or not a user with that address exists; the only difference is whether
  the assessment gets a respondent. The driver enforces this and cleanup is
  scoped to the `e2e-awsmail-` prefix, but do not work around it by hand.
- **Never activate a release when one is already ACTIVE.** Activation ARCHIVES
  the previous one and `ARCHIVED` is terminal — the environment's real
  requirements baseline could not be restored. The driver reuses the existing
  ACTIVE release and only seeds its own when none is active.
- The driver is destructive only within its `e2e-awsmail-` prefix and the two
  account IDs it generates (`884…`/`885…`, deliberately clear of the `886…`/`887…`
  pair used by `/aws-account-risk-assessment`, so the two can coexist).
