# Paperclip security-work management

Paperclip manages goals, projects, issues, and agent execution. SecMan remains
the authoritative store for assets, scanner findings, ownership, exceptions,
evidence, and finding history. The integration links the two systems instead of
copying SecMan's lifecycle into a second database.

## Recommended topology

```text
Visual / Web / GitHub checks ──v1 runs──> SecMan
                                            │
                                  asset-scoped MCP reads
                                            │
                                            v
Paperclip company → security goals → projects → issues → Codex / Claude Code
```

Use a dedicated Paperclip company for security work and create projects for
stable streams such as vulnerability triage, exceptions, scanner health, and
security engineering. Goals describe outcomes; issues are executable work. Keep
the SecMan finding ID and authenticated SecMan URL in each issue, but do not
paste attachment contents, access tokens, passwords, or sensitive evidence.

## SecMan MCP connection

Create a dedicated SecMan MCP API key with delegation enabled. For triage-only
agents grant `INTEGRATIONS_READ`, `ASSETS_READ`, and
`VULNERABILITIES_READ`. Add `INTEGRATIONS_WRITE` only to a scanner identity
that submits terminal snapshots; Paperclip work agents normally do not need it.

Every tool call requires both headers:

```text
X-MCP-API-Key: <Paperclip secret reference>
X-MCP-User-Email: <delegated SecMan user>
```

Store the key in Paperclip's secret manager and reference it from the adapter or
MCP configuration. Never place the key in an issue, repository file, adapter
argument, or committed environment file. Start read-only and confirm
`tools/list` exposes `get_integration_summary`, `list_integration_findings`,
`get_integration_finding`, `list_integration_runs`, and `get_integration_run`.
These bounded tools use the delegated user's SecMan asset visibility.

## Agent setup

Paperclip has local adapters for both Codex and Claude Code. Configure each
agent with this repository as its working directory and `dev` as the default
branch. Use worktrees for concurrent implementation issues, require tests in the
issue acceptance criteria, and keep auto-merge disabled for security-sensitive
or destructive work.

| Agent | SecMan access | Paperclip execution policy |
|---|---|---|
| Triage | integration/asset/vulnerability reads | may create and refine issues; no code writes |
| Implementer | same read scope | repository write in an isolated worktree; no production writes |
| Reviewer | same read scope | read-only diff and test evidence |
| Scanner service | integration write plus assigned assets | scheduled scanner only; no issue management |

Contributor skills must exist in both `.agents/skills/` (Codex) and
`.claude/skills/` (Claude Code). Paperclip may inject additional skills at run
time, but project guardrails stay authoritative and mirrored. Run
`./scripts/check-skill-sync.sh` before completing any skill change.

## Issue workflow

1. Triage calls `list_integration_findings` with `state=OPEN`, bounded filters,
   and pagination.
2. It reads a candidate with `get_integration_finding`, checks ownership and
   exception state, and deduplicates on the immutable SecMan finding ID.
3. It creates or updates a Paperclip issue whose acceptance criteria name the
   intended SecMan state and verification.
4. An implementer works on `dev` in a dedicated worktree, runs the relevant
   project skills, and records commit plus verification evidence.
5. A reviewer validates the immutable diff. Closing the Paperclip issue does
   not resolve SecMan; the next complete successful scanner run or an explicit
   SecMan lifecycle action does.

Use Paperclip execution policy to require review for authentication,
authorization, encryption, export, dependency, schema, or external-network
changes. Destructive SecMan test skills still require their own environment
preflight; Paperclip scheduling does not make a shared database disposable.

## Official Paperclip references

- [API overview](https://docs.paperclip.ing/reference/api/overview/)
- [Authentication](https://docs.paperclip.ing/reference/api/authentication/)
- [Issues API](https://docs.paperclip.ing/reference/api/issues/)
- [Goals and projects](https://docs.paperclip.ing/reference/api/goals-and-projects/)
- [Codex adapter](https://docs.paperclip.ing/reference/adapters/codex/)
- [Claude Code adapter](https://docs.paperclip.ing/reference/adapters/claude-code/)
- [Skills](https://docs.paperclip.ing/reference/skills/)
- [Execution policy](https://docs.paperclip.ing/guides/power/execution-policy/)
