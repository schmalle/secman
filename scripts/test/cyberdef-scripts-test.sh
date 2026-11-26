#!/usr/bin/env bash
# Self-test for scripts/cyberdef/: runs every script against a local stub MCP server
# (python3 stdlib, loopback only), so it needs no SecMan stack and no credentials.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIR="$REPO_ROOT/scripts/cyberdef"
WORK="$(mktemp -d)"
STUB_PID=""
cleanup() { [[ -n "$STUB_PID" ]] && kill "$STUB_PID" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

failures=0
pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1" >&2; failures=$((failures + 1)); }
# Runs a command quietly and records it as one named pass/fail.
expect() { local name="$1"; shift; if "$@" >/dev/null; then pass "$name"; else fail "$name"; fi; }

for tool in curl jq python3; do command -v "$tool" >/dev/null || { echo "skip: $tool missing" >&2; exit 0; }; done

# --- stub MCP server -----------------------------------------------------------------
cat >"$WORK/stub.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = sys.argv[1]
EVENTS = [{"id": i, "ticketId": 40 + i, "ticketKey": f"CD-2026-{40 + i:06d}", "eventType": "COMMENT",
           "actorKind": "OWNER", "actorUsername": "owner", "toStatus": None, "comment": f"note {i}"} for i in range(1, 6)]

def create(tickets, delivery):
    results = []
    for index, t in enumerate(tickets):
        dry = delivery.get("dryRun", False)
        to = delivery.get("recipients") or ["owner@example.test"]
        if t.get("awsAccountId") == "000000000000":
            results.append({"index": index, "outcome": "REJECTED", "errorCode": "UNROUTABLE_ACCOUNT", "dryRun": dry,
                            "errors": [{"field": "awsAccountId", "code": "UNROUTABLE_ACCOUNT", "message": "nobody"}]})
        else:
            results.append({"index": index, "outcome": "WOULD_CREATE" if dry else "CREATED", "recipients": to,
                            "dryRun": dry, "ticketKey": None if dry else f"CD-2026-{index:06d}", "errors": []})
    count = lambda o: sum(1 for r in results if r["outcome"] in o)
    return {"dryRun": delivery.get("dryRun", False), "created": count({"CREATED", "WOULD_CREATE"}),
            "duplicates": 0, "rejected": count({"REJECTED"}), "results": results}

def tool_result(name, args):
    if name == "create_cyberdef_ticket":
        return create([args["ticket"]], args.get("delivery", {}))["results"][0]
    if name == "create_cyberdef_tickets":
        return create(args["tickets"], args.get("delivery", {}))
    if name == "get_cyberdef_ticket_updates":
        page = [e for e in EVENTS if e["id"] > args["sinceEventId"]][: args["limit"]]
        last = page[-1]["id"] if page else args["sinceEventId"]
        return {"events": page, "nextCursor": last, "hasMore": any(e["id"] > last for e in EVENTS)}
    if name == "set_cyberdef_ticket_status" and args.get("expectedVersion") == 99:
        return None
    return {"echo": args}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        with open(LOG, "a") as f:
            f.write(json.dumps({"key": self.headers.get("X-MCP-API-Key"), "user": self.headers.get("X-MCP-User-Email"),
                                "body": body}) + "\n")
        name, args = body["params"]["name"], body["params"]["arguments"]
        result = tool_result(name, args)
        if result is None:
            reply = {"jsonrpc": "2.0", "id": body["id"], "error": {"code": -32603, "message": "VERSION_CONFLICT: changed"}}
        else:
            reply = {"jsonrpc": "2.0", "id": body["id"],
                     "result": {"content": [{"type": "text", "text": json.dumps(result)}], "isError": False}}
        data = json.dumps(reply).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

server = HTTPServer(("127.0.0.1", 0), Handler)
print(server.server_address[1], flush=True)
server.serve_forever()
PY
python3 -I "$WORK/stub.py" "$WORK/requests.jsonl" >"$WORK/port" &
STUB_PID=$!
for _ in $(seq 50); do [[ -s "$WORK/port" ]] && break; sleep 0.1; done
export SECMAN_BACKEND_URL="http://127.0.0.1:$(cat "$WORK/port")"
export SECMAN_MCP_KEY="test-key-not-a-secret"
export SECMAN_CYBERDEF_EMAIL="soc@example.test"
export NO_PROXY="127.0.0.1" no_proxy="127.0.0.1"
last_request() { tail -n 1 "$WORK/requests.jsonl"; }
exit_code() { set +e; "$@" >"$WORK/out" 2>"$WORK/err"; local rc=$?; set -e; echo "$rc"; }

# --- --print-request works offline -----------------------------------------------------
out="$(env -u SECMAN_BACKEND_URL -u SECMAN_MCP_KEY "$DIR/send-ticket.sh" --print-request --dry-run \
    --account 123456789012 --title t --severity LOW --description d --to "a@soc.example,b@soc.example")"
expect "print-request needs no credentials and masks the key" \
    jq -e '.headers["X-MCP-API-Key"] == "***" and .body.params.arguments.delivery.dryRun == true
           and .body.params.arguments.delivery.recipients == ["a@soc.example","b@soc.example"]' <<<"$out"
expect "print-request sends nothing" test ! -s "$WORK/requests.jsonl"

# --- send-ticket: dry run and recipient override ---------------------------------------
out="$("$DIR/send-ticket.sh" --file "$DIR/examples/ticket.json" --dry-run --to analyst@soc.example)"
expect "dry run returns WOULD_CREATE with the named recipient" \
    jq -e '.outcome == "WOULD_CREATE" and .recipients == ["analyst@soc.example"]' <<<"$out"
expect "dry run and recipients reach the server; key travels in the header" \
    jq -e '.key == "test-key-not-a-secret" and .user == "soc@example.test"
           and .body.params.name == "create_cyberdef_ticket"
           and .body.params.arguments.delivery.dryRun == true
           and .body.params.arguments.ticket.externalReference == "SOC-2026-4711"' <<<"$(last_request)"
expect "without --dry-run no dryRun flag is sent" bash -c \
    "'$DIR/send-ticket.sh' --file '$DIR/examples/ticket.json' >/dev/null && \
     tail -n1 '$WORK/requests.jsonl' | jq -e '.body.params.arguments.delivery | has(\"dryRun\") | not' >/dev/null"
expect "a rejected ticket exits 3" test "$(exit_code "$DIR/send-ticket.sh" --account 000000000000 --title t --severity LOW --description d)" = 3
expect "an invalid recipient is refused locally with exit 2" \
    test "$(exit_code "$DIR/send-ticket.sh" --file "$DIR/examples/ticket.json" --to not-an-address)" = 2
expect "missing required fields exit 2" test "$(exit_code "$DIR/send-ticket.sh" --title only)" = 2

# --- send-tickets: chunking, batch-wide flags ------------------------------------------
jq -n '[range(150) | {externalReference:"R\(.)", awsAccountId:"123456789012", title:"t", severity:"LOW", description:"d"}]' \
    >"$WORK/many.json"
: >"$WORK/requests.jsonl"
"$DIR/send-tickets.sh" --file "$WORK/many.json" --dry-run --to analyst@soc.example >"$WORK/out" 2>"$WORK/err"
expect "150 tickets go out in two calls of at most 100" \
    test "$(jq -s 'map(.body.params.arguments.tickets | length)' "$WORK/requests.jsonl" | jq -c .)" = "[100,50]"
expect "batch-wide dryRun and recipients are sent" \
    jq -e '.body.params.arguments.delivery == {dryRun: true, recipients: ["analyst@soc.example"]}' \
    <<<"$(head -n 1 "$WORK/requests.jsonl")"
expect "result indexes refer to the file, not the chunk" test "$(tail -n 1 "$WORK/out" | jq .index)" = 149
expect "summary names the dry run" grep -q "150 would be created" "$WORK/err"

# --- ticket-updates: cursor, paging, handler failure -----------------------------------
STATE="$WORK/state/cursor"
"$DIR/ticket-updates.sh" --state "$STATE" --limit 2 >"$WORK/out"
expect "all events are read across pages" test "$(wc -l <"$WORK/out" | tr -d ' ')" = 5
expect "cursor is saved after the last event" test "$(cat "$STATE")" = 5
"$DIR/ticket-updates.sh" --state "$STATE" >"$WORK/out"
expect "a second run sees nothing new" test ! -s "$WORK/out"
cat >"$WORK/handler.sh" <<'EOF'
#!/usr/bin/env bash
[[ "$(jq .id)" != 3 ]]
EOF
chmod +x "$WORK/handler.sh"
expect "a failing handler stops the run with exit 1" \
    test "$(exit_code "$DIR/ticket-updates.sh" --state "$STATE" --since 0 --on-event "$WORK/handler.sh")" = 1
expect "the cursor stays before the failed event" test "$(cat "$STATE")" = 2

# --- ticket-status / my-tickets --------------------------------------------------------
"$DIR/ticket-status.sh" close --id 42 --dry-run >/dev/null
expect "close --dry-run sends dryRun to the defender tool" \
    jq -e '.body.params.name == "set_cyberdef_ticket_status"
           and .body.params.arguments == {id:42, toStatus:"CLOSED", dryRun:true}' <<<"$(last_request)"
expect "an MCP error exits 1 and shows the stable code" bash -c \
    "! '$DIR/ticket-status.sh' reopen --id 1 --comment x --expected-version 99 2>'$WORK/err' >/dev/null && grep -q VERSION_CONFLICT '$WORK/err'"
"$DIR/my-tickets.sh" reopen --id 7 --comment "still public" --as owner@example.test >/dev/null
expect "owners use the owner tool as themselves" \
    jq -e '.user == "owner@example.test" and .body.params.name == "update_my_security_ticket_status"
           and .body.params.arguments.toStatus == "OPEN"' <<<"$(last_request)"
expect "owners must comment when closing" test "$(exit_code "$DIR/my-tickets.sh" close --id 7)" = 2

# --- static checks -----------------------------------------------------------------------
for script in "$DIR"/*.sh "$DIR"/examples/*.sh; do
    expect "bash -n $(basename "$script")" bash -n "$script"
done
if command -v shellcheck >/dev/null; then
    expect "shellcheck" shellcheck -x "$DIR"/*.sh "$DIR"/examples/*.sh
fi
expect "the API key never appears on a command line" bash -c "! grep -n 'X-MCP-API-Key: \${SECMAN_MCP_KEY}' '$DIR'/*.sh"

if (( failures > 0 )); then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "all cyberdef script checks passed"
