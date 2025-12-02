# Secman CLI Reference

**Last Updated:** 2025-11-26
**Version:** 1.0

Command-line interface for CrowdStrike vulnerability queries, notifications, and user mapping management.

---

## Table of Contents

1. [Overview](#overview)
2. [Installation](#installation)
3. [Configuration](#configuration)
4. [Commands](#commands)
5. [Cron Job Setup](#cron-job-setup)
6. [AWS Integration](#aws-integration)
7. [Troubleshooting](#troubleshooting)

---

## Overview

The Secman CLI provides command-line access to:
- Query CrowdStrike Falcon API for vulnerabilities
- Send automated notification emails for outdated assets
- Manage user-to-asset mappings (AWS accounts, AD domains)
- Export vulnerability data to JSON/CSV

### Requirements

- Java 21 (Amazon Corretto recommended)
- Network access to CrowdStrike API
- Network access to Secman backend (for `--save` operations)

---

## Installation

### Build the CLI JAR

From the repository root:

```bash
# Build standalone JAR with all dependencies
./gradlew :cli:shadowJar

# Verify output
ls -lh src/cli/build/libs/cli-0.1.0-all.jar
```

### Deploy to Server

```bash
# Copy to server
scp src/cli/build/libs/cli-0.1.0-all.jar user@server:/opt/secman/bin/secman-cli.jar

# Verify
java -jar /opt/secman/bin/secman-cli.jar --help
```

### Directory Structure

```
/opt/secman/
├── bin/
│   └── secman-cli.jar          # CLI JAR file
├── config/
│   ├── credentials.conf        # CrowdStrike credentials
│   └── application.yml         # Optional overrides
└── logs/
    └── cronjob.log            # Cron execution logs
```

---

## Configuration

### Configuration Priority

1. **System properties** (highest priority)
2. **Environment variables**
3. **Config files** (`~/.secman/`)
4. **Defaults**

### Environment Variables

See [ENVIRONMENT.md](./ENVIRONMENT.md#cli-environment-variables) for complete reference.

**CrowdStrike credentials:**
```bash
export CROWDSTRIKE_CLIENT_ID=your-client-id
export CROWDSTRIKE_CLIENT_SECRET=your-client-secret
export CROWDSTRIKE_BASE_URL=https://api.crowdstrike.com
```

**Backend authentication (for --save):**
```bash
export SECMAN_USERNAME=adminuser
export SECMAN_PASSWORD=your-password
export SECMAN_BACKEND_URL=https://api.yourdomain.com
```

### Config File Format

**`~/.secman/crowdstrike.yaml`:**
```yaml
clientId: your-client-id-here
clientSecret: your-client-secret-here
baseUrl: https://api.crowdstrike.com
```

**`~/.secman/credentials.conf`:**
```bash
CROWDSTRIKE_CLIENT_ID=your-client-id
CROWDSTRIKE_CLIENT_SECRET=your-client-secret
SECMAN_USERNAME=adminuser
SECMAN_PASSWORD=your-password
```

Secure credentials file:
```bash
chmod 600 ~/.secman/credentials.conf
chmod 600 ~/.secman/crowdstrike.yaml
```

---

## Commands

### Query Vulnerabilities

Query CrowdStrike for server vulnerabilities.

```bash
# Basic query
java -jar secman-cli.jar query servers --hostname web-server-01

# Filter by severity
java -jar secman-cli.jar query servers --hostname web-server-01 --severity HIGH,CRITICAL

# Save to database
java -jar secman-cli.jar query servers --hostname web-server-01 \
  --save --username admin --password secret

# Export to file
java -jar secman-cli.jar query servers --hostname web-server-01 \
  --output-file results.json --format json

# Full options
java -jar secman-cli.jar query servers \
  --hostname web-server-01 \
  --severity HIGH,CRITICAL \
  --min-days-open 30 \
  --limit 500 \
  --save \
  --username admin \
  --password secret \
  --backend-url https://api.yourdomain.com \
  --verbose
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--hostname` | Target hostname (required) | - |
| `--severity` | Filter by severity (comma-separated) | All |
| `--min-days-open` | Minimum days vulnerability has been open | 0 |
| `--limit` | Maximum results | 100 |
| `--save` | Save results to backend database | false |
| `--username` | Backend authentication username | - |
| `--password` | Backend authentication password | - |
| `--backend-url` | Backend API URL | http://localhost:8080 |
| `--output-file` | Export to file | - |
| `--format` | Export format (json, csv) | json |
| `--verbose` | Show detailed output | false |

### Send Notifications

Send email notifications for outdated assets.

```bash
# Dry run (no emails sent)
java -jar secman-cli.jar send-notifications --dry-run --verbose

# Send actual notifications
java -jar secman-cli.jar send-notifications --verbose

# Only process outdated assets
java -jar secman-cli.jar send-notifications --outdated-only
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--dry-run` | Report planned emails without sending | false |
| `--verbose` | Show detailed per-asset information | false |
| `--outdated-only` | Only process outdated assets | false |

### Manage User Mappings

Manage user-to-asset mappings for access control.

```bash
# List all mappings
java -jar secman-cli.jar manage-user-mappings list

# Add AWS account mapping
java -jar secman-cli.jar manage-user-mappings add-aws \
  --email user@example.com \
  --aws-account-id 123456789012

# Add AD domain mapping
java -jar secman-cli.jar manage-user-mappings add-domain \
  --email user@example.com \
  --domain CORP.EXAMPLE.COM

# Import from CSV
java -jar secman-cli.jar manage-user-mappings import \
  --file mappings.csv \
  --format csv

# Remove mapping
java -jar secman-cli.jar manage-user-mappings remove --id 42
```

**Subcommands:**

| Command | Description |
|---------|-------------|
| `list` | List all user mappings |
| `add-aws` | Add AWS account mapping |
| `add-domain` | Add AD domain mapping |
| `import` | Bulk import from CSV/JSON |
| `remove` | Remove mapping by ID |

### Manage Workgroups

Manage workgroup asset assignments with pattern-based selection.

```bash
# List all workgroups
java -jar secman-cli.jar manage-workgroups list

# List assets in a specific workgroup
java -jar secman-cli.jar manage-workgroups list --workgroup Production

# Search assets by pattern (preview before assigning)
java -jar secman-cli.jar manage-workgroups list --search-assets "ip-10-*"

# Assign assets by pattern (wildcards supported)
java -jar secman-cli.jar manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-10-*" \
  --admin-user admin@example.com

# Assign assets by pattern with type filter
java -jar secman-cli.jar manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*prod*" \
  --type SERVER \
  --admin-user admin@example.com

# Assign specific assets by ID
java -jar secman-cli.jar manage-workgroups assign-assets \
  --workgroup Production \
  --ids 1,2,3 \
  --admin-user admin@example.com

# Preview assignment without changes (dry-run)
java -jar secman-cli.jar manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*" \
  --dry-run

# Remove assets by pattern
java -jar secman-cli.jar manage-workgroups remove-assets \
  --workgroup Test \
  --pattern "*test*" \
  --admin-user admin@example.com

# Remove all assets from workgroup
java -jar secman-cli.jar manage-workgroups remove-assets \
  --workgroup Test \
  --all \
  --admin-user admin@example.com

# Output in different formats
java -jar secman-cli.jar manage-workgroups list --format JSON
java -jar secman-cli.jar manage-workgroups list --format CSV
```

**Subcommands:**

| Command | Description |
|---------|-------------|
| `list` | List workgroups or assets in a workgroup |
| `assign-assets` | Assign assets to a workgroup by pattern or IDs |
| `remove-assets` | Remove assets from a workgroup |

**Wildcard Patterns:**

| Pattern | Description | Example |
|---------|-------------|---------|
| `*` | Matches any characters | `ip-10-*` matches `ip-10-255-75-85` |
| `?` | Matches single character | `server?` matches `server1`, `serverA` |
| `*text*` | Contains text | `*prod*` matches `web-prod-01` |

---

## Cron Job Setup

### Wrapper Script

Create `/opt/secman/bin/cron-query-servers.sh`:

```bash
#!/bin/bash
#############################################
# Secman CLI Cron Execution Script
#############################################

JAR_PATH="/opt/secman/bin/secman-cli.jar"
CONFIG_DIR="/opt/secman/config"
LOG_DIR="/opt/secman/logs"
LOG_FILE="${LOG_DIR}/cronjob.log"
CREDENTIALS_FILE="${CONFIG_DIR}/credentials.conf"

SEVERITY="HIGH,CRITICAL"
MIN_DAYS_OPEN="1"

set -e
set -u
set -o pipefail

timestamp() {
    date '+%Y-%m-%d %H:%M:%S'
}

log() {
    echo "[$(timestamp)] $1" | tee -a "${LOG_FILE}"
}

main() {
    log "===== Secman CLI Cron Job Starting ====="

    # Load credentials
    if [ -f "${CREDENTIALS_FILE}" ]; then
        source "${CREDENTIALS_FILE}"
    else
        log "ERROR: Credentials file not found"
        exit 1
    fi

    # Execute CLI
    java -jar "${JAR_PATH}" \
        query servers \
        --severity "${SEVERITY}" \
        --min-days-open "${MIN_DAYS_OPEN}" \
        --save \
        --username "${SECMAN_USERNAME}" \
        --password "${SECMAN_PASSWORD}" \
        2>&1 | tee -a "${LOG_FILE}"

    log "===== Secman CLI Cron Job Completed ====="
}

main
```

Make executable:
```bash
chmod +x /opt/secman/bin/cron-query-servers.sh
```

### Cron Schedule Examples

```bash
crontab -e
```

**Daily at 2:00 AM:**
```cron
0 2 * * * /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1
```

**Every hour:**
```cron
0 * * * * /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1
```

**Business hours (Mon-Fri, 9 AM - 5 PM):**
```cron
0 9-17 * * 1-5 /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1
```

### Log Rotation

Create `/etc/logrotate.d/secman-cli`:

```
/opt/secman/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 0644 secman secman
}
```

### Parallel Execution Prevention

Add lock file mechanism to wrapper script:

```bash
LOCK_FILE="/var/lock/secman-cli.lock"

if [ -f "${LOCK_FILE}" ]; then
    log "ERROR: Another instance is running"
    exit 1
fi

touch "${LOCK_FILE}"
trap "rm -f ${LOCK_FILE}" EXIT

# ... rest of script
```

---

## AWS Integration

### Using AWS Secrets Manager

Create wrapper script `/opt/secman/bin/run-with-secrets.sh`:

```bash
#!/bin/bash

SECRETS=$(aws secretsmanager get-secret-value \
    --secret-id secman/crowdstrike \
    --region us-east-1 \
    --query SecretString \
    --output text)

export CROWDSTRIKE_CLIENT_ID=$(echo $SECRETS | jq -r .client_id)
export CROWDSTRIKE_CLIENT_SECRET=$(echo $SECRETS | jq -r .client_secret)
export SECMAN_USERNAME=$(echo $SECRETS | jq -r .username)
export SECMAN_PASSWORD=$(echo $SECRETS | jq -r .password)

java -jar /opt/secman/bin/secman-cli.jar "$@"
```

**Required IAM permissions:**
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "secretsmanager:GetSecretValue",
    "Resource": "arn:aws:secretsmanager:us-east-1:*:secret:secman/*"
  }]
}
```

### CloudWatch Logs Integration

Create `/opt/aws/amazon-cloudwatch-agent/etc/config.json`:

```json
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [{
          "file_path": "/opt/secman/logs/cronjob.log",
          "log_group_name": "/secman/cli/cronjob",
          "log_stream_name": "{instance_id}",
          "retention_in_days": 30
        }]
      }
    }
  }
}
```

Start agent:
```bash
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
    -a fetch-config -m ec2 -s \
    -c file:/opt/aws/amazon-cloudwatch-agent/etc/config.json
```

---

## Troubleshooting

### Common Issues

#### "Command not found" in cron

Add Java to PATH in crontab:
```cron
PATH=/usr/bin:/bin:/usr/local/bin:/usr/lib/jvm/java-21-amazon-corretto/bin
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto

0 2 * * * /opt/secman/bin/cron-query-servers.sh
```

#### "Credentials not found"

Verify format (no spaces around `=`):
```bash
# Correct
CROWDSTRIKE_CLIENT_ID=abc123

# Wrong
CROWDSTRIKE_CLIENT_ID = abc123
```

#### "Authentication failed"

- Verify CrowdStrike client ID and secret are correct
- Check base URL matches your CrowdStrike region
- Ensure API credentials have required scopes

#### "Out of Memory"

Add JVM options:
```bash
java -Xmx512m -Xms256m -jar secman-cli.jar ...
```

### Health Check Script

Create `/opt/secman/bin/check-health.sh`:

```bash
#!/bin/bash

LOG_FILE="/opt/secman/logs/cronjob.log"
MAX_AGE_HOURS=24

LAST_SUCCESS=$(grep "Completed Successfully" "${LOG_FILE}" | tail -1 | awk '{print $1, $2}')

if [ -z "${LAST_SUCCESS}" ]; then
    echo "WARNING: No successful executions found"
    exit 1
fi

LAST_EPOCH=$(date -d "${LAST_SUCCESS}" +%s 2>/dev/null || echo 0)
CURRENT_EPOCH=$(date +%s)
AGE_HOURS=$(( (CURRENT_EPOCH - LAST_EPOCH) / 3600 ))

if [ ${AGE_HOURS} -gt ${MAX_AGE_HOURS} ]; then
    echo "WARNING: Last success ${AGE_HOURS} hours ago"
    exit 1
fi

echo "OK: Last success ${AGE_HOURS} hours ago"
exit 0
```

---

## Related Documentation

- [Environment Variables](./ENVIRONMENT.md) - Complete configuration reference
- [Deployment Guide](./DEPLOYMENT.md) - Server setup
- [CrowdStrike Import](./CROWDSTRIKE_IMPORT.md) - Import technical details
- [User Mapping Commands](../src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md) - Detailed user mapping CLI subcommands
- [Workgroup Commands](../src/cli/src/main/resources/cli-docs/WORKGROUP_COMMANDS.md) - Detailed workgroup management CLI subcommands

---

*For CLI help: `java -jar secman-cli.jar --help`*
