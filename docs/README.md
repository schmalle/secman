# Secman Documentation

Security requirement and risk assessment management tool.

**Last Updated:** 2026-04-23

---

## Table of Contents

| Document                                      | Description                                    |
| --------------------------------------------- | ---------------------------------------------- |
| [Architecture](./ARCHITECTURE.md)             | System design, data model, and design patterns |
| [Deployment Guide](./DEPLOYMENT.md)           | Production deployment on Linux                 |
| [Environment Variables](./ENVIRONMENT.md)     | Complete configuration reference               |
| [CLI Reference](./CLI.md)                     | Command-line interface and cron jobs           |
| [MCP Integration](./MCP.md)                   | AI assistant integration (Claude, etc.)        |
| [CrowdStrike Import](./CROWDSTRIKE_IMPORT.md) | Vulnerability import technical details         |
| [Testing Guide](./TESTING.md)                 | Test infrastructure and patterns               |
| [Troubleshooting](./TROUBLESHOOTING.md)       | Common issues and solutions                    |
| [E2E Exception Workflow](./E2E_EXCEPTION_WORKFLOW_TEST.md) | End-to-end exception workflow test    |
| [S3 User Mapping Import](./S3_USER_MAPPING_IMPORT.md) | S3-based user mapping imports            |
| [Skills & Agents](./SKILLS_AND_AGENTS.md)             | Claude Code skills and agent reference   |
| [Credential Management](./1PASSWORD.md)               | Secret management with pass-cli          |
| [CrowdStrike Checkin Monitor](../src/clinotify/README.md) | Telegram alert when CrowdStrike imports go stale |
| [Docker Deployment](../docker/README.md)                  | Docker container deployment             |
| [Database Installation](../scriptpp/install/db/README.md) | Database setup scripts and defaults    |
| [MCP Go Client](../scriptpp/mcp/README.md)             | Standalone Go MCP client example         |
| [UI Screenshots](../pictures/README.md)               | Latest UI screenshots                    |

---

## Quick Start by Role

### Administrators

Setting up and maintaining Secman in production:

1. **[Environment Variables](./ENVIRONMENT.md)** - Configure all components
2. **[Deployment Guide](./DEPLOYMENT.md)** - Install on Linux servers
3. **[CLI Reference](./CLI.md)** - Set up automated cron jobs
4. **[Troubleshooting](./TROUBLESHOOTING.md)** - Diagnose and fix issues

### Developers

Understanding and extending the codebase:

1. **[Architecture](./ARCHITECTURE.md)** - System design and patterns
2. **[Testing Guide](./TESTING.md)** - Test infrastructure
3. **[Environment Variables](./ENVIRONMENT.md)** - Local development setup
4. **[CrowdStrike Import](./CROWDSTRIKE_IMPORT.md)** - Import patterns

### Security Teams

Using Secman for security management:

1. **[MCP Integration](./MCP.md)** - AI assistant workflows
2. **[CrowdStrike Import](./CROWDSTRIKE_IMPORT.md)** - Vulnerability data
3. **[CLI Reference](./CLI.md)** - Automated queries and notifications

---

## Architecture Overview

```
                                   Internet
                                      |
                              [Nginx :80/:443]
                                 Reverse Proxy
                                      |
            +-------------------------+-------------------------+
            |                         |                         |
       /api/*                    /oauth/*                      /*
            |                         |                         |
            v                         v                         v
    [Backend :8080]           [Backend :8080]          [Frontend :4321]
    Kotlin/Micronaut          OAuth callbacks          Astro/React SSR
            |                                                   |
            +---------------------------------------------------+
                                      |
                              [MariaDB :3306]
```

**Technology Stack:**

- **Backend**: Kotlin 2.3.20, Java 21, Micronaut 4.10, Hibernate JPA
- **Frontend**: Astro 6.1, React 19, Bootstrap 5.3, Axios
- **Database**: MariaDB 11.4 with Flyway migrations + Hibernate auto-update
- **CLI**: Picocli 4.7.7, CrowdStrike API, AWS SDK v2
- **Build**: Gradle 9.4.1 (Kotlin DSL)

For detailed architecture, see [ARCHITECTURE.md](./ARCHITECTURE.md).

---

## Development Setup

### Backend (port 8080)

```bash
./scriptpp/startbackenddev.sh
```

### Frontend (port 4321)

```bash
cd src/frontend
npm install
npm run dev
```

### CLI

```bash
# Build once
./gradlew :cli:shadowJar

# Use via wrapper
./scriptpp/secman help
./scriptpp/secman query servers --dry-run
./scriptpp/secman send-admin-summary --dry-run
```

---

## Production Deployment

### 1. Prerequisites

- Linux server (Amazon Linux 2023, Ubuntu 20.04+, RHEL 8+)
- Java 21 (Amazon Corretto recommended)
- Node.js 20.x
- MariaDB 11.4+
- Nginx

### 2. Essential Configuration

```bash
# Database
DB_USERNAME=secman
DB_PASSWORD=CHANGEME

# Security (generate unique values!)
JWT_SECRET=$(openssl rand -base64 32)
SECMAN_ENCRYPTION_PASSWORD=$(openssl rand -hex 32)
SECMAN_ENCRYPTION_SALT=$(openssl rand -hex 8)

# URLs
SECMAN_BACKEND_URL=https://api.yourdomain.com
FRONTEND_URL=https://secman.yourdomain.com

# Email (optional)
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=noreply@yourdomain.com
SMTP_PASSWORD=app_password
```

See [ENVIRONMENT.md](./ENVIRONMENT.md) for complete variable reference.

### 3. Installation

Follow [DEPLOYMENT.md](./DEPLOYMENT.md) for step-by-step instructions:

- MariaDB database setup
- Backend deployment
- Frontend deployment
- Nginx reverse proxy
- SSL/TLS configuration
- Systemd services
- Security hardening

---

## MCP Integration (AI Assistants)

Connect Claude Desktop, Claude Code, or other MCP clients:

### Claude Code (Recommended)

```bash
claude mcp add --transport http secman http://localhost:8080/mcp \
  --header "X-MCP-API-Key: sk-your-api-key" \
  --header "X-MCP-User-Email: your.email@company.com"
```

### Claude Desktop

```json
{
  "mcpServers": {
    "secman": {
      "url": "http://localhost:8080/mcp",
      "headers": {
        "X-MCP-API-Key": "sk-your-api-key",
        "X-MCP-User-Email": "your.email@company.com"
      }
    }
  }
}
```

See [MCP.md](./MCP.md) for complete setup, fallback options, and available tools.

---

## Automated Operations

### Vulnerability Import (CrowdStrike)

```bash
# Daily vulnerability sync
0 2 * * * /opt/secman/bin/secman query servers
```

### Email Notifications

```bash
# Weekly vulnerability reports
0 8 * * 1 /opt/secman/bin/secman send-notifications
```

### Admin Summary

```bash
# Weekly admin summary email
0 9 * * 1 /opt/secman/bin/secman send-admin-summary
```

See [CLI.md](./CLI.md) for all commands and cron setup.

---

## Health Checks

```bash
# Backend
curl http://localhost:8080/health
# Expected: {"status":"UP","service":"secman-backend-ng","version":"0.1"}

# Frontend
curl http://localhost:4321/

# External (via nginx)
curl https://secman.yourdomain.com/
```

---

## Troubleshooting Quick Reference

| Issue               | Check                                |
| ------------------- | ------------------------------------ |
| Backend won't start | `journalctl -u secman-backend -n 50` |
| Frontend blank page | Browser console, `PUBLIC_API_URL`     |
| Database connection | `mysql -u secman -p secman`          |
| 502 Bad Gateway     | `systemctl status secman-backend`    |
| MCP auth fails      | API key valid? Headers correct?      |
| OAuth callback fail | Check identity provider config       |

See [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) for detailed solutions.

---

## Support

- **GitHub Issues**: [github.com/schmalle/secman/issues](https://github.com/schmalle/secman/issues)
- **Documentation**: This `/docs` directory

---

## Documentation Map

```
docs/
├── README.md              <- You are here (index)
├── ARCHITECTURE.md        <- System design & data model
├── DEPLOYMENT.md          <- Production deployment
├── ENVIRONMENT.md         <- Configuration reference
├── CLI.md                 <- Command-line interface
├── MCP.md                 <- AI assistant integration
├── CROWDSTRIKE_IMPORT.md  <- Vulnerability import
├── TESTING.md             <- Test infrastructure
├── TROUBLESHOOTING.md     <- Common issues & solutions
├── E2E_EXCEPTION_WORKFLOW_TEST.md  <- Exception E2E test
├── S3_USER_MAPPING_IMPORT.md      <- S3 import guide
├── SKILLS_AND_AGENTS.md           <- Skills & agents reference
├── 1PASSWORD.md                   <- Credential management with pass-cli
src/clinotify/
└── README.md                      <- CrowdStrike checkin Telegram monitor
docker/
└── README.md                      <- Docker container deployment
scriptpp/install/db/
└── README.md                      <- Database setup scripts
scriptpp/mcp/
└── README.md                      <- Go MCP client example
pictures/
└── README.md                      <- UI screenshots
```

---

*For CLI help: `./scriptpp/secman help`*
