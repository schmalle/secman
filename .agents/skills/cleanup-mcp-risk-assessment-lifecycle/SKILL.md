---
name: cleanup-mcp-risk-assessment-lifecycle
description: >
  Remove all data created by the holistic MCP risk-assessment lifecycle fixture.
  Deletes only rows identified by exact e2e-mcp-ra fixture markers and is safe to
  rerun after an interrupted or --keep-data test. Use when the user asks to clean
  up or remove the MCP risk-assessment test data.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.claude/skills/cleanup-mcp-risk-assessment-lifecycle/SKILL.md` are one skill
> kept in two harness trees. Port every change to the counterpart in the same
> commit, translating harness-specific mechanics. Verify with
> `./scripts/check-skill-sync.sh`.

# Cleanup MCP risk-assessment lifecycle fixture

Read `.agents/skills/_shared/stack-lifecycle.md`, then cold-start the backend and
frontend. Start scripts require `sandbox_permissions: "require_escalated"`.

Run the idempotent cleanup:

```bash
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-mcp-risk-assessment-lifecycle.sh --cleanup-only
```

Success requires exit 0 and `Fixture cleanup completed`. Stop both services
afterwards, including on failure.

The cleanup order is assessment, mapping, the now-unreferenced nameless AWS
account row, requirement, use case, and users. It resolves the manually supplied address from the fixture username and
deletes the mapping by exact email. It identifies the assessment by an exact
notes marker and other entities by exact fixture names. Never broaden any match
to a substring or account-number fragment.

Report what was removed and whether the cleanup completed. Never commit or push.
