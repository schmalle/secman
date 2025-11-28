# Secman Documentation

Security requirement and risk assessment management tool documentation.

---

## Quick Links

| Document | Description |
|----------|-------------|
| [Environment Variables](./ENVIRONMENT.md) | Complete reference for all configuration variables |
| [Deployment Guide](./DEPLOYMENT.md) | Production deployment on Linux (Amazon Linux, Ubuntu, RHEL) |
| [CLI Reference](./CLI.md) | Command-line interface usage and cron job setup |
| [MCP Integration](./MCP_INTEGRATION.md) | Model Context Protocol for AI assistants |
| [CrowdStrike Import](./CROWDSTRIKE_IMPORT.md) | Technical reference for vulnerability import |

---

## Documentation Overview

### Configuration

- **[ENVIRONMENT.md](./ENVIRONMENT.md)** - All environment variables for backend, frontend, and CLI components with templates and security best practices.

### Deployment & Operations

- **[DEPLOYMENT.md](./DEPLOYMENT.md)** - Complete production deployment guide including:
  - System requirements and prerequisites
  - MariaDB database setup
  - Backend (Kotlin/Micronaut) deployment
  - Frontend (Astro/React) deployment
  - Nginx reverse proxy configuration
  - SSL/TLS with Let's Encrypt
  - Systemd service management
  - Security hardening
  - Monitoring and troubleshooting

### Command-Line Interface

- **[CLI.md](./CLI.md)** - CLI tool documentation including:
  - CrowdStrike vulnerability queries
  - Automated notification emails
  - User mapping management
  - Cron job configuration
  - AWS Secrets Manager integration

### Integrations

- **[MCP_INTEGRATION.md](./MCP_INTEGRATION.md)** - Model Context Protocol integration for AI assistants:
  - Claude Desktop configuration
  - API key management
  - Available MCP tools
  - Security considerations

### Technical References

- **[CROWDSTRIKE_IMPORT.md](./CROWDSTRIKE_IMPORT.md)** - Deep dive into:
  - Duplicate prevention mechanism
  - Transactional replace pattern
  - JPA cascade configuration
  - Timestamp calculation
  - Edge case handling

---

## Architecture

```
                                   Internet
                                      |
                              [Nginx :80/:443]
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

**Components:**
- **Backend**: Kotlin 2.2.21, Java 21, Micronaut 4.10, Hibernate JPA
- **Frontend**: Astro 5.15, React 19, Bootstrap 5.3, Axios
- **Database**: MariaDB 12 with auto-migration
- **CLI**: Picocli 4.7, CrowdStrike API integration

---

## Quick Start

### Development Setup

```bash
# Backend (port 8080)
cd src/backendng
./gradlew run

# Frontend (port 4321)
cd src/frontend
npm install
npm run dev
```

### Production Deployment

1. Set environment variables (see [ENVIRONMENT.md](./ENVIRONMENT.md))
2. Follow [DEPLOYMENT.md](./DEPLOYMENT.md) for full setup
3. Configure cron jobs per [CLI.md](./CLI.md)

### Environment Variables Quick Reference

**Required for production:**
```bash
# Database
DB_USERNAME=secman
DB_PASSWORD=secure_password

# Security (generate these!)
JWT_SECRET=$(openssl rand -base64 32)
SECMAN_ENCRYPTION_PASSWORD=$(openssl rand -hex 32)
SECMAN_ENCRYPTION_SALT=$(openssl rand -hex 8)

# URLs
BACKEND_BASE_URL=https://api.yourdomain.com
FRONTEND_URL=https://secman.yourdomain.com

# Email
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=noreply@yourdomain.com
SMTP_PASSWORD=app_password
```

---

## Support

- **GitHub Issues**: Report bugs and feature requests
- **Project Repository**: https://github.com/schmalle/secman

---

*Last Updated: 2025-11-26*
