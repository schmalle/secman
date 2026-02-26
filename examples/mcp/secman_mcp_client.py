#!/usr/bin/env python3
"""
Comprehensive MCP client library for Secman.

Provides a typed Python client for interacting with the Secman MCP server
via the Streamable HTTP transport. User delegation (X-MCP-User-Email) is
mandatory for all data-accessing endpoints.

Requirements:
    pip install requests

Usage as library:
    from secman_mcp_client import SecmanMcpClient

    client = SecmanMcpClient(
        api_key="sk-your-key",
        user_email="admin@company.com"
    )
    client.initialize()
    tools = client.list_tools()
    result = client.call_tool("get_requirements", {"limit": 10})

Usage as CLI:
    python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com list-tools
    python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com call get_requirements --args '{"limit":5}'
    python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com ping
    python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com test
"""

import argparse
import json
import sys
import time

try:
    import requests
except ImportError:
    print("Error: 'requests' package is required. Install with: pip install requests")
    sys.exit(1)


class McpError(Exception):
    """Error from MCP server."""
    def __init__(self, code: int, message: str, data=None):
        super().__init__(message)
        self.code = code
        self.data = data


class SecmanMcpClient:
    """
    MCP client for Secman with mandatory user delegation.

    Args:
        api_key: MCP API key (starts with sk-)
        user_email: User email for delegation (mandatory)
        base_url: Secman server URL
        verbose: Print request/response details
        timeout: Request timeout in seconds
    """

    def __init__(
        self,
        api_key: str,
        user_email: str,
        base_url: str = "http://localhost:8080",
        verbose: bool = False,
        timeout: int = 30,
    ):
        if not api_key:
            raise ValueError("api_key is required")
        if not user_email:
            raise ValueError("user_email is required (mandatory for all data endpoints)")

        self.api_key = api_key
        self.user_email = user_email
        self.base_url = base_url.rstrip("/")
        self.mcp_url = f"{self.base_url}/mcp"
        self.verbose = verbose
        self.timeout = timeout
        self._request_id = 0

    def _next_id(self) -> str:
        self._request_id += 1
        return f"req-{self._request_id}"

    def _headers(self) -> dict:
        return {
            "Content-Type": "application/json",
            "X-MCP-API-Key": self.api_key,
            "X-MCP-User-Email": self.user_email,
        }

    def _send(self, method: str, params: dict = None) -> dict:
        """Send a JSON-RPC request and return the result."""
        request_id = self._next_id()
        payload = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
        }
        if params is not None:
            payload["params"] = params

        if self.verbose:
            print(f">>> {method} (id={request_id})")
            print(f"    {json.dumps(params or {}, indent=2)}")

        resp = requests.post(
            self.mcp_url,
            headers=self._headers(),
            json=payload,
            timeout=self.timeout,
        )
        resp.raise_for_status()
        data = resp.json()

        if self.verbose:
            print(f"<<< {json.dumps(data, indent=2)}")

        if "error" in data and data["error"] is not None:
            err = data["error"]
            raise McpError(err.get("code", -1), err.get("message", "Unknown error"), err.get("data"))

        return data.get("result", {})

    def initialize(self) -> dict:
        """Perform MCP handshake. Returns server info and capabilities."""
        return self._send("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "secman-mcp-client-py", "version": "1.0.0"},
        })

    def list_tools(self) -> list:
        """List available tools. Returns list of tool definitions."""
        result = self._send("tools/list")
        return result.get("tools", [])

    def call_tool(self, name: str, arguments: dict = None) -> dict:
        """Call a tool by name with optional arguments."""
        return self._send("tools/call", {
            "name": name,
            "arguments": arguments or {},
        })

    def ping(self) -> dict:
        """Ping the server."""
        return self._send("ping")

    def test_connection(self) -> bool:
        """Test connectivity: initialize → ping → list tools. Returns True if all succeed."""
        try:
            print("Testing connection...")

            print("  1. Initialize... ", end="", flush=True)
            info = self.initialize()
            server = info.get("serverInfo", {})
            print(f"OK ({server.get('name', '?')} v{server.get('version', '?')})")

            print("  2. Ping... ", end="", flush=True)
            self.ping()
            print("OK")

            print("  3. List tools... ", end="", flush=True)
            tools = self.list_tools()
            print(f"OK ({len(tools)} tools)")

            print(f"\nConnection test passed. Delegating as: {self.user_email}")
            return True
        except McpError as e:
            print(f"FAILED (MCP error {e.code}: {e})")
            return False
        except requests.RequestException as e:
            print(f"FAILED (HTTP error: {e})")
            return False


def main():
    parser = argparse.ArgumentParser(
        description="Secman MCP Client",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s --api-key sk-xxx --user-email admin@co.com list-tools
  %(prog)s --api-key sk-xxx --user-email admin@co.com call get_requirements --args '{"limit":5}'
  %(prog)s --api-key sk-xxx --user-email admin@co.com ping
  %(prog)s --api-key sk-xxx --user-email admin@co.com test
        """,
    )
    parser.add_argument("--api-key", required=True, help="MCP API key")
    parser.add_argument("--user-email", required=True, help="User email for delegation (mandatory)")
    parser.add_argument("--base-url", default="http://localhost:8080", help="Server URL")
    parser.add_argument("--verbose", "-v", action="store_true", help="Verbose output")
    parser.add_argument("--timeout", type=int, default=30, help="Request timeout (seconds)")

    sub = parser.add_subparsers(dest="command", help="Commands")
    sub.add_parser("list-tools", help="List available tools")
    sub.add_parser("ping", help="Ping server")
    sub.add_parser("test", help="Test connection")

    call_p = sub.add_parser("call", help="Call a tool")
    call_p.add_argument("tool_name", help="Tool name")
    call_p.add_argument("--args", "-a", default="{}", help="Tool arguments as JSON")

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        return 1

    client = SecmanMcpClient(
        api_key=args.api_key,
        user_email=args.user_email,
        base_url=args.base_url,
        verbose=args.verbose,
        timeout=args.timeout,
    )

    try:
        if args.command == "list-tools":
            client.initialize()
            tools = client.list_tools()
            print(f"Available tools ({len(tools)}):")
            for t in tools:
                print(f"  {t['name']}: {t.get('description', '')[:80]}")
            return 0

        elif args.command == "call":
            tool_args = json.loads(args.args)
            client.initialize()
            result = client.call_tool(args.tool_name, tool_args)
            print(json.dumps(result, indent=2))
            return 0

        elif args.command == "ping":
            result = client.ping()
            print("Pong!", json.dumps(result))
            return 0

        elif args.command == "test":
            return 0 if client.test_connection() else 1

    except McpError as e:
        print(f"MCP Error {e.code}: {e}", file=sys.stderr)
        return 1
    except requests.RequestException as e:
        print(f"HTTP Error: {e}", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"Invalid JSON arguments: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
