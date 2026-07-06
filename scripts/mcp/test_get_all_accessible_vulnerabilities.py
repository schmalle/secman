#!/usr/bin/env python3
"""
Smoke test / ad-hoc query tool for the MCP tool `get_all_accessible_vulnerabilities`.

Calls the Streamable HTTP JSON-RPC endpoint (POST {SECMAN_HOST}/mcp), per the
"Programmatic example (Python)" pattern documented in docs/MCP.md.

Required environment variables (source via pass-cli — never hardcode):
    SECMAN_BACKEND_URL / SECMAN_HOST  Base host URL (either works; a bare
                                      hostname without a scheme is treated as https://)
    SECMAN_MCP_KEY                    MCP API key (X-MCP-API-Key)

Two modes:

1. Smoke-test suite (no arguments) — exercises ADMIN vs. scoped-user access
   control, severity filtering, and the missing-delegation error path. Reads
   the ADMIN/scoped-user emails from environment variables (source via
   pass-cli):
       SECMAN_ADMIN_EMAIL   Email of a delegated ADMIN user
       SECMAN_USER_EMAIL    Email of a delegated non-admin/scoped user

   Usage:
       python3 scripts/mcp/test_get_all_accessible_vulnerabilities.py

2. Ad-hoc query — the email of the user whose accessible vulnerabilities to
   query is passed on the commandline (never read from an environment
   variable in this mode). Prints a summary and, optionally, the matching
   vulnerabilities for that specific user.

   Usage:
       python3 scripts/mcp/test_get_all_accessible_vulnerabilities.py <email> \\
           [--severity CRITICAL,HIGH] [--include-excepted] [--limit N] [--csv | --output FILE]

   Examples:
       python3 scripts/mcp/test_get_all_accessible_vulnerabilities.py alice@example.com
       python3 scripts/mcp/test_get_all_accessible_vulnerabilities.py bob@example.com --severity CRITICAL --limit 50
       python3 scripts/mcp/test_get_all_accessible_vulnerabilities.py bob@example.com --csv > bob_vulns.csv
       python3 scripts/mcp/test_get_all_accessible_vulnerabilities.py bob@example.com --output bob_vulns.csv
"""

import argparse
import csv
import json
import os
import sys

import requests

TOOL_NAME = "get_all_accessible_vulnerabilities"


def require_env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        print(f"FAIL: missing required environment variable {name} (source it via pass-cli)")
        sys.exit(1)
    return value


def resolve_host() -> str:
    """SECMAN_BACKEND_URL takes precedence over SECMAN_HOST, matching the fallback
    convention in scripts/test/crowdstrike_vulnerability_match.py. A bare hostname
    (no scheme) is assumed to be https://."""
    raw = os.environ.get("SECMAN_BACKEND_URL") or os.environ.get("SECMAN_HOST")
    if not raw:
        print("FAIL: missing required environment variable SECMAN_BACKEND_URL or SECMAN_HOST (source it via pass-cli)")
        sys.exit(1)
    return raw if "://" in raw else f"https://{raw}"


def call_tool(host: str, api_key: str, email: str, arguments: dict) -> dict:
    """POST a JSON-RPC tools/call request and return the parsed 'result' or raise on transport error."""
    resp = requests.post(
        f"{host.rstrip('/')}/mcp",
        headers={
            "X-MCP-API-Key": api_key,
            "X-MCP-User-Email": email,
            "Content-Type": "application/json",
        } if email else {
            "X-MCP-API-Key": api_key,
            "Content-Type": "application/json",
        },
        json={
            "jsonrpc": "2.0",
            "id": "1",
            "method": "tools/call",
            "params": {"name": TOOL_NAME, "arguments": arguments},
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def extract_tool_result(rpc_response: dict) -> dict:
    """Unwrap the JSON-RPC envelope to get the tool's own result payload (success or error)."""
    if "error" in rpc_response:
        return {"_rpc_error": rpc_response["error"]}
    content = rpc_response["result"]["content"]
    if isinstance(content, list):
        # MCP content blocks: [{"type": "text", "text": "<json>"}]
        text = content[0]["text"]
        return json.loads(text) if isinstance(text, str) else text
    return content


def write_csv(fileobj, vulnerabilities: list) -> None:
    writer = csv.writer(fileobj)
    writer.writerow(["vulnerability_id", "severity", "asset_name", "asset_ip"])
    for vuln in vulnerabilities:
        asset = vuln.get("asset") or {}
        writer.writerow([vuln.get("vulnerabilityId"), vuln.get("cvssSeverity"), asset.get("name"), asset.get("ip")])


def run_adhoc_query(email: str, severity: list, include_excepted: bool, limit: int, as_csv: bool, output: str) -> int:
    """Query get_all_accessible_vulnerabilities for a single, commandline-supplied
    user email and print a summary. The email is never read from the environment
    here — it must be passed as a commandline argument so the caller always
    explicitly states whose accessible vulnerabilities are being queried."""
    host = resolve_host()
    api_key = require_env("SECMAN_MCP_KEY")

    arguments = {"includeExcepted": include_excepted, "limit": limit}
    if severity:
        arguments["severity"] = severity

    rpc_response = call_tool(host, api_key, email, arguments)
    result = extract_tool_result(rpc_response)

    if "_rpc_error" in result:
        print(f"FAIL: call for {email} returned RPC error: {result['_rpc_error']}")
        return 1

    if output:
        # Write the file directly rather than relying on shell redirection of stdout:
        # wrappers like `pass-cli run` merge the subprocess's stdout/stderr, so a
        # `> file.csv` redirect on the wrapped command would pick up warnings/summary too.
        with open(output, "w", newline="") as f:
            write_csv(f, result["vulnerabilities"])
        print(f"Wrote {result['returned']} rows to {output} "
              f"(total={result['total']} truncated={result['truncated']} exceptedFiltered={result['exceptedFiltered']})")
        return 0

    if as_csv:
        # Summary goes to stderr so stdout is clean CSV, suitable for redirecting to a file
        # (note: this doesn't help when running through pass-cli, which merges the streams —
        # use --output in that case).
        print(f"Accessible vulnerabilities for {email}: total={result['total']} returned={result['returned']} "
              f"truncated={result['truncated']} exceptedFiltered={result['exceptedFiltered']}", file=sys.stderr)
        write_csv(sys.stdout, result["vulnerabilities"])
        return 0

    print(f"Accessible vulnerabilities for {email}:")
    print(f"  total={result['total']} returned={result['returned']} truncated={result['truncated']} "
          f"exceptedFiltered={result['exceptedFiltered']}")
    for vuln in result["vulnerabilities"]:
        asset = vuln.get("asset") or {}
        print(f"  - {vuln.get('vulnerabilityId')} [{vuln.get('cvssSeverity')}] "
              f"asset={asset.get('name')} ({asset.get('ip')})")

    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Smoke test / ad-hoc query for the MCP tool get_all_accessible_vulnerabilities",
    )
    parser.add_argument(
        "email",
        nargs="?",
        help="Email of the user whose accessible vulnerabilities to query (ad-hoc mode). "
             "Omit to run the full ADMIN-vs-scoped-user smoke-test suite instead.",
    )
    parser.add_argument(
        "--severity",
        help="Comma-separated severity filter for ad-hoc mode, e.g. CRITICAL,HIGH",
    )
    parser.add_argument(
        "--include-excepted",
        action="store_true",
        help="Include vulnerabilities covered by active exceptions (ad-hoc mode).",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=5000,
        help="Max vulnerabilities to return (ad-hoc mode, default 5000).",
    )
    parser.add_argument(
        "--csv",
        action="store_true",
        help="Print the vulnerability list as CSV on stdout (ad-hoc mode). "
             "The summary line is printed to stderr so stdout stays valid CSV.",
    )
    parser.add_argument(
        "--output",
        metavar="FILE",
        help="Write the vulnerability list as CSV directly to FILE (ad-hoc mode), instead of stdout. "
             "Preferred over `--csv > file` when running through a wrapper (e.g. pass-cli run) "
             "that merges the subprocess's stdout and stderr.",
    )
    args = parser.parse_args()

    if args.email:
        severity = [s.strip().upper() for s in args.severity.split(",")] if args.severity else None
        return run_adhoc_query(args.email, severity, args.include_excepted, args.limit, args.csv, args.output)

    host = resolve_host()
    api_key = require_env("SECMAN_MCP_KEY")
    admin_email = require_env("SECMAN_ADMIN_EMAIL")
    user_email = require_env("SECMAN_USER_EMAIL")

    failures = []

    # 1. ADMIN call — expect success with the full response shape
    admin_rpc = call_tool(host, api_key, admin_email, {})
    admin_result = extract_tool_result(admin_rpc)
    expected_keys = {"vulnerabilities", "total", "returned", "truncated", "exceptedFiltered"}
    if "_rpc_error" in admin_result:
        failures.append(f"ADMIN call returned RPC error: {admin_result['_rpc_error']}")
    elif not expected_keys.issubset(admin_result.keys()):
        failures.append(f"ADMIN response missing keys, got: {sorted(admin_result.keys())}")
    else:
        print(f"PASS: admin call succeeded, total={admin_result['total']}, returned={admin_result['returned']}")
        for vuln in admin_result["vulnerabilities"][:5]:
            for field in ("id", "vulnerabilityId", "cvssSeverity", "asset"):
                if field not in vuln:
                    failures.append(f"vulnerability entry missing field '{field}': {vuln}")
        admin_total = admin_result["total"]

        # 2. Scoped user call — total should never exceed the admin's total
        user_rpc = call_tool(host, api_key, user_email, {})
        user_result = extract_tool_result(user_rpc)
        if "_rpc_error" in user_result:
            failures.append(f"scoped-user call returned RPC error: {user_result['_rpc_error']}")
        elif user_result["total"] > admin_total:
            failures.append(
                f"scoped-user total ({user_result['total']}) exceeds admin total ({admin_total}) — access control regression"
            )
        else:
            print(f"PASS: scoped-user call succeeded, total={user_result['total']} <= admin total={admin_total}")

    # 3. Severity filter — every returned item must match
    severity_rpc = call_tool(host, api_key, admin_email, {"severity": ["CRITICAL"]})
    severity_result = extract_tool_result(severity_rpc)
    if "_rpc_error" in severity_result:
        failures.append(f"severity-filtered call returned RPC error: {severity_result['_rpc_error']}")
    else:
        mismatched = [
            v for v in severity_result["vulnerabilities"]
            if (v.get("cvssSeverity") or "").upper() != "CRITICAL"
        ]
        if mismatched:
            failures.append(f"severity filter leaked non-CRITICAL rows: {mismatched[:3]}")
        else:
            print(f"PASS: severity filter returned {severity_result['returned']} CRITICAL-only rows")

    # 4. Missing delegation header — expect JSON-RPC error -32007 (DELEGATION_REQUIRED)
    # per docs/MCP.md's Errors table and JsonRpcResponse.delegationRequired().
    no_delegation_rpc = call_tool(host, api_key, "", {})
    rpc_error = no_delegation_rpc.get("error")
    if not rpc_error or rpc_error.get("code") != -32007:
        failures.append(f"missing-delegation call did not return error code -32007: {no_delegation_rpc}")
    else:
        print("PASS: missing X-MCP-User-Email correctly rejected with code -32007 (delegation required)")

    print()
    if failures:
        print(f"RESULT: {len(failures)} failure(s)")
        for f in failures:
            print(f"  - {f}")
        return 1

    print("RESULT: all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
