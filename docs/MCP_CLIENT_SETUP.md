# Connect SecMan MCP clients

SecMan exposes a Streamable HTTP MCP endpoint at:

```text
https://secman.covestro.net/mcp
```

Replace the hostname when connecting to another SecMan deployment. Keep the
`/mcp` path. Production clients should use HTTPS.

Every `tools/list` and `tools/call` request needs both of these headers:

| Header | Value |
|---|---|
| `X-MCP-API-Key` | A SecMan MCP API key, shown only once when it is created |
| `X-MCP-User-Email` | The email address of the SecMan user whose roles and data access apply |

The effective access is the intersection of the API key permissions and the
delegated user's role permissions. The key must have delegation enabled, and
the email domain must be allowed by the key. Use a least-privilege key for each
automation; reserve **All permissions** for an admin-controlled integration
that genuinely needs every MCP operation.

## Prepare credentials

The examples use these environment variables:

```bash
export SECMAN_MCP_URL="https://secman.covestro.net/mcp"
export SECMAN_MCP_KEY="sk-replace-with-the-key-shown-by-secman"
export SECMAN_MCP_USER_EMAIL="agent@example.com"
```

Do not commit real values. If Proton Pass is used, add equivalent mappings to a
local, gitignored `secmanpp.env`, then start the client through Proton Pass:

```bash
pass-cli run --env-file /absolute/path/to/secmanpp.env -- codex
pass-cli run --env-file /absolute/path/to/secmanpp.env -- cursor
pass-cli run --env-file /absolute/path/to/secmanpp.env -- claude
```

Use the executable name installed on the machine. A GUI application opened
from Finder, the Dock, or a desktop shortcut may not inherit shell variables;
launch it from the Proton Pass command or configure the client-specific bridge
described below.

Before configuring a client, verify the endpoint and credentials without
printing the key:

```bash
curl --fail-with-body --silent --show-error "$SECMAN_MCP_URL" \
  -H 'Content-Type: application/json' \
  -H "X-MCP-API-Key: $SECMAN_MCP_KEY" \
  -H "X-MCP-User-Email: $SECMAN_MCP_USER_EMAIL" \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools | length'
```

## Cursor

Create `~/.cursor/mcp.json` for a personal configuration available in every
workspace, or `.cursor/mcp.json` for a project configuration:

```json
{
  "mcpServers": {
    "secman": {
      "url": "${env:SECMAN_MCP_URL}",
      "headers": {
        "X-MCP-API-Key": "${env:SECMAN_MCP_KEY}",
        "X-MCP-User-Email": "${env:SECMAN_MCP_USER_EMAIL}"
      }
    }
  }
}
```

Restart Cursor after changing its environment or configuration. Open
**Customize > MCPs** to enable SecMan. The Cursor CLI uses the same file; run
`agent mcp list` to inspect the connection. For failures, open the Output panel
and select **MCP Logs**.

Cursor documents remote URLs, headers, environment interpolation, and both
configuration locations in its [MCP documentation](https://cursor.com/docs/mcp).

## Codex CLI and Codex app

Add this to `~/.codex/config.toml`:

```toml
[mcp_servers.secman]
url = "https://secman.covestro.net/mcp"
env_http_headers = { "X-MCP-API-Key" = "SECMAN_MCP_KEY", "X-MCP-User-Email" = "SECMAN_MCP_USER_EMAIL" }
```

`env_http_headers` stores environment-variable names rather than secret values.
Start Codex in an environment containing those variables, then run:

```bash
codex mcp list
```

The Codex app and CLI share `config.toml`. Start a new task after changing MCP
configuration so the server and its tools are loaded. See the official
[Codex MCP configuration reference](https://developers.openai.com/codex/mcp/)
for server settings and credential helpers.

## Claude Code

For a shareable project configuration, create `.mcp.json` but do not commit
secrets:

```json
{
  "mcpServers": {
    "secman": {
      "type": "http",
      "url": "${SECMAN_MCP_URL:-https://secman.covestro.net/mcp}",
      "headers": {
        "X-MCP-API-Key": "${SECMAN_MCP_KEY}",
        "X-MCP-User-Email": "${SECMAN_MCP_USER_EMAIL}"
      }
    }
  }
}
```

Claude Code expands environment variables in HTTP `url` and `headers` fields.
Run Claude Code from the configured project, approve the project MCP server,
then check it:

```bash
claude mcp list
claude mcp get secman
```

For a private machine-local configuration instead, use the CLI command below.
It saves the expanded header values in Claude Code's local configuration, so
prefer `.mcp.json` plus environment variables when the file can be kept free of
secrets.

```bash
claude mcp add --transport http --scope user secman "$SECMAN_MCP_URL" \
  --header "X-MCP-API-Key: $SECMAN_MCP_KEY" \
  --header "X-MCP-User-Email: $SECMAN_MCP_USER_EMAIL"
```

Use `/mcp` inside Claude Code to see connection status. Anthropic documents
HTTP transport, headers, scopes, and environment expansion in the
[Claude Code MCP guide](https://code.claude.com/docs/en/mcp).

## Claude and Claude Desktop

### Claude web or the Claude Desktop custom-connector UI

Claude's remote custom connectors are configured under **Customize >
Connectors > Add custom connector**. They connect from Anthropic's cloud and
currently expose OAuth client settings, but not arbitrary static request
headers. SecMan currently requires the two static headers above and does not
provide MCP OAuth, so its endpoint cannot be connected directly through that
UI. Do not configure it as an unauthenticated connector.

This direct option becomes usable when SecMan implements OAuth for MCP, or when
the connector UI supports custom headers. The endpoint must also be reachable
from Anthropic's cloud. See Anthropic's [custom connector setup and network
requirements](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp).

### Claude Desktop local bridge

Claude Desktop can instead start a local stdio bridge. This keeps the request
origin on the workstation and lets Proton Pass provide the required headers.
First find the absolute executable paths:

```bash
command -v pass-cli
command -v npx
```

Then add the following to `claude_desktop_config.json`, replacing both example
paths with the command output and replacing the `secmanpp.env` path:

```json
{
  "mcpServers": {
    "secman": {
      "command": "/absolute/path/to/pass-cli",
      "args": [
        "run",
        "--env-file",
        "/absolute/path/to/secmanpp.env",
        "--",
        "/absolute/path/to/npx",
        "-y",
        "mcp-remote",
        "https://secman.covestro.net/mcp",
        "--header",
        "X-MCP-API-Key:${SECMAN_MCP_KEY}",
        "--header",
        "X-MCP-User-Email:${SECMAN_MCP_USER_EMAIL}"
      ]
    }
  }
}
```

Configuration file locations:

| Platform | Location |
|---|---|
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |
| Linux | `~/.config/Claude/claude_desktop_config.json` |

Restart Claude Desktop completely after editing the file. `mcp-remote` expands
the `${...}` values after Proton Pass has populated the bridge process
environment. Its [upstream documentation](https://github.com/punkpeye/mcp-remote)
describes custom headers and the stdio-to-HTTP bridge. Pin an approved package
version instead of `-y mcp-remote` for managed production workstations.

## Verify the SecMan integration

After the client reports that `secman` is connected, start with read-only
prompts:

```text
Use SecMan to list the available use cases.
Use SecMan to list open risk assessments for use case 12.
Use SecMan to show my security statistics.
```

Then verify a tool needed by the intended workflow. For example, a requirements
manager can ask:

```text
Use SecMan to add a draft requirement, assign it to use case 12, read it back,
and do not delete or publish anything.
```

The client can only discover tools allowed by both the key and delegated user.
Consult these guides for task-level inputs and examples:

- [Complete MCP reference](MCP.md)
- [Requirement and use-case management](MCP_REQUIREMENT_MANAGEMENT.md)
- [Risk-assessment lifecycle](MCP_RISK_ASSESSMENT_LIFECYCLE.md)
- [Statistics](MCP_STATISTICS.md)
- [Paperclip automation](PAPERCLIP_RISK_ASSESSMENT_AUTOMATION.md)

## Troubleshooting

| Symptom | Check |
|---|---|
| `401 Unauthorized` | The key value is correct, active, and not expired. Confirm the client inherited the environment variables. |
| `X-MCP-User-Email is required` | Configure the email header on the server entry, not only on a test request. |
| `403 Forbidden` | Delegation is enabled, the email domain is allowed, the user exists and is active, and both the key and role grant the operation. |
| A tool is missing | The key lacks its list permission or the delegated role does not imply it. Compare against [MCP permission groups](MCP.md#permission-groups--tools). |
| Tool is listed but call is denied | The tool's execution-time role check is stricter than discovery. See the Roles column in the [tool reference](MCP.md#tool-reference). |
| Desktop client cannot start the bridge | Use absolute paths for `pass-cli`, `npx`, and `secmanpp.env`; authenticate `pass-cli`; then restart the desktop application. |
| Claude custom connector cannot authenticate | Use Claude Code or the Claude Desktop local bridge until SecMan supports MCP OAuth. |

