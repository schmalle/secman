# Gemini Context for SecMan Project

## Project Overview

**SecMan** is a comprehensive security management platform designed for handling security requirements, tracking vulnerabilities, assessing risks, and maintaining compliance. It features a full-stack architecture with a rigorous security-first approach.

## Technology Stack

### Backend (`src/backendng`)
*   **Language:** Kotlin 2.2 (Java 21)
*   **Framework:** Micronaut 4.10
*   **Database:** MariaDB 12 (Hibernate JPA with auto-migration)
*   **Build System:** Gradle 9.2 (Kotlin DSL)
*   **Authentication:** JWT, OAuth2 (Microsoft/GitHub), RBAC

### Frontend (`src/frontend`)
*   **Framework:** Astro 5.15
*   **UI Library:** React 19
*   **Styling:** Bootstrap 5.3 + Bootstrap Icons
*   **State/Networking:** Axios, SessionStorage for JWT

### CLI & Tools
*   **CLI Tools (`src/cli`):** Kotlin-based utilities for notifications, user mappings, and data imports.
*   **Helper Tools (`src/helper`):** Python 3.11+ scripts for CrowdStrike Falcon API integration.
*   **MCP Server (`mcp/`):** Model Context Protocol integration for AI assistants.

## Architecture & Directory Structure

*   `src/backendng/`: Main backend service.
    *   `src/main/kotlin/com/secman/`: Domain, Repository, Service, Controller layers.
*   `src/frontend/`: Frontend application.
    *   `src/pages/`: Astro pages (routing).
    *   `src/components/`: React components.
*   `src/cli/`: Standalone CLI tools sharing logic with the backend.
*   `specs/`: Detailed feature specifications (Mandatory reference for new features).
*   `docs/`: Comprehensive documentation (API, Environment, Deployment).

## Building & Running

### Backend
```bash
cd src/backendng
./gradlew build      # Compile and build
./gradlew run        # Start the server (Port 8080)
```

### Frontend
```bash
cd src/frontend
npm install          # Install dependencies
npm run dev          # Start development server (Port 4321)
npm run build        # Build for production
```

### CLI Tools
```bash
# Example: Send notifications
./gradlew cli:run --args='send-notifications --dry-run'
```

### Helper Tools
```bash
cd src/helper
pip install -e .
falcon-vulns --help
```

## Development Guidelines & Conventions

**CRITICAL RULES (from AGENTS.md):**
1.  **NO Test Cases:** Do not create or write test cases. Verification is done via build and manual checks.
2.  **Security First:** Always prioritize security. Validate inputs, sanitize data, and strictly adhere to RBAC.
3.  **Strict Commit Messages:** Use the format `Type: Summary` (e.g., `Add: Admin User Notification System`).
4.  **Kotlin Style:** 4-space indentation, `UpperCamelCase` types, constructor injection, immutable data classes.
5.  **TypeScript Style:** 2-space indentation, named exports.

### Key Architectural Patterns

*   **Transactional Replace:** Used for imports (e.g., CrowdStrike) to prevent duplicates. Deletes existing records for an asset before inserting new ones. **Never use `CascadeType.ALL` on `Asset.vulnerabilities`.**
*   **Unified Access Control:** Users see assets if they are ADMIN, or if the asset belongs to their workgroup, was created by them, or matches their Cloud Account ID / AD Domain mappings.
*   **Event-Driven:** Uses `@EventListener` and `@Async` for decoupling (e.g., user creation triggering mapping application).

## Important Configuration

*   **Backend Config:** `src/backendng/src/main/resources/application.yml`
*   **Frontend Config:** `src/frontend/astro.config.mjs`
*   **Environment:** See `.env.example` and `docs/ENVIRONMENT.md`.

## API & Database

*   **API Prefix:** `/api` (e.g., `/api/assets`, `/api/auth/login`)
*   **Authentication:** `Authorization: Bearer <token>`
*   **DB Schema:** Auto-migrated by Hibernate.
*   **Access:** Local access via `mysql -u secman -pCHANGEME secman`.

## Documentation References

*   **`README.md`**: General overview and features.
*   **`CLAUDE.md`**: Detailed API endpoints and developer cheat sheet.
*   **`AGENTS.md`**: Specific behavior rules for AI agents.
*   **`specs/`**: Logic and requirements for specific features.
