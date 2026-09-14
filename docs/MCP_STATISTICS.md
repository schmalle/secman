# MCP statistics for automation

SecMan exposes three read-only aggregate tools for dashboards, Paperclip
heartbeats, and other MCP automation. They return bounded summaries rather than
unbounded entity lists and require mandatory user delegation.

## Tool selection

| Tool | Use it for | Required API-key permission | Delegated roles | Data scope |
|---|---|---|---|---|
| `get_secman_statistics` | Estate-wide operational overview | `SYSTEM_INFO` | ADMIN | All SecMan data |
| `get_my_security_statistics` | Vulnerability posture for the acting agent | `VULNERABILITIES_READ` | ADMIN, VULN, SECCHAMPION | ADMIN/SECCHAMPION: all assets; VULN: accessible assets only |
| `get_risk_assessment_statistics` | Assessment workload and completion monitoring | `ASSESSMENTS_READ` | ADMIN, RISK, SECCHAMPION | ADMIN/SECCHAMPION: all assessments; RISK: assessments where the user is assessor, requestor, or respondent |

Effective permission remains the intersection of the MCP key permissions and
the delegated user's role-implied permissions. A powerful key therefore does
not turn a delegated VULN user into an administrator.

## `get_secman_statistics`

This ADMIN-only tool takes no arguments. It returns:

- `assets` and `vulnerabilities`: current row counts;
- `users.total`, `users.loggedInLast7Days`, and `users.neverLoggedIn`;
- `requirements` and `useCases`;
- `riskAssessments.total`, `.started`, and `.completed`;
- `generatedAt`: the UTC instant at which the summary was generated.

`loggedInLast7Days` uses a rolling seven-day window ending at request time and
counts users whose `lastLogin` was updated by a successful password or OAuth
login. It is not a count of sessions or MCP calls. The response contains no
usernames, email addresses, asset names, or vulnerability details.

Request:

```json
{
  "jsonrpc":"2.0",
  "id":"paperclip-estate-summary",
  "method":"tools/call",
  "params":{"name":"get_secman_statistics","arguments":{}}
}
```

Representative tool content:

```json
{
  "generatedAt":"2026-09-13T08:15:00Z",
  "assets":1250,
  "vulnerabilities":8402,
  "users":{"total":118,"loggedInLast7Days":42,"neverLoggedIn":9},
  "requirements":260,
  "useCases":14,
  "riskAssessments":{"total":73,"started":11,"completed":60}
}
```

## `get_my_security_statistics`

This tool also takes no arguments. It returns `scope`, `assetCount`, and a
vulnerability summary with `total` plus `bySeverity` buckets `CRITICAL`,
`HIGH`, `MEDIUM`, `LOW`, `UNKNOWN`, and `OTHER`.

For a delegated VULN user, the aggregate is computed only over asset IDs already
authorized by SecMan's unified asset-access rules. No-access users get zeros;
SecMan does not fall back to global results. ADMIN and SECCHAMPION users receive
`scope: "ALL_ASSETS"`; other allowed callers receive
`scope: "DELEGATED_USER_ASSETS"`.

```json
{
  "jsonrpc":"2.0",
  "id":"paperclip-posture",
  "method":"tools/call",
  "params":{"name":"get_my_security_statistics","arguments":{}}
}
```

Representative tool content:

```json
{
  "generatedAt":"2026-09-13T08:15:00Z",
  "scope":"DELEGATED_USER_ASSETS",
  "assetCount":18,
  "vulnerabilities":{
    "total":91,
    "bySeverity":{"CRITICAL":2,"HIGH":14,"MEDIUM":39,"LOW":31,"UNKNOWN":3,"OTHER":2}
  }
}
```

## `get_risk_assessment_statistics`

The optional `useCaseName` argument is an exact, case-insensitive use-case name
(maximum 255 characters). Omitting it summarizes all assessments visible to the
delegated user. The response returns `total` and `byStatus` counts for
`STARTED`, `COMPLETED`, and `OTHER`.

```json
{
  "jsonrpc":"2.0",
  "id":"paperclip-cloud-workload",
  "method":"tools/call",
  "params":{
    "name":"get_risk_assessment_statistics",
    "arguments":{"useCaseName":"Cloud workload"}
  }
}
```

Representative tool content:

```json
{
  "generatedAt":"2026-09-13T08:15:00Z",
  "useCaseName":"Cloud workload",
  "total":16,
  "byStatus":{"STARTED":5,"COMPLETED":10,"OTHER":1}
}
```

Use this aggregate for a cheap heartbeat decision, then call
`list_risk_assessments` only when a count requires action. The list call returns
the assessment IDs needed for `get_risk_assessment_questionnaire`, reminders,
answers, submission, and evaluation.

## Paperclip operating pattern

1. A company-wide admin observer calls `get_secman_statistics` on a low-frequency
   schedule and opens an issue only when an agreed threshold changes.
2. A vulnerability agent calls `get_my_security_statistics`. Alert on high or
   critical counts, then retrieve details using existing scoped vulnerability
   tools.
3. A risk coordinator calls `get_risk_assessment_statistics`, optionally per use
   case. When `STARTED` is non-zero, it calls `list_risk_assessments` with the
   same use-case filter and follows the documented assessment lifecycle.
4. Store only aggregate results in Paperclip unless the issue needs specific
   SecMan object IDs. Keep answer comments and user data in SecMan.

Each Paperclip agent should have a distinct delegated SecMan identity and the
smallest key permission set shown above. Send `X-MCP-User-Email` on every
`tools/list` and `tools/call`. Treat HTTP 200 with `result.isError:true` as a
failed tool call. Do not retry authorization or validation failures unchanged.

## Related documentation

- [MCP integration and complete tool reference](MCP.md)
- [Paperclip risk-assessment automation](PAPERCLIP_RISK_ASSESSMENT_AUTOMATION.md)
- [Paperclip security-work management](PAPERCLIP.md)
- [MCP risk-assessment lifecycle](MCP_RISK_ASSESSMENT_LIFECYCLE.md)
