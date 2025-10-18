# CrowdStrike Vulnerability Monitor

The secman CLI provides a continuous monitoring capability that polls the CrowdStrike API at regular intervals to detect HIGH and CRITICAL severity vulnerabilities across your infrastructure.

## Features

- **Continuous Polling**: Automatically queries CrowdStrike API at configurable intervals
- **Severity Filtering**: Focuses exclusively on HIGH and CRITICAL vulnerabilities
- **Automatic Storage**: Stores detected vulnerabilities in secman database via backend API
- **Graceful Shutdown**: Handles SIGINT/SIGTERM cleanly without data loss
- **Statistics Tracking**: Provides visibility into monitoring operations
- **Flexible Configuration**: Supports CLI arguments and configuration files

## Quick Start

### 1. Configure CrowdStrike Credentials

```bash
secman config --client-id YOUR_CLIENT_ID --client-secret YOUR_CLIENT_SECRET
```

### 2. Start Monitoring

```bash
# Monitor all devices with default 5-minute interval
secman monitor --hostnames server01,server02,server03

# Monitor with custom interval
secman monitor --interval 10 --hostnames prod-server-01,prod-server-02

# Dry run (no storage)
secman monitor --dry-run --hostnames test-server
```

## Command Options

| Option | Description | Default |
|--------|-------------|---------|
| `--interval <minutes>` | Polling interval in minutes | 5 |
| `--hostnames <list>` | Comma-separated list of hostnames to monitor | (required) |
| `--backend-url <url>` | Backend API URL | http://localhost:8080 |
| `--config <path>` | Configuration file path | ~/.secman/monitor.conf |
| `--dry-run` | Query but don't store results | false |
| `--no-storage` | Disable automatic storage | false |
| `--verbose` | Enable verbose logging | false |

## Configuration File

Create `~/.secman/monitor.conf` for persistent configuration:

```yaml
crowdstrike:
  polling_interval_minutes: 5
  severity_filter:
    - HIGH
    - CRITICAL

backend:
  base_url: "http://localhost:8080"
  timeout_seconds: 30
  retry_attempts: 3

monitor:
  log_level: INFO
  statistics_enabled: true
```

## Usage Examples

### Monitor Production Servers

```bash
secman monitor --interval 5 \
  --hostnames prod-web-01,prod-web-02,prod-db-01 \
  --backend-url http://secman.company.com:8080
```

### Development Testing

```bash
# Test without storing results
secman monitor --dry-run --interval 2 --hostnames dev-server-01 --verbose
```

### Background Monitoring

```bash
# Run as background process with output to log file
nohup secman monitor --interval 10 --hostnames server01,server02 > monitor.log 2>&1 &
```

## Output Example

```
=== CrowdStrike Vulnerability Monitor ===
Polling interval: 5 minutes
Severity filter: HIGH, CRITICAL
Dry run: NO
Monitoring hostnames: server01, server02
Storage: ENABLED

Starting monitor... Press Ctrl+C to stop

[2025-10-18 20:30:00] Polling CrowdStrike API...
[2025-10-18 20:30:05] Poll complete:
  - Devices queried: 2
  - HIGH/CRITICAL vulnerabilities: 15
  - Stored: 15
  - Skipped (duplicates): 0
  - Duration: 5234ms

[2025-10-18 20:35:00] Polling CrowdStrike API...
[2025-10-18 20:35:04] Poll complete:
  - Devices queried: 2
  - HIGH/CRITICAL vulnerabilities: 15
  - Stored: 0
  - Skipped (duplicates): 15
  - Duration: 4521ms

^C
Shutting down monitor...

=== Monitor Statistics ===
Total polls: 2
Total vulnerabilities found: 30
Average vulnerabilities per poll: 15.0
Total errors: 0
Average poll duration: 4877ms
Total runtime: 05:04
```

## Architecture

```
┌─────────────────┐     Periodic      ┌──────────────────────┐
│ PollingMonitor  │───────Timer───────▶│ CrowdStrikePoller   │
│   Command       │                    │    Service          │
└─────────────────┘                    └──────────────────────┘
                                                │
                                                │ Query API
                                                │ (severity:HIGH|CRITICAL)
                                                ▼
                                       ┌──────────────────────┐
                                       │  CrowdStrike API     │
                                       │  (via shared module) │
                                       └──────────────────────┘
                                                │
                                                │ Vulnerabilities
                                                ▼
                                       ┌──────────────────────┐
                                       │ VulnerabilityStorage │
                                       │     Service          │
                                       └──────────────────────┘
                                                │
                                                │ HTTP POST
                                                ▼
                                       ┌──────────────────────┐
                                       │ Secman Backend API   │
                                       │ /api/crowdstrike/    │
                                       │ vulnerabilities/save │
                                       └──────────────────────┘
```

## Error Handling

The monitor implements robust error handling:

- **Rate Limiting**: Detects 429 responses and implements exponential backoff
- **Network Failures**: Retries transient errors with exponential backoff
- **Token Expiration**: Automatically refreshes OAuth2 tokens
- **API Errors**: Logs errors and continues monitoring
- **Backend Unavailable**: Continues polling even if storage fails

## Best Practices

1. **Polling Interval**: Use 5-10 minute intervals for production to avoid rate limits
2. **Backend Availability**: Ensure backend API is running before starting monitor
3. **Credential Security**: Store CrowdStrike credentials securely in config file
4. **Logging**: Use `--verbose` for troubleshooting, disable for production
5. **Monitoring**: Track statistics to ensure monitor is operating correctly
6. **Graceful Shutdown**: Always use Ctrl+C for clean shutdown

## Troubleshooting

### Monitor Not Finding Devices

```bash
# Verify hostnames are correct
secman query --hostname server01 --verbose

# Check CrowdStrike configuration
secman config --show
```

### Backend Connection Failed

```bash
# Test backend API directly
curl http://localhost:8080/api/crowdstrike/vulnerabilities

# Override backend URL
secman monitor --backend-url http://different-host:8080
```

### Rate Limit Exceeded

```bash
# Increase polling interval
secman monitor --interval 15 --hostnames server01
```

## systemd Service (Linux)

Create `/etc/systemd/system/secman-monitor.service`:

```ini
[Unit]
Description=Secman CrowdStrike Vulnerability Monitor
After=network.target

[Service]
Type=simple
User=secman
WorkingDirectory=/opt/secman
ExecStart=/opt/secman/bin/secman monitor --interval 10 --hostnames server01,server02
Restart=always
RestartSec=30

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl enable secman-monitor
sudo systemctl start secman-monitor
sudo systemctl status secman-monitor
```

## Related Documentation

- [CrowdStrike API Configuration](README.md#configuration)
- [Query Command](README.md#query-command)
- [Feature Specification](../../specs/026-crowdstrike-polling-monitor/spec.md)

## Support

For issues or questions:
- GitHub: https://github.com/schmalle/secman
- Feature Spec: specs/026-crowdstrike-polling-monitor/spec.md
