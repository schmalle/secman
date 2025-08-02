# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Secman is a security requirement management and risk assessment tool built with:
- **Backend**: Micronaut 4.4.3 with Kotlin 2.0.21 (port 8080)
- **Frontend**: Astro 5.12.3 with React 19.1.0 integration (port 4321)
- **Database**: MariaDB 11.4
- **Authentication**: JWT with OAuth2 support (GitHub provider configured)

## Essential Commands

### Backend Development (Kotlin/Micronaut)
```bash
cd src/backendng

# Build and run
gradle build
gradle run

# Run tests
gradle test

# Build JAR for deployment
gradle shadowJar
# Output: build/libs/secman-backend-ng-0.1-all.jar

# Run with specific profile
gradle run -Dmicronaut.environments=dev
```

### Frontend Development (Astro/React)
```bash
cd src/frontend

# Install dependencies
npm install

# Development server
npm run dev

# Build for production
npm run build

# Run tests
npm run test                    # Unit tests with Playwright
npm run test:e2e               # End-to-end tests
npm run test:ui                # Tests with UI
npm run test:headed            # Tests in browser
```

### Database Commands
```bash
# Reset database (from project root)
./scripts/reset_database.sh

# Quick reset with sample data
./scripts/quick_reset.sh

# Access database
mysql -u secman -pCHANGEME secman
```

### Docker Development
```bash
# Start all services
./docker/scripts/dev.sh up

# View logs
./docker/scripts/dev.sh logs -f

# Stop services
./docker/scripts/dev.sh down

# Access containers
docker-compose -f docker-compose.dev.yml exec backend bash
docker-compose -f docker-compose.dev.yml exec frontend sh
```

## Architecture & Key Components

### Backend Structure
- **Controllers** (`src/backendng/src/main/kotlin/com/secman/controller/`): REST API endpoints
  - `AuthController`: JWT authentication and login
  - `OAuthController`: OAuth2 flow handling
  - `RequirementController`: Requirements CRUD operations
  - `RiskAssessmentController`: Risk assessment management
  
- **Services** (`src/backendng/src/main/kotlin/com/secman/service/`):
  - `OAuthService`: OAuth provider integration
  - `TranslationService`: OpenRouter API integration for translations
  - `EmailService`: Email notifications (JavaMail)
  - `NormParsingService`: Excel file parsing for requirements import

- **Domain Models** (`src/backendng/src/main/kotlin/com/secman/domain/`): JPA entities with Hibernate
  
- **Security**: Micronaut Security with JWT tokens and BCrypt password hashing

### Frontend Structure
- **Pages** (`src/frontend/src/pages/`): Astro pages with `.astro` extension
- **Components** (`src/frontend/src/components/`): React components (`.tsx`) for interactive UI
  - Management components: `RequirementManagement`, `RiskAssessmentManagement`, etc.
  - `Login.tsx`: Handles both local and OAuth authentication
  
- **Authentication Flow**:
  - JWT tokens stored in localStorage
  - Auth state managed via `utils/auth.ts`
  - Protected routes check authentication in Layout components

### API Patterns
- Base API path: `/api/*`
- Authentication: Bearer token in Authorization header
- CORS configured for localhost:4321 (frontend)
- Anonymous endpoints: `/api/auth/login`, `/oauth/**`, `/health`

## Key Configuration Files

- **Backend**: `src/backendng/src/main/resources/application.yml`
  - Database connection
  - JWT secret configuration
  - OAuth provider settings
  - CORS configuration

- **Frontend**: `src/frontend/astro.config.mjs`
  - React integration
  - Node.js adapter for SSR
  - Build output configuration

## Testing Approach

### Backend Tests
- Framework: Micronaut Test with JUnit 5
- Mock framework: Mockk for Kotlin
- Test database: H2 in-memory
- Run specific tests: `gradle test --tests "com.secman.service.*"`

### Frontend Tests
- Framework: Playwright
- Test files: `src/frontend/tests/*.spec.ts`
- Helper utilities in `test-helpers.ts`
- Environment variables: `PLAYWRIGHT_TEST_USERNAME`, `PLAYWRIGHT_TEST_PASSWORD`

### E2E Test Scripts
- `./scripts/tests/simple-e2e-test.sh`: Basic smoke tests
- `./scripts/tests/comprehensive-e2e-test.sh`: Full test suite
- Test data population: `./scripts/tests/populate-testdata.sh`

## Important Implementation Details

1. **OAuth Flow**: 
   - State stored in database (`OAuthState` entity)
   - Callback URL: `/oauth/callback/{providerId}`
   - Success redirect: `/login/success?token={jwt}`

2. **File Uploads**:
   - Requirements can have attached files
   - Files stored in database as `RequirementFile` entities
   - Binary data stored in `file_data` LONGBLOB column

3. **Translation Service**:
   - Uses OpenRouter API (requires `OPENROUTER_API_KEY`)
   - Configured in `TranslationConfig` entity
   - Supports automatic requirement translation

4. **Default Users** (password: "password"):
   - `adminuser`: Full admin rights
   - `normaluser`: Basic user access

## Environment Variables

Required for development:
```bash
DB_USERNAME=secman
DB_PASSWORD=CHANGEME
JWT_SECRET=your-256-bit-secret-key

# Optional
OPENROUTER_API_KEY=your-key
GITHUB_CLIENT_ID=your-github-app-id
GITHUB_CLIENT_SECRET=your-github-app-secret
FRONTEND_URL=http://localhost:4321
```

## Common Development Tasks

### Adding a New API Endpoint
1. Create controller method in appropriate controller
2. Add security annotations (`@Secured`)
3. Define request/response DTOs with `@Serdeable`
4. Add repository method if needed
5. Update frontend API calls in components

### Adding a New React Component
1. Create `.tsx` file in `src/frontend/src/components/`
2. Import in relevant Astro page with `client:load` directive
3. Follow existing patterns for API integration
4. Use Bootstrap classes for styling (already included)

### Database Schema Changes
- Hibernate auto-update is enabled (`hbm2ddl.auto: update`)
- Add/modify entity classes in `domain` package
- Database tables are created/updated automatically on startup

## Debugging Tips

- Backend logs: Check console output or `backend.log`
- Frontend logs: Browser developer console
- API testing: Use `/health` endpoint to verify backend is running
- Database issues: Check connection settings in `application.yml`
- OAuth issues: Verify provider configuration and callback URLs