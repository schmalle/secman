# Falcon Vulnerability Query Tool

CrowdStrike Falcon CLI tool for querying and exporting vulnerability data with flexible filtering.

## Features

- Query vulnerabilities from CrowdStrike Falcon API
- Filter by device type (CLIENT/SERVER/BOTH)
- Filter by severity levels (MEDIUM/HIGH/CRITICAL)
- Filter by days open, AD domain, hostname
- Export to multiple formats (XLSX, CSV, TXT)
- Environment-based authentication
- Detailed exit codes for automation

## Installation

```bash
pip install -r requirements.txt
pip install -e .
```

## Configuration

Set required environment variables:

```bash
export FALCON_CLIENT_ID="your_client_id"
export FALCON_CLIENT_SECRET="your_client_secret"
export FALCON_CLOUD_REGION="us-1"  # or us-2, eu-1, us-gov-1
```

## Usage

### Basic Query

```bash
falcon-vulns --device-type SERVER --severity CRITICAL --min-days-open 30
```

### Filtered by Domain

```bash
falcon-vulns --device-type BOTH --severity HIGH CRITICAL \
             --min-days-open 90 --ad-domain CORP.LOCAL \
             --output /reports/vulns.xlsx
```

### CSV Export with Hostname Filter

```bash
falcon-vulns --device-type BOTH --severity MEDIUM HIGH CRITICAL \
             --min-days-open 0 --hostname WEB-SERVER-01 \
             --format CSV --verbose
```

## Command-Line Arguments

### Required
- `--device-type {CLIENT,SERVER,BOTH}` - Device type filter
- `--severity {MEDIUM,HIGH,CRITICAL} [...]` - Severity levels (space-separated)
- `--min-days-open N` - Minimum days vulnerability has been open

### Optional
- `--ad-domain DOMAIN` - Filter by Active Directory domain
- `--hostname NAME` - Filter by specific hostname
- `--output PATH` - Custom export file path
- `--format {XLSX,CSV,TXT}` - Export format (default: XLSX)
- `--verbose` - Enable detailed logging

## Exit Codes

- `0` - Success
- `1` - Authentication error
- `2` - Network error
- `3` - Invalid command-line arguments
- `4` - API error (rate limit, server error)
- `5` - Export file write error

## Development

### Run Tests

```bash
pytest tests/
```

### Type Checking

```bash
mypy src/
```

### Linting

```bash
ruff check src/ tests/
```

## Project Structure

```
src/
├── models/        # Data models
├── services/      # API service layer
├── cli/           # CLI interface
├── exporters/     # Export formatters
└── lib/           # Shared utilities

tests/
├── contract/      # API contract tests
├── integration/   # End-to-end tests
└── unit/          # Unit tests
```

## Constitutional Compliance

This project follows the CrowdStrike Falcon Helper Constitution v1.0.0:

- ✅ Python 3.11+ with type hints
- ✅ CLI-first interface
- ✅ Environment-based configuration
- ✅ Dual logging modes (normal/verbose)
- ✅ XLSX export capabilities
- ✅ FalconPy library integration (when T026-T029 implemented)
- ✅ Latest stable dependencies

## License

MIT

## See Also

- [CrowdStrike Falcon API Documentation](https://www.falconpy.io)
- [Feature Specification](specs/002-implement-a-python/spec.md)
- [Implementation Plan](specs/002-implement-a-python/plan.md)
- [Task List](specs/002-implement-a-python/tasks.md)
