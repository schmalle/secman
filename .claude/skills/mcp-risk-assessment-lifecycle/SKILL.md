---
name: mcp-risk-assessment-lifecycle
description: >
  Run the holistic MCP risk-assessment lifecycle test with a mailbox supplied by
  the user. Creates a new respondent, security champion, one use case containing
  exactly one requirement, an AWS account mapping, and an account-native risk
  assessment; then lists it by use case, answers and submits it as the respondent,
  and evaluates it as the assessor through MCP. Cleans up before and after. Use
  when the user asks to test the complete MCP risk-assessment workflow or its
  PaperclipAI-ready contract.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.agents/skills/mcp-risk-assessment-lifecycle/SKILL.md` are one skill kept in
> two harness trees. Port every change to the counterpart in the same commit,
> translating harness-specific mechanics. Verify with
> `./scripts/check-skill-sync.sh`.

# MCP risk-assessment lifecycle

Run the complete, reversible test fixture and iterate on failures. Read
`.claude/skills/_shared/stack-lifecycle.md` before touching the stack.

## Required user input

Obtain the email address to assign to the new respondent using the harness's user-question mechanism.
Do not select a placeholder silently. The driver refuses an address already
belonging to a SecMan user so it can never overwrite or delete a real account.
The address domain must be allowed by the MCP API key's delegation policy.

## Run

1. Cold-stop and start backend and frontend exactly as the shared lifecycle
   requires. Start scripts require Bash tool `dangerouslyDisableSandbox: true`.
2. Run:

   ```bash
   mkdir -p .e2e-logs
   pass-cli run --env-file ./secmanpp.env -- \
     ./scripts/test/test-e2e-mcp-risk-assessment-lifecycle.sh \
     --user-email '<supplied-address>' --verbose \
     2>&1 | tee .e2e-logs/e2e-mcp-risk-assessment-lifecycle-run-1.log
   ```

3. Success requires exit 0 and `Failed: 0`. A missing MCP key permission is a
   configuration failure, not permission to weaken a tool guard. The key needs
   `USER_ACTIVITY`, `REQUIREMENTS_WRITE`,
   `ASSESSMENTS_WRITE`, `ASSESSMENTS_READ`, `ASSESSMENTS_EXECUTE`, and
   `NOTIFICATIONS_SEND`.
4. On failure, stop both services before editing, restart both, and retry. Use
   the shared five-iteration budget and stop after two identical failed fixes.
5. Stop both services on success or failure.

## What must pass

- both users, the use case, linked requirement, account mapping, and
  assessment are created through MCP;
- the use case questionnaire contains exactly the one created requirement;
- the coordinator previews the respondent notification through MCP and SecMan
  reports exactly one outstanding answer without sending mail;
- the new respondent lists the open assessment with `status=STARTED` and the
  exact `useCaseName`;
- a non-respondent cannot save answers;
- the respondent saves and submits the answer through MCP;
- the assessor evaluates the completed questionnaire and receives the expected
  compliance finding;
- the completed assessment is listable by use case;
- pre-run and `EXIT` cleanup remove only exact `e2e-mcp-ra-` fixture markers.

Use `--keep-data` only when the user explicitly wants to inspect the fixture.
Tell them to invoke `cleanup-mcp-risk-assessment-lifecycle` afterwards.

The maintained contract and PaperclipAI examples are in
`docs/MCP_RISK_ASSESSMENT_LIFECYCLE.md`. Consult it when a tool schema,
permission, delegation identity, or expected result is unclear.

Never commit or push.
