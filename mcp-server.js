#!/usr/bin/env node

/**
 * Secman MCP Server
 * Connects Claude Desktop to Secman backend via Model Context Protocol
 */

const { Server } = require('@modelcontextprotocol/sdk/server/index.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  McpError,
  ErrorCode,
} = require('@modelcontextprotocol/sdk/types.js');

// Configuration
const SECMAN_BASE_URL = 'http://localhost:8080';
const API_KEY = 'sk-mv5Nioy54KJO4tw1JQYDGQMSTadbFakyLlE1UmrkzNCSYV2M';

class SecmanMCPServer {
  constructor() {
    this.server = new Server(
      {
        name: 'secman-mcp-server',
        version: '0.1.0',
      },
      {
        capabilities: {
          tools: {},
        },
      }
    );

    this.setupToolHandlers();
  }

  setupToolHandlers() {
    // List available tools
    this.server.setRequestHandler(ListToolsRequestSchema, async () => {
      return {
        tools: [
          {
            name: 'get_requirements',
            description: 'Retrieve security requirements from Secman',
            inputSchema: {
              type: 'object',
              properties: {
                limit: {
                  type: 'number',
                  description: 'Maximum number of requirements to return',
                  maximum: 100,
                  default: 20
                },
                offset: {
                  type: 'number',
                  description: 'Number of requirements to skip',
                  minimum: 0,
                  default: 0
                },
                tags: {
                  type: 'array',
                  items: { type: 'string' },
                  description: 'Filter by tags'
                },
                status: {
                  type: 'string',
                  enum: ['DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'],
                  description: 'Filter by requirement status'
                },
                priority: {
                  type: 'string',
                  enum: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'],
                  description: 'Filter by priority level'
                }
              }
            }
          },
          {
            name: 'search_requirements',
            description: 'Search security requirements by text',
            inputSchema: {
              type: 'object',
              properties: {
                query: {
                  type: 'string',
                  description: 'Search query text'
                },
                limit: {
                  type: 'number',
                  description: 'Maximum number of results',
                  maximum: 50,
                  default: 10
                }
              },
              required: ['query']
            }
          }
        ]
      };
    });

    // Handle tool calls
    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      const { name, arguments: args } = request.params;

      try {
        switch (name) {
          case 'get_requirements':
            return await this.getRequirements(args);
          case 'search_requirements':
            return await this.searchRequirements(args);
          default:
            throw new McpError(
              ErrorCode.MethodNotFound,
              `Unknown tool: ${name}`
            );
        }
      } catch (error) {
        console.error('Tool execution error:', error);
        throw new McpError(
          ErrorCode.InternalError,
          `Tool execution failed: ${error.message}`
        );
      }
    });
  }

  async getRequirements(args = {}) {
    const response = await this.callSecmanAPI('/tools/call', {
      jsonrpc: '2.0',
      id: `req-${Date.now()}`,
      method: 'tools/call',
      params: {
        name: 'get_requirements',
        arguments: args
      }
    });

    if (response.error) {
      throw new Error(response.error.message || 'Failed to get requirements');
    }

    const requirements = response.result.content.requirements || [];
    const total = response.result.content.total || 0;

    return {
      content: [
        {
          type: 'text',
          text: `Found ${total} security requirements:\n\n` +
            requirements.map(req =>
              `**${req.title}**\n` +
              `Description: ${req.description}\n` +
              `Norm: ${req.norm}\n` +
              `Chapter: ${req.chapter}\n` +
              `Use Cases: ${req.usecases?.join(', ') || 'None'}\n`
            ).join('\n---\n')
        }
      ]
    };
  }

  async searchRequirements(args) {
    // For now, we'll use get_requirements with a limit since search isn't implemented yet
    const requirements = await this.getRequirements({
      limit: args.limit || 10
    });

    // Filter results based on query (simple text search)
    const query = args.query.toLowerCase();
    const filteredText = requirements.content[0].text
      .split('---')
      .filter(section => section.toLowerCase().includes(query))
      .join('\n---\n');

    return {
      content: [
        {
          type: 'text',
          text: filteredText || 'No requirements found matching your search query.'
        }
      ]
    };
  }

  async callSecmanAPI(endpoint, data) {
    const fetch = (await import('node-fetch')).default;

    const response = await fetch(`${SECMAN_BASE_URL}/api/mcp${endpoint}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-MCP-API-Key': API_KEY
      },
      body: JSON.stringify(data)
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    return await response.json();
  }

  async run() {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error('Secman MCP Server running on stdio');
  }
}

// Run the server
if (require.main === module) {
  const server = new SecmanMCPServer();
  server.run().catch(console.error);
}

module.exports = SecmanMCPServer;