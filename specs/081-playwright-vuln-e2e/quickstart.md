# Quickstart: Playwright E2E Tests for Vulnmanagement Lense

## Prerequisites

1. **secman application** running and accessible (both backend + frontend)
2. **1Password CLI** (`op`) v2.x installed and authenticated
3. **Google Chrome** and **Microsoft Edge** installed on the test machine
4. **Node.js** 18+ installed

## Setup

```bash
cd tests/e2e
npm install
npx playwright install chrome msedge
```

## Running Tests

### With 1Password (recommended)

```bash
cd tests/e2e
./run-e2e.sh
```

The runner script resolves credentials from 1Password vault `test/secman` and runs the full test matrix.

### With manual credentials

```bash
cd tests/e2e
SECMAN_BASE_URL=http://localhost:4321 \
SECMAN_ADMIN_NAME=adminuser \
SECMAN_ADMIN_PASS=adminpass \
SECMAN_USER_USER=normaluser \
SECMAN_USER_PASS=normalpass \
npx playwright test
```

### Single browser only

```bash
npx playwright test --project=chrome
npx playwright test --project=msedge
```

### Headed mode (for debugging)

```bash
npx playwright test --headed --project=chrome
```

## Test Matrix

| User Role | Chrome | Edge |
|-----------|--------|------|
| ADMIN     | ✓      | ✓    |
| VULN user | ✓      | ✓    |

**Total**: 4 test runs

## Viewing Reports

After test execution:

```bash
npx playwright show-report
```

Opens the HTML test report in a browser showing pass/fail per browser/user combination.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Browser not found" | Run `npx playwright install chrome msedge` |
| "Cannot connect to..." | Ensure secman frontend is running at the configured URL |
| "Login failed" | Verify credentials and that the user accounts exist |
| "op: command not found" | Install 1Password CLI: `brew install 1password-cli` |
| "Vault not found" | Run `op signin` and verify vault access |
