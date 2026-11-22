# SecMan

Security requirement, vulnerability and risk-assessment platform.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Status: Alpha](https://img.shields.io/badge/Status-Alpha-yellow.svg)]()

![Landing Page](docs/landing.png)

## What it does

- Security requirement lifecycle (versioned releases: PREPARATION → ALIGNMENT → ACTIVE → ARCHIVED), Excel/Word/CSV export, automated translation (OpenRouter, 10 languages).
- Vulnerability management: CrowdStrike Falcon import, GitHub App Dependabot alert tracking with 30-day non-decrease owner alerts, exception-request workflow, statistics, heatmap, materialized view for outdated-asset queries. CrowdStrike findings use first detection on the affected system as their SLA start.
- Asset inventory: Nmap/Masscan import, AD/cloud metadata, criticality, workgroup-scoped access.
- Risk register, demand classification, compliance-framework mapping (SOC2, ISO 27001, NIST), optional AI-assisted pre-fill of compliance answers (OpenRouter LLM, confidence scoring + citations, human review required).
- AWS account onboarding: imports requesting notifications use a saved welcome-email (default) or risk-assessment policy. The welcome subject and body are editable in the web UI; advanced guided questionnaires remain supported.
- Risk assessments support SaaS and COTS solution names, searchable existing assessors/respondents, and email-only respondents who need no SecMan account.
- AuthN: local (BCrypt) + OAuth2/OIDC + Passkey/WebAuthn + optional MFA.
- AuthZ: 10-role RBAC (including the additive BETA eligibility role) with asset access through workgroup, AWS account, AD domain, and sharing grants.
- AI integration: MCP server (Streamable HTTP) for Claude Desktop/Code, Paperclip, and other MCP clients, with mandatory user delegation.
- Email: SMTP/SES with retry + audit, Thymeleaf HTML templates, escalation tiers.
- Inactive-user cleanup: an opt-in nightly job warns users before deletion, records each run, and applies a safety brake; dry-run is the default.

## Stack

Kotlin 2.4.21 / Java 25 · Micronaut 5.2.5 · Hibernate JPA · Astro 7.4.13 + React 19 · Bootstrap 5.3 · MariaDB 11.4 · Gradle 9.9.1 (Kotlin DSL) · Picocli 4.7.7 · AWS SDK v2 · Go 1.24 (mobile relay).

## Quick Start (development)

Prereqs: Java 25, Node 22.23.4+, MariaDB 11.4+, Git, and an authenticated [Proton Pass CLI](docs/PASS_CLI.md) (`pass-cli`) for the dev start scripts.

```bash
git clone https://github.com/schmalle/secman.git && cd secman
cd scripts/install/db && ./installdb.sh && cd -    # creates DB 'secman' / user 'secman'/'CHANGEME'
./scripts/startbackenddev.sh                       # canonical dev start (pass-cli wraps gradle)
cd src/frontend && npm install && cd - && ./scripts/startfrontenddev.sh   # canonical dev start, http://localhost:4321
```

On startup with an empty users table the backend creates `admin` with a 20-char random password printed once to stdout (look for `DEFAULT ADMIN USER CREATED`). Copy it immediately. Change it after first login.

> **Production deploys** must replace the default DB password and generate `JWT_SECRET`, `SECMAN_ENCRYPTION_PASSWORD`, `SECMAN_ENCRYPTION_SALT`. See `docs/DEPLOYMENT.md`.

## Layout

```
src/
  backendng/   Kotlin/Micronaut: domain → repository → service → controller, mcp/, dto/, filter/
  frontend/   Astro pages + React islands, services/ (Axios)
  cli/        Picocli commands + service/
  adread/     Python AD workgroup import + AWS asset synchronization
  relay/      Go: zero-dependency DMZ relay for the mobile app
  clinotify/  stdlib-only Python monitoring helpers
docs/         see below
scripts/     ALL scripts live here (canonical — never invoke gradle/npm/etc. directly for dev start)
specs/        historical implementation plans (frozen)
```

## Common commands

```bash
# Build & run
./scripts/test/run-isolated-e2e.sh --database-only -- ./gradlew build  # full build incl. tests
./scripts/startbackenddev.sh            # backend dev (port 8080)
./scripts/startfrontenddev.sh           # frontend dev (port 4321)

# CLI (build once, then use the wrapper)
./gradlew :cli:shadowJar
./scripts/secman help
./scripts/secman query servers --device-type SERVER_FAMILY --last-seen-days 1 --dry-run
./scripts/secman send-notifications --dry-run --verbose
./scripts/secman manage-user-mappings list --send-email
./scripts/secman add-vulnerability --hostname host --cve CVE-2024-1234 --criticality HIGH
./scripts/secman export-requirements --format xlsx

# Python importer: preview AWS asset assignments from canonical workgroup owners
./scripts/sync-workgroup-assets.sh --dry-run
# Production alternative (set SECMAN_AWS_SECRET_ID and AWS_REGION first):
./scripts/sync-workgroup-assets-aws.sh --dry-run

# Tests
./scripts/runbackendtests.sh --tests "*ServiceTest*"      # focused service tests
./scripts/runbackendtests.sh --tests "*IntegrationTest*"  # integration (disposable MariaDB, see docs/TESTING.md)
./gradlew :cli:test
# Full-stack and feature tests use the isolated runner described in docs/TESTING.md.
```

## API and configuration

Browser sessions use the HttpOnly `secman_auth` cookie. See the
[API map](CLAUDE.md#api-endpoints-concise) for endpoint families and the
[access guide](docs/USER_ACCESS_OVERVIEW.md) for object-level grants.

For MCP clients, follow [client setup](docs/MCP_CLIENT_SETUP.md). The
[MCP reference](docs/MCP.md) lists tools and permissions. Tool discovery and
calls require both the API key and a delegated user email.

Use the [environment reference](docs/ENVIRONMENT.md) for configuration
and the [deployment guide](docs/DEPLOYMENT.md) for production setup.

## Documentation

- [Install and run SecMan](INSTALL.md)
- [Browse operator, feature, and developer guides](docs/README.md)
- [Review release history](docs/CHANGELOG.md)

## Workflow

Branches: `dev` or `task/<name>` created from `dev`; never commit directly to `main` or `master`. Commits: `type(scope): description` (Conventional Commits). PRs need security review for AuthN/AuthZ/crypto/storage changes.

## Roadmap (open)

Vulnerability correlation/trending · automated remediation workflows · Tenable/Qualys integrations · advanced reporting dashboards · SAML/LDAP · ML risk scoring.

## License & contact

[AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0). Maintainer: Markus "flake" Schmall — markus@schmall.io · [@flakedev@infosec.exchange](https://infosec.exchange/@flakedev). Built with assistance from Anthropic Claude.

> **Alpha software.** Test before production use.
