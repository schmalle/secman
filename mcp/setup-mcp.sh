#!/bin/bash

echo "Setting up Secman MCP Server for Claude Desktop..."

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "Error: Node.js is required but not installed."
    echo "Please install Node.js version 18 or higher from: https://nodejs.org/"
    exit 1
fi

# Check Node.js version
NODE_VERSION=$(node -v | sed 's/v//')
MAJOR_VERSION=$(echo $NODE_VERSION | cut -d. -f1)

if [ "$MAJOR_VERSION" -lt 18 ]; then
    echo "Error: Node.js version 18 or higher is required. Found: $NODE_VERSION"
    exit 1
fi

echo "✓ Node.js version $NODE_VERSION detected"

# Install dependencies
echo "Installing MCP dependencies..."
npm install

if [ $? -ne 0 ]; then
    echo "Error: Failed to install dependencies"
    exit 1
fi

echo "✓ Dependencies installed"

# Make MCP server executable
chmod +x mcp-server.js
echo "✓ MCP server made executable"

# Test MCP server
echo "Testing MCP server..."
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}' | timeout 5s node mcp-server.js > /dev/null 2>&1

if [ $? -eq 0 ]; then
    echo "✓ MCP server test successful"
else
    echo "⚠ MCP server test failed - check if backend is running"
fi

# Get current directory
CURRENT_DIR=$(pwd)

echo ""
echo "Setup complete! 🎉"
echo ""
echo "Next steps:"
echo "1. Ensure Secman backend is running: cd src/backendng && gradle run"
echo "2. Add this to your Claude Desktop config (~/.claude/claude_desktop_config.json):"
echo ""
echo '{'
echo '  "mcpServers": {'
echo '    "secman": {'
echo '      "command": "node",'
echo "      \"args\": [\"$CURRENT_DIR/mcp-server.js\"],"
echo '      "env": {}'
echo '    }'
echo '  }'
echo '}'
echo ""
echo "3. Restart Claude Desktop"
echo "4. Test with: 'Show me the security requirements from Secman'"