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

- **Backend**: Micronaut 4.4 + Kotlin 2.1.0 (Java 21)
- **Frontend**: Astro 5.14 + React 19 + Bootstrap 5.3
- **Database**: MariaDB 11.4 with Hibernate JPA
- **Helper Tools**: Python 3.11+ (CrowdStrike Falcon API integration)
- **Build System**: Gradle 8.14+ (Kotlin DSL)
- **Testing**: JUnit 5 + MockK (backend), Playwright (E2E), pytest (Python)
- **Container**: Docker with multi-architecture support (AMD64/ARM64)
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
- ✅ Vulnerability exceptions (IP-based or product-based)
- ✅ Workgroup-scoped vulnerability views
- ✅ AWS account-based vulnerability overview
- ✅ Days-open tracking and trending
- ✅ Vulnerability lifecycle management

### Asset Management
- ✅ Import assets from Nmap XML scans
- ✅ Import assets from Masscan XML scans
- ✅ Network service discovery and port tracking
- ✅ Asset metadata (cloud account ID, AD domain, OS version)
- ✅ Workgroup-based asset assignment
- ✅ Vulnerability correlation per asset
- ✅ Asset profile views with comprehensive details

### Access Control & Multi-Tenancy
- ✅ **Workgroups**: Organize users and assets into isolated groups
- ✅ **User Mapping**: CSV/Excel upload for AWS account ↔ user associations
- ✅ **Role-Based Access Control (RBAC)**:
  - `USER` - Basic access to assigned workgroups
  - `ADMIN` - Full system administration
  - `VULN` - Vulnerability management permissions
  - `RELEASE_MANAGER` - Release creation and management
- ✅ **Row-Level Security**: Users see only their workgroup resources + owned items

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
- ✅ Excel import for requirements, vulnerabilities, user mappings
- ✅ CSV import for user mappings (auto-detect delimiter/encoding)
- ✅ Nmap/Masscan XML import for network scans
- ✅ Export to Excel/Word with release selection
- ✅ Multi-language export (automated translation)
- ✅ Release comparison export with diff highlighting

## Quick Start

### Prerequisites

**Docker (Recommended)**
- Docker 20.10+
- Docker Compose 2.0+
- Git

**Local Development**
- Java 21 (JDK 21)
- Node.js 20+
- MariaDB 11.4+
- Gradle 8.14+
- Python 3.11+ (optional, for helper tools)

### Installation

**Option 1: Docker (Recommended)**

```bash
# Clone repository
git clone https://github.com/schmalle/secman.git
cd secman

# Copy environment template
cp .env.example .env

# Start all services
docker-compose up -d

# View logs
docker-compose logs -f

# Access application
# Frontend: http://localhost:4321
# Backend API: http://localhost:8080/api
```

**Option 2: Local Development**

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
│   │   └── src/test/       # Backend tests
│   ├── frontend/           # Astro + React frontend
│   │   ├── src/
│   │   │   ├── components/ # React components
│   │   │   ├── pages/      # Astro pages
│   │   │   └── services/   # API clients
│   │   └── tests/e2e/      # Playwright tests
│   └── helper/             # Python CLI tools
│       ├── src/
│       │   ├── cli/        # CLI commands
│       │   ├── services/   # Business logic
│       │   └── exporters/  # Export utilities
│       └── tests/          # pytest tests
├── docker/                 # Docker configs
└── scripts/                # Utility scripts
```

### Common Commands

```bash
# Backend
cd src/backendng
./gradlew build              # Build
./gradlew test               # Run tests
./gradlew run                # Start server (port 8080)

# Frontend
cd src/frontend
npm run dev                  # Dev server (port 4321)
npm run build                # Production build
npm run test                 # E2E tests (Playwright)

# Helper Tools
cd src/helper
pip install -e .             # Install in dev mode
falcon-vulns --help          # CLI help
pytest tests/                # Run tests
ruff check .                 # Lint

# Docker
docker-compose up -d         # Start all services
docker-compose logs -f       # View logs
docker-compose down          # Stop all services
docker-compose exec backend ./gradlew test  # Run backend tests in container
```

### Testing

The project follows Test-Driven Development (TDD) with comprehensive test coverage:

```bash
# Backend tests (JUnit 5 + MockK)
cd src/backendng
./gradlew test                    # Run all tests
./gradlew test --tests "*Contract*"  # Contract tests only

# Frontend E2E tests (Playwright)
cd src/frontend
npm run test                      # Run all E2E tests
npm run test:debug                # Debug mode

# Helper tool tests (pytest)
cd src/helper
pytest tests/                     # Run all tests
pytest tests/unit/                # Unit tests only
pytest tests/integration/         # Integration tests
pytest --cov                      # With coverage
```

**Test Coverage Targets:** ≥80% for all components

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
| `/api/account-vulns` | GET | User's account vulnerabilities | USER (non-admin) |
| `/api/workgroups` | POST | Create workgroup | ADMIN |
| `/api/import/upload-nmap-xml` | POST | Import Nmap scan | ADMIN |
| `/api/import/upload-user-mappings-csv` | POST | Import user mappings | ADMIN |

See [CLAUDE.md](CLAUDE.md) for complete API reference.

## Database

- **Engine:** MariaDB 11.4
- **Database:** `secman`
- **User:** `secman` / `CHANGEME`
- **Port:** 3306
- **Schema:** Auto-migrated via Hibernate

**Access:**
```bash
# Via Docker
docker-compose exec database mysql -u secman -pCHANGEME secman

# Local
mysql -u secman -pCHANGEME secman

# Reset database (development only)
./scripts/reset_database.sh
```

## Configuration

**Backend:** `src/backendng/src/main/resources/application.yml`
**Frontend:** `src/frontend/astro.config.mjs`
**Docker:** `.env` file (copy from `.env.example`)
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

## Docker Deployment

### Multi-Architecture Build

```bash
# Build for AMD64 and ARM64
./docker/scripts/build-multiarch.sh latest

# Tag and push
docker tag secman/backend:latest your-registry/secman-backend:latest
docker push your-registry/secman-backend:latest
```

### Production Deployment

```bash
# Configure environment
cp .env.example .env.production
# Edit .env.production with production values

# Deploy
docker-compose -f docker-compose.prod.yml up -d

# Health checks
curl http://localhost:8080/health  # Backend
curl http://localhost:4321/health  # Frontend
```

## Development Workflow

This project follows **Test-Driven Development (TDD)**:

1. **Write contract tests** (API compliance)
2. **Write unit tests** (business logic)
3. **Implement code** to make tests pass
4. **Refactor** while keeping tests green
5. **Commit** with conventional commit messages

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

# Make changes with TDD
# ... write tests, implement, refactor ...

# Commit with conventional commits
git add .
git commit -m "feat(scope): description"

# Push and create PR
git push origin 024-feature-name
```

## Contributing

We welcome contributions! Please follow these guidelines:

1. **Fork** the repository
2. **Create a feature branch** (`024-feature-name`)
3. **Write tests first** (TDD approach)
4. **Ensure tests pass** (`./gradlew test`, `npm test`)
5. **Follow code style** (Kotlin conventions, ESLint)
6. **Submit a pull request** with clear description

For major changes, please open an issue first to discuss what you would like to change.

## Roadmap

- [ ] Advanced vulnerability correlation and trending
- [ ] Automated remediation workflows
- [ ] Integration with additional vulnerability scanners
- [ ] Advanced reporting and dashboards
- [ ] Mobile application (React Native)
- [ ] SAML/LDAP authentication
- [ ] Compliance frameworks (SOC2, ISO 27001)
- [ ] Risk scoring engine with ML

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
