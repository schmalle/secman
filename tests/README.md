# Secman End-to-End Tests

This directory contains comprehensive end-to-end tests for the Secman application, covering both API and UI functionality.

## Quick Start

Run all tests:
```bash
./run-e2e-tests.sh
```

Run specific test suites:
```bash
./run-e2e-tests.sh --api-only    # API tests only
./run-e2e-tests.sh --ui-only     # UI tests only
```

## Test Structure

```
tests/
├── api/                          # Backend API tests
│   ├── auth.test.ts             # Authentication tests
│   ├── requirements.test.ts     # Requirements CRUD tests
│   ├── requirements-generation.test.ts  # Requirements generation tests
│   ├── risk-assessment.test.ts  # Risk assessment workflow tests
│   ├── releases.test.ts         # Release management tests
│   └── release-handling.test.ts # Advanced release handling tests
├── ui/                          # Frontend UI tests
│   ├── auth.spec.ts            # Authentication UI tests
│   ├── requirements.spec.ts    # Requirements management UI tests
│   ├── requirements-generation.spec.ts  # Requirements generation UI tests
│   ├── risk-assessment.spec.ts # Risk assessment UI tests
│   └── releases.spec.ts        # Release management UI tests
├── scripts/                     # Test utilities
│   ├── setup-test-env.js       # Environment setup
│   └── cleanup-test-env.js     # Environment cleanup
├── setup/                       # Test configuration
│   └── jest.setup.ts           # Jest setup file
├── package.json                 # Test dependencies
├── jest.config.js              # Jest configuration
├── playwright.config.ts        # Playwright configuration
└── tsconfig.json               # TypeScript configuration
```

## Test Categories

### API Tests (Backend)
- **Authentication**: Login, logout, session management, role-based access
- **Requirements Management**: CRUD operations, validation, associations
- **Requirements Generation**: Templates, AI assistance, bulk operations, translation
- **Risk Assessment**: Asset management, risk scoring, assessment workflows, token-based responses
- **Release Management**: Lifecycle management, content snapshots, versioning, export
- **Release Handling**: Advanced versioning, comparison, filtering, search

### UI Tests (Frontend)
- **Authentication UI**: Login forms, session persistence, role-based navigation
- **Requirements UI**: Management interface, forms, validation, responsive design
- **Requirements Generation UI**: Wizards, templates, AI assistance, bulk import
- **Risk Assessment UI**: Assessment creation, status management, reporting
- **Release Management UI**: Status transitions, content management, export functionality

## Test Data Management

Tests use isolated test data:
- Test users: `admin@test.com`, `user@test.com`, `external@test.com`
- Test entities prefixed with: `TEST-`, `API-`, `UI-`, `GEN-`, etc.
- Automatic cleanup after each test suite

## Environment Requirements

### Backend
- Java 8+ and sbt
- MariaDB database
- Play Framework application running on port 9000

### Frontend
- Node.js and npm
- Astro/React application running on port 4321

### Test Tools
- Node.js 16+
- Jest for API testing
- Playwright for UI testing
- Supertest for HTTP assertions

## Configuration

### Environment Variables
```bash
# Test Database
SECMAN_DB_URL=jdbc:mariadb://localhost:3306/secman_test
SECMAN_DB_USER=secman_test
SECMAN_DB_PASSWORD=test_password

# Test Services
SECMAN_SMTP_HOST=localhost
SECMAN_SMTP_PORT=1025
SECMAN_OPENROUTER_API_KEY=test_key
```

### Test Execution Options

The main test script supports various options:

```bash
# Full test suite
./run-e2e-tests.sh

# API tests only
./run-e2e-tests.sh --api-only

# UI tests only  
./run-e2e-tests.sh --ui-only

# Skip environment setup (if servers already running)
./run-e2e-tests.sh --no-setup

# Skip cleanup (for debugging)
./run-e2e-tests.sh --no-cleanup

# Get help
./run-e2e-tests.sh --help
```

## Development Workflow

### Adding New Tests

1. **API Tests**: Add to appropriate file in `api/` directory
2. **UI Tests**: Add to appropriate file in `ui/` directory
3. **Follow naming conventions**: Use descriptive test names
4. **Include cleanup**: Always clean up test data
5. **Test isolation**: Each test should be independent

### Running Tests During Development

```bash
# Watch mode for API tests
cd tests && npm run test:watch

# Run specific test file
cd tests && npx jest auth.test.ts

# Run specific UI test
cd tests && npx playwright test auth.spec.ts

# Debug mode with headed browser
cd tests && npx playwright test --headed --debug
```

### Test Debugging

1. **API Tests**: Use `console.log` and Jest's `--verbose` flag
2. **UI Tests**: Use Playwright's `--headed` and `--debug` flags
3. **Screenshots**: Failed UI tests automatically capture screenshots
4. **Server Logs**: Check `backend.log` and `frontend.log` in test results

## Continuous Integration

The test suite is designed to run in CI environments:

- Automated server startup and shutdown
- Test isolation and cleanup
- Comprehensive reporting (HTML, JUnit XML, JSON)
- Screenshot and video capture for failures
- Parallel execution support (configurable)

## Test Reports

After running tests, reports are available in:
- `test-results-<timestamp>/` - Combined results with logs
- `playwright-report/` - Playwright HTML report
- `coverage/` - Jest coverage reports
- `test-results.xml` - JUnit XML for CI integration

## Troubleshooting

### Common Issues

1. **Port conflicts**: Ensure ports 9000 and 4321 are available
2. **Database connection**: Verify MariaDB is running and accessible
3. **Dependencies**: Run `npm install` in both `tests/` and `src/frontend/`
4. **Permissions**: Ensure test scripts are executable (`chmod +x`)

### Debug Commands

```bash
# Check if servers are running
curl http://localhost:9000/api/auth/status
curl http://localhost:4321

# Kill processes on test ports
lsof -ti:9000 | xargs kill -9
lsof -ti:4321 | xargs kill -9

# Clean up test data
cd tests && node scripts/cleanup-test-env.js
```

## Contributing

When adding new tests:

1. Follow existing patterns and naming conventions
2. Include proper error handling and cleanup
3. Add JSDoc comments for complex test logic
4. Update this README if adding new test categories
5. Ensure tests work in both local and CI environments

## Security Notes

- Test credentials are hardcoded for testing purposes only
- Test database should be isolated from production
- API keys in tests should be non-functional test values
- All test data is automatically cleaned up after execution