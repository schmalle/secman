# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.1.0] - 2025-10-05

### Added
- Initial implementation of vulnerability query CLI tool
- Data models for vulnerabilities, devices, filters, and exports
- XLSX, CSV, and TXT export formatters
- CLI argument parsing with argparse
- Environment variable validation
- Exit code strategy (0-5) for automation
- Type hints and strict mypy configuration
- Ruff linting configuration
- Test structure (contract, integration, unit)

### Pending
- Falcon API service layer (T026-T029)
- Retry logic with exponential backoff
- Progress indication for long queries
- Integration tests implementation
- Unit tests for validation logic

### Constitutional Compliance
- Python 3.11+ ✅
- CLI-first interface ✅
- Environment-based auth ✅
- Dual logging modes ✅
- XLSX export ✅
- Latest dependencies ✅
