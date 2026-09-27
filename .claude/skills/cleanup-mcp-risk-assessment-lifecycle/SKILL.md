---
name: cleanup-mcp-risk-assessment-lifecycle
description: >
  Audit interrupted MCP risk-assessment test fixtures. The isolated runner
  removes verified runner-owned databases; legacy rows without durable
  ownership proof are reported, never deleted by prefix alone.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.agents/skills/cleanup-mcp-risk-assessment-lifecycle/SKILL.md` are one skill
> kept in two harness trees. Port every change to the counterpart in the same
> commit, translating harness-specific mechanics. Verify with
> `./scripts/check-skill-sync.sh`.

# Cleanup MCP risk-assessment lifecycle fixture

Read `.claude/skills/_shared/stack-lifecycle.md`. Do not start the regular
backend or run the old `--cleanup-only` driver on a persistent database.
Run the ownership-verified abandoned-database sweep:

```bash
./scripts/test/run-isolated-e2e.sh --database-only -- /usr/bin/true
```

The runner removes only abandoned schemas with its exact ownership marker. For
historical `e2e-mcp-ra-` rows on a persistent database, gather a read-only
inventory and report it. Names or prefixes alone do not prove test ownership;
preserve those rows until the user supplies stronger evidence. Never commit or push.
