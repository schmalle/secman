# SecMan - Security Management Platform

![Landing Page](docs/landing.png)

**A comprehensive security requirement, vulnerability, and risk assessment management platform**

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Status: Alpha](https://img.shields.io/badge/Status-Alpha-yellow.svg)]()

---

## Overview

SecMan is a full-stack security management platform that helps organizations manage security requirements, track vulnerabilities, assess risks, and maintain compliance. Originally started as a simple requirement formatter, it has evolved into a comprehensive security operations tool with vulnerability management, asset tracking, workgroup-based access control, and AI assistant integration.

**Key Capabilities:**
- 📋 Security requirements management with version control and release tracking
- 🔍 Vulnerability management with CrowdStrike Falcon integration
- 🖥️ Asset inventory with network scan import (Nmap, Masscan)
- 👥 Multi-tenant workgroup-based access control
- 🤖 AI assistant integration via Model Context Protocol (MCP)
- 📊 Multi-format export (Excel, Word, CSV) with automated translation
- 🔐 Enterprise authentication (JWT, OAuth2, GitHub SSO)

## Technology Stack

- **Backend**: Micronaut 4.10 + Kotlin 2.2 (Java 21)
- **Frontend**: Astro 5.15 + React 19 + Bootstrap 5.3
- **Database**: MariaDB 12 with Hibernate JPA
- **Helper Tools**: Python 3.11+ (CrowdStrike Falcon API integration)
- **Build System**: Gradle 9.2 (Kotlin DSL)
- **CLI Tools**: Kotlin-based notification system and data import utilities
- **Authentication**: JWT with OAuth2 and GitHub SSO

## Features

### Requirements Management
- ✅ Create, edit, and organize security requirements
- ✅ Version control with release management (DRAFT → PUBLISHED → ARCHIVED)
- ✅ Release comparison and diff visualization
- ✅ Point-in-time requirement snapshots
- ✅ Excel and Word export with customizable templates
- ✅ Automated translation (20+ languages via OpenRouter API)
- ✅ Relationship tracking (requirements ↔ norms ↔ use cases)

### Vulnerability Management
- ✅ Import vulnerabilities from Excel/CSV or CrowdStrike Falcon API
- ✅ CVE tracking with CVSS severity scoring
- ✅ Vulnerability exception requests with approval workflow
- ✅ Auto-approval logic for exception requests
- ✅ Workgroup-scoped vulnerability views
- ✅ AWS account-based vulnerability overview
- ✅ Days-open tracking and trending
- ✅ Vulnerability lifecycle management
- ✅ Real-time SSE updates for exception request badge counts
- ✅ Outdated assets materialized view for fast performance (<2s for 10K+ assets)

### Asset Management
- ✅ Import assets from Nmap XML scans
- ✅ Import assets from Masscan XML scans
- ✅ Network service discovery and port tracking
- ✅ Asset metadata (cloud account ID, AD domain, OS version)
- ✅ Workgroup-based asset assignment with nested hierarchy support
- ✅ Criticality levels (LOW, MEDIUM, HIGH, CRITICAL) for assets and workgroups
- ✅ Vulnerability correlation per asset
- ✅ Asset profile views with comprehensive details
- ✅ Outdated asset tracking with automated email notifications

### Access Control & Multi-Tenancy
- ✅ **Workgroups**: Organize users and assets into isolated groups with nested hierarchy
- ✅ **User Mapping**: CSV/Excel upload for AWS account ↔ user and AD domain associations
- ✅ **Role-Based Access Control (RBAC)**:
  - `USER` - Basic access to assigned workgroups
  - `ADMIN` - Full system administration
  - `VULN` - Vulnerability management permissions
  - `RELEASE_MANAGER` - Release creation and management
  - `SECCHAMPION` - Security champion access
- ✅ **Row-Level Security**: Users see only their workgroup resources + owned items
- ✅ **Last Admin Protection**: System prevents deletion/demotion of the last ADMIN user
- ✅ **Unified Access Control**: AWS account ID and AD domain-based asset filtering

### AI Assistant Integration
- ✅ **MCP Tools** (Model Context Protocol) for AI assistants:
  - `get_assets` - Query asset inventory
  - `get_scans` - Retrieve scan history
  - `get_vulnerabilities` - Search vulnerabilities
  - `search_products` - Find products/services
  - `get_asset_profile` - Comprehensive asset profiles
- ✅ Permission-scoped API access (ASSETS_READ, SCANS_READ, VULNERABILITIES_READ)
- ✅ Rate limiting (1000 req/min, 50K req/hour)

### Helper Tools
- ✅ **Falcon Vulnerability Query Tool** (`falcon-vulns` CLI):
  - Query CrowdStrike Falcon API with flexible filters
  - Filter by device type (CLIENT/SERVER), severity, days open
  - Export to XLSX, CSV, or TXT formats
  - AD domain and hostname filtering

### Import/Export
- ✅ Excel import for requirements, vulnerabilities, user mappings, assets
- ✅ CSV import for user mappings (auto-detect delimiter/encoding)
- ✅ Nmap/Masscan XML import for network scans
- ✅ Export to Excel/Word with release selection
- ✅ Multi-language export (automated translation)
- ✅ Release comparison export with diff highlighting
- ✅ Notification log export to CSV (admin only)

### Email Notifications (Feature 035)
- ✅ Automated outdated asset notifications (2-level escalation: professional → urgent)
- ✅ New vulnerability notifications (opt-in via user preferences)
- ✅ Email aggregation (one email per owner with all their assets)
- ✅ Thymeleaf HTML templates with plain-text fallback
- ✅ SMTP with 3-attempt retry logic and 1s delay
- ✅ Comprehensive audit logging (NotificationLog table)
- ✅ CLI-based notification sender with dry-run mode
- ✅ User preference management UI (/notification-preferences)
- ✅ Notification log viewer with filtering and CSV export (admin only)

## Quick Start

### Prerequisites

- Java 21 (JDK 21)
- Node.js 20+
- MariaDB 12+
- Gradle 9.2+
- Python 3.11+ (optional, for helper tools)
- Git

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

| Username | Password | Roles |
|----------|----------|-------|
| `adminuser` | `password` | ADMIN, USER |
| `normaluser` | `password` | USER |
| `vulnuser` | `password` | VULN, USER |
| `releaseuser` | `password` | RELEASE_MANAGER, USER |

**⚠️ IMPORTANT:** Change default passwords immediately in production!

## Development

### Project Structure

```
secman/
├── src/
│   ├── backendng/          # Kotlin/Micronaut backend
│   │   ├── src/main/kotlin/com/secman/
│   │   │   ├── controller/ # REST controllers
│   │   │   ├── domain/     # JPA entities
│   │   │   ├── repository/ # Data repositories
│   │   │   ├── service/    # Business logic
│   │   │   └── mcp/        # MCP tools
│   ├── cli/                # Kotlin CLI tools (notifications)
│   │   └── src/main/kotlin/com/secman/cli/
│   │       ├── commands/   # CLI commands
│   │       └── service/    # Business logic
│   ├── frontend/           # Astro + React frontend
│   │   ├── src/
│   │   │   ├── components/ # React components
│   │   │   ├── pages/      # Astro pages
│   │   │   └── services/   # API clients
│   └── helper/             # Python helper tools
│       ├── src/
│       │   ├── cli/        # Falcon CLI commands
│       │   ├── services/   # Business logic
│       │   └── exporters/  # Export utilities
└── scripts/                # Utility scripts
```

### Common Commands

```bash
# Backend
cd src/backendng
./gradlew build              # Build
./gradlew run                # Start server (port 8080)

# Frontend
cd src/frontend
npm run dev                  # Dev server (port 4321)
npm run build                # Production build

# CLI Tools
cd src/cli
./gradlew run --args='send-notifications'              # Send email notifications
./gradlew run --args='send-notifications --dry-run'    # Dry run mode

# Helper Tools
cd src/helper
pip install -e .             # Install in dev mode
falcon-vulns --help          # CLI help
ruff check .                 # Lint
```

### CLI Notification System

SecMan includes a Kotlin-based CLI for sending automated email notifications:

```bash
# Send outdated asset notifications (respects user preferences)
./gradlew cli:run --args='send-notifications'

# Dry run mode (no emails sent)
./gradlew cli:run --args='send-notifications --dry-run --verbose'

# Send only outdated asset notifications (skip new vulnerabilities)
./gradlew cli:run --args='send-notifications --outdated-only'
```

**Features:**
- Email notifications for outdated assets (2-level escalation)
- New vulnerability notifications (opt-in via user preferences)
- Thymeleaf HTML templates with plain-text fallback
- SMTP with retry logic and audit logging
- SSE real-time progress updates

## Helper Tools

### Falcon Vulnerability Query Tool

Query CrowdStrike Falcon API for vulnerability data with flexible filtering.

**Installation:**
```bash
cd src/helper
pip install -r requirements.txt
pip install -e .
```

**Configuration:**
```bash
export FALCON_CLIENT_ID="your_client_id"
export FALCON_CLIENT_SECRET="your_client_secret"
export FALCON_CLOUD_REGION="us-1"  # us-1, us-2, eu-1, us-gov-1
```

**Usage Examples:**
```bash
# Critical vulnerabilities on servers, open 30+ days
falcon-vulns --device-type SERVER --severity CRITICAL --min-days-open 30

# Export to CSV with domain filter
falcon-vulns --device-type BOTH --severity HIGH CRITICAL \
             --ad-domain CORP.LOCAL \
             --output vulns.csv --format CSV

# Verbose logging with hostname filter
falcon-vulns --device-type CLIENT --hostname WEB-* --verbose
```

See [src/helper/README.md](src/helper/README.md) for full documentation.

## API Documentation

### Authentication
All endpoints require JWT authentication via `Authorization: Bearer <token>` header.

**Login:**
```bash
POST /api/auth/login
{
  "username": "adminuser",
  "password": "password"
}
```

### Key Endpoints

| Endpoint | Method | Description | Roles |
|----------|--------|-------------|-------|
| `/api/requirements` | GET | List requirements | Authenticated |
| `/api/requirements/export/xlsx` | GET | Export to Excel | Authenticated |
| `/api/releases` | POST | Create release | ADMIN, RELEASE_MANAGER |
| `/api/assets` | GET | List assets (workgroup-filtered) | Authenticated |
| `/api/vulnerabilities/current` | GET | Current vulnerabilities | ADMIN, VULN |
| `/api/vulnerability-exception-requests` | POST | Create exception request | Authenticated |
| `/api/vulnerability-exception-requests/pending/count` | GET | Badge count for pending requests | ADMIN, VULN |
| `/api/outdated-assets` | GET | List outdated assets (paginated) | Authenticated |
| `/api/materialized-view-refresh/trigger` | POST | Trigger view refresh | ADMIN |
| `/api/notification-preferences` | GET/PUT | Manage notification preferences | Authenticated |
| `/api/notification-logs` | GET | Notification audit logs | ADMIN |
| `/api/account-vulns` | GET | User's account vulnerabilities | USER (non-admin) |
| `/api/workgroups` | POST | Create workgroup | ADMIN |
| `/api/import/upload-nmap-xml` | POST | Import Nmap scan | ADMIN |
| `/api/import/upload-user-mappings-csv` | POST | Import user mappings | ADMIN |

See [CLAUDE.md](CLAUDE.md) for complete API reference.

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
**Helper Tools:** Environment variables (see above)

### Key Environment Variables

```bash
# Database
DB_HOST=localhost
DB_PORT=3306
DB_NAME=secman
DB_PASSWORD=CHANGEME

# JWT
JWT_SECRET=your-secret-key-here

# OAuth (optional)
GITHUB_CLIENT_ID=your-github-client-id
GITHUB_CLIENT_SECRET=your-github-secret

# Translation (optional)
OPENROUTER_API_KEY=your-openrouter-key

# Falcon API (optional)
FALCON_CLIENT_ID=your-falcon-client-id
FALCON_CLIENT_SECRET=your-falcon-secret
FALCON_CLOUD_REGION=us-1
```

## Development Workflow

1. **Implement features** following security-first principles
2. **Follow RBAC patterns** for access control
3. **Ensure API backward compatibility**
4. **Commit** with conventional commit messages
5. **Review security implications** before completion

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
# ... implement, test manually, review security ...

# Commit with conventional commits
git add .
git commit -m "feat(scope): description"

# Push and create PR
git push origin 024-feature-name
```


## Documentation

Comprehensive documentation is available for key system components:

### CrowdStrike Vulnerability Import

The CrowdStrike vulnerability import system uses a transactional replace pattern to prevent duplicate entries and ensure data consistency.

**Key Features:**
- **Duplicate Prevention**: Each (Asset, CVE) combination exists exactly once
- **Idempotency**: Same import → same database state (no duplicates)
- **Remediation Tracking**: Missing CVEs indicate patched vulnerabilities
- **Transaction Safety**: All-or-nothing import with automatic rollback on failure

**Documentation**: [docs/CROWDSTRIKE_IMPORT.md](docs/CROWDSTRIKE_IMPORT.md)

This document explains:
- How the transactional replace pattern works
- Why it was chosen over alternatives (upsert, soft delete, differential sync)
- Edge case handling (concurrent imports, duplicate hostnames, missing CVE IDs)
- Performance characteristics and optimization notes
- Usage examples and integration tests


## Contributing

We welcome contributions! Please follow these guidelines:

1. **Fork** the repository
2. **Create a feature branch** (`024-feature-name`)
3. **Implement features** following security-first principles
4. **Follow code style** (Kotlin conventions, ESLint)
5. **Review for security vulnerabilities** (XSS, SQL injection, etc.)
6. **Submit a pull request** with clear description

For major changes, please open an issue first to discuss what you would like to change.

## Roadmap

- [x] Automated email notifications for outdated assets (Feature 035)
- [x] Vulnerability exception request workflow (Feature 031)
- [x] Materialized views for performance optimization (Feature 034)
- [x] Last admin protection (Feature 037)
- [x] Asset and workgroup criticality levels (Feature 039)
- [x] Nested workgroup hierarchies (Feature 040)
- [ ] Advanced vulnerability correlation and trending analytics
- [ ] Automated remediation workflows with approval gates
- [ ] Integration with additional vulnerability scanners (Tenable, Qualys)
- [ ] Advanced reporting dashboards with Chart.js/Recharts
- [ ] Mobile application (React Native)
- [ ] SAML/LDAP authentication
- [ ] Compliance frameworks mapping (SOC2, ISO 27001, NIST)
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

**⚠️ Alpha Software:** This project is under active development. Features may change, and breaking changes may occur. Not recommended for production use without thorough testing.
