# Secman CLI Cron Job Setup for Amazon Linux 2023

This guide describes how to set up the Secman CLI tool to run from a cron job on Amazon Linux 2023 using a standalone JAR file.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Building the JAR File](#building-the-jar-file)
- [Amazon Linux 2023 Setup](#amazon-linux-2023-setup)
- [Credential Management](#credential-management)
- [Cron Job Configuration](#cron-job-configuration)
- [Logging and Monitoring](#logging-and-monitoring)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)

---

## Prerequisites

### On Your Development Machine

- Git repository cloned
- Gradle 9.2 or higher
- Java 21 JDK (for building)

### On Amazon Linux 2023 Server

- Amazon Linux 2023 instance
- Network access to CrowdStrike API
- Network access to Secman backend (if using `--save` option)
- Sufficient disk space for logs (recommended: 1GB minimum)

---

## Building the JAR File

The CLI module uses the Shadow plugin to create a "fat JAR" containing all dependencies.

### 1. Build the Standalone JAR

From the **repository root** directory:

```bash
# Build the shadow JAR (includes all dependencies)
./gradlew :cli:shadowJar

# Verify the JAR was created
ls -lh src/cli/build/libs/
```

**Expected output:**
```
cli-0.1.0-all.jar  (approximately 50-80 MB)
```

The `-all.jar` suffix indicates this is the shadow JAR with all dependencies bundled.

### 2. Test the JAR Locally

Before deploying, verify the JAR works:

```bash
# Test the JAR
java -jar src/cli/build/libs/cli-0.1.0-all.jar --help

# Expected output: CLI help information
```

### 3. Copy JAR to Amazon Linux 2023

```bash
# Replace with your server details
SERVER_IP="your-server-ip"
SERVER_USER="ec2-user"

# Copy JAR to server
scp src/cli/build/libs/cli-0.1.0-all.jar ${SERVER_USER}@${SERVER_IP}:/opt/secman/
```

---

## Amazon Linux 2023 Setup

### 1. Install Java 21

Amazon Linux 2023 includes Amazon Corretto (AWS's OpenJDK distribution):

```bash
# Update package manager
sudo dnf update -y

# Install Amazon Corretto 21
sudo dnf install -y java-21-amazon-corretto-devel

# Verify installation
java -version
# Expected: openjdk version "21.0.x" ... Amazon Corretto

# Set Java 21 as default if multiple versions installed
sudo alternatives --config java
```

### 2. Create Application Directory Structure

```bash
# Create directory structure
sudo mkdir -p /opt/secman/{bin,config,logs}
sudo chown -R ec2-user:ec2-user /opt/secman

# Move JAR to bin directory
mv /opt/secman/cli-0.1.0-all.jar /opt/secman/bin/secman-cli.jar

# Make JAR executable (optional, but convenient)
chmod +x /opt/secman/bin/secman-cli.jar
```

**Directory structure:**
```
/opt/secman/
├── bin/
│   └── secman-cli.jar          # Standalone JAR file
├── config/
│   ├── credentials.conf        # CrowdStrike credentials (created later)
│   └── application.yml         # Optional: Override backend URL, logging
└── logs/
    ├── cronjob.log            # Cron execution logs
    └── secman-cli.log         # Application logs
```

### 3. Test JAR on Server

```bash
# Test basic execution
java -jar /opt/secman/bin/secman-cli.jar --help

# If this fails, check Java version and JAR integrity
```

---

## Credential Management

### Option 1: Separate Credentials File (Recommended for Cron)

Create `/opt/secman/config/credentials.conf`:

```bash
# Create credentials file
cat > /opt/secman/config/credentials.conf << 'EOF'
# CrowdStrike API Credentials
CROWDSTRIKE_CLIENT_ID=your-client-id-here
CROWDSTRIKE_CLIENT_SECRET=your-client-secret-here

# Backend Authentication (if using --save)
SECMAN_USERNAME=adminuser
SECMAN_PASSWORD=your-secure-password

# Backend URL (optional override)
SECMAN_BACKEND_URL=http://localhost:8080
EOF

# Secure the credentials file (readable only by owner)
chmod 600 /opt/secman/config/credentials.conf
```

### Option 2: AWS Secrets Manager (Production Recommended)

For production environments, use AWS Secrets Manager:

```bash
# Install AWS CLI
sudo dnf install -y aws-cli

# Create secret
aws secretsmanager create-secret \
    --name secman/crowdstrike \
    --secret-string '{
        "client_id":"your-client-id",
        "client_secret":"your-client-secret",
        "username":"adminuser",
        "password":"your-password"
    }' \
    --region us-east-1

# Create wrapper script that retrieves secrets
cat > /opt/secman/bin/run-with-secrets.sh << 'EOF'
#!/bin/bash

# Retrieve secrets from AWS Secrets Manager
SECRETS=$(aws secretsmanager get-secret-value \
    --secret-id secman/crowdstrike \
    --region us-east-1 \
    --query SecretString \
    --output text)

# Export as environment variables
export CROWDSTRIKE_CLIENT_ID=$(echo $SECRETS | jq -r .client_id)
export CROWDSTRIKE_CLIENT_SECRET=$(echo $SECRETS | jq -r .client_secret)
export SECMAN_USERNAME=$(echo $SECRETS | jq -r .username)
export SECMAN_PASSWORD=$(echo $SECRETS | jq -r .password)

# Run CLI with passed arguments
java -jar /opt/secman/bin/secman-cli.jar "$@"
EOF

chmod +x /opt/secman/bin/run-with-secrets.sh
```

**Note:** Requires IAM role with `secretsmanager:GetSecretValue` permission attached to EC2 instance.

---

## Cron Job Configuration

### 1. Create Execution Wrapper Script

Create `/opt/secman/bin/cron-query-servers.sh`:

```bash
cat > /opt/secman/bin/cron-query-servers.sh << 'EOF'
#!/bin/bash

#############################################
# Secman CLI Cron Execution Script
# Description: Query servers with HIGH severity vulnerabilities
# Author: Secman Team
# Version: 1.0
#############################################

# Configuration
JAR_PATH="/opt/secman/bin/secman-cli.jar"
CONFIG_DIR="/opt/secman/config"
LOG_DIR="/opt/secman/logs"
LOG_FILE="${LOG_DIR}/cronjob.log"
CREDENTIALS_FILE="${CONFIG_DIR}/credentials.conf"

# CLI Arguments
SEVERITY="HIGH"
MIN_DAYS_OPEN="1"
SAVE_FLAG="--save"
USERNAME=""  # Will be loaded from credentials
PASSWORD=""  # Will be loaded from credentials

# Error handling
set -e  # Exit on error
set -u  # Exit on undefined variable
set -o pipefail  # Catch errors in pipelines

# Timestamp function
timestamp() {
    date '+%Y-%m-%d %H:%M:%S'
}

# Logging function
log() {
    echo "[$(timestamp)] $1" | tee -a "${LOG_FILE}"
}

# Error handler
error_exit() {
    log "ERROR: $1"
    exit 1
}

# Main execution
main() {
    log "===== Secman CLI Cron Job Starting ====="

    # Verify JAR exists
    if [ ! -f "${JAR_PATH}" ]; then
        error_exit "JAR file not found: ${JAR_PATH}"
    fi

    # Load credentials
    if [ -f "${CREDENTIALS_FILE}" ]; then
        log "Loading credentials from ${CREDENTIALS_FILE}"
        source "${CREDENTIALS_FILE}"

        # Set variables from credentials file
        USERNAME="${SECMAN_USERNAME:-}"
        PASSWORD="${SECMAN_PASSWORD:-}"

        # Export CrowdStrike credentials as environment variables
        export CROWDSTRIKE_CLIENT_ID="${CROWDSTRIKE_CLIENT_ID:-}"
        export CROWDSTRIKE_CLIENT_SECRET="${CROWDSTRIKE_CLIENT_SECRET:-}"
    else
        error_exit "Credentials file not found: ${CREDENTIALS_FILE}"
    fi

    # Validate credentials
    if [ -z "${USERNAME}" ] || [ -z "${PASSWORD}" ]; then
        error_exit "Username or password not set in credentials file"
    fi

    if [ -z "${CROWDSTRIKE_CLIENT_ID}" ] || [ -z "${CROWDSTRIKE_CLIENT_SECRET}" ]; then
        error_exit "CrowdStrike credentials not set in credentials file"
    fi

    # Construct Java command
    log "Executing: query servers --severity ${SEVERITY} --min-days-open ${MIN_DAYS_OPEN} ${SAVE_FLAG}"

    # Execute CLI
    java -jar "${JAR_PATH}" \
        query servers \
        --severity "${SEVERITY}" \
        --min-days-open "${MIN_DAYS_OPEN}" \
        ${SAVE_FLAG} \
        --username "${USERNAME}" \
        --password "${PASSWORD}" \
        2>&1 | tee -a "${LOG_FILE}"

    EXIT_CODE=$?

    if [ ${EXIT_CODE} -eq 0 ]; then
        log "===== Secman CLI Cron Job Completed Successfully ====="
    else
        error_exit "CLI execution failed with exit code: ${EXIT_CODE}"
    fi
}

# Execute main function
main
</EOF>

# Make script executable
chmod +x /opt/secman/bin/cron-query-servers.sh
```

### 2. Test the Wrapper Script

```bash
# Test manual execution
/opt/secman/bin/cron-query-servers.sh

# Check logs
tail -f /opt/secman/logs/cronjob.log
```

### 3. Configure Cron Job

```bash
# Edit crontab for current user
crontab -e
```

Add one of the following entries based on your schedule requirements:

#### Example 1: Daily at 2:00 AM

```cron
# Secman CLI - Query servers daily at 2:00 AM
0 2 * * * /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1
```

#### Example 2: Every Hour

```cron
# Secman CLI - Query servers every hour
0 * * * * /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1
```

#### Example 3: Business Hours Only (Mon-Fri, 9 AM - 5 PM)

```cron
# Secman CLI - Query servers during business hours every hour
0 9-17 * * 1-5 /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1
```

#### Example 4: Multiple Times Per Day

```cron
# Secman CLI - Query servers at 8 AM, 12 PM, 4 PM, 8 PM daily
0 8,12,16,20 * * * /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1
```

### 4. Verify Cron Configuration

```bash
# List current cron jobs
crontab -l

# Check cron service is running
sudo systemctl status crond

# Enable cron service at boot (if not already)
sudo systemctl enable crond
```

---

## Logging and Monitoring

### 1. Log Rotation

Create `/etc/logrotate.d/secman-cli`:

```bash
sudo cat > /etc/logrotate.d/secman-cli << 'EOF'
/opt/secman/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 0644 ec2-user ec2-user
    sharedscripts
    postrotate
        # Optional: Send notification or trigger alerting
        /usr/bin/logger "Secman CLI logs rotated"
    endscript
}
EOF

# Test log rotation configuration
sudo logrotate -d /etc/logrotate.d/secman-cli

# Force log rotation (for testing)
sudo logrotate -f /etc/logrotate.d/secman-cli
```

### 2. CloudWatch Logs Integration (Optional)

Install and configure CloudWatch agent:

```bash
# Install CloudWatch agent
sudo dnf install -y amazon-cloudwatch-agent

# Create CloudWatch configuration
sudo cat > /opt/aws/amazon-cloudwatch-agent/etc/config.json << 'EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/opt/secman/logs/cronjob.log",
            "log_group_name": "/secman/cli/cronjob",
            "log_stream_name": "{instance_id}",
            "retention_in_days": 30
          },
          {
            "file_path": "/opt/secman/logs/secman-cli.log",
            "log_group_name": "/secman/cli/application",
            "log_stream_name": "{instance_id}",
            "retention_in_days": 30
          }
        ]
      }
    }
  }
}
EOF

# Start CloudWatch agent
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
    -a fetch-config \
    -m ec2 \
    -s \
    -c file:/opt/aws/amazon-cloudwatch-agent/etc/config.json

# Enable at boot
sudo systemctl enable amazon-cloudwatch-agent
```

**Note:** Requires IAM role with CloudWatch Logs permissions.

### 3. Monitoring Script Execution

Create a simple monitoring script `/opt/secman/bin/check-cron-health.sh`:

```bash
cat > /opt/secman/bin/check-cron-health.sh << 'EOF'
#!/bin/bash

LOG_FILE="/opt/secman/logs/cronjob.log"
MAX_AGE_HOURS=24

# Check if log file exists
if [ ! -f "${LOG_FILE}" ]; then
    echo "WARNING: Log file not found: ${LOG_FILE}"
    exit 1
fi

# Check last successful execution
LAST_SUCCESS=$(grep "Completed Successfully" "${LOG_FILE}" | tail -1 | awk '{print $1, $2}')

if [ -z "${LAST_SUCCESS}" ]; then
    echo "WARNING: No successful executions found in log"
    exit 1
fi

# Calculate age in hours
LAST_SUCCESS_EPOCH=$(date -d "${LAST_SUCCESS}" +%s 2>/dev/null || echo 0)
CURRENT_EPOCH=$(date +%s)
AGE_HOURS=$(( (CURRENT_EPOCH - LAST_SUCCESS_EPOCH) / 3600 ))

if [ ${AGE_HOURS} -gt ${MAX_AGE_HOURS} ]; then
    echo "WARNING: Last successful execution was ${AGE_HOURS} hours ago (${LAST_SUCCESS})"
    exit 1
else
    echo "OK: Last successful execution ${AGE_HOURS} hours ago (${LAST_SUCCESS})"
    exit 0
fi
EOF

chmod +x /opt/secman/bin/check-cron-health.sh

# Test health check
/opt/secman/bin/check-cron-health.sh
```

---

## Troubleshooting

### Issue 1: "Command not found" Error

**Symptom:**
```
/bin/sh: java: command not found
```

**Solution:**
```bash
# Add Java to cron environment
crontab -e

# Add this line at the top of crontab:
PATH=/usr/bin:/bin:/usr/local/bin:/usr/lib/jvm/java-21-amazon-corretto/bin
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto

# Or use full path in wrapper script
/usr/bin/java -jar /opt/secman/bin/secman-cli.jar ...
```

### Issue 2: Permission Denied

**Symptom:**
```
-bash: /opt/secman/bin/cron-query-servers.sh: Permission denied
```

**Solution:**
```bash
# Fix permissions
chmod +x /opt/secman/bin/cron-query-servers.sh
chown ec2-user:ec2-user /opt/secman/bin/cron-query-servers.sh

# Verify
ls -la /opt/secman/bin/cron-query-servers.sh
```

### Issue 3: Credentials Not Loading

**Symptom:**
```
ERROR: Username or password not set in credentials file
```

**Solution:**
```bash
# Verify credentials file format (no spaces around =)
cat /opt/secman/config/credentials.conf

# Should look like:
SECMAN_USERNAME=adminuser
SECMAN_PASSWORD=password

# NOT like:
SECMAN_USERNAME = adminuser  # WRONG - spaces around =
```

### Issue 4: JAR Not Found

**Symptom:**
```
ERROR: JAR file not found: /opt/secman/bin/secman-cli.jar
```

**Solution:**
```bash
# Verify JAR location
ls -la /opt/secman/bin/

# If JAR is missing, rebuild and copy:
# (on development machine)
./gradlew :cli:shadowJar
scp src/cli/build/libs/cli-0.1.0-all.jar ec2-user@server:/opt/secman/bin/secman-cli.jar
```

### Issue 5: Out of Memory

**Symptom:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
```bash
# Add JVM memory settings to wrapper script
java -Xmx512m -Xms256m -jar /opt/secman/bin/secman-cli.jar ...

# Or create a separate JVM options file
cat > /opt/secman/config/jvm.options << 'EOF'
-Xmx512m
-Xms256m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
EOF

# Use in wrapper script
JAVA_OPTS=$(cat /opt/secman/config/jvm.options | tr '\n' ' ')
java ${JAVA_OPTS} -jar /opt/secman/bin/secman-cli.jar ...
```

### Issue 6: Network Connectivity

**Symptom:**
```
ERROR: Failed to connect to CrowdStrike API
```

**Solution:**
```bash
# Test network connectivity
curl -I https://api.crowdstrike.com

# Check DNS resolution
nslookup api.crowdstrike.com

# Test backend connectivity (if using --save)
curl -I http://localhost:8080/api/health

# Verify security groups allow outbound HTTPS (443)
# Verify VPC route tables
```

---

## Best Practices

### 1. Security

- **Never commit credentials** to version control
- Use **AWS Secrets Manager** or **SSM Parameter Store** for production
- Set **restrictive permissions** on credentials file (`chmod 600`)
- Use **IAM roles** instead of long-lived credentials when possible
- **Rotate credentials** regularly (every 90 days minimum)
- **Audit cron logs** regularly for suspicious activity

### 2. Reliability

- **Monitor cron execution** with health check script
- Set up **alerting** for failed executions (CloudWatch Alarms)
- Use **log rotation** to prevent disk space issues
- Test **JAR updates** in development before deploying to production
- Maintain **at least 1GB free disk space** for logs

### 3. Performance

- **Avoid overlapping executions** - ensure cron interval is longer than typical execution time
- Use **appropriate JVM memory settings** based on dataset size
- Consider **off-peak hours** for large queries to minimize backend load
- Monitor **execution duration trends** to detect performance degradation

### 4. Maintenance

- **Document custom configurations** in comments
- Keep **JAR versions** synchronized with backend
- Test **cron schedule changes** with manual execution first
- Maintain **rollback plan** (keep previous JAR version)
- Review **logs monthly** for errors or warnings

### 5. Cron Schedule Recommendations

| Use Case | Recommended Schedule | Cron Expression |
|----------|---------------------|-----------------|
| Real-time monitoring | Every 5 minutes | `*/5 * * * *` |
| Regular updates | Hourly | `0 * * * *` |
| Daily reports | Daily at 2 AM | `0 2 * * *` |
| Business hours only | Mon-Fri, 9 AM-5 PM, hourly | `0 9-17 * * 1-5` |
| Weekly summary | Sundays at 11 PM | `0 23 * * 0` |

---

## Advanced Configuration

### Custom Severity Filters

Modify the wrapper script to query different severities:

```bash
# In /opt/secman/bin/cron-query-servers.sh

# Query HIGH and CRITICAL
SEVERITY="HIGH,CRITICAL"

# Or query only CRITICAL
SEVERITY="CRITICAL"
```

### Environment-Specific Configurations

Create multiple wrapper scripts for different environments:

```bash
# Production
/opt/secman/bin/cron-query-prod.sh

# Staging
/opt/secman/bin/cron-query-staging.sh

# Development
/opt/secman/bin/cron-query-dev.sh
```

Each with different:
- Backend URLs
- Credentials
- Severity filters
- Schedules

### Parallel Execution Prevention

Add lock file mechanism to prevent concurrent executions:

```bash
# In wrapper script, add at the beginning of main():

LOCK_FILE="/var/lock/secman-cli.lock"

if [ -f "${LOCK_FILE}" ]; then
    log "ERROR: Another instance is already running (lock file exists: ${LOCK_FILE})"
    exit 1
fi

# Create lock file
touch "${LOCK_FILE}"

# Ensure lock file is removed on exit
trap "rm -f ${LOCK_FILE}" EXIT
```

---

## Example: Complete Production Setup

### Step-by-Step Production Deployment

```bash
# 1. Prepare server
sudo dnf update -y
sudo dnf install -y java-21-amazon-corretto-devel aws-cli jq

# 2. Create directory structure
sudo mkdir -p /opt/secman/{bin,config,logs}
sudo chown -R ec2-user:ec2-user /opt/secman

# 3. Upload JAR (from development machine)
scp src/cli/build/libs/cli-0.1.0-all.jar ec2-user@prod-server:/opt/secman/bin/secman-cli.jar

# 4. Create credentials using AWS Secrets Manager
aws secretsmanager create-secret \
    --name prod/secman/credentials \
    --secret-string file://secrets.json \
    --region us-east-1

# 5. Create wrapper script with Secrets Manager integration
# (See "AWS Secrets Manager" section above)

# 6. Test manual execution
/opt/secman/bin/run-with-secrets.sh query servers --severity HIGH --min-days-open 1 --save

# 7. Configure cron
crontab -e
# Add: 0 */6 * * * /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1

# 8. Set up log rotation
sudo vi /etc/logrotate.d/secman-cli

# 9. Configure CloudWatch monitoring
# (See "CloudWatch Logs Integration" section above)

# 10. Set up health check monitoring
/opt/secman/bin/check-cron-health.sh
```

---

## References

- [Secman CLI Monitor Documentation](../src/cli/MONITOR.md)
- [Amazon Linux 2023 User Guide](https://docs.aws.amazon.com/linux/al2023/ug/)
- [Cron Expression Reference](https://crontab.guru/)
- [AWS Secrets Manager](https://docs.aws.amazon.com/secretsmanager/)
- [CloudWatch Logs Agent](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/)

---

## Support

For issues or questions:
- **GitHub Issues**: https://github.com/yourusername/secman/issues
- **Internal Wiki**: [Your internal documentation]
- **Email**: secman-support@yourcompany.com

---

**Version**: 1.0
**Last Updated**: 2025-11-15
**Maintained by**: Secman Team
