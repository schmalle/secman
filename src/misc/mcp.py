import requests

# Setup
api_key = "your-api-key-here"
base_url = "http://localhost:8080/api/mcp"

headers = {
    "X-MCP-API-Key": "sk-mv5Nioy54KJO4tw1JQYDGQMSTadbFakyLlE1UmrkzNCSYV2M",
    "Content-Type": "application/json"
}

# Create session
session_response = requests.post(f"{base_url}/session",
    headers=headers,
    json={
        "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
        "clientInfo": {"name": "Python Client", "version": "1.0.0"}
    }
)

# Call tool
tool_response = requests.post(f"{base_url}/tools/call",
    headers=headers,
    json={
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tools/call",
        "params": {
            "name": "get_requirements",
            "arguments": {"limit": 10, "tags": ["GDPR"]}
        }
    }
)

print(tool_response.json())