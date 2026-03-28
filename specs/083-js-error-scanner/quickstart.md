# Quickstart: JavaScript Error Scanner

**Branch**: `083-js-error-scanner` | **Date**: 2026-03-20

## Prerequisites

1. **1Password CLI** (`op`) installed and authenticated:
   ```bash
   brew install 1password-cli
   op signin
   ```

2. **Playwright browsers** installed (via existing e2e test setup):
   ```bash
   cd tests/e2e && npm install && npx playwright install chrome
   ```

3. **Secman instance** running and accessible (local or remote)

4. **1Password vault** `test/secman` must contain:
   - `SECMAN_ADMIN_NAME` — login username
   - `SECMAN_ADMIN_PASS` — login password
   - `SECMAN_HOST` — instance URL (e.g., `https://secman.example.com`)
   - `SECMAN_SSL_ACCEPT_ALL` — set to `true` for self-signed certs

## Usage

### Run the scanner

```bash
./tests/js-error-scanner.sh
```

That's it. The script:
1. Resolves credentials from 1Password
2. Launches a headless browser
3. Logs into secman
4. Visits all ~40 static pages
5. Prints a report of JavaScript errors found
6. Exits with code 0 (clean) or 1 (errors found)

### Override the host URL

If you want to scan a different instance without changing 1Password:

```bash
SECMAN_BACKEND_URL="https://staging.secman.example.com" ./tests/js-error-scanner.sh
```

### Skip 1Password (direct credentials)

```bash
SECMAN_ADMIN_NAME="admin" \
SECMAN_ADMIN_PASS="password" \
SECMAN_BACKEND_URL="http://localhost:4321" \
SECMAN_INSECURE="true" \
node tests/js-error-scanner.mjs
```

## Output Example

```
=== Secman JavaScript Error Scanner ===
Host: https://secman.example.com
Scanning 42 pages...

[  1/42] /login .......................... CLEAN
[  2/42] / ............................... CLEAN
[  3/42] /assets ......................... CLEAN
[  4/42] /vulnerabilities/current ........ 2 errors
[  5/42] /admin/email-config ............. TIMEOUT (30s)
...

=== SCAN RESULTS ===

Pages with errors:

  /vulnerabilities/current
    [UNCAUGHT EXCEPTION] TypeError: Cannot read properties of undefined (reading 'map')
    [CONSOLE ERROR] Failed to fetch /api/vulnerabilities/current: 500

  /admin/email-config
    [TIMEOUT] Page did not reach networkidle within 30s

Summary: 42 pages scanned | 39 clean | 2 errors | 1 timeout
Exit code: 1
```

## Files

| File | Purpose |
|------|---------|
| `tests/js-error-scanner.sh` | Bash wrapper — 1Password creds, SSL config, invokes Node.js script |
| `tests/js-error-scanner.mjs` | Node.js script — Playwright browser automation and report output |
