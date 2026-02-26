#!/usr/bin/env python3
"""
Simple MCP client for Secman using the Streamable HTTP transport.

Demonstrates the initialize → tools/list → tools/call flow with mandatory
X-MCP-User-Email delegation header.

Requirements:
    pip install requests

Environment variables:
    SECMAN_API_KEY      - MCP API key (required)
    SECMAN_USER_EMAIL   - User email for delegation (required)
    SECMAN_BASE_URL     - Server URL (default: http://localhost:8080)
"""

import json
import os
import sys

try:
    import requests
except ImportError:
    print("Error: 'requests' package is required. Install with: pip install requests")
    sys.exit(1)


def main():
    api_key = os.environ.get("SECMAN_API_KEY")
    user_email = os.environ.get("SECMAN_USER_EMAIL")
    base_url = os.environ.get("SECMAN_BASE_URL", "http://localhost:8080")

    if not api_key:
        print("Error: SECMAN_API_KEY environment variable is required")
        sys.exit(1)
    if not user_email:
        print("Error: SECMAN_USER_EMAIL environment variable is required")
        print("The server requires X-MCP-User-Email for all data-accessing endpoints")
        sys.exit(1)

    mcp_url = f"{base_url}/mcp"
    headers = {
        "Content-Type": "application/json",
        "X-MCP-API-Key": api_key,
        "X-MCP-User-Email": user_email,
    }

    # Step 1: Initialize
    print("=== Step 1: Initialize ===")
    resp = requests.post(mcp_url, headers=headers, json={
        "jsonrpc": "2.0",
        "id": "init-1",
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "simple-client", "version": "1.0.0"}
        }
    })
    print(json.dumps(resp.json(), indent=2))

    # Step 2: List tools
    print("\n=== Step 2: List Tools ===")
    resp = requests.post(mcp_url, headers=headers, json={
        "jsonrpc": "2.0",
        "id": "list-1",
        "method": "tools/list"
    })
    data = resp.json()
    tools = data.get("result", {}).get("tools", [])
    print(f"Available tools ({len(tools)}):")
    for tool in tools:
        print(f"  - {tool['name']}: {tool['description'][:80]}...")

    # Step 3: Call a tool (get_requirements)
    print("\n=== Step 3: Call Tool (get_requirements) ===")
    resp = requests.post(mcp_url, headers=headers, json={
        "jsonrpc": "2.0",
        "id": "call-1",
        "method": "tools/call",
        "params": {
            "name": "get_requirements",
            "arguments": {"limit": 5}
        }
    })
    print(json.dumps(resp.json(), indent=2))


if __name__ == "__main__":
    main()
