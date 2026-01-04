# Secman Environment Variables Reference

**Last Updated:** 2025-12-04
**Version:** 1.0

This document provides a comprehensive reference for all environment variables used by Secman components.

---

## Table of Contents

1. [Backend Environment Variables](#backend-environment-variables)
2. [Frontend Environment Variables](#frontend-environment-variables)
3. [CLI Environment Variables](#cli-environment-variables)
4. [Quick Reference Tables](#quick-reference-tables)
5. [Environment File Templates](#environment-file-templates)

---

## Backend Environment Variables

The backend (Kotlin/Micronaut) is configured via environment variables, system properties, or `application.yml`.

### Database Configuration


| Variable      | Description               | Default    | Required |
| ------------- | ------------------------- | ---------- | -------- |
| `DB_USERNAME` | MariaDB database username | `secman`   | Yes      |
| `DB_PASSWORD` | MariaDB database password | `CHANGEME` | Yes      |

**Example:**

```bash
export DB_USERNAME=secman
export DB_PASSWORD=your_secure_password
```

### Authentication & Security


| Variable                     | Description                                       | Default             | Required             |
| ---------------------------- | ------------------------------------------------- | ------------------- | -------------------- |
| `JWT_SECRET`                 | JWT signing secret (must be 256 bits / 32 bytes)  | Development default | **Yes (Production)** |
| `SECMAN_ENCRYPTION_PASSWORD` | Encryption password for sensitive database fields | Development default | **Yes (Production)** |
| `SECMAN_ENCRYPTION_SALT`     | Encryption salt (16 hex characters)               | Development default | **Yes (Production)** |

**Security Notes:**

- `JWT_SECRET` must be at least 256 bits for HMAC-SHA256 signing
- Generate with: `openssl rand -base64 32`
- `SECMAN_ENCRYPTION_PASSWORD` is used for encrypting sensitive fields (OAuth secrets, API keys)
- Generate with: `openssl rand -hex 32`
- `SECMAN_ENCRYPTION_SALT` must be exactly 16 hex characters
- Generate with: `openssl rand -hex 8`

**Example:**

```bash
export JWT_SECRET=$(openssl rand -base64 32)
export SECMAN_ENCRYPTION_PASSWORD=$(openssl rand -hex 32)
export SECMAN_ENCRYPTION_SALT=$(openssl rand -hex 8)
```

### Email / SMTP Configuration


| Variable            | Description                  | Default                      | Required |
| ------------------- | ---------------------------- | ---------------------------- | -------- |
| `SMTP_HOST`         | SMTP server hostname         | `smtp.example.com`           | Yes      |
| `SMTP_PORT`         | SMTP server port             | `587`                        | Yes      |
| `SMTP_USERNAME`     | SMTP authentication username | `noreply@secman.example.com` | Yes      |
| `SMTP_PASSWORD`     | SMTP authentication password | `changeme`                   | Yes      |
| `SMTP_FROM_ADDRESS` | Email "From" address         | `noreply@secman.example.com` | Yes      |
| `SMTP_FROM_NAME`    | Email "From" display name    | `Security Management System` | No       |
| `SMTP_ENABLE_TLS`   | Enable STARTTLS              | `true`                       | No       |

**Example for Gmail:**

```bash
export SMTP_HOST=smtp.gmail.com
export SMTP_PORT=587
export SMTP_USERNAME=your-email@gmail.com
export SMTP_PASSWORD=your-app-specific-password
export SMTP_FROM_ADDRESS=your-email@gmail.com
export SMTP_FROM_NAME="Security Management System"
export SMTP_ENABLE_TLS=true
```

**Note:** For Gmail, you must use an [App Password](https://support.google.com/accounts/answer/185833), not your regular password.

### URL Configuration


| Variable           | Description                            | Default                       | Required |
| ------------------ | -------------------------------------- | ----------------------------- | -------- |
| `BACKEND_BASE_URL` | Public URL of the backend API          | `https://secman.covestro.net` | Yes      |
| `FRONTEND_URL`     | Public URL of the frontend application | `http://localhost:4321`       | Yes      |

These URLs are used for:

- CORS configuration
- Email notification links
- OAuth callback URLs

**Example:**

```bash
export BACKEND_BASE_URL=https://api.yourdomain.com
export FRONTEND_URL=https://secman.yourdomain.com
```

### OAuth Configuration

These variables control OAuth robustness settings for handling race conditions and transient failures during authentication.


| Variable                               | Description                                   | Default | Required |
| -------------------------------------- | --------------------------------------------- | ------- | -------- |
| `OAUTH_STATE_RETRY_MAX_ATTEMPTS`       | Maximum retry attempts for OAuth state lookup | `5`     | No       |
| `OAUTH_STATE_RETRY_INITIAL_DELAY`      | Initial delay between retries (ms)            | `100`   | No       |
| `OAUTH_STATE_RETRY_MAX_DELAY`          | Maximum delay between retries (ms)            | `500`   | No       |
| `OAUTH_STATE_RETRY_BACKOFF_MULTIPLIER` | Exponential backoff multiplier                | `1.5`   | No       |
| `OAUTH_TOKEN_EXCHANGE_MAX_RETRIES`     | Maximum retries for token exchange            | `2`     | No       |
| `OAUTH_TOKEN_EXCHANGE_RETRY_DELAY`     | Delay between token exchange retries (ms)     | `500`   | No       |

**Behavior:**

- **State Retry**: Handles race conditions where Microsoft Azure callbacks arrive before the state-save transaction commits (100-500ms with cached SSO). Uses exponential backoff: 100ms → 150ms → 225ms → 337ms → 500ms.
- **Token Exchange Retry**: Handles transient 5xx server errors and timeouts during OAuth token exchange. Does NOT retry 4xx errors (permanent failures).

**When to Adjust:**

- Increase `OAUTH_STATE_RETRY_MAX_ATTEMPTS` if users frequently see "login session not found" errors
- Increase delays if database replication lag is suspected
- Increase `OAUTH_TOKEN_EXCHANGE_MAX_RETRIES` if the OAuth provider (Microsoft, GitHub) has intermittent availability issues

**Example:**

```bash
# Increase retry tolerance for slow database environments
export OAUTH_STATE_RETRY_MAX_ATTEMPTS=7
export OAUTH_STATE_RETRY_MAX_DELAY=1000

# Increase token exchange retries for unreliable OAuth providers
export OAUTH_TOKEN_EXCHANGE_MAX_RETRIES=3
export OAUTH_TOKEN_EXCHANGE_RETRY_DELAY=1000
```

### Vulnerability Configuration


| Variable                              | Description                                             | Default | Required |
| ------------------------------------- | ------------------------------------------------------- | ------- | -------- |
| `VULN_USE_PATCH_PUBLICATION_DATE`     | Use patch publication date for calculating days open    | `false` | No       |
| `VULN_REQUIRE_PATCH_PUBLICATION_DATE` | Only import vulnerabilities with patch publication date | `false` | No       |

**Behavior:**

- When `VULN_USE_PATCH_PUBLICATION_DATE=false`: days_open = current_time - detection_time
- When `VULN_USE_PATCH_PUBLICATION_DATE=true`: days_open = scan_timestamp - patch_publication_date

---

## Frontend Environment Variables

The frontend (Astro/React) uses environment variables prefixed with `PUBLIC_` to expose them to the client.


| Variable         | Description     | Default       | Required |
| ---------------- | --------------- | ------------- | -------- |
| `PUBLIC_API_URL` | Backend API URL | Auto-detected | No       |

**Behavior:**

- In development (`localhost`): defaults to `http://localhost:8080`
- In production (non-localhost): uses relative URLs (empty string)

**Example `.env` file:**

```bash
PUBLIC_API_URL=https://api.yourdomain.com
```

---

## CLI Environment Variables

The CLI tool supports multiple sources for configuration with the following priority:

1. System properties (highest priority)
2. Environment variables
3. Config files (`~/.secman/`)
4. Defaults

### CrowdStrike API Credentials



| Variable               | Description                          |
| ---------------------- | ------------------------------------ |
| `FALCON_CLIENT_ID`     | Alias for`CROWDSTRIKE_CLIENT_ID`     |
| `FALCON_CLIENT_SECRET` | Alias for`CROWDSTRIKE_CLIENT_SECRET` |
| `FALCON_CLOUD_REGION`  | CrowdStrike cloud region             |

**Base URL by Region:**


| Region         | Base URL                                 |
| -------------- | ---------------------------------------- |
| US-1 (default) | `https://api.crowdstrike.com`            |
| US-2           | `https://api.us-2.crowdstrike.com`       |
| EU-1           | `https://api.eu-1.crowdstrike.com`       |
| US-GOV-1       | `https://api.laggar.gcw.crowdstrike.com` |
| US-GOV-2       | `https://api.laggar.gcw.crowdstrike.com` |

**Example:**

```bash
export FALCON_CLIENT_ID=your-client-id
export FALCON_CLIENT_SECRET=your-client-secret
export FALCON_BASE_URL=https://api.eu-1.crowdstrike.com
```

### Backend Authentication (for --save flag)


| Variable             | Description                         | Default                 | Required   |
| -------------------- | ----------------------------------- | ----------------------- | ---------- |
| `SECMAN_USERNAME`    | Backend username for authentication | -                       | For --save |
| `SECMAN_PASSWORD`    | Backend password for authentication | -                       | For --save |
| `SECMAN_BACKEND_URL` | Backend API URL                     | `http://localhost:8080` | No         |

**Example:**

```bash
export SECMAN_USERNAME=adminuser
export SECMAN_PASSWORD=your-password
export SECMAN_BACKEND_URL=https://api.yourdomain.com
```


## Quick Reference Tables

### Production Checklist


| Variable                     | Component | Must Change |
| ---------------------------- | --------- | ----------- |
| `DB_PASSWORD`                | Backend   | Yes         |
| `JWT_SECRET`                 | Backend   | Yes         |
| `SECMAN_ENCRYPTION_PASSWORD` | Backend   | Yes         |
| `SECMAN_ENCRYPTION_SALT`     | Backend   | Yes         |
| `SMTP_PASSWORD`              | Backend   | Yes         |
| `BACKEND_BASE_URL`           | Backend   | Yes         |
| `FRONTEND_URL`               | Backend   | Yes         |
| `CROWDSTRIKE_CLIENT_SECRET`  | CLI       | Yes         |

### All Variables by Component

#### Backend (29 variables)

```
DB_USERNAME, DB_PASSWORD
JWT_SECRET
SECMAN_ENCRYPTION_PASSWORD, SECMAN_ENCRYPTION_SALT
SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD
SMTP_FROM_ADDRESS, SMTP_FROM_NAME, SMTP_ENABLE_TLS
BACKEND_BASE_URL, FRONTEND_URL
OAUTH_STATE_RETRY_MAX_ATTEMPTS, OAUTH_STATE_RETRY_INITIAL_DELAY
OAUTH_STATE_RETRY_MAX_DELAY, OAUTH_STATE_RETRY_BACKOFF_MULTIPLIER
OAUTH_TOKEN_EXCHANGE_MAX_RETRIES, OAUTH_TOKEN_EXCHANGE_RETRY_DELAY
VULN_USE_PATCH_PUBLICATION_DATE, VULN_REQUIRE_PATCH_PUBLICATION_DATE
```

#### Frontend (1 variable)

```
PUBLIC_API_URL
```

#### CLI (9 variables)

```
FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_BASE_URL
FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION
SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_BACKEND_URL
```

---

## Environment File Templates

### Backend Production Template (`/etc/secman/backend.env`)

```bash
# =============================================================================
# Secman Backend Production Configuration
# =============================================================================

# --- Database Configuration ---
DB_USERNAME=secman
DB_PASSWORD=REPLACE_WITH_SECURE_PASSWORD

# --- JWT Authentication ---
# Generate with: openssl rand -base64 32
JWT_SECRET=REPLACE_WITH_256_BIT_SECRET

# --- Encryption Configuration ---
# Generate password with: openssl rand -hex 32
SECMAN_ENCRYPTION_PASSWORD=REPLACE_WITH_GENERATED_PASSWORD
# Generate salt with: openssl rand -hex 8
SECMAN_ENCRYPTION_SALT=REPLACE_WITH_16_HEX_CHARS

# --- SMTP Email Configuration ---
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=noreply@yourdomain.com
SMTP_PASSWORD=REPLACE_WITH_APP_PASSWORD
SMTP_FROM_ADDRESS=noreply@yourdomain.com
SMTP_FROM_NAME=Security Management System
SMTP_ENABLE_TLS=true

# --- URL Configuration ---
BACKEND_BASE_URL=https://api.yourdomain.com
FRONTEND_URL=https://secman.yourdomain.com

# --- Optional: Vulnerability Settings ---
VULN_USE_PATCH_PUBLICATION_DATE=false
VULN_REQUIRE_PATCH_PUBLICATION_DATE=false

# --- Optional: OAuth Robustness Settings ---
# Increase these if users experience intermittent OAuth login failures
# OAUTH_STATE_RETRY_MAX_ATTEMPTS=5
# OAUTH_STATE_RETRY_INITIAL_DELAY=100
# OAUTH_STATE_RETRY_MAX_DELAY=500
# OAUTH_STATE_RETRY_BACKOFF_MULTIPLIER=1.5
# OAUTH_TOKEN_EXCHANGE_MAX_RETRIES=2
# OAUTH_TOKEN_EXCHANGE_RETRY_DELAY=500
```

### Frontend Production Template (`/etc/secman/frontend.env`)

```bash
# =============================================================================
# Secman Frontend Production Configuration
# =============================================================================

# Backend API URL (use relative URL for same-domain deployment)
PUBLIC_API_URL=

# Node.js Production Settings
NODE_ENV=production
HOST=127.0.0.1
PORT=4321
```

### CLI Credentials Template (`~/.secman/credentials.conf`)

```bash
# =============================================================================
# Secman CLI Credentials
# =============================================================================

# --- CrowdStrike API Credentials ---
FALCON_CLIENT_ID=your-client-id-here
FALCON_CLIENT_SECRET=your-client-secret-here
FALCON_BASE_URL=https://api.crowdstrike.com

# --- Backend Authentication (for --save) ---
SECMAN_USERNAME=adminuser
SECMAN_PASSWORD=your-secure-password
SECMAN_BACKEND_URL=https://api.yourdomain.com
```

### CLI YAML Config Template (`~/.secman/crowdstrike.yaml`)

```yaml
# CrowdStrike API Configuration
clientId: your-client-id-here
clientSecret: your-client-secret-here
baseUrl: https://api.crowdstrike.com
```

---

## Security Best Practices

1. **Never commit credentials** to version control
2. **Use strong, unique passwords** generated with cryptographic tools
3. **Restrict file permissions** on environment files:
   ```bash
   sudo chmod 600 /etc/secman/*.env
   sudo chmod 600 ~/.secman/credentials.conf
   ```
4. **Rotate credentials regularly** (every 90 days recommended)
5. **Use AWS Secrets Manager** or similar for production deployments
6. **Audit access** to credential files and environment variables
7. **Use different credentials** for development, staging, and production

---

## Troubleshooting

### Common Issues

**"JWT signature verification failed"**

- Ensure `JWT_SECRET` is at least 32 characters (256 bits)
- Verify the same secret is used across all backend instances

**"Failed to decrypt sensitive data"**

- `SECMAN_ENCRYPTION_PASSWORD` or `SECMAN_ENCRYPTION_SALT` changed after data was encrypted
- These values must remain constant for the lifetime of encrypted data

**"SMTP authentication failed"**

- For Gmail: Use an App Password, not your regular password
- Verify `SMTP_HOST` and `SMTP_PORT` are correct for your provider

**"CrowdStrike API: 401 Unauthorized"**

- Verify `CROWDSTRIKE_CLIENT_ID` and `CROWDSTRIKE_CLIENT_SECRET` are correct
- Ensure the API credentials have the required scopes
- Check `CROWDSTRIKE_BASE_URL` matches your CrowdStrike cloud region

**"Your login session was not found" (OAuth)**

- This indicates the OAuth state was not found in the database when the callback arrived
- Increase `OAUTH_STATE_RETRY_MAX_ATTEMPTS` and `OAUTH_STATE_RETRY_MAX_DELAY` to handle database replication lag
- Check if the `oauth_states` table cleanup job is running too aggressively

**"Could not complete authentication" (OAuth)**

- This indicates token exchange with the OAuth provider failed
- Check network connectivity to the OAuth provider (Microsoft, GitHub)
- Increase `OAUTH_TOKEN_EXCHANGE_MAX_RETRIES` for unreliable network conditions
- Review backend logs for specific HTTP error codes from the provider

**"Your login session expired" (OAuth)**

- The OAuth state existed but was older than 10 minutes
- This can happen if users take too long at the OAuth provider login page
- Ensure users complete the OAuth flow within 10 minutes

---

**Related Documentation:**

- [Deployment Guide](./DEPLOYMENT.md)
- [CLI Reference](./CLI.md)
- [Technical: CrowdStrike Import](./CROWDSTRIKE_IMPORT.md)
