#!/usr/bin/env node

/**
 * Secman MCP Server
 * Connects Claude Desktop to Secman backend via Model Context Protocol
 *
 * Exposes 15 tools:
 * - Requirements: get_requirements, export_requirements, add_requirement, delete_all_requirements
 * - Assets: get_assets, get_all_assets_detail, get_asset_profile, get_asset_complete_profile
 * - Vulnerabilities: get_vulnerabilities, get_all_vulnerabilities_detail
 * - Scans: get_scans, get_asset_scan_results, search_products
 * - Admin: list_users (requires ADMIN role via User Delegation)
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
const SECMAN_BASE_URL = process.env.SECMAN_BASE_URL || 'http://localhost:8080';
const API_KEY = process.env.SECMAN_API_KEY
// User identifier for MCP User Delegation (required for admin tools like list_users)
// Set this to the email or username of a Secman user with appropriate roles (e.g., ADMIN)
// Examples: "admin@example.com" or "adminuser"
const USER_EMAIL = process.env.SECMAN_USER_EMAIL || '';

class SecmanMCPServer {
  constructor() {
    this.server = new Server(
      {
        name: 'secman-mcp-server',
        version: '0.2.0',
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
          // =====================
          // REQUIREMENTS TOOLS
          // =====================
          {
            name: 'get_requirements',
            description: 'Retrieve security requirements from Secman with optional filtering',
            inputSchema: {
              type: 'object',
              properties: {
                limit: {
                  type: 'number',
                  description: 'Maximum number of requirements to return. Returns all requirements if not specified.',
                  minimum: 1
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
            name: 'export_requirements',
            description: 'Export all requirements to Excel (xlsx) or Word (docx) format. Returns base64-encoded file content.',
            inputSchema: {
              type: 'object',
              properties: {
                format: {
                  type: 'string',
                  enum: ['xlsx', 'docx'],
                  description: 'Export format: xlsx (Excel) or docx (Word)'
                }
              },
              required: ['format']
            }
          },
          {
            name: 'add_requirement',
            description: 'Create a new security requirement',
            inputSchema: {
              type: 'object',
              properties: {
                shortreq: {
                  type: 'string',
                  description: 'Short requirement text (required)'
                },
                details: {
                  type: 'string',
                  description: 'Detailed description'
                },
                motivation: {
                  type: 'string',
                  description: 'Why this requirement exists'
                },
                example: {
                  type: 'string',
                  description: 'Implementation example'
                },
                norm: {
                  type: 'string',
                  description: 'Regulatory norm reference (e.g., ISO 27001)'
                },
                usecase: {
                  type: 'string',
                  description: 'Use case description'
                },
                chapter: {
                  type: 'string',
                  description: 'Chapter/category for grouping'
                }
              },
              required: ['shortreq']
            }
          },
          {
            name: 'delete_all_requirements',
            description: 'Delete ALL requirements from the system (ADMIN only). Requires explicit confirmation.',
            inputSchema: {
              type: 'object',
              properties: {
                confirm: {
                  type: 'boolean',
                  description: 'Must be true to confirm deletion'
                }
              },
              required: ['confirm']
            }
          },

          // =====================
          // ASSET TOOLS
          // =====================
          {
            name: 'get_assets',
            description: 'Retrieve asset inventory with filtering and pagination',
            inputSchema: {
              type: 'object',
              properties: {
                page: {
                  type: 'number',
                  description: 'Page number (0-indexed)',
                  minimum: 0,
                  default: 0
                },
                pageSize: {
                  type: 'number',
                  description: 'Number of items per page (max 500)',
                  minimum: 1,
                  maximum: 500,
                  default: 100
                },
                name: {
                  type: 'string',
                  description: 'Filter by asset name (partial match, case-insensitive)'
                },
                type: {
                  type: 'string',
                  description: 'Filter by exact asset type (e.g., SERVER, WORKSTATION)'
                },
                ip: {
                  type: 'string',
                  description: 'Filter by IP address (partial match)'
                },
                owner: {
                  type: 'string',
                  description: 'Filter by owner (exact match)'
                },
                group: {
                  type: 'string',
                  description: 'Filter by group membership (exact match)'
                }
              }
            }
          },
          {
            name: 'get_all_assets_detail',
            description: 'Retrieve all assets with comprehensive filtering, pagination, and workgroup info',
            inputSchema: {
              type: 'object',
              properties: {
                name: {
                  type: 'string',
                  description: 'Filter by asset name (case-insensitive contains)'
                },
                type: {
                  type: 'string',
                  description: 'Filter by asset type (exact match, e.g., SERVER, CLIENT, NETWORK_DEVICE)'
                },
                ip: {
                  type: 'string',
                  description: 'Filter by IP address (contains)'
                },
                owner: {
                  type: 'string',
                  description: 'Filter by owner name (contains)'
                },
                group: {
                  type: 'string',
                  description: 'Filter by group membership (exact match)'
                },
                page: {
                  type: 'integer',
                  description: 'Page number (0-indexed, default=0)',
                  minimum: 0,
                  default: 0
                },
                pageSize: {
                  type: 'integer',
                  description: 'Items per page (default=100, max=1000)',
                  minimum: 1,
                  maximum: 1000,
                  default: 100
                }
              }
            }
          },
          {
            name: 'get_asset_profile',
            description: 'Retrieve comprehensive profile for a single asset including vulnerabilities and scan history',
            inputSchema: {
              type: 'object',
              properties: {
                assetId: {
                  type: 'number',
                  description: 'The asset ID to retrieve profile for'
                },
                includeVulnerabilities: {
                  type: 'boolean',
                  description: 'Include vulnerability data (default: true)',
                  default: true
                },
                includeScanHistory: {
                  type: 'boolean',
                  description: 'Include scan history (default: true)',
                  default: true
                },
                vulnerabilityLimit: {
                  type: 'number',
                  description: 'Max number of vulnerabilities to return (max 100)',
                  minimum: 1,
                  maximum: 100,
                  default: 20
                },
                scanHistoryLimit: {
                  type: 'number',
                  description: 'Max number of scan results to return (max 50)',
                  minimum: 1,
                  maximum: 50,
                  default: 10
                }
              },
              required: ['assetId']
            }
          },
          {
            name: 'get_asset_complete_profile',
            description: 'Retrieve complete asset profile including base details, all vulnerabilities, and all scan results',
            inputSchema: {
              type: 'object',
              properties: {
                assetId: {
                  type: 'integer',
                  description: 'The asset ID to retrieve (required)'
                },
                includeVulnerabilities: {
                  type: 'boolean',
                  description: 'Include vulnerability data (default=true)',
                  default: true
                },
                includeScanResults: {
                  type: 'boolean',
                  description: 'Include scan result data (default=true)',
                  default: true
                }
              },
              required: ['assetId']
            }
          },

          // =====================
          // VULNERABILITY TOOLS
          // =====================
          {
            name: 'get_vulnerabilities',
            description: 'Retrieve vulnerability data with optional filtering and pagination',
            inputSchema: {
              type: 'object',
              properties: {
                page: {
                  type: 'number',
                  description: 'Page number (0-indexed)',
                  minimum: 0,
                  default: 0
                },
                pageSize: {
                  type: 'number',
                  description: 'Number of items per page (max 500)',
                  minimum: 1,
                  maximum: 500,
                  default: 100
                },
                cveId: {
                  type: 'string',
                  description: 'Filter by CVE ID (partial match, case-insensitive)'
                },
                severity: {
                  type: 'array',
                  items: { type: 'string' },
                  description: 'Filter by CVSS severity levels (Critical, High, Medium, Low, Info)'
                },
                assetId: {
                  type: 'number',
                  description: 'Filter by asset ID'
                },
                startDate: {
                  type: 'string',
                  description: 'Filter vulnerabilities scanned after this date (ISO-8601)',
                  format: 'date-time'
                },
                endDate: {
                  type: 'string',
                  description: 'Filter vulnerabilities scanned before this date (ISO-8601)',
                  format: 'date-time'
                }
              }
            }
          },
          {
            name: 'get_all_vulnerabilities_detail',
            description: 'Retrieve vulnerabilities with severity/asset/days-open filtering and detailed asset info',
            inputSchema: {
              type: 'object',
              properties: {
                severity: {
                  type: 'string',
                  enum: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'],
                  description: 'Filter by severity level'
                },
                assetId: {
                  type: 'integer',
                  description: 'Filter by specific asset ID'
                },
                minDaysOpen: {
                  type: 'integer',
                  description: 'Filter by minimum number of days vulnerability has been open',
                  minimum: 0
                },
                page: {
                  type: 'integer',
                  description: 'Page number (0-indexed, default=0)',
                  minimum: 0,
                  default: 0
                },
                pageSize: {
                  type: 'integer',
                  description: 'Items per page (default=100, max=1000)',
                  minimum: 1,
                  maximum: 1000,
                  default: 100
                }
              }
            }
          },

          // =====================
          // SCAN TOOLS
          // =====================
          {
            name: 'get_scans',
            description: 'Retrieve scan history with optional filtering and pagination',
            inputSchema: {
              type: 'object',
              properties: {
                page: {
                  type: 'number',
                  description: 'Page number (0-indexed)',
                  minimum: 0,
                  default: 0
                },
                pageSize: {
                  type: 'number',
                  description: 'Number of items per page (max 500)',
                  minimum: 1,
                  maximum: 500,
                  default: 100
                },
                scanType: {
                  type: 'string',
                  enum: ['nmap', 'masscan'],
                  description: 'Filter by scan type'
                },
                uploadedBy: {
                  type: 'string',
                  description: 'Filter by uploader username'
                },
                startDate: {
                  type: 'string',
                  description: 'Filter scans after this date (ISO-8601)',
                  format: 'date-time'
                },
                endDate: {
                  type: 'string',
                  description: 'Filter scans before this date (ISO-8601)',
                  format: 'date-time'
                }
              }
            }
          },
          {
            name: 'get_asset_scan_results',
            description: 'Retrieve scan results (open ports, services, products) with optional filtering',
            inputSchema: {
              type: 'object',
              properties: {
                portMin: {
                  type: 'integer',
                  description: 'Minimum port number (1-65535)',
                  minimum: 1,
                  maximum: 65535
                },
                portMax: {
                  type: 'integer',
                  description: 'Maximum port number (1-65535)',
                  minimum: 1,
                  maximum: 65535
                },
                service: {
                  type: 'string',
                  description: 'Filter by service name (case-insensitive contains)'
                },
                state: {
                  type: 'string',
                  enum: ['open', 'filtered', 'closed'],
                  description: 'Filter by port state'
                },
                page: {
                  type: 'integer',
                  description: 'Page number (0-indexed, default=0)',
                  minimum: 0,
                  default: 0
                },
                pageSize: {
                  type: 'integer',
                  description: 'Items per page (default=100, max=1000)',
                  minimum: 1,
                  maximum: 1000,
                  default: 100
                }
              }
            }
          },
          {
            name: 'search_products',
            description: 'Search for products/services discovered in network scans across infrastructure',
            inputSchema: {
              type: 'object',
              properties: {
                page: {
                  type: 'number',
                  description: 'Page number (0-indexed)',
                  minimum: 0,
                  default: 0
                },
                pageSize: {
                  type: 'number',
                  description: 'Number of items per page (max 500)',
                  minimum: 1,
                  maximum: 500,
                  default: 100
                },
                service: {
                  type: 'string',
                  description: 'Filter by service name (partial match, case-insensitive)'
                },
                stateFilter: {
                  type: 'string',
                  enum: ['open', 'filtered', 'closed', 'all'],
                  description: 'Filter by port state (default: open)',
                  default: 'open'
                }
              }
            }
          },

          // =====================
          // ADMIN TOOLS
          // =====================
          {
            name: 'list_users',
            description: 'List all users in the system (ADMIN only, requires User Delegation). Returns user details including username, email, roles, and authentication source.',
            inputSchema: {
              type: 'object',
              properties: {}
            }
          }
        ]
      };
    });

    // Handle tool calls
    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      const { name, arguments: args } = request.params;

      try {
        // All tools use the same backend API pattern
        return await this.callBackendTool(name, args || {});
      } catch (error) {
        console.error(`Tool ${name} execution error:`, error);
        throw new McpError(
          ErrorCode.InternalError,
          `Tool execution failed: ${error.message}`
        );
      }
    });
  }

  /**
   * Generic method to call any backend MCP tool
   */
  async callBackendTool(toolName, args = {}) {
    const response = await this.callSecmanAPI('/tools/call', {
      jsonrpc: '2.0',
      id: `req-${Date.now()}`,
      method: 'tools/call',
      params: {
        name: toolName,
        arguments: args
      }
    });

    if (response.error) {
      throw new Error(response.error.message || `Failed to call ${toolName}`);
    }

    // Format the response for better readability
    const content = response.result?.content || response.result || {};
    const formattedText = this.formatResponse(toolName, content);

    return {
      content: [
        {
          type: 'text',
          text: formattedText
        }
      ]
    };
  }

  /**
   * Format responses for human readability based on tool type
   */
  formatResponse(toolName, content) {
    try {
      switch (toolName) {
        case 'get_requirements':
          return this.formatRequirements(content);
        case 'get_assets':
        case 'get_all_assets_detail':
          return this.formatAssets(content);
        case 'get_asset_profile':
        case 'get_asset_complete_profile':
          return this.formatAssetProfile(content);
        case 'get_vulnerabilities':
        case 'get_all_vulnerabilities_detail':
          return this.formatVulnerabilities(content);
        case 'get_scans':
          return this.formatScans(content);
        case 'get_asset_scan_results':
          return this.formatScanResults(content);
        case 'search_products':
          return this.formatProducts(content);
        case 'export_requirements':
          return this.formatExport(content);
        case 'add_requirement':
          return this.formatAddResult(content);
        case 'delete_all_requirements':
          return this.formatDeleteResult(content);
        case 'list_users':
          return this.formatUsers(content);
        default:
          return JSON.stringify(content, null, 2);
      }
    } catch (e) {
      // Fallback to JSON if formatting fails
      return JSON.stringify(content, null, 2);
    }
  }

  formatRequirements(content) {
    const requirements = content.requirements || [];
    const total = content.total || requirements.length;

    if (requirements.length === 0) {
      return 'No requirements found.';
    }

    let text = `Found ${total} security requirements:\n\n`;
    requirements.forEach((req, i) => {
      text += `**${i + 1}. ${req.shortreq || req.title || 'Untitled'}**\n`;
      if (req.chapter) text += `   Chapter: ${req.chapter}\n`;
      if (req.norm) text += `   Norm: ${req.norm}\n`;
      if (req.details) text += `   Details: ${req.details.substring(0, 200)}${req.details.length > 200 ? '...' : ''}\n`;
      text += '\n';
    });
    return text;
  }

  formatAssets(content) {
    const assets = content.assets || [];
    const total = content.total || assets.length;

    if (assets.length === 0) {
      return 'No assets found.';
    }

    let text = `Found ${total} assets (showing ${assets.length}):\n\n`;
    assets.forEach((asset, i) => {
      text += `**${asset.name || 'Unknown'}** (ID: ${asset.id})\n`;
      text += `   Type: ${asset.type || 'N/A'} | IP: ${asset.ip || 'N/A'}\n`;
      if (asset.owner) text += `   Owner: ${asset.owner}\n`;
      if (asset.osVersion) text += `   OS: ${asset.osVersion}\n`;
      text += '\n';
    });

    if (content.totalPages > 1) {
      text += `\nPage ${(content.page || 0) + 1} of ${content.totalPages}`;
    }
    return text;
  }

  formatAssetProfile(content) {
    const asset = content.asset || {};
    let text = `**Asset Profile: ${asset.name || 'Unknown'}**\n`;
    text += `ID: ${asset.id} | Type: ${asset.type || 'N/A'} | IP: ${asset.ip || 'N/A'}\n`;
    if (asset.owner) text += `Owner: ${asset.owner}\n`;
    if (asset.osVersion) text += `OS: ${asset.osVersion}\n`;
    if (asset.description) text += `Description: ${asset.description}\n`;

    // Vulnerability stats
    if (content.vulnerabilityStats) {
      const stats = content.vulnerabilityStats;
      text += `\n**Vulnerabilities:** ${stats.total || 0} total\n`;
      if (stats.bySeverity) {
        Object.entries(stats.bySeverity).forEach(([sev, count]) => {
          text += `   ${sev}: ${count}\n`;
        });
      }
    }

    // Vulnerabilities list
    if (content.vulnerabilities?.items) {
      text += `\n**Recent Vulnerabilities:**\n`;
      content.vulnerabilities.items.slice(0, 5).forEach(vuln => {
        text += `   - ${vuln.vulnerabilityId} (${vuln.cvssSeverity})\n`;
      });
    }

    // Current state
    if (content.currentState) {
      const state = content.currentState;
      text += `\n**Current State:**\n`;
      text += `   Open Ports: ${state.totalOpenPorts || 0}\n`;
      if (state.services?.length > 0) {
        text += `   Services: ${state.services.join(', ')}\n`;
      }
    }

    return text;
  }

  formatVulnerabilities(content) {
    const vulns = content.vulnerabilities || [];
    const total = content.total || vulns.length;

    if (vulns.length === 0) {
      return 'No vulnerabilities found.';
    }

    let text = `Found ${total} vulnerabilities (showing ${vulns.length}):\n\n`;
    vulns.forEach(vuln => {
      text += `**${vuln.vulnerabilityId}** - ${vuln.cvssSeverity || 'Unknown'}\n`;
      text += `   Asset: ${vuln.assetName || vuln.asset?.name || 'Unknown'} (ID: ${vuln.assetId || vuln.asset?.id})\n`;
      if (vuln.daysOpen) text += `   Days Open: ${vuln.daysOpen}\n`;
      if (vuln.vulnerableProductVersions) text += `   Affected: ${vuln.vulnerableProductVersions}\n`;
      text += '\n';
    });

    if (content.totalPages > 1) {
      text += `\nPage ${(content.page || 0) + 1} of ${content.totalPages}`;
    }
    return text;
  }

  formatScans(content) {
    const scans = content.scans || [];
    const total = content.total || scans.length;

    if (scans.length === 0) {
      return 'No scans found.';
    }

    let text = `Found ${total} scans (showing ${scans.length}):\n\n`;
    scans.forEach(scan => {
      text += `**${scan.filename || 'Unknown'}** (ID: ${scan.id})\n`;
      text += `   Type: ${scan.scanType || 'N/A'} | Hosts: ${scan.hostCount || 0}\n`;
      text += `   Date: ${scan.scanDate || 'Unknown'} | By: ${scan.uploadedBy || 'Unknown'}\n`;
      text += '\n';
    });
    return text;
  }

  formatScanResults(content) {
    const results = content.scanResults || [];
    const total = content.total || results.length;

    if (results.length === 0) {
      return 'No scan results found.';
    }

    let text = `Found ${total} port results (showing ${results.length}):\n\n`;
    results.forEach(result => {
      text += `**Port ${result.portNumber}/${result.protocol}** - ${result.state}\n`;
      text += `   Service: ${result.service || 'Unknown'} ${result.version ? `(${result.version})` : ''}\n`;
      if (result.asset) {
        text += `   Asset: ${result.asset.name} (${result.asset.ip})\n`;
      }
      text += '\n';
    });
    return text;
  }

  formatProducts(content) {
    const products = content.products || [];

    if (products.length === 0) {
      return 'No products found.';
    }

    let text = `Found ${content.uniqueProducts || products.length} unique products:\n\n`;
    products.forEach(product => {
      text += `**${product.service}** ${product.version !== 'unversioned' ? `v${product.version}` : ''}\n`;
      text += `   Found on ${product.assetCount} asset(s)\n`;
      text += `   Ports: ${product.ports?.join(', ') || 'N/A'}\n`;
      text += '\n';
    });
    return text;
  }

  formatExport(content) {
    if (content.success && content.data) {
      return `Export successful!\nFormat: ${content.format || 'xlsx'}\nSize: ${content.data.length} bytes (base64)\n\nNote: The file content is base64-encoded. Decode and save to use.`;
    }
    return JSON.stringify(content, null, 2);
  }

  formatAddResult(content) {
    if (content.success) {
      return `Requirement created successfully!\nID: ${content.id}\nShort Req: ${content.shortreq}\nChapter: ${content.chapter || 'Uncategorized'}`;
    }
    return JSON.stringify(content, null, 2);
  }

  formatDeleteResult(content) {
    if (content.success) {
      return `Delete operation completed.\nDeleted: ${content.deletedCount} requirements\n${content.message || ''}`;
    }
    return JSON.stringify(content, null, 2);
  }

  formatUsers(content) {
    const users = content.users || [];
    const total = content.totalCount || users.length;

    if (users.length === 0) {
      return 'No users found.';
    }

    let text = `Found ${total} users:\n\n`;
    users.forEach((user, i) => {
      text += `**${i + 1}. ${user.username}** (ID: ${user.id})\n`;
      text += `   Email: ${user.email || 'N/A'}\n`;
      text += `   Roles: ${user.roles?.join(', ') || 'None'}\n`;
      text += `   Auth Source: ${user.authSource || 'LOCAL'}\n`;
      text += `   MFA Enabled: ${user.mfaEnabled ? 'Yes' : 'No'}\n`;
      if (user.lastLogin) text += `   Last Login: ${user.lastLogin}\n`;
      text += '\n';
    });
    return text;
  }

  async callSecmanAPI(endpoint, data) {
    const fetch = (await import('node-fetch')).default;

    // Build headers with API key and optional user delegation
    const headers = {
      'Content-Type': 'application/json',
      'X-MCP-API-Key': API_KEY
    };

    // Add User Delegation header if configured (required for admin tools like list_users)
    if (USER_EMAIL) {
      headers['X-MCP-User-Email'] = USER_EMAIL;
    }

    const response = await fetch(`${SECMAN_BASE_URL}/api/mcp${endpoint}`, {
      method: 'POST',
      headers,
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
    console.error('Secman MCP Server v0.2.0 running on stdio');
    console.error(`Backend URL: ${SECMAN_BASE_URL}`);
    console.error(`User Delegation: ${USER_EMAIL ? `enabled (${USER_EMAIL})` : 'disabled (set SECMAN_USER_EMAIL for admin tools)'}`);
    console.error('Available tools: 15 (requirements, assets, vulnerabilities, scans, admin)');
  }
}

// Run the server
if (require.main === module) {
  const server = new SecmanMCPServer();
  server.run().catch(console.error);
}

module.exports = SecmanMCPServer;
