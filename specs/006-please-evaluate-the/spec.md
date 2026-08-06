# Feature Specification: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products

**Feature Branch**: `006-please-evaluate-the`
**Created**: 2025-10-04
**Status**: Draft
**Input**: User description: "please evaluate the existing code and make asset inventory, scans, vulnerability information but also product information as found in the vulnerabily scans available via MCP. Please also ensure that the code is working by implementing corresponding tests."

## Execution Flow (main)
```
1. Parse user description from Input
   → Feature: Expose security data via MCP tools
2. Extract key concepts from description
   → Actors: AI assistants, security analysts, automation tools
   → Actions: Query assets, retrieve scans, analyze vulnerabilities, inspect products
   → Data: Assets, Scans, ScanResults, ScanPorts, Vulnerabilities
   → Constraints: Permission-based access, data filtering, performance
3. For each unclear aspect:
   → [CLARIFIED: Existing MCP infrastructure exists in codebase]
   → [CLARIFIED: Data models already exist: Asset, Scan, ScanResult, ScanPort, Vulnerability]
   → [CLARIFIED: Product information comes from ScanPort.service and ScanPort.version]
4. Fill User Scenarios & Testing section
   → Primary scenario: AI assistant queries security data
5. Generate Functional Requirements
   → Each requirement is testable via API endpoints
6. Identify Key Entities (existing entities to be exposed)
7. Run Review Checklist
   → No implementation details in spec
8. Return: SUCCESS (spec ready for planning)
```

---

## ⚡ Quick Guidelines
- ✅ Focus on WHAT users need and WHY
- ❌ Avoid HOW to implement (no tech stack, APIs, code structure)
- 👥 Written for business stakeholders, not developers

---

## Clarifications

### Session 2025-10-04
- Q: FR-008 requires pagination support, but what are the maximum limits to prevent abuse? → A: Max 500 items per page, max 50,000 total results per query
- Q: Performance targets (NFR-001) mention "typical queries" but what scale should the system be designed for? → A: Medium deployment: ~10,000 assets, ~100,000 scan results, ~500,000 vulnerabilities
- Q: Should the system implement rate limiting to protect against excessive API usage? → A: Yes - limit per API key: 1,000 requests/minute, 50,000 requests/hour
- Q: How should the system handle concurrent queries that may read data being modified (e.g., new scan import during query)? → A: Snapshot isolation - queries see consistent point-in-time data (no partial/incomplete results)
- Q: FR-007 mentions MCP permission framework, but how do clients authenticate to obtain API keys? → A: Both - JWT users can create/manage their own API keys via admin interface

---

## User Scenarios & Testing

### Primary User Story
As an **AI assistant** or **security automation tool**, I need to access the organization's security data (assets, scan results, vulnerabilities, and discovered products) through a standardized MCP interface so that I can provide intelligent analysis, answer security questions, and automate security workflows without requiring direct database access or custom API integrations.

### Acceptance Scenarios

1. **Asset Inventory Query**
   - **Given** the system contains multiple assets with various properties (name, IP, type, owner, groups)
   - **When** a user queries for assets with optional filters (by name, type, IP, owner, or group)
   - **Then** the system returns matching assets with all relevant metadata including last seen timestamp and relationships

2. **Scan History Retrieval**
   - **Given** the system has imported multiple network scans (Nmap, Masscan) over time
   - **When** a user requests scan history for a specific asset or time period
   - **Then** the system returns scan records with metadata (scan date, type, uploader, host count) and associated results

3. **Vulnerability Analysis**
   - **Given** the system has vulnerability data linked to specific assets
   - **When** a user searches for vulnerabilities by asset, CVE ID, severity, or time range
   - **Then** the system returns vulnerability records with CVE identifiers, CVSS severity, affected products, days open, and scan timestamps

4. **Product Discovery**
   - **Given** scans have identified services and products running on network hosts
   - **When** a user searches for specific products, services, or versions across the infrastructure
   - **Then** the system returns discovered products with port numbers, protocols, service names, versions, and the assets where they were found

5. **Cross-Reference Query**
   - **Given** an asset has scan results, open ports, and vulnerabilities
   - **When** a user requests comprehensive information about a specific asset
   - **Then** the system returns the asset profile including current vulnerabilities, latest scan results, discovered services/products, and port states

6. **API Key Management**
   - **Given** a user is authenticated via JWT with appropriate privileges
   - **When** the user creates a new MCP API key with specific permissions through the administrative interface
   - **Then** the system generates a unique API key, associates it with the user, and allows MCP tool access according to the assigned permissions

### Edge Cases

- What happens when querying for non-existent assets, scans, or vulnerabilities?
  → System returns empty results with appropriate metadata indicating no matches found

- How does the system handle large result sets that could overwhelm the client?
  → System supports pagination parameters (page number, page size) with enforced limits: maximum 500 items per page and maximum 50,000 total results per query

- What happens when an asset has no associated scans or vulnerabilities?
  → System returns the asset record with empty collections for scans and vulnerabilities, clearly indicating no associated data

- How are permissions enforced for sensitive security data?
  → Users authenticate via JWT to access the web interface where they create MCP API keys with specific permissions (ASSETS_READ, SCANS_READ, VULNERABILITIES_READ); MCP tool calls then authenticate using these API keys

- What happens when scan data contains incomplete product information?
  → System returns available fields (service name, version, protocol) and indicates missing data as null/empty values without failing the query

- What happens when an API key exceeds rate limits?
  → System returns a rate limit error with details on current usage, limits, and retry-after timing; request is rejected without processing

- How does the system handle queries running concurrently with data imports or modifications?
  → System uses snapshot isolation to ensure queries see consistent point-in-time data without partial or incomplete results, even during concurrent write operations

## Requirements

### Functional Requirements

- **FR-001**: System MUST allow querying of asset inventory with filtering by name, IP address, type, owner, and group membership
- **FR-002**: System MUST provide access to scan history including scan metadata (type, date, filename, uploader, duration, host count)
- **FR-003**: System MUST expose scan results showing discovered hosts with IP addresses, hostnames, and discovery timestamps
- **FR-004**: System MUST allow retrieval of port-level scan data including port numbers, protocols, states, service names, and product versions
- **FR-005**: System MUST provide vulnerability data access with filtering by asset, CVE identifier, CVSS severity, and scan timestamp
- **FR-006**: System MUST support product discovery queries to locate specific services, applications, or versions across the infrastructure
- **FR-007**: System MUST allow authenticated JWT users to create and manage their own MCP API keys through an administrative interface, with each API key having configurable permissions for accessing security data
- **FR-008**: System MUST enforce permission-based access control for MCP tool calls using API key permissions (ASSETS_READ, SCANS_READ, VULNERABILITIES_READ)
- **FR-009**: System MUST support pagination for all data retrieval operations with a maximum of 500 items per page and a maximum of 50,000 total results per query to prevent resource exhaustion
- **FR-010**: System MUST allow cross-referencing queries to retrieve comprehensive asset profiles including scans, vulnerabilities, and products
- **FR-011**: System MUST return structured data in a consistent format suitable for machine processing
- **FR-012**: System MUST include audit logging for all MCP tool calls accessing security data
- **FR-013**: System MUST validate all query parameters to prevent injection attacks and ensure data integrity
- **FR-014**: System MUST enforce rate limiting of 1,000 requests per minute and 50,000 requests per hour per API key to prevent system abuse and ensure fair resource allocation
- **FR-015**: System MUST handle queries for historical data across multiple scan timestamps
- **FR-016**: System MUST support filtering vulnerabilities by severity levels (Critical, High, Medium, Low, Informational)
- **FR-017**: System MUST allow searching for assets by partial name or IP address matches
- **FR-018**: All MCP tools MUST be thoroughly tested with unit tests, integration tests, and end-to-end test coverage
- **FR-019**: Tests MUST validate permission enforcement, data filtering, pagination, rate limiting, error handling, and edge cases
- **FR-020**: Tests MUST cover authentication failures, invalid parameters, empty result sets, large data volumes, and rate limit enforcement

### Key Entities

- **Asset**: Represents infrastructure components (servers, devices, endpoints) with attributes including name, type, IP address, owner, description, groups, cloud identifiers, Active Directory domain, OS version, last seen timestamp, and relationships to scans and vulnerabilities

- **Scan**: Represents a network scan import event with metadata including scan type (nmap/masscan), filename, scan execution date, uploader identity, host count, duration, and upload timestamp

- **ScanResult**: Represents discovery of a specific host during a scan, linking the scan to an asset with IP address, hostname (if resolved), discovery timestamp, and associated port data

- **ScanPort**: Represents port-level discovery data including port number, protocol (tcp/udp), state (open/filtered/closed), service name, and product version information

- **Vulnerability**: Represents a security vulnerability discovered during a scan, linked to an asset with CVE identifier, CVSS severity rating, vulnerable product/version information, days open metric, scan timestamp, and creation timestamp

- **Product/Service Information**: Derived from ScanPort entities, representing discovered software, services, and applications running on network hosts with service names, versions, and deployment locations

### Non-Functional Requirements

- **NFR-001**: MCP tool queries MUST respond within reasonable time limits (under 5 seconds for typical queries, under 30 seconds for complex aggregations) at medium deployment scale (approximately 10,000 assets, 100,000 scan results, 500,000 vulnerabilities)
- **NFR-002**: System MUST support concurrent MCP tool calls from multiple clients without performance degradation at medium deployment scale
- **NFR-003**: System MUST ensure data consistency using snapshot isolation so queries return complete, point-in-time results without partial data even during concurrent write operations (scan imports, vulnerability updates)
- **NFR-004**: MCP tools MUST provide clear, actionable error messages when queries fail or return no results
- **NFR-005**: All security data access MUST be logged for audit and compliance purposes
- **NFR-006**: MCP tool responses MUST follow the Model Context Protocol specification for structure and format
- **NFR-007**: Test coverage MUST reach at least 80% for all new MCP tool implementations

---

## Review & Acceptance Checklist

### Content Quality
- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness
- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Scope is clearly bounded
- [x] Dependencies identified (existing MCP infrastructure, data models)

---

## Execution Status

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities marked (all clarified through codebase analysis)
- [x] User scenarios defined
- [x] Requirements generated
- [x] Entities identified
- [x] Review checklist passed

---
