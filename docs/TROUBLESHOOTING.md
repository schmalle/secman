# Troubleshooting

Symptom → diagnosis → fix. For full env reference see `docs/ENVIRONMENT.md`.

## Backend

**Won't start**
```bash
sudo systemctl status secman-backend
sudo journalctl -u secman-backend -n 100
ls /opt/secman/app/src/backendng/build/libs/backendng-*-all.jar
```
Causes: wrong Java (`java -version` must be 21); DB unreachable (`mysql -u secman -p secman`; check `DB_*` env); port in use (`lsof -i :8080`, set `MICRONAUT_SERVER_PORT`); missing required env (`JWT_SECRET`, `SECMAN_ENCRYPTION_PASSWORD`).

**Health check fails**
```bash
curl -v http://localhost:8080/health
# expected: {"status":"UP","service":"secman-backend-ng","version":"0.1"}
```

**500 on API**: check stack trace in journal. Verify DB up. Check required request fields. Bump verbosity: `SECMAN_LOGGING=ALL` then restart.

## Frontend

**Won't start**
```bash
sudo journalctl -u secman-frontend -n 100
node -v                                                # must be 20.x
ls /opt/secman/app/src/frontend/dist/server/
```
Causes: backend down (`curl http://localhost:8080/health`); missing build (`npm ci && npm run build`); wrong Node.

**Blank after login**: clear `localStorage`/cache; check browser console; verify `PUBLIC_API_URL` reachable from browser; verify CORS `allowed-origins` matches frontend URL.

**Session expires immediately**: `JWT_SECRET` rotated between restarts; check token expiration in `application.yml`; verify NTP/clock sync.

## Database

```bash
sudo systemctl status mariadb
mysql -u secman -p secman
sudo systemctl start mariadb                           # if down
```

**Migration errors**: check journal for SQL error. Backup. Inspect schema. Repair Flyway: `./gradlew flywayInfo && ./gradlew flywayRepair`.

**Slow**:
```sql
SHOW FULL PROCESSLIST;
SELECT table_name, ROUND(data_length/1024/1024,2) AS mb
  FROM information_schema.tables WHERE table_schema='secman'
  ORDER BY data_length DESC;
```

## CLI

| Symptom | Fix |
|---|---|
| `Command not found` in cron | add `PATH` and `JAVA_HOME` to crontab (point at `/usr/lib/jvm/java-21-amazon-corretto/bin`) |
| `Credentials not found` | check format — no spaces around `=` (`FALCON_CLIENT_ID=abc` not `FALCON_CLIENT_ID = abc`); `chmod 600` |
| CrowdStrike `401 Unauthorized` | verify client/secret + region in `FALCON_BASE_URL` (US-1/US-2/EU-1/GOV); required scopes |
| Out of memory | `java -Xmx1g -jar secman-cli.jar …` (or in cron wrapper) |
| Want to test safely | `--dry-run` available on `query servers`, `send-notifications`, `manage-user-mappings list --send-email`, `manage-user-mappings import`, `import-s3` |

## MCP

| Symptom | Fix |
|---|---|
| `Authentication required` | header missing/wrong/expired; check key isn't revoked |
| `DELEGATION_HEADER_REQUIRED` | add `X-MCP-User-Email`; ensure key has `delegationEnabled: true`; `initialize`/`ping` are exempt |
| `Permission denied` | effective = `api_key.permissions ∩ user.role_implied`; both sides must include the needed permission |
| `Origin not allowed` | browser request without allowed origin; localhost is always allowed; configure `secman.mcp.transport.allowed-origins` |
| Claude Desktop won't connect | config in `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) / `%APPDATA%\Claude\…` (Win); use absolute paths; restart app; check Claude Desktop logs |

Smoke test:
```bash
curl -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'X-MCP-API-Key: sk-…' \
  -d '{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}'

curl -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'X-MCP-API-Key: sk-…' -H 'X-MCP-User-Email: you@company.com' \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/list"}'
```

## nginx / proxy

**502 Bad Gateway**:
```bash
systemctl status secman-backend secman-frontend
sudo tail -f /var/log/nginx/secman-api-error.log
curl http://localhost:8080/health
curl http://localhost:4321/
```

**SSL**:
```bash
ls /etc/letsencrypt/live/<domain>/
openssl x509 -in <cert>.pem -noout -dates
sudo certbot renew --force-renewal
openssl s_client -connect <domain>:443
```

**CORS errors** in browser:
```yaml
micronaut:
  server:
    cors:
      enabled: true
      configurations:
        web:
          allowed-origins: [ "https://secman.example.com" ]
```

## Auth

**OAuth `Invalid state`**: token >10 min old, browser back/forward mid-flow, or DB-commit timing race.
```yaml
secman:
  oauth:
    state-retry: { max-attempts: 5, initial-delay-ms: 100 }
```
Also tunable via `OAUTH_STATE_RETRY_*` env.

**OAuth `Token exchange failed`**: provider config in DB; redirect URLs match exactly; provider credentials valid.

**JWT expired**: default 8h. Adjust:
```yaml
micronaut.security.token.jwt.generator.access-token.expiration: 28800
```

**Password reset email not delivered**: check SMTP config; `notification_logs` table; SMTP connectivity:
```bash
curl -v --url smtp://smtp.gmail.com:587 --user user:pass --mail-from from@example.com
```

## Performance

**Slow API**:
```sql
SET GLOBAL slow_query_log='ON'; SET GLOBAL long_query_time=1;
```
```yaml
datasources.default: { maximum-pool-size: 20, minimum-idle: 5 }
```
Look for N+1 in service layer.

**High memory**:
```bash
jcmd $(pgrep -f backendng) GC.heap_info
java -Xmx2g -jar backendng.jar
```

**Vuln import slow**: batch-size limits in import requests; verify indexes on `vulnerabilities` (`asset_id`, `(asset_id, vulnerability_id)`); enable cache for repeated queries.

## Debug commands

```bash
# Backend
curl http://localhost:8080/health
curl -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"…"}'
curl -H 'Authorization: Bearer <jwt>' http://localhost:8080/api/assets

# DB
mysql -u secman -p secman -e "SELECT 1"
mysql -u secman -p secman -e \
  "SELECT 'assets' AS t, COUNT(*) FROM assets UNION SELECT 'vulnerabilities', COUNT(*) FROM vulnerabilities"
mysql -u secman -p secman -e "SELECT email,last_login FROM users ORDER BY last_login DESC LIMIT 5"

# CLI
./scripts/secman help
./scripts/secman query servers --dry-run --limit 10 --verbose
```

## Log locations

| Component | Where |
|---|---|
| Backend (systemd) | `journalctl -u secman-backend` |
| Frontend (systemd) | `journalctl -u secman-frontend` |
| nginx access | `/var/log/nginx/secman-api-access.log` |
| nginx error | `/var/log/nginx/secman-api-error.log` |
| CLI cron | `/opt/secman/logs/cronjob.log` |
| MariaDB | `/var/log/mariadb/` or `/var/log/mysql/` |
| Security audit | `logs/security-audit.log` (always written; not silenced by `SECMAN_LOGGING=NO`) |

Enable backend debug:
```bash
export SECMAN_LOGGING=ALL && sudo systemctl restart secman-backend
# or in application.yml: logger.levels.com.secman: DEBUG
```

Header dump (for proxy-stripped headers, JWT claim mismatches, CORS):
```bash
export SECMAN_DEBUG=true     # NOT in production — logs API keys
```
