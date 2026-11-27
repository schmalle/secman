# CYBERDEF ticket scripts

Shell clients for the [Cyber Defence account-ticket MCP tools](../../docs/CYBERDEF_TICKETS.md).
They use `bash`, `curl` and `jq`. `pass-cli` supplies credentials when they are missing from
the environment.

| Script | Side | Purpose |
|---|---|---|
| `send-ticket.sh` | Cyber Defence | Submit one ticket from flags and/or a JSON file |
| `send-tickets.sh` | Cyber Defence | Submit many tickets from a JSON array, 100 per call |
| `ticket-updates.sh` | Cyber Defence | Read owners' answers and status changes by cursor; optional handler per event |
| `ticket-status.sh` | Cyber Defence | `list`, `get`, `close`, `reopen`, `comment` on any ticket |
| `my-tickets.sh` | Account owner | List, inspect and comment in the caller's read scope. Close and reopen as an owner. |
| `examples/` | — | `ticket.json`, `tickets.json`, and `on-event.sh` (a handler to adapt) |

## Credentials

The scripts read `SECMAN_BACKEND_URL` and `SECMAN_MCP_KEY` from the environment. If either
is missing they re-run themselves under `pass-cli run --env-file secmanpp.env`, the same way
the other repository scripts do. The API key reaches curl through a header file and never
appears on a command line or in `--print-request` output.

The MCP key needs **User Delegation** and, depending on the side, `TICKETS_SUBMIT` or
`TICKETS_RESPOND`. The scripts act as the delegated user given by `--as EMAIL`, then
`SECMAN_CYBERDEF_EMAIL`, then `SECMAN_ADMIN_EMAIL`. That user must hold `CYBERDEF` (or
`ADMIN`) for the Cyber Defence scripts. For `my-tickets.sh`, pass `--as` the account owner.

## Dry run and target addresses

Use the delivery and preview flags as follows.

- `--dry-run` sends the request with `dryRun: true`. SecMan validates the ticket and resolves
  the recipients and due date, then stores nothing and sends no mail.
  - `send-ticket.sh` / `send-tickets.sh` return `WOULD_CREATE` with the recipient list,
    `DUPLICATE` for an existing reference, or `REJECTED` with the reason.
  - `ticket-status.sh close|reopen` and `my-tickets.sh close|reopen` return the status change
    and `wouldNotify` instead of applying it.
- `--to ADDR[,ADDR]` on the send scripts (repeatable, max 20) mails these addresses instead of the people
  allocated to the account. Each address must belong to a SecMan user or user mapping, or
  sit on a domain listed in `secman.cyberdef.recipient-override.allowed-domains`. Otherwise
  the ticket is rejected with `RECIPIENT_NOT_ALLOWED`. Being mailed grants no access: the
  ticket stays visible only to people with access to the account.
- `--print-request` is a local preview. It prints the request that would be sent (key
  masked) and exits without network access or credentials.

Combine them to test safely. For example, send a ticket for a real account to your own
mailbox: `--to you@yourcompany.example`. Or check a whole batch first:
`send-tickets.sh --file findings.json --dry-run`.

## Examples

```bash
# Preview locally, then dry-run on the server, then send for real
scripts/cyberdef/send-ticket.sh --file scripts/cyberdef/examples/ticket.json --print-request
scripts/cyberdef/send-ticket.sh --file scripts/cyberdef/examples/ticket.json --dry-run
scripts/cyberdef/send-ticket.sh --file scripts/cyberdef/examples/ticket.json

# Flags only, mailed to two analysts instead of the owners
scripts/cyberdef/send-ticket.sh --account 123456789012 --severity HIGH --ref SOC-4714 \
  --title "S3 bucket allows public listing" --description "Bucket policy grants s3:ListBucket to *." \
  --resource S3_BUCKET=prod-exports@eu-central-1 --to analyst1@soc.example,analyst2@soc.example

# Bulk, rerunnable: tickets with an externalReference come back as DUPLICATE on a rerun
scripts/cyberdef/send-tickets.sh --file findings.json --dry-run
scripts/cyberdef/send-tickets.sh --file findings.json > results.jsonl

# Handle answers: once, or continuously with a handler
scripts/cyberdef/ticket-updates.sh
scripts/cyberdef/ticket-updates.sh --follow --interval 120 --on-event scripts/cyberdef/examples/on-event.sh

# Verify an owner's fix: inspect, then reopen with a reason (dry run first)
scripts/cyberdef/ticket-status.sh get --key CD-2026-000042
scripts/cyberdef/ticket-status.sh reopen --id 42 --comment "Snapshot is still public" --dry-run
scripts/cyberdef/ticket-status.sh reopen --id 42 --comment "Snapshot is still public" --expected-version 3

# Owner side
scripts/cyberdef/my-tickets.sh list --status OPEN --as owner@yourcompany.example
scripts/cyberdef/my-tickets.sh close --id 42 --comment "Public share removed, credentials rotated" --as owner@yourcompany.example
```

## Output and exit codes

`send-ticket.sh` prints the per-ticket result. `send-tickets.sh` prints one JSON line per
ticket (`index` is the position in the file) and a summary on stderr. `ticket-updates.sh`
prints one JSON line per event, unless `--on-event` handles them.

| Exit | Meaning |
|---|---|
| 0 | success |
| 1 | runtime or MCP error; the message starts with the stable code, e.g. `VERSION_CONFLICT:` |
| 2 | usage error, detected locally before anything is sent |
| 3 | at least one ticket was rejected (see `errorCode` in the output) |

## The update cursor

`ticket-updates.sh` stores the last handled event id in
`${XDG_STATE_HOME:-~/.local/state}/secman-cyberdef/cursor` (override with `--state`).
It writes the file after each successfully handled event, by write-then-rename. If the
handler fails, the run stops and that event is handled again next time. `--since 0`
replays everything. Consumers that write downstream should deduplicate by event ID because
a crash after handler success and before the cursor write can replay that event.

## Tests

`scripts/test/cyberdef-scripts-test.sh` runs every script against a local stub MCP
server (python3 stdlib, loopback only). It checks dry-run and override propagation, local
validation, batching, cursor handling and exit codes. It needs no SecMan stack.
