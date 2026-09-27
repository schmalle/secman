#!/usr/bin/env bash
# Query SecMan vulnerabilities through its MCP Streamable HTTP endpoint.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  query-vulnerabilities.sh --url URL --user-email EMAIL [options]

Required:
  --url URL              SecMan base URL or full /mcp endpoint
  --user-email EMAIL     Identity to delegate this request to
  --key KEY              MCP API key (or set SECMAN_MCP_KEY)

Filters:
  --page N               Zero-based page (default 0)
  --page-size N          Results per page, 1-500 (default 100)
  --cve-id TEXT          CVE substring filter
  --severity LEVEL       Repeatable: Critical, High, Medium, Low, Info
  --asset-id ID          Restrict to one asset
  --start-date ISO       Scan timestamp lower bound
  --end-date ISO         Scan timestamp upper bound
  --include-excepted     Include findings covered by active exceptions
  --include-installers   Include installer and setup-artifact findings
  --insecure             Let curl accept an untrusted/self-signed TLS certificate
  -h, --help             Show this help

Output is the get_vulnerabilities result as formatted JSON. Requires curl and jq.
USAGE
}

url=""
api_key="${SECMAN_MCP_KEY:-}"
user_email=""
insecure=false
page=0
page_size=100
args='{}'
severity='[]'

add_arg() {
  local key="$1" value="$2"
  args="$(jq -c --arg key "$key" --argjson value "$value" '. + {($key):$value}' <<< "$args")"
}

while (($#)); do
  case "$1" in
    --url) (($# >= 2)) || { echo "Missing value for --url" >&2; exit 2; }; url="$2"; shift 2 ;;
    --key) (($# >= 2)) || { echo "Missing value for --key" >&2; exit 2; }; api_key="$2"; shift 2 ;;
    --user-email) (($# >= 2)) || { echo "Missing value for --user-email" >&2; exit 2; }; user_email="$2"; shift 2 ;;
    --page) (($# >= 2)) || { echo "Missing value for --page" >&2; exit 2; }; page="$2"; shift 2 ;;
    --page-size) (($# >= 2)) || { echo "Missing value for --page-size" >&2; exit 2; }; page_size="$2"; shift 2 ;;
    --cve-id) (($# >= 2)) || { echo "Missing value for --cve-id" >&2; exit 2; }; add_arg cveId "$(jq -Rn --arg v "$2" '$v')"; shift 2 ;;
    --severity) (($# >= 2)) || { echo "Missing value for --severity" >&2; exit 2; }; severity="$(jq -c --arg v "$2" '. + [$v]' <<< "$severity")"; shift 2 ;;
    --asset-id) (($# >= 2)) || { echo "Missing value for --asset-id" >&2; exit 2; }; add_arg assetId "$(jq -n --argjson v "$2" '$v')"; shift 2 ;;
    --start-date) (($# >= 2)) || { echo "Missing value for --start-date" >&2; exit 2; }; add_arg startDate "$(jq -Rn --arg v "$2" '$v')"; shift 2 ;;
    --end-date) (($# >= 2)) || { echo "Missing value for --end-date" >&2; exit 2; }; add_arg endDate "$(jq -Rn --arg v "$2" '$v')"; shift 2 ;;
    --include-excepted) add_arg includeExcepted true; shift ;;
    --include-installers) add_arg includeInstallerFindings true; shift ;;
    --insecure) insecure=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -n "$url" ]] || { echo "--url is required" >&2; exit 2; }
[[ -n "$api_key" ]] || { echo "--key or SECMAN_MCP_KEY is required" >&2; exit 2; }
[[ -n "$user_email" ]] || { echo "--user-email is required: SecMan requires delegated identity for data access" >&2; exit 2; }
[[ "$url" =~ ^https?://[^[:space:]]+$ ]] || { echo "--url must be an http(s) URL" >&2; exit 2; }
[[ "$page" =~ ^[0-9]+$ ]] || { echo "--page must be a non-negative integer" >&2; exit 2; }
[[ "$page_size" =~ ^[0-9]+$ ]] && ((page_size >= 1 && page_size <= 500)) || { echo "--page-size must be between 1 and 500" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

args="$(jq -c --argjson page "$page" --argjson pageSize "$page_size" --argjson severity "$severity" \
  '. + {page:$page,pageSize:$pageSize} + (if ($severity|length)>0 then {severity:$severity} else {} end)' <<< "$args")"
endpoint="${url%/}"
[[ "$endpoint" == */mcp ]] || endpoint="$endpoint/mcp"
curl_tls=()
if [[ "$insecure" == true ]]; then
  curl_tls+=(--insecure)
  echo "Warning: TLS certificate verification is disabled for this request." >&2
fi

request_id=0
rpc() {
  local method="$1" params="$2" delegated="${3:-false}" response
  request_id=$((request_id + 1))
  local headers=(-H 'Content-Type: application/json' -H "X-MCP-API-Key: $api_key")
  [[ "$delegated" == true ]] && headers+=(-H "X-MCP-User-Email: $user_email")
  local payload
  payload="$(jq -nc --argjson id "$request_id" --arg method "$method" --argjson params "$params" \
    '{jsonrpc:"2.0",id:$id,method:$method,params:$params}')"
  if ! response="$(curl --silent --show-error --fail-with-body --max-time 60 "${curl_tls[@]}" \
      -X POST "$endpoint" "${headers[@]}" --data "$payload")"; then
    echo "MCP request '$method' failed while contacting $endpoint" >&2
    exit 1
  fi
  if jq -e '.error != null' >/dev/null <<< "$response"; then
    jq -r '"MCP error [" + ((.error.code // "unknown")|tostring) + "]: " + (.error.message // "request failed")' <<< "$response" >&2
    exit 1
  fi
  printf '%s' "$response"
}

rpc initialize '{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"secman-vulnerability-query","version":"1.0"}}' >/dev/null
reply="$(rpc tools/list '{}' true)"
jq -e '.result.tools | any(.name == "get_vulnerabilities")' >/dev/null <<< "$reply" || {
  echo "The MCP key cannot list get_vulnerabilities. Check its permissions and delegated user." >&2
  exit 1
}
reply="$(rpc tools/call "$(jq -nc --argjson arguments "$args" '{name:"get_vulnerabilities",arguments:$arguments}')" true)"
result="$(jq -c '.result.content // empty' <<< "$reply")"
if [[ "$result" == "null" || -z "$result" ]]; then
  echo "MCP response did not include tool result content" >&2
  exit 1
fi
if jq -e 'type == "array" and length > 0 and .[0].text != null' >/dev/null <<< "$result"; then
  tool_json="$(jq -r '.[0].text' <<< "$result")"
  if jq -e 'has("error")' >/dev/null <<< "$tool_json"; then
    jq -c . <<< "$tool_json" >&2
    exit 1
  fi
  jq . <<< "$tool_json"
else
  jq . <<< "$result"
fi
