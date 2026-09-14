# MCP requirement and use-case management

SecMan exposes the complete security-requirement and use-case lifecycle through
the Streamable HTTP MCP endpoint at `POST /mcp`. This interface is intended for
interactive MCP clients and automation systems such as Paperclip. SecMan remains
the system of record.

## Authorization model

Every `tools/list` and `tools/call` request needs both headers:

```text
X-MCP-API-Key: sk-...
X-MCP-User-Email: requirement.manager@example.com
```

The API key must have delegation enabled and allow the delegated user's email
domain. The delegated user must have `ADMIN`, `REQ`, or `SECCHAMPION`.

| Operation | API-key permission |
|---|---|
| list requirements and use cases | `REQUIREMENTS_READ` |
| create, update, or assign | `REQUIREMENTS_WRITE` |
| delete one requirement or use case | `REQUIREMENTS_DELETE` |

The effective permission is the intersection of the key permissions and the
delegated user's role-implied permissions. `ADMIN` has all permissions. `REQ`
and `SECCHAMPION` imply all three requirement permissions.

For an automation key that manages the full lifecycle, grant exactly
`REQUIREMENTS_READ`, `REQUIREMENTS_WRITE`, and `REQUIREMENTS_DELETE`. Do not use
an all-permissions key unless the agent also needs unrelated SecMan functions.

## Tool catalogue

| Tool | Purpose |
|---|---|
| `get_requirements` | List and filter current requirements; includes structured relationship IDs |
| `add_requirement` | Create a requirement, with optional use-case and norm assignments |
| `update_requirement` | Patch every editable requirement field and optionally replace relationships |
| `delete_requirement` | Delete one requirement after confirmation; frozen requirements are rejected |
| `list_use_cases` | List or search use cases with bounded pagination |
| `create_use_case` | Create a uniquely named use case |
| `update_use_case` | Rename a non-protected use case |
| `delete_use_case` | Delete an unassigned, non-protected use case after confirmation |
| `set_requirement_use_cases` | Replace the complete use-case assignment set for one requirement |

All list operations are bounded. `get_requirements.limit` is at most 100 and
`offset` is at most 10,000. `list_use_cases.pageSize` is at most 100 and its
page number is at most 10,000.

## Recommended two-step workflow

This is the least ambiguous sequence when an agent creates a requirement and
then assigns a use case.

1. Create the requirement without `useCaseIds` and retain the returned `id`.
2. Find or create the use case and retain its `id`.
3. Call `set_requirement_use_cases` with the requirement ID and the complete
   desired set of use-case IDs.
4. Read the requirement using `get_requirements` and verify
   `useCaseAssignments`.

`set_requirement_use_cases` uses replacement semantics, not append semantics.
Passing `[7, 9]` makes those the only assignments. Passing `[]` removes every
use-case assignment. This makes retries idempotent and prevents an agent from
silently accumulating stale relationships.

### 1. Add the requirement

```json
{
  "jsonrpc": "2.0",
  "id": "requirement-create",
  "method": "tools/call",
  "params": {
    "name": "add_requirement",
    "arguments": {
      "shortreq": "Encrypt customer data at rest",
      "details": "Use organization-approved encryption for stored customer data.",
      "motivation": "Limit the impact of storage disclosure.",
      "example": "Use an approved managed KMS key.",
      "language": "en",
      "norm": "ISO 27001",
      "chapter": "Cryptography"
    }
  }
}
```

The response contains `id`, `internalId`, `revision`, every editable field, and
the current `useCases` and `norms` arrays.

### 2. Find or create the use case

```json
{
  "jsonrpc": "2.0",
  "id": "use-case-list",
  "method": "tools/call",
  "params": {
    "name": "list_use_cases",
    "arguments": {"search": "AWS", "page": 0, "pageSize": 50}
  }
}
```

If it does not exist:

```json
{
  "jsonrpc": "2.0",
  "id": "use-case-create",
  "method": "tools/call",
  "params": {
    "name": "create_use_case",
    "arguments": {"name": "AWS customer-data workload"}
  }
}
```

Names are unique case-insensitively and may be at most 255 characters.

### 3. Assign the use case

```json
{
  "jsonrpc": "2.0",
  "id": "requirement-assign",
  "method": "tools/call",
  "params": {
    "name": "set_requirement_use_cases",
    "arguments": {"requirementId": 321, "useCaseIds": [17]}
  }
}
```

The response echoes the resulting `useCases` with IDs and names.

### 4. Verify the result

```json
{
  "jsonrpc": "2.0",
  "id": "requirement-verify",
  "method": "tools/call",
  "params": {
    "name": "get_requirements",
    "arguments": {"search": "Encrypt customer data", "detailed": true, "limit": 10}
  }
}
```

Use `useCaseAssignments`, not only the legacy `usecases` name array, when an
agent needs stable IDs for a later write.

## Complete requirement editing

`update_requirement` is a partial update. Omitted values stay unchanged.
`useCaseIds` and `normIds`, when present, replace their complete relationship
sets. To set an optional text field to database `null`, put its name in
`clearFields`; a field cannot be supplied and cleared in the same call.

Editable text fields are `shortreq`, `details`, `language`, `example`,
`motivation`, `usecase`, `norm`, and `chapter`. `shortreq` cannot be cleared.
The legacy free-text `usecase` field is distinct from the structured
`useCaseIds` relationship.

```json
{
  "jsonrpc": "2.0",
  "id": "requirement-update",
  "method": "tools/call",
  "params": {
    "name": "update_requirement",
    "arguments": {
      "requirementId": 321,
      "shortreq": "Encrypt regulated customer data at rest",
      "details": "Use an approved key-management service and rotate keys.",
      "clearFields": ["example", "usecase"],
      "useCaseIds": [17, 23]
    }
  }
}
```

Content changes increment the requirement revision. Relationship-only changes
do not, matching the web application behavior.

### Effect on risk assessments

New risk assessments are pinned to the single `ACTIVE` requirements release.
Editing a live requirement or changing its use-case assignments therefore does
not rewrite the questionnaire or evidence of an assessment that has already
started. Starting an MCP risk assessment fails when no `ACTIVE` release exists,
or when any selected use case has no requirement in that release. Publish and
activate a release containing the desired requirement assignments before calling
`create_risk_assessment`.

## Use-case editing and safe deletion

Rename a use case:

```json
{"jsonrpc":"2.0","id":"uc-update","method":"tools/call","params":{"name":"update_use_case","arguments":{"useCaseId":17,"name":"AWS regulated workload"}}}
```

System-protected use cases cannot be renamed or deleted. An assigned use case
cannot be deleted: first remove it from every requirement with
`set_requirement_use_cases`, then delete it.

```json
{"jsonrpc":"2.0","id":"uc-delete","method":"tools/call","params":{"name":"delete_use_case","arguments":{"useCaseId":17,"confirm":true}}}
```

Requirement deletion also requires `confirm: true`. A requirement frozen in a
release is immutable evidence and deletion returns `CONFLICT`.

```json
{"jsonrpc":"2.0","id":"req-delete","method":"tools/call","params":{"name":"delete_requirement","arguments":{"requirementId":321,"confirm":true}}}
```

For cleanup of agent-created data, delete in this order: remove assignments,
delete the requirement, then delete the use case. Scope cleanup by the exact IDs
returned during creation; never use `delete_all_requirements` on a shared system.

## Automated reversible lifecycle test

The maintained driver performs the complete create, list, assign, verify,
delete, and list-again sequence through MCP:

```bash
set -o pipefail
pass-cli run --env-file ./secmanpp.env -- \
  ./scripts/test/test-e2e-mcp-requirement-use-case-lifecycle.sh \
  --verbose
```

The delegated identity can instead come from `SECMAN_MCP_USER_EMAIL`, with
`SECMAN_ADMIN_EMAIL` as the fallback. To override it, pass a literal address to
`--user-email`; an outer shell cannot expand a Proton Pass variable before
`pass-cli run` resolves it. The driver generates unique fixture names, retains
the returned IDs, and its failure trap attempts to unassign and delete only
those IDs. A successful run ends with:

```text
MCP requirement/use-case lifecycle passed. Passed: 11, Failed: 0
```

Codex uses `.agents/skills/mcp-requirement-use-case-lifecycle/SKILL.md` and
Claude Code uses the mirrored
`.claude/skills/mcp-requirement-use-case-lifecycle/SKILL.md`. Both skills apply
the same cold-start, execution, cleanup, and verification contract.

## Curl-compatible call

Store credentials outside scripts and issue files. This example expects them in
environment variables populated by Proton Pass or another secret provider.

```bash
curl -fsS "$SECMAN_HOST/mcp" \
  -H 'Content-Type: application/json' \
  -H "X-MCP-API-Key: $SECMAN_MCP_KEY" \
  -H "X-MCP-User-Email: $SECMAN_USER_EMAIL" \
  --data '{"jsonrpc":"2.0","id":"list","method":"tools/call","params":{"name":"list_use_cases","arguments":{"page":0,"pageSize":50}}}'
```

For Proton Pass conventions, see [PASS_CLI.md](PASS_CLI.md). For the full MCP
transport, error, and troubleshooting reference, see [MCP.md](MCP.md).

## Paperclip agent contract

A Paperclip requirement-curator agent should receive:

- a delegated SecMan MCP connection, with the API key held as a Paperclip secret;
- `REQUIREMENTS_READ` and `REQUIREMENTS_WRITE`, plus
  `REQUIREMENTS_DELETE` only when cleanup is part of its job;
- the exact two-step assignment rule above;
- a requirement to persist returned IDs in the Paperclip issue before the next mutation;
- a cleanup policy that uses those IDs and requires explicit confirmation.

Recommended acceptance criteria for an automated issue:

1. `add_requirement` returns an ID.
2. `create_use_case` or `list_use_cases` resolves one use-case ID.
3. `set_requirement_use_cases` returns exactly that ID.
4. `get_requirements` shows the same relationship in `useCaseAssignments`.
5. Any requested update is re-read and compared field by field.

Do not put MCP keys, passwords, or sensitive requirement evidence into a
Paperclip issue. See [PAPERCLIP.md](PAPERCLIP.md) for the broader execution and
review boundary.
