# Documentation Index

Security requirement, vulnerability and risk assessment management tool.

```
                Internet
                   │
            [nginx :80/:443]
                   │
   /api/*  ───►  Backend :8080  ◄───  /oauth/*
                   │
                   ▼
            MariaDB :3306
                   ▲
                   │
   /*      ───►  Frontend :4321 (Astro/React SSR)
```

Stack: Kotlin 2.3.21 / Java 21 · Micronaut 4.10 · Hibernate JPA · Astro 6.3 / React 19 · Bootstrap 5.3 · MariaDB 11.4 · Gradle 9.5.0 · Picocli 4.7.7.

## Index

| Doc | What's inside |
|---|---|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Layered architecture, data model, design patterns |
| [DEPLOYMENT.md](./DEPLOYMENT.md) | Linux production: nginx, systemd, SSL, hardening, monitoring |
| [ENVIRONMENT.md](./ENVIRONMENT.md) | All env vars (backend / frontend / CLI) |
| [CLI.md](./CLI.md) | CLI commands, cron, S3, AWS Secrets Manager |
| [MCP.md](./MCP.md) | MCP tools, API keys, delegation, troubleshooting |
| [CROWDSTRIKE_IMPORT.md](./CROWDSTRIKE_IMPORT.md) | Transactional-replace pattern + JPA cascade trap |
| [TESTING.md](./TESTING.md) | JUnit/Mockk/Testcontainers stack and patterns |
| [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) | Symptom → fix |
| [E2E_EXCEPTION_WORKFLOW_TEST.md](./E2E_EXCEPTION_WORKFLOW_TEST.md) | Vuln-exception MCP E2E |
| [S3_USER_MAPPING_IMPORT.md](./S3_USER_MAPPING_IMPORT.md) | `import-s3` / `download-s3` / `print-s3` / `list-bucket` |
| [SKILLS_AND_AGENTS.md](./SKILLS_AND_AGENTS.md) | Claude Code skills + sub-agents |
| [PASS_CLI.md](./PASS_CLI.md) | `pass-cli` (Proton Pass) secret resolution |
| [../src/clinotify/README.md](../src/clinotify/README.md) | CrowdStrike checkin Telegram monitor |
| [../docker/README.md](../docker/README.md) | Docker container deployment |
| [../scripts/install/db/README.md](../scripts/install/db/README.md) | DB setup scripts and defaults |
| [../scripts/mcp/README.md](../scripts/mcp/README.md) | Standalone Go MCP client |
| [../pictures/README.md](../pictures/README.md) | UI screenshots |

## By role

- **Admins** — `DEPLOYMENT.md` → `ENVIRONMENT.md` → `CLI.md` (cron) → `TROUBLESHOOTING.md`.
- **Developers** — `ARCHITECTURE.md` → `TESTING.md` → `ENVIRONMENT.md` → `CROWDSTRIKE_IMPORT.md` → `CLAUDE.md` (root).
- **Security teams** — `MCP.md` → `CROWDSTRIKE_IMPORT.md` → `CLI.md`.

## Local dev (TL;DR)

```bash
# DB
cd scripts/install/db && ./installdb.sh && cd -

# Backend (port 8080)
./scripts/startbackenddev.sh

# Frontend (port 4321)
cd src/frontend && npm install && npm run dev

# CLI (build once, then wrapper)
./gradlew :cli:shadowJar
./scripts/secman help
```

First-run admin password is logged to backend stdout — copy it from the `DEFAULT ADMIN USER CREATED` block.

## Production minimum

```bash
DB_CONNECT=jdbc:mariadb://localhost:3306/secman
DB_PASSWORD=...
JWT_SECRET=$(openssl rand -base64 32)
SECMAN_ENCRYPTION_PASSWORD=$(openssl rand -hex 32)
SECMAN_ENCRYPTION_SALT=$(openssl rand -hex 8)
SECMAN_BACKEND_URL=https://api.example.com
FRONTEND_URL=https://secman.example.com
```

Full reference: [`ENVIRONMENT.md`](./ENVIRONMENT.md). Step-by-step deploy: [`DEPLOYMENT.md`](./DEPLOYMENT.md).

## MCP

```bash
# Claude Code
claude mcp add --transport http secman http://localhost:8080/mcp \
  --header "X-MCP-API-Key: sk-..." \
  --header "X-MCP-User-Email: you@company.com"
```

Or in `claude_desktop_config.json`:
```json
{ "mcpServers": { "secman": {
    "url": "http://localhost:8080/mcp",
    "headers": { "X-MCP-API-Key": "sk-...", "X-MCP-User-Email": "you@company.com" }
} } }
```
`X-MCP-User-Email` is mandatory for `tools/list` and `tools/call`. Full setup: [`MCP.md`](./MCP.md).

## Cron-friendly automation

```cron
# vuln sync: nightly
0 2 * * *      /opt/secman/bin/secman query servers
# notification email: weekly Mon 08:00
0 8 * * 1      /opt/secman/bin/secman send-notifications
# admin summary: weekly Mon 09:00
0 9 * * 1      /opt/secman/bin/secman send-admin-summary
# CrowdStrike freshness: every 10 min
*/10 * * * *   TELEGRAM_BOT_TOKEN=… TELEGRAM_CHAT_ID=… \
               /opt/secman/src/clinotify/check_crowdstrike_checkin.py \
               --url https://secman.example.com --max-age-minutes 120
```

## Health & quick triage

```bash
curl http://localhost:8080/health    # {"status":"UP",...}
curl http://localhost:4321/
curl https://secman.example.com/
```

| Symptom | Check first |
|---|---|
| Backend won't start | `journalctl -u secman-backend -n 50` |
| Blank frontend | browser console; `PUBLIC_API_URL` |
| DB | `mysql -u secman -p secman` |
| 502 | `systemctl status secman-backend secman-frontend` |
| MCP auth fails | API key valid? `X-MCP-User-Email` set? |
| OAuth callback fails | identity-provider config |

Deeper: [`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md).

## Support

[GitHub issues](https://github.com/schmalle/secman/issues).
