import React, { useState } from 'react';

interface SetupStep {
  id: string;
  title: string;
  description: string;
  completed: boolean;
}

const McpSetupGuide: React.FC = () => {
  const [currentStep, setCurrentStep] = useState(0);
  const [apiKey, setApiKey] = useState('');
  const [selectedClient, setSelectedClient] = useState<'claude' | 'custom' | ''>('');

  const steps: SetupStep[] = [
    {
      id: 'create-api-key',
      title: 'Create MCP API Key',
      description: 'Generate a secure API key for your AI assistant to authenticate with Secman.',
      completed: false
    },
    {
      id: 'choose-client',
      title: 'Choose Your AI Client',
      description: 'Select which AI assistant you want to connect to Secman.',
      completed: false
    },
    {
      id: 'configure-client',
      title: 'Configure Your Client',
      description: 'Set up your AI client with the MCP server configuration.',
      completed: false
    },
    {
      id: 'test-connection',
      title: 'Test Connection',
      description: 'Verify that your AI assistant can successfully connect to Secman.',
      completed: false
    }
  ];

  const claudeDesktopConfig = `{
  "mcpServers": {
    "secman": {
      "command": "node",
      "args": ["/path/to/secman-mcp-client.js"],
      "env": {
        "SECMAN_API_URL": "http://localhost:8080/api/mcp",
        "SECMAN_API_KEY": "${apiKey || 'your-api-key-here'}"
      }
    }
  }
}`;

  const genericMcpConfig = `{
  "server_url": "http://localhost:8080/api/mcp",
  "api_key": "${apiKey || 'your-api-key-here'}",
  "capabilities": {
    "tools": {},
    "resources": {},
    "prompts": {}
  },
  "client_info": {
    "name": "Your AI Assistant",
    "version": "1.0.0"
  }
}`;

  const pythonExample = `import requests

# Setup
api_key = "${apiKey || 'your-api-key-here'}"
base_url = "http://localhost:8080/api/mcp"

headers = {
    "X-MCP-API-Key": api_key,
    "Content-Type": "application/json"
}

# Test connection
capabilities = requests.get(f"{base_url}/capabilities", headers=headers)
print("Capabilities:", capabilities.json())

# Create session
session_response = requests.post(f"{base_url}/session",
    headers=headers,
    json={
        "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
        "clientInfo": {"name": "Python Client", "version": "1.0.0"}
    }
)
print("Session:", session_response.json())

# Call a tool
tool_response = requests.post(f"{base_url}/tools/call",
    headers=headers,
    json={
        "jsonrpc": "2.0",
        "id": "test-1",
        "method": "tools/call",
        "params": {
            "name": "get_requirements",
            "arguments": {"limit": 5}
        }
    }
)
print("Tool result:", tool_response.json())`;

  const curlExample = `# Test authentication
curl -H "X-MCP-API-Key: ${apiKey || 'your-api-key-here'}" \\
  http://localhost:8080/api/mcp/capabilities

# Create session
curl -X POST \\
  -H "X-MCP-API-Key: ${apiKey || 'your-api-key-here'}" \\
  -H "Content-Type: application/json" \\
  -d '{"capabilities":{"tools":{},"resources":{},"prompts":{}},"clientInfo":{"name":"Test Client","version":"1.0.0"}}' \\
  http://localhost:8080/api/mcp/session

# Call tool
curl -X POST \\
  -H "X-MCP-API-Key: ${apiKey || 'your-api-key-here'}" \\
  -H "Content-Type: application/json" \\
  -d '{"jsonrpc":"2.0","id":"test","method":"tools/call","params":{"name":"get_requirements","arguments":{"limit":1}}}' \\
  http://localhost:8080/api/mcp/tools/call`;

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text).then(() => {
      // Could add a toast notification here
    });
  };

  const renderStepContent = (stepIndex: number) => {
    switch (stepIndex) {
      case 0:
        return (
          <div>
            <p>Before you can connect an AI assistant to Secman, you need to create an MCP API key.</p>

            <div className="alert alert-info">
              <h6>📋 What you'll need:</h6>
              <ul className="mb-0">
                <li>Admin access to Secman</li>
                <li>Appropriate permissions for the tools you want to use</li>
              </ul>
            </div>

            <h5>Step 1: Create API Key</h5>
            <ol>
              <li>Navigate to the <strong>MCP API Keys</strong> section</li>
              <li>Click <strong>"Create New API Key"</strong></li>
              <li>Enter a descriptive name (e.g., "Claude Desktop Integration")</li>
              <li>Select the permissions your AI assistant needs:
                <ul>
                  <li><code>REQUIREMENTS_READ</code> - Read security requirements</li>
                  <li><code>ASSESSMENTS_READ</code> - Read risk assessments</li>
                  <li><code>REQUIREMENTS_WRITE</code> - Create/modify requirements (optional)</li>
                  <li><code>ASSESSMENTS_WRITE</code> - Create/modify assessments (optional)</li>
                </ul>
              </li>
              <li>Set an expiration date (recommended: 90 days)</li>
              <li>Click <strong>"Create API Key"</strong></li>
              <li><strong>Important:</strong> Copy the generated API key immediately - it won't be shown again!</li>
            </ol>

            <div className="mt-3">
              <label htmlFor="apiKeyInput" className="form-label">Paste your API key here (for configuration examples):</label>
              <input
                type="text"
                className="form-control"
                id="apiKeyInput"
                placeholder="sk-..."
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
              />
            </div>
          </div>
        );

      case 1:
        return (
          <div>
            <h5>Choose Your AI Assistant</h5>
            <p>Secman supports any AI client that implements the Model Context Protocol (MCP).</p>

            <div className="row">
              <div className="col-md-6">
                <div
                  className={`card cursor-pointer ${selectedClient === 'claude' ? 'border-primary' : ''}`}
                  onClick={() => setSelectedClient('claude')}
                  style={{ cursor: 'pointer' }}
                >
                  <div className="card-body text-center">
                    <h5>🤖 Claude Desktop</h5>
                    <p className="text-muted">Anthropic's Claude with native MCP support</p>
                    {selectedClient === 'claude' && <span className="badge bg-primary">Selected</span>}
                  </div>
                </div>
              </div>

              <div className="col-md-6">
                <div
                  className={`card cursor-pointer ${selectedClient === 'custom' ? 'border-primary' : ''}`}
                  onClick={() => setSelectedClient('custom')}
                  style={{ cursor: 'pointer' }}
                >
                  <div className="card-body text-center">
                    <h5>⚙️ Custom Client</h5>
                    <p className="text-muted">Your own MCP-compatible client</p>
                    {selectedClient === 'custom' && <span className="badge bg-primary">Selected</span>}
                  </div>
                </div>
              </div>
            </div>

            <div className="mt-4">
              <h6>Supported Features:</h6>
              <ul>
                <li>🔍 Search and retrieve security requirements</li>
                <li>📊 Access risk assessments and findings</li>
                <li>🏷️ Browse tags and categories</li>
                <li>✏️ Create new requirements and assessments (with write permissions)</li>
                <li>🔄 Real-time data access</li>
                <li>🛡️ Secure API key authentication</li>
              </ul>
            </div>
          </div>
        );

      case 2:
        return (
          <div>
            <h5>Configure Your {selectedClient === 'claude' ? 'Claude Desktop' : 'MCP Client'}</h5>

            {selectedClient === 'claude' && (
              <div>
                <p>Add the following configuration to your Claude Desktop config file:</p>

                <div className="alert alert-info">
                  <strong>Config file locations:</strong>
                  <ul className="mb-0">
                    <li><strong>macOS:</strong> <code>~/Library/Application Support/Claude/claude_desktop_config.json</code></li>
                    <li><strong>Windows:</strong> <code>%APPDATA%\Claude\claude_desktop_config.json</code></li>
                  </ul>
                </div>

                <div className="position-relative">
                  <pre className="bg-light p-3 rounded">
                    <code>{claudeDesktopConfig}</code>
                  </pre>
                  <button
                    className="btn btn-outline-secondary btn-sm position-absolute top-0 end-0 m-2"
                    onClick={() => copyToClipboard(claudeDesktopConfig)}
                    title="Copy to clipboard"
                  >
                    📋
                  </button>
                </div>

                <div className="alert alert-warning mt-3">
                  <strong>Important:</strong> After adding the configuration, restart Claude Desktop for changes to take effect.
                </div>
              </div>
            )}

            {selectedClient === 'custom' && (
              <div>
                <div className="nav nav-tabs mb-3" id="configTabs">
                  <button className="nav-link active" data-bs-toggle="tab" data-bs-target="#json-config">
                    JSON Configuration
                  </button>
                  <button className="nav-link" data-bs-toggle="tab" data-bs-target="#python-example">
                    Python Example
                  </button>
                  <button className="nav-link" data-bs-toggle="tab" data-bs-target="#curl-example">
                    cURL Examples
                  </button>
                </div>

                <div className="tab-content">
                  <div className="tab-pane fade show active" id="json-config">
                    <p>Basic MCP client configuration:</p>
                    <div className="position-relative">
                      <pre className="bg-light p-3 rounded">
                        <code>{genericMcpConfig}</code>
                      </pre>
                      <button
                        className="btn btn-outline-secondary btn-sm position-absolute top-0 end-0 m-2"
                        onClick={() => copyToClipboard(genericMcpConfig)}
                      >
                        📋
                      </button>
                    </div>
                  </div>

                  <div className="tab-pane fade" id="python-example">
                    <p>Python client example:</p>
                    <div className="position-relative">
                      <pre className="bg-light p-3 rounded">
                        <code>{pythonExample}</code>
                      </pre>
                      <button
                        className="btn btn-outline-secondary btn-sm position-absolute top-0 end-0 m-2"
                        onClick={() => copyToClipboard(pythonExample)}
                      >
                        📋
                      </button>
                    </div>
                  </div>

                  <div className="tab-pane fade" id="curl-example">
                    <p>Test with cURL commands:</p>
                    <div className="position-relative">
                      <pre className="bg-light p-3 rounded">
                        <code>{curlExample}</code>
                      </pre>
                      <button
                        className="btn btn-outline-secondary btn-sm position-absolute top-0 end-0 m-2"
                        onClick={() => copyToClipboard(curlExample)}
                      >
                        📋
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>
        );

      case 3:
        return (
          <div>
            <h5>Test Your Connection</h5>
            <p>Verify that your AI assistant can successfully connect to Secman.</p>

            <div className="row">
              <div className="col-md-6">
                <div className="card">
                  <div className="card-header">
                    <h6>🤖 Test with AI Assistant</h6>
                  </div>
                  <div className="card-body">
                    <p>Try asking your AI assistant:</p>
                    <blockquote className="blockquote">
                      <p>"Show me the security requirements in Secman"</p>
                    </blockquote>
                    <p>Or:</p>
                    <blockquote className="blockquote">
                      <p>"What risk assessments are available?"</p>
                    </blockquote>
                  </div>
                </div>
              </div>

              <div className="col-md-6">
                <div className="card">
                  <div className="card-header">
                    <h6>🔧 Manual Testing</h6>
                  </div>
                  <div className="card-body">
                    <p>Test the API directly:</p>
                    <button
                      className="btn btn-primary btn-sm mb-2"
                      onClick={() => window.open('/api/mcp/capabilities', '_blank')}
                    >
                      Test Capabilities Endpoint
                    </button>
                    <br />
                    <small className="text-muted">
                      This will open the MCP capabilities endpoint. Add your API key as X-MCP-API-Key header.
                    </small>
                  </div>
                </div>
              </div>
            </div>

            <div className="alert alert-success mt-4">
              <h6>✅ Connection Successful!</h6>
              <p>If your AI assistant can retrieve data from Secman, your setup is complete!</p>

              <h6>Available Tools:</h6>
              <ul className="mb-0">
                <li><code>get_requirements</code> - Retrieve security requirements</li>
                <li><code>search_requirements</code> - Search through requirements</li>
                <li><code>get_assessments</code> - Retrieve risk assessments</li>
                <li><code>search_assessments</code> - Search through assessments</li>
                <li><code>get_tags</code> - Get available tags</li>
                <li><code>search_all</code> - Universal search</li>
              </ul>
            </div>

            <div className="alert alert-info">
              <h6>🎉 Next Steps:</h6>
              <ul className="mb-0">
                <li>Explore the MCP Dashboard to monitor usage</li>
                <li>Check the audit logs for API activity</li>
                <li>Create additional API keys for different use cases</li>
                <li>Review the full documentation for advanced features</li>
              </ul>
            </div>
          </div>
        );

      default:
        return null;
    }
  };

  return (
    <div className="container mt-4">
      <div className="row">
        <div className="col-md-3">
          {/* Step Navigation */}
          <div className="card sticky-top">
            <div className="card-header">
              <h6>Setup Progress</h6>
            </div>
            <div className="card-body">
              {steps.map((step, index) => (
                <div
                  key={step.id}
                  className={`d-flex align-items-center mb-3 cursor-pointer ${
                    index === currentStep ? 'text-primary' : index < currentStep ? 'text-success' : 'text-muted'
                  }`}
                  onClick={() => setCurrentStep(index)}
                  style={{ cursor: 'pointer' }}
                >
                  <div className={`me-2 ${index <= currentStep ? 'text-primary' : 'text-muted'}`}>
                    {index < currentStep ? '✅' : index === currentStep ? '🔄' : '⏳'}
                  </div>
                  <div>
                    <div className="fw-bold">{step.title}</div>
                    <small className="text-muted">{step.description}</small>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="col-md-9">
          {/* Step Content */}
          <div className="card">
            <div className="card-header">
              <h4>
                Step {currentStep + 1}: {steps[currentStep].title}
              </h4>
            </div>
            <div className="card-body">
              {renderStepContent(currentStep)}

              {/* Navigation Buttons */}
              <div className="d-flex justify-content-between mt-4">
                <button
                  className="btn btn-outline-secondary"
                  onClick={() => setCurrentStep(Math.max(0, currentStep - 1))}
                  disabled={currentStep === 0}
                >
                  ← Previous
                </button>
                <button
                  className="btn btn-primary"
                  onClick={() => setCurrentStep(Math.min(steps.length - 1, currentStep + 1))}
                  disabled={currentStep === steps.length - 1}
                >
                  Next →
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default McpSetupGuide;