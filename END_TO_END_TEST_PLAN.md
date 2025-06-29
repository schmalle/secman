# Secman End-to-End Testing Plan

## Overview

This document outlines the comprehensive end-to-end testing strategy for the Secman (Security Management) application. The testing approach covers both backend API functionality and frontend user interface interactions to ensure complete system reliability.

## Testing Architecture

### Backend Testing (API Layer)
- **Framework**: Node.js with Jest and Supertest
- **Target**: Play Framework API endpoints (port 9000)
- **Scope**: RESTful API endpoints, authentication, data validation

### Frontend Testing (UI Layer)  
- **Framework**: Playwright with TypeScript
- **Target**: Astro/React frontend (port 4321)
- **Scope**: User interactions, form submissions, navigation, responsive design

### Integration Testing
- **Database**: MariaDB with test data setup/teardown
- **Services**: Email services, file import/export, external API calls

## Test Scenarios

### 1. Authentication & Authorization

#### Backend API Tests
- `POST /api/auth/login` - Valid login credentials
- `POST /api/auth/login` - Invalid credentials
- `GET /api/auth/status` - Session validation
- `POST /api/auth/logout` - Session termination
- Role-based access control (USER vs ADMIN)

#### Frontend UI Tests
- Login form validation and submission
- Session persistence across page reloads
- Automatic redirect to login for unauthenticated users
- Role-based UI element visibility
- Logout functionality

### 2. User Management

#### Backend API Tests
- `GET /api/users` - List users (admin only)
- `POST /api/users` - Create new user
- `PUT /api/users/{id}` - Update user
- `DELETE /api/users/{id}` - Delete user
- Role assignment validation

#### Frontend UI Tests
- User management interface navigation
- User creation form with validation
- User editing and role assignment
- Admin-only access restrictions

### 3. Requirements Management

#### Backend API Tests
- `GET /api/requirements` - List requirements
- `POST /api/requirements` - Create requirement
- `PUT /api/requirements/{id}` - Update requirement
- `DELETE /api/requirements/{id}` - Delete requirement
- Association with norms and use cases
- Export functionality (DOCX, XLSX)
- Translation integration
- Admin bulk deletion

#### Frontend UI Tests
- Requirements list and filtering
- Requirement creation form with all fields
- Complex form validation
- Association management (norms, use cases)
- Export functionality testing
- Bulk operations interface

### 4. Standards & Use Cases Management

#### Backend API Tests
- CRUD operations for standards and use cases
- Relationship management between entities
- Version control integration

#### Frontend UI Tests
- Standards management interface
- Use case creation and editing
- Relationship visualization and management

### 5. Risk Assessment Workflow

#### Backend API Tests
- Asset management CRUD operations
- Risk creation and scoring
- Risk assessment creation with participants
- Token generation for external responses
- Email notification system
- Assessment completion workflow

#### Frontend UI Tests
- Asset management interface
- Risk assessment creation wizard
- Risk scoring interface
- Assessment dashboard and status tracking
- External response interface (token-based)

### 6. Release Management

#### Backend API Tests
- Release creation and versioning
- Content snapshot functionality
- Release publishing and archiving
- Version comparison APIs

#### Frontend UI Tests
- Release management interface
- Version comparison views
- Release publishing workflow
- Content versioning UI

### 7. Import/Export Functionality

#### Backend API Tests
- Excel file import processing
- Various export formats (DOCX, XLSX)
- File validation and error handling
- Translation service integration

#### Frontend UI Tests
- File upload interface
- Import progress and validation feedback
- Export options and generation
- File download functionality

### 8. Administrative Functions

#### Backend API Tests
- System configuration management
- Email and translation settings
- Admin-only endpoint access
- Bulk operations

#### Frontend UI Tests
- Admin dashboard and navigation
- Configuration management forms
- System settings interface
- Admin-only feature access

## Test Data Management

### Database Setup
- Automated test database initialization
- Test data seeding for each test scenario
- Database cleanup after each test suite
- Test isolation and parallel execution support

### Test Users
- `admin@test.com` - Admin user for privileged operations
- `user@test.com` - Standard user for regular operations
- `external@test.com` - External user for response testing

### Sample Data
- Requirements with various field combinations
- Standards and use cases with relationships
- Assets with different types and configurations
- Risk assessments in various states
- Release versions with different statuses

## Environment Configuration

### Backend Test Environment
```bash
# Test database configuration
SECMAN_DB_URL=jdbc:mariadb://localhost:3306/secman_test
SECMAN_DB_USER=secman_test
SECMAN_DB_PASSWORD=test_password

# Test email configuration
SECMAN_SMTP_HOST=localhost
SECMAN_SMTP_PORT=1025
SECMAN_SMTP_USER=test
SECMAN_SMTP_PASS=test

# Test translation service
SECMAN_OPENROUTER_API_KEY=test_key
```

### Frontend Test Environment
```javascript
// playwright.config.ts
export default defineConfig({
  testDir: './tests',
  timeout: 30000,
  expect: { timeout: 5000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:4321',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
});
```

## Test Execution Strategy

### Local Development
1. Start backend server: `cd src/backend && sbt run`
2. Start frontend server: `cd src/frontend && npm run dev`
3. Run backend tests: `npm run test:api`
4. Run frontend tests: `npm run test:ui`
5. Run complete suite: `npm run test:e2e`

### Continuous Integration
1. Automated test database setup
2. Service startup orchestration
3. Parallel test execution
4. Test result reporting and artifacts
5. Cleanup and resource management

### Test Isolation
- Each test scenario runs in isolation
- Database transactions rollback after each test
- Session cleanup between test cases
- File system cleanup for uploads/exports

## Test Coverage Goals

### Backend API Coverage
- **Authentication**: 100% endpoint coverage
- **CRUD Operations**: All entity endpoints tested
- **Business Logic**: Risk scoring, release management
- **Error Handling**: Invalid inputs, unauthorized access
- **Integration**: Email, translation, file processing

### Frontend UI Coverage
- **User Journeys**: Complete workflows tested
- **Form Validation**: All form fields and validation rules
- **Navigation**: All routes and access controls
- **Responsive Design**: Key breakpoints tested
- **Error States**: Network errors, validation failures

### Integration Coverage
- **API-UI Integration**: Form submissions to API
- **Database Persistence**: Data integrity across operations
- **External Services**: Email sending, file processing
- **Session Management**: Authentication state consistency

## Test Reporting and Monitoring

### Test Results
- HTML reports with screenshots for failures
- JUnit XML for CI integration
- Coverage reports for code quality
- Performance metrics for key operations

### Continuous Monitoring
- Test execution time tracking
- Flaky test identification
- Test reliability metrics
- Environment health checks

## Maintenance and Updates

### Test Maintenance
- Regular test data updates
- Test scenario reviews and updates
- Performance optimization
- Browser compatibility updates

### Documentation
- Test scenario documentation
- API endpoint documentation
- Environment setup guides
- Troubleshooting guides

## Success Criteria

### Functional Requirements
- All critical user journeys work end-to-end
- Requirements generation and management functions correctly
- Release handling and versioning works properly
- Authentication and authorization systems are secure

### Performance Requirements
- Test suite completes within 15 minutes
- API response times under 2 seconds
- UI interactions respond within 1 second
- File operations complete within 10 seconds

### Reliability Requirements
- Test suite passes consistently (>95% success rate)
- Tests are deterministic and repeatable
- Parallel execution without conflicts
- Proper error handling and recovery

This comprehensive testing plan ensures the Secman application's reliability, security, and functionality across all user scenarios and system components.