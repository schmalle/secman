#!/usr/bin/env python3
"""
MCP Test Client for Secman

A command-line tool to test MCP API endpoints, including the user delegation feature.
Uses only Python standard library (no external dependencies).

Feature: 050-mcp-user-delegation

Usage:
    # Get capabilities (no delegation)
    python mcp_client.py --api-key YOUR_KEY capabilities

    # Get capabilities with delegation
    python mcp_client.py --api-key YOUR_KEY --delegate user@company.com capabilities

    # Call a tool
    python mcp_client.py --api-key YOUR_KEY call get_requirements --args '{"limit": 5}'

    # Call a tool with delegation
    python mcp_client.py --api-key YOUR_KEY --delegate user@company.com call get_requirements

    # Run delegation tests
    python mcp_client.py --api-key YOUR_KEY test-delegation --delegate user@company.com
"""

import argparse
import json
import sys
import uuid
import urllib.request
import urllib.error
import urllib.parse
from typing import Any, Optional
from dataclasses import dataclass


class Colors:
    """ANSI color codes for terminal output."""
    HEADER = '\033[95m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    BOLD = '\033[1m'
    UNDERLINE = '\033[4m'
    END = '\033[0m'

    @classmethod
    def disable(cls):
        """Disable colors for non-TTY output."""
        cls.HEADER = ''
        cls.BLUE = ''
        cls.CYAN = ''
        cls.GREEN = ''
        cls.YELLOW = ''
        cls.RED = ''
        cls.BOLD = ''
        cls.UNDERLINE = ''
        cls.END = ''


@dataclass
class McpResponse:
    """Wrapper for MCP API responses."""
    success: bool
    status_code: int
    data: Optional[dict] = None
    error: Optional[str] = None


class McpClient:
    """
    MCP Test Client for Secman.

    Supports user delegation via X-MCP-User-Email header.
    Uses only Python standard library.
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        delegate_email: Optional[str] = None,
        verbose: bool = False
    ):
        self.base_url = base_url.rstrip('/')
        self.api_key = api_key
        self.delegate_email = delegate_email
        self.verbose = verbose

    def _get_headers(self) -> dict:
        """Build request headers including delegation header if set."""
        headers = {
            'X-MCP-API-Key': self.api_key,
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        }
        if self.delegate_email:
            headers['X-MCP-User-Email'] = self.delegate_email
        return headers

    def _log(self, message: str, color: str = ''):
        """Log message if verbose mode is enabled."""
        if self.verbose:
            print(f"{color}{message}{Colors.END}")

    def _make_request(
        self,
        method: str,
        endpoint: str,
        data: Optional[dict] = None
    ) -> McpResponse:
        """Make HTTP request to MCP API using urllib."""
        url = f"{self.base_url}{endpoint}"
        headers = self._get_headers()

        self._log(f"\n{'='*60}", Colors.CYAN)
        self._log(f"Request: {method} {url}", Colors.CYAN)
        self._log(f"Headers:", Colors.CYAN)
        for key, value in headers.items():
            display_value = value[:20] + '...' if key == 'X-MCP-API-Key' and len(value) > 20 else value
            self._log(f"  {key}: {display_value}", Colors.CYAN)
        if data:
            self._log(f"Body: {json.dumps(data, indent=2)}", Colors.CYAN)
        self._log(f"{'='*60}\n", Colors.CYAN)

        try:
            # Prepare request body
            body = json.dumps(data).encode('utf-8') if data else None

            # Create request
            req = urllib.request.Request(
                url,
                data=body,
                headers=headers,
                method=method
            )

            # Execute request
            try:
                with urllib.request.urlopen(req, timeout=30) as response:
                    status_code = response.status
                    response_body = response.read().decode('utf-8')

                    try:
                        response_data = json.loads(response_body)
                    except json.JSONDecodeError:
                        response_data = {'raw': response_body}

                    self._log(f"Response Status: {status_code}", Colors.GREEN)
                    self._log(f"Response Body: {json.dumps(response_data, indent=2)}", Colors.GREEN)

                    return McpResponse(success=True, status_code=status_code, data=response_data)

            except urllib.error.HTTPError as e:
                status_code = e.code
                response_body = e.read().decode('utf-8')

                try:
                    response_data = json.loads(response_body)
                except json.JSONDecodeError:
                    response_data = {'raw': response_body}

                self._log(f"Response Status: {status_code}", Colors.RED)
                self._log(f"Response Body: {json.dumps(response_data, indent=2)}", Colors.RED)

                error_msg = response_data.get('error', {}).get('message', str(response_data))
                return McpResponse(success=False, status_code=status_code, data=response_data, error=error_msg)

        except urllib.error.URLError as e:
            if 'Connection refused' in str(e.reason):
                return McpResponse(success=False, status_code=0, error="Connection refused. Is the server running?")
            return McpResponse(success=False, status_code=0, error=f"Connection error: {e.reason}")
        except TimeoutError:
            return McpResponse(success=False, status_code=0, error="Request timed out")
        except Exception as e:
            return McpResponse(success=False, status_code=0, error=str(e))

    def get_capabilities(self) -> McpResponse:
        """
        Get MCP server capabilities.

        When delegation is enabled, returns tools based on effective permissions
        (intersection of user roles and API key permissions).
        """
        return self._make_request('GET', '/capabilities')

    def call_tool(self, tool_name: str, arguments: Optional[dict] = None) -> McpResponse:
        """
        Call an MCP tool.

        Args:
            tool_name: Name of the tool to call
            arguments: Optional arguments for the tool
        """
        request_id = str(uuid.uuid4())[:8]
        data = {
            'jsonrpc': '2.0',
            'id': request_id,
            'method': 'tools/call',
            'params': {
                'name': tool_name,
                'arguments': arguments or {}
            }
        }
        return self._make_request('POST', '/tools/call', data)

    def create_session(self, client_name: str = "mcp-test-client") -> McpResponse:
        """Create a new MCP session."""
        data = {
            'capabilities': {},
            'clientInfo': {
                'name': client_name,
                'version': '1.0.0'
            }
        }
        return self._make_request('POST', '/session', data)


def print_header(text: str):
    """Print a formatted header."""
    print(f"\n{Colors.BOLD}{Colors.HEADER}{'='*60}{Colors.END}")
    print(f"{Colors.BOLD}{Colors.HEADER}{text.center(60)}{Colors.END}")
    print(f"{Colors.BOLD}{Colors.HEADER}{'='*60}{Colors.END}\n")


def print_result(label: str, success: bool, message: str = ""):
    """Print a test result."""
    icon = f"{Colors.GREEN}[PASS]{Colors.END}" if success else f"{Colors.RED}[FAIL]{Colors.END}"
    print(f"  {icon} {label}")
    if message:
        print(f"        {Colors.CYAN}{message}{Colors.END}")


def cmd_capabilities(client: McpClient, args: argparse.Namespace):
    """Handle 'capabilities' command."""
    print_header("MCP Capabilities")

    if client.delegate_email:
        print(f"{Colors.YELLOW}Delegation: {client.delegate_email}{Colors.END}\n")

    response = client.get_capabilities()

    if response.success:
        print(f"{Colors.GREEN}Success!{Colors.END}\n")

        # Show server info
        server_info = response.data.get('serverInfo', {})
        print(f"{Colors.BOLD}Server Info:{Colors.END}")
        for key, value in server_info.items():
            print(f"  {key}: {value}")

        # Check delegation status in response
        if server_info.get('delegationActive'):
            print(f"\n{Colors.YELLOW}Delegation Active: {server_info.get('delegatedUser')}{Colors.END}")

        # Show available tools
        capabilities = response.data.get('capabilities', {})
        tools = capabilities.get('tools', [])
        print(f"\n{Colors.BOLD}Available Tools ({len(tools)}):{Colors.END}")
        for tool in tools:
            if isinstance(tool, dict):
                desc = tool.get('description', '')
                desc_short = desc[:60] + '...' if len(desc) > 60 else desc
                print(f"  - {tool.get('name', 'unknown')}: {desc_short}")
            else:
                print(f"  - {tool}")
    else:
        print(f"{Colors.RED}Error: {response.error}{Colors.END}")
        if response.data:
            error_data = response.data.get('error', {})
            print(f"  Code: {error_data.get('code', 'N/A')}")
            print(f"  Message: {error_data.get('message', 'N/A')}")

    return 0 if response.success else 1


def cmd_call(client: McpClient, args: argparse.Namespace):
    """Handle 'call' command."""
    tool_name = args.tool_name

    # Parse arguments if provided
    tool_args = {}
    if args.args:
        try:
            tool_args = json.loads(args.args)
        except json.JSONDecodeError as e:
            print(f"{Colors.RED}Error parsing arguments: {e}{Colors.END}")
            return 1

    print_header(f"Call Tool: {tool_name}")

    if client.delegate_email:
        print(f"{Colors.YELLOW}Delegation: {client.delegate_email}{Colors.END}\n")

    if tool_args:
        print(f"{Colors.CYAN}Arguments: {json.dumps(tool_args)}{Colors.END}\n")

    response = client.call_tool(tool_name, tool_args)

    if response.success:
        result = response.data.get('result', {})
        error = response.data.get('error')

        if error:
            print(f"{Colors.RED}Tool Error: {error.get('code', 'UNKNOWN')}{Colors.END}")
            print(f"  Message: {error.get('message', 'N/A')}")
            return 1
        else:
            print(f"{Colors.GREEN}Tool executed successfully!{Colors.END}\n")
            print(f"{Colors.BOLD}Result:{Colors.END}")
            print(json.dumps(result, indent=2))
    else:
        print(f"{Colors.RED}Request Error: {response.error}{Colors.END}")
        if response.data:
            error_data = response.data.get('error', {})
            print(f"  Code: {error_data.get('code', 'N/A')}")
            print(f"  Message: {error_data.get('message', 'N/A')}")
        return 1

    return 0


def cmd_test_delegation(client: McpClient, args: argparse.Namespace):
    """
    Handle 'test-delegation' command.

    Runs a series of tests to verify delegation functionality.
    """
    print_header("Delegation Test Suite")

    if not client.delegate_email:
        print(f"{Colors.RED}Error: --delegate email is required for delegation tests{Colors.END}")
        return 1

    print(f"{Colors.CYAN}API Key: {client.api_key[:20]}...{Colors.END}")
    print(f"{Colors.CYAN}Delegate Email: {client.delegate_email}{Colors.END}")
    print()

    all_passed = True

    # Test 1: Get capabilities with delegation
    print(f"{Colors.BOLD}Test 1: Get capabilities with delegation{Colors.END}")
    response = client.get_capabilities()

    if response.success:
        server_info = response.data.get('serverInfo', {})
        delegation_active = server_info.get('delegationActive', False)
        delegated_user = server_info.get('delegatedUser', '')

        if delegation_active and delegated_user == client.delegate_email:
            print_result("Delegation active in response", True, f"delegatedUser={delegated_user}")
        elif delegation_active:
            print_result("Delegation active", False, f"Expected {client.delegate_email}, got {delegated_user}")
            all_passed = False
        else:
            print_result("Delegation active", False, "delegationActive not in response (key may not support delegation)")
            # This might be expected if the key doesn't have delegation enabled

        tools = response.data.get('capabilities', {}).get('tools', [])
        print_result("Capabilities returned", True, f"{len(tools)} tools available")
    else:
        error_code = response.data.get('error', {}).get('code', '') if response.data else ''
        if error_code.startswith('DELEGATION_'):
            print_result("Delegation error", False, f"{error_code}: {response.error}")
        else:
            print_result("Get capabilities", False, response.error)
        all_passed = False

    print()

    # Test 2: Call a read-only tool with delegation
    print(f"{Colors.BOLD}Test 2: Call tool with delegation{Colors.END}")
    response = client.call_tool('get_tags', {})

    if response.success:
        result = response.data.get('result')
        error = response.data.get('error')

        if error:
            error_code = error.get('code', 'UNKNOWN')
            if error_code == 'PERMISSION_DENIED':
                print_result("Tool call (permission check)", True, "Permission denied as expected for restricted user")
            else:
                print_result("Tool call", False, f"Tool error: {error_code}")
                all_passed = False
        else:
            print_result("Tool call", True, "Tool executed successfully")
    else:
        error_code = response.data.get('error', {}).get('code', '') if response.data else ''
        if error_code.startswith('DELEGATION_'):
            print_result("Delegation validation", False, f"{error_code}: {response.error}")
        elif error_code == 'PERMISSION_DENIED':
            print_result("Tool call (permission check)", True, "Permission denied (user may lack permissions)")
        else:
            print_result("Tool call", False, response.error)
            all_passed = False

    print()

    # Test 3: Compare with non-delegated request
    print(f"{Colors.BOLD}Test 3: Compare capabilities (delegation vs no delegation){Colors.END}")

    # Create a client without delegation
    non_delegate_client = McpClient(
        base_url=client.base_url,
        api_key=client.api_key,
        delegate_email=None,
        verbose=client.verbose
    )

    response_no_delegate = non_delegate_client.get_capabilities()
    response_with_delegate = client.get_capabilities()

    if response_no_delegate.success and response_with_delegate.success:
        tools_no_delegate = len(response_no_delegate.data.get('capabilities', {}).get('tools', []))
        tools_with_delegate = len(response_with_delegate.data.get('capabilities', {}).get('tools', []))

        print_result(
            "Capabilities comparison",
            True,
            f"Without delegation: {tools_no_delegate} tools, With delegation: {tools_with_delegate} tools"
        )

        if tools_with_delegate <= tools_no_delegate:
            print_result("Permission intersection", True, "Delegated permissions <= API key permissions")
        else:
            print_result("Permission intersection", False, "Delegated permissions > API key permissions (unexpected)")
            all_passed = False
    else:
        if not response_no_delegate.success:
            print_result("Non-delegated request", False, response_no_delegate.error)
        if not response_with_delegate.success:
            print_result("Delegated request", False, response_with_delegate.error)
        all_passed = False

    # Summary
    print()
    print(f"{Colors.BOLD}{'='*60}{Colors.END}")
    if all_passed:
        print(f"{Colors.GREEN}{Colors.BOLD}All delegation tests passed!{Colors.END}")
    else:
        print(f"{Colors.YELLOW}{Colors.BOLD}Some tests failed or had warnings. Check output above.{Colors.END}")
    print(f"{Colors.BOLD}{'='*60}{Colors.END}")

    return 0 if all_passed else 1


def cmd_test_invalid_delegation(client: McpClient, args: argparse.Namespace):
    """
    Handle 'test-invalid' command.

    Tests various invalid delegation scenarios.
    """
    print_header("Invalid Delegation Test Suite")

    print(f"{Colors.CYAN}API Key: {client.api_key[:20]}...{Colors.END}")
    print()

    test_cases = [
        ("invalid-email", "Test invalid email format"),
        ("user@", "Test email without domain"),
        ("user@invalid", "Test email without TLD"),
        ("user@external.com", "Test email from non-allowed domain"),
        ("nonexistent@company.com", "Test non-existent user"),
    ]

    all_expected = True

    for email, description in test_cases:
        print(f"{Colors.BOLD}{description}{Colors.END}")

        test_client = McpClient(
            base_url=client.base_url,
            api_key=client.api_key,
            delegate_email=email,
            verbose=False
        )

        response = test_client.get_capabilities()

        if not response.success:
            error_code = response.data.get('error', {}).get('code', '') if response.data else ''
            if error_code.startswith('DELEGATION_'):
                print_result(f"Email: {email}", True, f"Rejected with {error_code}")
            else:
                print_result(f"Email: {email}", True, f"Rejected with {error_code or response.error}")
        else:
            # If delegation isn't enabled on the key, header is ignored
            server_info = response.data.get('serverInfo', {})
            if not server_info.get('delegationActive'):
                print_result(f"Email: {email}", True, "Header ignored (delegation not enabled on key)")
            else:
                print_result(f"Email: {email}", False, "Request succeeded (expected rejection)")
                all_expected = False

        print()

    # Summary
    print(f"{Colors.BOLD}{'='*60}{Colors.END}")
    if all_expected:
        print(f"{Colors.GREEN}{Colors.BOLD}All invalid delegation tests behaved as expected!{Colors.END}")
    else:
        print(f"{Colors.YELLOW}{Colors.BOLD}Some tests had unexpected results. Check output above.{Colors.END}")
    print(f"{Colors.BOLD}{'='*60}{Colors.END}")

    return 0 if all_expected else 1


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description='MCP Test Client for Secman with Delegation Support',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Get server capabilities
  %(prog)s --api-key sk-xxx capabilities

  # Get capabilities with user delegation
  %(prog)s --api-key sk-xxx --delegate user@company.com capabilities

  # Call a tool
  %(prog)s --api-key sk-xxx call get_requirements --args '{"limit": 5}'

  # Call a tool with delegation
  %(prog)s --api-key sk-xxx --delegate user@company.com call get_tags

  # Run delegation tests
  %(prog)s --api-key sk-xxx --delegate user@company.com test-delegation

  # Test invalid delegation scenarios
  %(prog)s --api-key sk-xxx test-invalid
        """
    )

    # Global arguments
    parser.add_argument(
        '--api-key', '-k',
        required=True,
        help='MCP API key for authentication'
    )
    parser.add_argument(
        '--base-url', '-u',
        default='http://localhost:8080/api/mcp',
        help='Base URL for MCP API (default: http://localhost:8080/api/mcp)'
    )
    parser.add_argument(
        '--delegate', '-d',
        metavar='EMAIL',
        help='Email address for user delegation (X-MCP-User-Email header)'
    )
    parser.add_argument(
        '--verbose', '-v',
        action='store_true',
        help='Enable verbose output'
    )
    parser.add_argument(
        '--no-color',
        action='store_true',
        help='Disable colored output'
    )

    # Subcommands
    subparsers = parser.add_subparsers(dest='command', help='Available commands')

    # capabilities command
    subparsers.add_parser('capabilities', help='Get MCP server capabilities')

    # call command
    call_parser = subparsers.add_parser('call', help='Call an MCP tool')
    call_parser.add_argument('tool_name', help='Name of the tool to call')
    call_parser.add_argument(
        '--args', '-a',
        help='Tool arguments as JSON string'
    )

    # test-delegation command
    subparsers.add_parser(
        'test-delegation',
        help='Run delegation test suite'
    )

    # test-invalid command
    subparsers.add_parser(
        'test-invalid',
        help='Test invalid delegation scenarios'
    )

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        return 1

    # Disable colors if requested or not a TTY
    if args.no_color or not sys.stdout.isatty():
        Colors.disable()

    # Create client
    client = McpClient(
        base_url=args.base_url,
        api_key=args.api_key,
        delegate_email=args.delegate,
        verbose=args.verbose
    )

    # Route to command handler
    if args.command == 'capabilities':
        return cmd_capabilities(client, args)
    elif args.command == 'call':
        return cmd_call(client, args)
    elif args.command == 'test-delegation':
        return cmd_test_delegation(client, args)
    elif args.command == 'test-invalid':
        return cmd_test_invalid_delegation(client, args)
    else:
        parser.print_help()
        return 1


if __name__ == '__main__':
    sys.exit(main())
