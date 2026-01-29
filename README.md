# SecMan - Security Management Platform

![Landing Page](docs/landing.png)

**A comprehensive security requirement, vulnerability, and risk assessment management platform**

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Status: Alpha](https://img.shields.io/badge/Status-Alpha-yellow.svg)]()

---

## Overview

SecMan is a full-stack security management platform that helps organizations manage security requirements, track vulnerabilities, assess risks, and maintain compliance. It provides vulnerability management, asset tracking, workgroup-based access control, AI assistant integration via MCP, and enterprise authentication with OAuth2/OIDC and passkeys.

**Key Capabilities:**

- Security requirements management with version control and release tracking
- Vulnerability management with CrowdStrike Falcon integration
- Asset inventory with network scan import (Nmap, Masscan)
- Risk assessment and demand classification
- Multi-tenant workgroup-based access control
- AI assistant integration via Model Context Protocol (MCP)
- Multi-format export (Excel, Word, CSV) with automated translation
- Enterprise authentication (JWT, OAuth2/OIDC, Passkeys/WebAuthn)
- Email notifications with configurable SMTP providers
- Admin summary reports and vulnerability statistics

## Technology Stack

- **Backend**: Micronaut 4.10 + Kotlin 2.3.0 (Java 21)
- **Frontend**: Astro 5.16 + React 19 + Bootstrap 5.3
- **Database**: MariaDB 12 with Hibernate JPA
- **Build System**: Gradle 9.3 (Kotlin DSL)
- **CLI Tools**: Kotlin/Picocli for CrowdStrike queries, notifications, and data management
- **Authentication**: JWT with OAuth2/OIDC and Passkeys (WebAuthn)

## Features

### Requirements Management

- Create, edit, and organize security requirements
- Version control with release management (DRAFT, PUBLISHED, ARCHIVED)
- Release comparison and diff visualization
- Point-in-time requirement snapshots
- Excel and Word export with customizable templates
- Automated translation (20+ languages via OpenRouter API)
- Relationship tracking (requirements, norms, use cases, standards)

### Vulnerability Management

- Import vulnerabilities from Excel/CSV or CrowdStrike Falcon API
- CVE tracking with CVSS severity scoring
- Vulnerability exception requests with approval workflow
- Auto-approval logic for exception requests
- Workgroup-scoped, account-scoped, and domain-scoped vulnerability views
- Vulnerability statistics dashboard
- Days-open tracking and trending
- Vulnerability lifecycle management
- Real-time SSE updates for exception request badge counts
- Outdated assets materialized view for fast performance (<2s for 10K+ assets)
- Configurable vulnerability settings

### Asset Management

- Import assets from Nmap and Masscan XML scans
- Network service discovery and port tracking
- Asset metadata (cloud account ID, cloud instance ID, AD domain, OS version)
- Workgroup-based asset assignment with nested hierarchy support
- Criticality levels (LOW, MEDIUM, HIGH, CRITICAL) for assets and workgroups
- Vulnerability correlation per asset
- Asset profile views with comprehensive details
- Outdated asset tracking with automated email notifications
- Bulk asset operations (delete, export)

### Risk Assessment

- Risk register with assessment tracking
- Demand classification for security reviews
- Standards and norms management
- Alignment reviews with external stakeholders (token-based access)

### Access Control & Multi-Tenancy

- **Workgroups**: Organize users and assets into isolated groups with nested hierarchy
- **User Mapping**: CSV/Excel upload for AWS account and AD domain associations
- **Role-Based Access Control (RBAC)**:
  - `USER` - Basic access to assigned workgroups
  - `ADMIN` - Full system administration
  - `VULN` - Vulnerability management permissions
  - `RELEASE_MANAGER` - Release creation and management
  - `SECCHAMPION` - Security champion access
  - `REQ` - Requirements editor
- **Row-Level Security**: Users see only their workgroup resources + owned items
- **Last Admin Protection**: System prevents deletion/demotion of the last ADMIN user
- **Unified Access Control**: AWS account ID and AD domain-based asset filtering

### Authentication

- Local username/password with BCrypt hashing
- OAuth2/OIDC with configurable identity providers (Azure AD, Google, etc.)
- Passkeys/WebAuthn for passwordless authentication
- MFA toggle support
- Session management with JWT tokens

### AI Assistant Integration (MCP)

- **Model Context Protocol** support for AI assistants (Claude, etc.)
- Streamable HTTP transport (direct connection, no middleware required)
- 14+ MCP tools for requirements, assets, vulnerabilities, scans, releases, user mappings
- User delegation (act on behalf of users)
- API key management with granular permissions
- Rate limiting and session management
- Audit logging for all MCP operations

### CLI Tools

- **CrowdStrike Vulnerability Query** (`query servers`):
  Query Falcon API with flexible filters (device type, severity, days open)
- **Email Notifications** (`send-notifications`):
  Outdated asset and new vulnerability notifications with escalation
- **Admin Summary** (`send-admin-summary`):
  Generate and send admin summary reports
- **User Mapping Management** (`manage-user-mappings`):
  Manage AWS account and AD domain mappings
- **Workgroup Management** (`manage-workgroups`):
  Pattern-based asset assignment to workgroups
- **Manual Vulnerability Entry** (`add-vulnerability`):
  Add or update vulnerabilities via CLI
- **AWS/Domain Commands** (`add-aws`, `add-domain`):
  Add AWS accounts and AD domains to assets
- **Requirement Management** (`add-requirement`, `export-requirements`, `delete-all-requirements`):
  CLI-based requirement operations
- **S3 Import** (`import-s3`):
  Import data from S3 buckets
- **Configuration** (`config`):
  Manage CLI configuration
- **Monitor** (`monitor`):
  System monitoring utilities

### Email Notifications

- Configurable email providers (SMTP, AWS SES)
- Automated outdated asset notifications (2-level escalation: professional, then urgent)
- New vulnerability notifications (opt-in via user preferences)
- Admin summary emails with system statistics
- Email aggregation (one email per owner with all their assets)
- Thymeleaf HTML templates with plain-text fallback
- SMTP with retry logic and audit logging
- Test email account management
- User preference management UI

### Import/Export

- Excel import for requirements, vulnerabilities, user mappings, assets
- CSV import for user mappings (auto-detect delimiter/encoding)
- Nmap/Masscan XML import for network scans
- S3-based imports for automated pipelines
- Export to Excel/Word with release selection
- Multi-language export (automated translation)
- Release comparison export with diff highlighting
- Notification log export to CSV (admin only)
- Vulnerability exception export

### Admin Features

- Application settings management
- Identity provider configuration (OAuth2/OIDC)
- Email provider configuration
- CrowdStrike Falcon configuration
- Maintenance banner management
- MCP API key management
- Translation configuration
- Notification settings
- Vulnerability configuration
- User management with role assignment
- User mapping management
- Config bundle import/export
- Materialized view refresh control

## Quick Start

### Prerequisites

- Java 21 (JDK 21, Amazon Corretto recommended)
- Node.js 20+
- MariaDB 12+
- Gradle 9.3+ (wrapper included)
- Git
- Docker (optional, for integration tests)

### Installation

```bash
# Clone repository
git clone https://github.com/schmalle/secman.git
cd secman

# Create database
cd scripts/install
./install.sh
cd ../..

# Start backend (in one terminal)
cd src/backendng
./gradlew run

# Start frontend (in another terminal)
cd src/frontend
npm install
npm run dev
```

### Default Credentials

| Username      | Password   | Roles                 |
| ------------- | ---------- | --------------------- |
| `adminuser`   | `password` | ADMIN, USER           |
| `normaluser`  | `password` | USER                  |
| `vulnuser`    | `password` | VULN, USER            |
| `releaseuser` | `password` | RELEASE_MANAGER, USER |

**IMPORTANT:** Change default passwords immediately in production!

## Development

### Project Structure

```
secman/
├── src/
│   ├── backendng/          # Kotlin/Micronaut backend
│   │   ├── src/main/kotlin/com/secman/
│   │   │   ├── controller/ # REST controllers (52 controllers)
│   │   │   ├── domain/     # JPA entities
│   │   │   ├── repository/ # Data repositories
│   │   │   ├── service/    # Business logic (98+ services)
│   │   │   ├── config/     # Configuration classes
│   │   │   ├── dto/        # Data transfer objects
│   │   │   ├── filter/     # HTTP filters
│   │   │   └── mcp/        # MCP tools and registry
│   │   └── src/test/       # Backend tests (unit + integration)
│   ├── cli/                # Kotlin CLI tools
│   │   └── src/main/kotlin/com/secman/cli/
│   │       ├── commands/   # CLI commands (21 commands)
│   │       └── service/    # Business logic
│   ├── frontend/           # Astro + React frontend
│   │   ├── src/
│   │   │   ├── components/ # React components
│   │   │   ├── pages/      # Astro pages (61 pages)
│   │   │   └── services/   # API clients
├── docs/                   # Documentation
│   ├── ARCHITECTURE.md     # System architecture
│   ├── CLI.md              # CLI reference
│   ├── DEPLOYMENT.md       # Production deployment
│   ├── ENVIRONMENT.md      # Environment variables
│   ├── MCP.md              # MCP integration guide
│   ├── TESTING.md          # Test guide
│   ├── CROWDSTRIKE_IMPORT.md # CrowdStrike import details
│   └── TROUBLESHOOTING.md  # Troubleshooting
├── mcp/                    # MCP Node.js bridge
├── scripts/                # Utility scripts
└── specs/                  # Feature specifications
```

### Common Commands

```bash
# Backend
cd src/backendng
./gradlew build              # Build (includes tests)
./gradlew run                # Start server (port 8080)

# Frontend
cd src/frontend
npm run dev                  # Dev server (port 4321)
npm run build                # Production build

# CLI Tools (from repository root)
./gradlew :cli:shadowJar                                           # Build standalone JAR
./bin/secman help                                                   # Show all commands
./bin/secman query servers --dry-run                                # Query CrowdStrike
./bin/secman send-notifications --dry-run --verbose                 # Email notifications
./bin/secman send-admin-summary --dry-run                           # Admin summary
./bin/secman manage-workgroups list                                 # List workgroups
./bin/secman manage-user-mappings --help                            # User mappings
./bin/secman add-vulnerability --help                               # Add vulnerability
./bin/secman export-requirements --format xlsx                      # Export requirements

# Tests
./gradlew build                                         # All tests
./gradlew :backendng:test --tests "*ServiceTest*"      # Unit tests only
./gradlew :backendng:test --tests "*IntegrationTest*"  # Integration tests (Docker required)
./gradlew :cli:test                                    # CLI tests
```

## API Documentation

### Authentication

All endpoints require JWT authentication via `Authorization: Bearer <token>` header unless noted otherwise.

**Login:**

```bash
POST /api/auth/login
{
  "username": "adminuser",
  "password": "password"
}
```

### Key Endpoints

| Endpoint                                              | Method  | Description                      | Access                 |
| ----------------------------------------------------- | ------- | -------------------------------- | ---------------------- |
| `/api/auth/login`                                     | POST    | Authenticate user                | Public                 |
| `/api/requirements`                                   | GET     | List requirements                | Authenticated          |
| `/api/requirements/export/xlsx`                       | GET     | Export to Excel                  | Authenticated          |
| `/api/releases`                                       | GET/POST| List/create releases             | ADMIN, RELEASE_MANAGER |
| `/api/releases/compare`                               | GET     | Compare releases                 | Authenticated          |
| `/api/assets`                                         | GET/POST| List/create assets               | Authenticated          |
| `/api/assets/bulk`                                    | DELETE  | Bulk delete assets               | ADMIN                  |
| `/api/assets/export`                                  | GET     | Export assets                    | Authenticated          |
| `/api/vulnerabilities/current`                        | GET     | Current vulnerabilities          | ADMIN, VULN            |
| `/api/vulnerabilities/cli-add`                        | POST    | Add vulnerability via CLI        | ADMIN, VULN            |
| `/api/vulnerability-exception-requests`               | POST    | Create exception request         | Authenticated          |
| `/api/vulnerability-exception-requests/pending/count` | GET     | Badge count for pending requests | ADMIN, VULN            |
| `/api/vulnerability-exceptions`                       | GET     | List active exceptions           | Authenticated          |
| `/api/outdated-assets`                                | GET     | List outdated assets (paginated) | Authenticated          |
| `/api/materialized-view-refresh/trigger`              | POST    | Trigger view refresh             | ADMIN                  |
| `/api/notification-preferences`                       | GET/PUT | Manage notification preferences  | Authenticated          |
| `/api/notification-logs`                              | GET     | Notification audit logs          | ADMIN                  |
| `/api/account-vulns`                                  | GET     | Account-scoped vulnerabilities   | USER                   |
| `/api/workgroups`                                     | GET/POST| List/create workgroups           | ADMIN                  |
| `/api/import/upload-nmap-xml`                         | POST    | Import Nmap scan                 | ADMIN                  |
| `/api/import/upload-vulnerability-xlsx`               | POST    | Import vulnerabilities           | ADMIN                  |
| `/api/import/upload-user-mappings-csv`                | POST    | Import user mappings             | ADMIN                  |
| `/api/import/upload-assets-xlsx`                      | POST    | Import assets                    | ADMIN                  |
| `/api/user-mappings`                                  | GET/POST| Manage user mappings             | ADMIN                  |
| `/api/identity-providers`                             | GET/POST| Manage OAuth/OIDC providers      | ADMIN                  |
| `/api/maintenance-banners`                            | GET/POST| Manage maintenance banners       | ADMIN                  |
| `/api/maintenance-banners/active`                     | GET     | Active banners                   | Public                 |
| `/api/users/profile`                                  | GET     | User profile                     | Authenticated          |
| `/api/users/profile/mfa-toggle`                       | PUT     | Toggle MFA                       | Authenticated          |
| `/oauth/authorize`                                    | GET     | OAuth2 authorization             | Public                 |
| `/oauth/callback`                                     | GET     | OAuth2 callback                  | Public                 |
| `/mcp`                                                | POST    | MCP Streamable HTTP endpoint     | MCP API Key            |
| `/health`                                             | GET     | Health check                     | Public                 |

See [CLAUDE.md](CLAUDE.md) for the complete API reference.

## Database

- **Engine:** MariaDB 12
- **Database:** `secman`
- **User:** `secman` / `CHANGEME`
- **Port:** 3306
- **Schema:** Auto-migrated via Hibernate

**Access:**

```bash
# Local
mysql -u secman -pCHANGEME secman

# Reset database (development only)
./scripts/reset_database.sh
```

## Configuration

**Backend:** `src/backendng/src/main/resources/application.yml`
**Frontend:** `src/frontend/astro.config.mjs`
**CLI:** Environment variables and `./bin/secman config`

### Key Environment Variables

```bash
# Database
DB_HOST=localhost
DB_PORT=3306
DB_NAME=secman
DB_PASSWORD=CHANGEME

# JWT
JWT_SECRET=your-secret-key-here

# Encryption (for sensitive config storage)
SECMAN_ENCRYPTION_PASSWORD=your-encryption-password
SECMAN_ENCRYPTION_SALT=your-encryption-salt

# OAuth (optional)
GITHUB_CLIENT_ID=your-github-client-id
GITHUB_CLIENT_SECRET=your-github-secret

# Translation (optional)
OPENROUTER_API_KEY=your-openrouter-key

# CrowdStrike API (for CLI)
FALCON_CLIENT_ID=your-client-id
FALCON_CLIENT_SECRET=your-client-secret
FALCON_BASE_URL=https://api.crowdstrike.com

# Email (SMTP)
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=noreply@yourdomain.com
SMTP_PASSWORD=app_password

# CLI Authentication
SECMAN_USERNAME=adminuser
SECMAN_PASSWORD=your-password
```

See [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md) for the complete environment variable reference.

## MCP Integration (AI Assistants)

Connect Claude Desktop, Claude Code, or other MCP clients:

### Claude Code (Recommended)

```bash
claude mcp add --transport http secman http://localhost:8080/mcp \
  --header "X-MCP-API-Key: sk-your-api-key"
```

### Claude Desktop

```json
{
  "mcpServers": {
    "secman": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp",
               "--header", "X-MCP-API-Key: sk-your-api-key"]
    }
  }
}
```

See [docs/MCP.md](docs/MCP.md) for the complete MCP setup guide, available tools, and user delegation.

## Development Workflow

1. **Implement features** following security-first principles
2. **Follow RBAC patterns** for access control
3. **Ensure API backward compatibility**
4. **Run `./gradlew build`** to verify no errors
5. **Commit** with conventional commit messages
6. **Review security implications** before completion

### Conventional Commits

```bash
feat(vulnerability): add CVE severity filtering
fix(asset): correct workgroup permission check
docs(readme): update installation instructions
test(release): add comparison endpoint tests
```

### Git Workflow

```bash
# Create feature branch
git checkout -b 024-feature-name

# Implement feature
# ... implement, test, review security ...

# Commit with conventional commits
git add .
git commit -m "feat(scope): description"

# Push and create PR
git push origin 024-feature-name
```

## Documentation

Comprehensive documentation is available in the `docs/` directory:

| Document                                              | Description                                    |
| ----------------------------------------------------- | ---------------------------------------------- |
| [Architecture](docs/ARCHITECTURE.md)                  | System design, data model, and design patterns |
| [Deployment Guide](docs/DEPLOYMENT.md)                | Production deployment on Linux                 |
| [Environment Variables](docs/ENVIRONMENT.md)          | Complete configuration reference               |
| [CLI Reference](docs/CLI.md)                          | Command-line interface and cron jobs           |
| [MCP Integration](docs/MCP.md)                        | AI assistant integration (Claude, etc.)        |
| [CrowdStrike Import](docs/CROWDSTRIKE_IMPORT.md)      | Vulnerability import technical details         |
| [Testing Guide](docs/TESTING.md)                      | Test infrastructure and patterns               |
| [Troubleshooting](docs/TROUBLESHOOTING.md)            | Common issues and solutions                    |

## Contributing

We welcome contributions! Please follow these guidelines:

1. **Fork** the repository
2. **Create a feature branch** (`024-feature-name`)
3. **Implement features** following security-first principles
4. **Follow code style** (Kotlin conventions, ESLint)
5. **Verify build passes** (`./gradlew build`)
6. **Review for security vulnerabilities** (XSS, SQL injection, etc.)
7. **Submit a pull request** with clear description

For major changes, please open an issue first to discuss what you would like to change.

## Roadmap

- [X] Automated email notifications for outdated assets
- [X] Vulnerability exception request workflow
- [X] Materialized views for performance optimization
- [X] Last admin protection
- [X] Asset and workgroup criticality levels
- [X] Nested workgroup hierarchies
- [X] Compliance frameworks mapping (SOC2, ISO 27001, NIST)
- [X] OAuth2/OIDC identity provider management
- [X] Passkey/WebAuthn authentication
- [X] MCP AI assistant integration
- [X] Admin summary emails
- [X] S3 import pipeline
- [X] Risk assessment module
- [X] Vulnerability statistics dashboard
- [ ] Advanced vulnerability correlation and trending analytics
- [ ] Automated remediation workflows with approval gates
- [ ] Integration with additional vulnerability scanners (Tenable, Qualys)
- [ ] Advanced reporting dashboards
- [ ] SAML/LDAP authentication
- [ ] Risk scoring engine with machine learning

## License

[GNU Affero General Public License v3.0 (AGPL-3.0)](https://www.gnu.org/licenses/agpl-3.0)

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

## Contact

**Maintainer:** Markus "flake" Schmall

- **Email:** markus@schmall.io
- **Mastodon:** [@flakedev@infosec.exchange](https://infosec.exchange/@flakedev)
- **Telegram:** @flakedev

## Acknowledgments

Built with assistance from AI-powered development tools (Anthropic Claude).

---

**Alpha Software:** This project is under active development. Features may change, and breaking changes may occur. Not recommended for production use without thorough testing.
