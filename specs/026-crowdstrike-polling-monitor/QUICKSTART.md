# Quick Start Guide: CrowdStrike Polling Monitor

## Prerequisites

1. CrowdStrike API credentials configured
2. Secman backend running on http://localhost:8080 (or specify URL)
3. secman CLI built and ready

## 5-Minute Setup

### Step 1: Configure CrowdStrike Credentials

```bash
secman config --client-id YOUR_CLIENT_ID --client-secret YOUR_CLIENT_SECRET
```

### Step 2: Start Monitoring

```bash
# Monitor specific servers (replace with your hostnames)
secman monitor --hostnames server01,server02,server03

# Or with custom interval (10 minutes)
secman monitor --interval 10 --hostnames prod-server-01,prod-server-02
```

### Step 3: Verify It's Working

You should see output like:

```
=== CrowdStrike Vulnerability Monitor ===
Polling interval: 5 minutes
Severity filter: HIGH, CRITICAL
Monitoring hostnames: server01, server02, server03
Storage: ENABLED

Starting monitor... Press Ctrl+C to stop

[2025-10-18 20:30:00] Polling CrowdStrike API...
[2025-10-18 20:30:05] Poll complete:
  - Devices queried: 3
  - HIGH/CRITICAL vulnerabilities: 12
  - Stored: 12
  - Duration: 5234ms
```

## Common Use Cases

### Test Before Production

```bash
# Dry run - no storage
secman monitor --dry-run --hostnames test-server --verbose
```

### Production Monitoring

```bash
# 10-minute intervals for production servers
secman monitor --interval 10 \
  --hostnames prod-web-01,prod-web-02,prod-db-01 \
  --backend-url http://secman.company.com:8080
```

### Background Process

```bash
# Run in background with logging
nohup secman monitor --interval 5 --hostnames server01,server02 > monitor.log 2>&1 &

# Check logs
tail -f monitor.log

# Stop monitor
pkill -f "secman monitor"
```

## Troubleshooting

### No Vulnerabilities Found

```bash
# Test with query command first
secman query --hostname server01 --severity critical --verbose
```

### Backend Connection Failed

```bash
# Verify backend is running
curl http://localhost:8080/api/crowdstrike/vulnerabilities

# Use different backend URL
secman monitor --backend-url http://different-host:8080 --hostnames server01
```

### Rate Limit Exceeded

```bash
# Increase polling interval
secman monitor --interval 15 --hostnames server01,server02
```

## Command Reference

```bash
# Basic
secman monitor --hostnames <comma-separated-list>

# With options
secman monitor \
  --interval <minutes> \              # Polling interval (default: 5)
  --hostnames <list> \                # Required: hostnames to monitor
  --backend-url <url> \               # Backend URL (default: localhost:8080)
  --dry-run \                         # Query but don't store
  --no-storage \                      # Disable storage
  --verbose                           # Enable verbose logging
```

## Stop the Monitor

Press `Ctrl+C` - the monitor will shutdown gracefully and display statistics:

```
Shutting down monitor...

=== Monitor Statistics ===
Total polls: 12
Total vulnerabilities found: 144
Average vulnerabilities per poll: 12.0
Total errors: 0
Average poll duration: 4877ms
Total runtime: 01:00:00
```

## Next Steps

- [Full Documentation](MONITOR.md)
- [Feature Specification](spec.md)
- [Implementation Details](IMPLEMENTATION.md)
