---
name: mcp-requirement-use-case-lifecycle
description: >
  Run the reversible SecMan MCP test that creates and lists one requirement,
  creates and lists one use case, assigns it to the requirement, verifies the
  structured relationship, deletes both fixtures, and lists again to prove
  cleanup. Use when the user asks to test MCP requirement/use-case CRUD or the
  exact create-list-assign-delete lifecycle for Codex or Claude Code.
---

> **Sync policy (two-way, mandatory)**: This file and
> `.claude/skills/mcp-requirement-use-case-lifecycle/SKILL.md` are one skill
> kept in two harness trees. Port every change to the counterpart in the same
> commit, translating harness-specific mechanics. Verify with
> `./scripts/check-skill-sync.sh`.

# MCP requirement and use-case lifecycle

Run the exact reversible lifecycle through MCP. Read
`.agents/skills/_shared/stack-lifecycle.md` before touching the stack.

## Preconditions

- Resolve `SECMAN_HOST`, `SECMAN_MCP_KEY`, and the delegated identity through
  `pass-cli` and `secmanpp.env`.
- The MCP key needs `REQUIREMENTS_READ`, `REQUIREMENTS_WRITE`, and
  `REQUIREMENTS_DELETE`, with delegation enabled for the user's domain.
- The delegated user must have `ADMIN`, `REQ`, or `SECCHAMPION`.
- The test creates uniquely named fixtures and deletes only the IDs returned by
  their MCP creation calls. Never replace this with a bulk-delete tool.

## Run

1. Cold-stop and start backend and frontend exactly as the shared lifecycle
   requires. Start scripts require `sandbox_permissions: "require_escalated"`.
2. Run outside the sandbox so Proton Pass is available:

   ```bash
   mkdir -p .e2e-logs
   set -o pipefail
   pass-cli run --env-file ./secmanpp.env -- \
     ./scripts/test/test-e2e-mcp-requirement-use-case-lifecycle.sh \
     --verbose \
     2>&1 | tee .e2e-logs/e2e-mcp-requirement-use-case-lifecycle.log
   ```

   Omit `--user-email` to use `SECMAN_MCP_USER_EMAIL`, falling back to
   `SECMAN_ADMIN_EMAIL`. To override it, pass a literal address with
   `--user-email`; do not expand a Proton Pass variable in the outer shell
   before `pass-cli run` has resolved it.
3. Success requires exit 0, `Failed: 0`, and all of these observations:
   requirement create and list; use-case create and list; assignment response;
   assignment in `useCaseAssignments`; unassignment; both confirmed deletes;
   and post-delete listings containing neither fixture.
4. On failure, the driver's `EXIT` trap makes a best-effort cleanup using only
   the returned IDs. Use the shared five-iteration fix budget; stop services
   before editing and cold-start before every retry.
5. Stop backend and frontend on success or failure.

The tool schemas and manual equivalent are documented in
`docs/MCP_REQUIREMENT_MANAGEMENT.md`.

Never commit or push.
