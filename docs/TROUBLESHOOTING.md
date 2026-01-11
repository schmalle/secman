# Troubleshooting Guide

**Last Updated:** 2026-01-11

This guide consolidates common issues and solutions for all Secman components.

---

## Table of Contents

1. [Backend Issues](#backend-issues)
2. [Frontend Issues](#frontend-issues)
3. [Database Issues](#database-issues)
4. [CLI Issues](#cli-issues)
5. [MCP Integration Issues](#mcp-integration-issues)
6. [Nginx/Proxy Issues](#nginxproxy-issues)
7. [Authentication Issues](#authentication-issues)
8. [Performance Issues](#performance-issues)
9. [Debug Commands](#debug-commands)
10. [Log Locations](#log-locations)

---

## Backend Issues

### Backend Won't Start

**Symptoms:** Service fails to start, immediate crash after launch

**Diagnostics:**
```bash
# Check service status
sudo systemctl status secman-backend

# View recent logs
sudo journalctl -u secman-backend -n 100

# Check JAR exists
ls -la /opt/secman/app/build/libs/backendng-*-all.jar
```

**Common causes:**

1. **Wrong Java version**
   ```bash
   java -version  # Must show 21.x
   ```
   Fix: Install Java 21 (Amazon Corretto, OpenJDK, etc.)

2. **Database connection failed**
   ```bash
   # Test connection
   mysql -u secman -p -h localhost secman
   ```
   Fix: Verify `DB_USERNAME`, `DB_PASSWORD`, `DB_CONNECT` environment variables

3. **Port already in use**
   ```bash
   lsof -i :8080
   ```
   Fix: Stop conflicting service or change `micronaut.server.port`

4. **Missing environment variables**
   Check required variables are set: `JWT_SECRET`, `SECMAN_ENCRYPTION_PASSWORD`

### Backend Health Check Failing

```bash
# Check health endpoint
curl -v http://localhost:8080/health
```

**Expected response:**
```json
{"status":"UP","service":"secman-backend-ng","version":"0.1"}
```

### API Returns 500 Error

1. Check backend logs for stack trace
2. Verify database connectivity
3. Check for missing required fields in request
4. Enable debug logging:
   ```bash
   export SECMAN_LOG_LEVEL=DEBUG
   sudo systemctl restart secman-backend
   ```

---

## Frontend Issues

### Frontend Won't Start

**Diagnostics:**
```bash
sudo journalctl -u secman-frontend -n 100
node -v  # Must be 18+ or 20.x
ls -la /opt/secman/app/src/frontend/dist/server/
```

**Common causes:**

1. **Backend unreachable**
   ```bash
   curl http://localhost:8080/health
   ```
   Fix: Start backend first, verify `PUBLIC_BACKEND_URL`

2. **Build artifacts missing**
   ```bash
   cd /opt/secman/app/src/frontend
   npm ci && npm run build
   ```

3. **Wrong Node.js version**
   ```bash
   nvm use 20
   # or install Node 20
   ```

### Blank Page After Login

- Clear browser cache and localStorage
- Check browser console for JavaScript errors
- Verify CORS configuration matches frontend URL
- Check `PUBLIC_BACKEND_URL` is accessible from browser

### Session Expires Immediately

- Verify `JWT_SECRET` is consistent across restarts
- Check token expiration settings in `application.yml`
- Ensure server time is synchronized (NTP)

---

## Database Issues

### Connection Refused

```bash
# Check MariaDB status
sudo systemctl status mariadb

# Test connection
mysql -u secman -p secman
```

**Fixes:**
- Start MariaDB: `sudo systemctl start mariadb`
- Verify user has remote access if needed
- Check `bind-address` in MariaDB config

### Migration Errors

Hibernate auto-migration failures:

1. Check for conflicting schema changes
2. Review backend logs for SQL errors
3. Manual fix (backup first!):
   ```bash
   mysql -u secman -p secman
   > ALTER TABLE ... ;
   ```

### Performance Issues

```sql
-- Check slow queries
SHOW FULL PROCESSLIST;

-- Check table sizes
SELECT table_name, round(data_length/1024/1024,2) as 'Size (MB)'
FROM information_schema.tables
WHERE table_schema = 'secman'
ORDER BY data_length DESC;
```

---

## CLI Issues

### "Command not found" in Cron

Add Java to PATH in crontab:
```cron
PATH=/usr/bin:/bin:/usr/local/bin:/usr/lib/jvm/java-21-amazon-corretto/bin
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto

0 2 * * * /opt/secman/bin/cron-query-servers.sh
```

### "Credentials not found"

Verify credentials file format (no spaces around `=`):
```bash
# Correct
CROWDSTRIKE_CLIENT_ID=abc123

# Wrong
CROWDSTRIKE_CLIENT_ID = abc123
```

### "Authentication failed" (CrowdStrike)

- Verify client ID and secret are correct
- Check base URL matches your CrowdStrike region:
  - US-1: `https://api.crowdstrike.com`
  - US-2: `https://api.us-2.crowdstrike.com`
  - EU-1: `https://api.eu-1.crowdstrike.com`
- Ensure API credentials have required scopes

### Out of Memory

Add JVM options:
```bash
java -Xmx512m -Xms256m -jar secman-cli.jar ...
```

Or in wrapper script:
```bash
#!/bin/bash
java -Xmx1g -jar /opt/secman/app/build/libs/secman-cli.jar "$@"
```

### Dry Run Mode

Test commands without making changes:
```bash
./bin/secman query servers --dry-run
./bin/secman send-notifications --dry-run
```

---

## MCP Integration Issues

### "Authentication required"

- Ensure `X-MCP-API-Key` header is present
- Verify API key is valid and not expired
- Check API key has required permissions

### "Permission denied"

- Verify API key permissions include the required tool
- For admin tools (`list_users`), ensure:
  - User Delegation is enabled on the key
  - `X-MCP-User-Email` header is set
  - Delegated user has ADMIN role

### "Origin not allowed"

- Occurs when making requests from a browser
- Localhost origins are always allowed
- Configure allowed origins in `application.yml`

### MCP Server Fails to Start (Node.js bridge)

```bash
# Check Node.js
node -v  # Must be 18+

# Install dependencies
cd /path/to/secman
npm install

# Make executable
chmod +x mcp/mcp-server.js

# Test manually
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | node mcp/mcp-server.js
```

### Claude Desktop Not Connecting

1. Verify config file location:
   - macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
   - Windows: `%APPDATA%\Claude\claude_desktop_config.json`

2. Use absolute paths in config

3. Restart Claude Desktop after config changes

4. Check Claude Desktop logs for errors

### Test MCP Endpoint

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: sk-your-key" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list"}'
```

---

## Nginx/Proxy Issues

### 502 Bad Gateway

```bash
# Check services running
systemctl status secman-backend secman-frontend

# Check nginx error logs
sudo tail -f /var/log/nginx/secman-api-error.log

# Test upstream directly
curl http://localhost:8080/health
curl http://localhost:4321/
```

### SSL Certificate Issues

```bash
# Verify certificate files exist
sudo ls -la /etc/letsencrypt/live/yourdomain.com/

# Check expiry
openssl x509 -in /path/to/cert.pem -noout -dates

# Force renewal
sudo certbot renew --force-renewal

# Test SSL
openssl s_client -connect yourdomain.com:443
```

### CORS Errors

Check `application.yml` CORS configuration:
```yaml
micronaut:
  server:
    cors:
      enabled: true
      configurations:
        web:
          allowed-origins:
            - "https://yourdomain.com"
```

---

## Authentication Issues

### OAuth Login Fails

**"Invalid state" error:**
- State token expired (>10 minutes)
- Browser went back/forward during OAuth flow
- Database transaction timing issue (fast SSO)

Configure retry settings in `application.yml`:
```yaml
secman:
  oauth:
    state-retry:
      max-attempts: 5
      initial-delay-ms: 100
```

**"Token exchange failed":**
- Check OAuth provider configuration in database
- Verify redirect URLs match exactly
- Check provider credentials

### JWT Token Expired

- Default expiration: 8 hours
- Adjust in `application.yml`:
  ```yaml
  micronaut:
    security:
      token:
        jwt:
          generator:
            access-token:
              expiration: 28800  # seconds
  ```

### Password Reset Not Working

- Verify SMTP configuration
- Check email logs in database
- Test SMTP connectivity:
  ```bash
  curl -v --url "smtp://smtp.gmail.com:587" --user "user:password" --mail-from "from@example.com"
  ```

---

## Performance Issues

### Slow API Responses

1. **Check database queries:**
   ```bash
   # Enable query logging temporarily
   mysql -e "SET GLOBAL slow_query_log = 'ON';"
   mysql -e "SET GLOBAL long_query_time = 1;"
   ```

2. **Review connection pool:**
   ```yaml
   datasources:
     default:
       maximum-pool-size: 20
       minimum-idle: 5
   ```

3. **Check for N+1 queries** in service layer

### High Memory Usage

```bash
# Check Java heap
jcmd $(pgrep -f backendng) GC.heap_info

# Increase heap if needed
java -Xmx2g -jar backendng.jar
```

### Vulnerability Import Slow

- Use batch size limits in import requests
- Check database indexes on `vulnerabilities` table
- Enable caching for repeated queries

---

## Debug Commands

### Backend

```bash
# Health check
curl http://localhost:8080/health

# Test authentication
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password"}'

# Test with JWT
curl -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/assets
```

### Database

```bash
# Connection test
mysql -u secman -p secman -e "SELECT 1"

# Table counts
mysql -u secman -p secman -e "SELECT 'assets' as t, COUNT(*) FROM assets UNION SELECT 'vulnerabilities', COUNT(*) FROM vulnerabilities"

# Recent users
mysql -u secman -p secman -e "SELECT email, last_login FROM users ORDER BY last_login DESC LIMIT 5"
```

### CLI

```bash
# Show help
./bin/secman help

# Test with dry run
./bin/secman query servers --dry-run --limit 10

# Verbose output
./bin/secman query servers --verbose
```

---

## Log Locations

| Component | Location |
|-----------|----------|
| Backend (systemd) | `sudo journalctl -u secman-backend` |
| Frontend (systemd) | `sudo journalctl -u secman-frontend` |
| Nginx access | `/var/log/nginx/secman-api-access.log` |
| Nginx error | `/var/log/nginx/secman-api-error.log` |
| CLI (if configured) | `/opt/secman/logs/cronjob.log` |
| MariaDB | `/var/log/mariadb/` or `/var/log/mysql/` |

### Enable Debug Logging

```bash
# Backend
export SECMAN_LOG_LEVEL=DEBUG
sudo systemctl restart secman-backend

# Or in application.yml
logger:
  levels:
    com.secman: DEBUG
```

---

## Getting Help

If these solutions don't resolve your issue:

1. Collect relevant logs
2. Note the exact error message
3. Document steps to reproduce
4. Check [GitHub Issues](https://github.com/schmalle/secman/issues)
5. Open a new issue with collected information

---

## See Also

- [Deployment Guide](./DEPLOYMENT.md) - Production setup
- [Environment Variables](./ENVIRONMENT.md) - Configuration reference
- [CLI Reference](./CLI.md) - Command-line tools
- [MCP Integration](./MCP.md) - AI assistant integration

---

*For backend debug logging: `export SECMAN_LOG_LEVEL=DEBUG && systemctl restart secman-backend`*
