# Development Setup Guide

This guide provides detailed instructions for setting up a development environment for Secman, both with and without Docker.

## Architecture Overview

Secman is built with modern technologies:

- **Backend**: Micronaut 4.4.3 with Kotlin 2.0.21
- **Frontend**: Astro 5.11.0 with React 19.1.0
- **Database**: MariaDB 11.4
- **Build System**: Gradle 8.14.3 (Kotlin DSL)
- **Package Manager**: npm (Node.js 20+)

## Prerequisites

### Docker Development (Recommended)

- Docker 20.10+
- Docker Compose 2.0+
- Git
- IDE of choice (IntelliJ IDEA, VS Code, etc.)

### Local Development

- Java 17 (OpenJDK or similar)
- Node.js 20.19.4+
- MariaDB 11.4+
- Gradle 8.14+
- Git

## Docker Development Setup

### Quick Start

1. **Clone and configure:**
   ```bash
   git clone https://github.com/schmalle/secman.git
   cd secman
   cp .env.example .env
   ```

2. **Start development environment:**
   ```bash
   ./docker/scripts/dev.sh up
   ```

3. **Access applications:**
   - Frontend: http://localhost:4321
   - Backend: http://localhost:8080
   - Database: localhost:3306

### Development Workflow

**Starting services:**
```bash
# Start all services with live reload
./docker/scripts/dev.sh up

# Start in background
./docker/scripts/dev.sh up -d

# Start specific service
docker-compose -f docker-compose.dev.yml up frontend
```

**Viewing logs:**
```bash
# All logs
./docker/scripts/dev.sh logs

# Specific service
./docker/scripts/dev.sh logs backend
./docker/scripts/dev.sh logs frontend
./docker/scripts/dev.sh logs database

# Follow logs
./docker/scripts/dev.sh logs -f
```

**Making changes:**
- Backend: Edit files in `src/backendng/` - changes trigger automatic rebuild
- Frontend: Edit files in `src/frontend/` - changes trigger hot reload
- Database: Scripts in `docker/database/init/` run on container creation

**Debugging:**
- Backend debug port: 5005 (connect with IDE)
- Frontend dev tools: Available in browser

### Container Shell Access

```bash
# Backend container
docker-compose -f docker-compose.dev.yml exec backend bash

# Frontend container
docker-compose -f docker-compose.dev.yml exec frontend sh

# Database container
docker-compose -f docker-compose.dev.yml exec database mysql -u secman -pCHANGEME secman
```

## Local Development Setup

### 1. Database Setup

**Install MariaDB:**
```bash
# Ubuntu/Debian
sudo apt install mariadb-server

# macOS
brew install mariadb

# Start service
sudo systemctl start mariadb  # Linux
brew services start mariadb   # macOS
```

**Create database and user:**
```sql
mysql -u root -p

CREATE DATABASE secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'secman'@'localhost' IDENTIFIED BY 'CHANGEME';
GRANT ALL PRIVILEGES ON secman.* TO 'secman'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

### 2. Backend Setup

**Navigate to backend directory:**
```bash
cd src/backendng
```

**Configure database connection:**
Edit `src/main/resources/application.yml`:
```yaml
datasources:
  default:
    url: jdbc:mariadb://localhost:3306/secman
    username: secman
    password: CHANGEME
```

**Build and run:**
```bash
# Build project
gradle build

# Run application
gradle run

# Run with specific profile
gradle run -Dmicronaut.environments=dev

# Run tests
gradle test
```

**Development server features:**
- Automatic restart on code changes
- Debug port available on 5005
- Hot reload for Kotlin code
- API available at http://localhost:8080

### 3. Frontend Setup

**Navigate to frontend directory:**
```bash
cd src/frontend
```

**Install dependencies:**
```bash
npm install
```

**Development commands:**
```bash
# Start development server
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview

# Run tests
npm run test

# Run end-to-end tests
npm run test:e2e
```

**Development server features:**
- Hot module replacement (HMR)
- TypeScript compilation
- React fast refresh
- Available at http://localhost:4321

## IDE Configuration

### IntelliJ IDEA

**Backend (Kotlin):**
1. Open `src/backendng` as Gradle project
2. Enable Kotlin plugin
3. Configure run configuration:
   - Main class: `com.secman.ApplicationKt`
   - VM options: `-Dmicronaut.environments=dev`
   - Debug port: 5005

**Frontend (TypeScript/React):**
1. Open `src/frontend` as Node.js project
2. Configure npm run configuration:
   - Scripts: `dev`, `build`, `test`
   - Enable ESLint and Prettier

### VS Code

**Recommended extensions:**
- Kotlin Language
- Gradle for Java
- Astro
- ES7+ React/Redux/React-Native snippets
- Prettier - Code formatter
- ESLint

**Configuration files:**
```json
// .vscode/settings.json
{
  "kotlin.languageServer.enabled": true,
  "typescript.preferences.importModuleSpecifier": "relative",
  "eslint.workingDirectories": ["src/frontend"],
  "prettier.configPath": "src/frontend/.prettierrc"
}
```

## Testing

### Backend Tests (Micronaut Test)

```bash
cd src/backendng

# Run all tests
gradle test

# Run specific test class
gradle test --tests "com.secman.service.*"

# Run with coverage
gradle test jacocoTestReport

# Integration tests
gradle integrationTest
```

**Writing tests:**
```kotlin
@MicronautTest
class UserServiceTest {
    
    @Inject
    lateinit var userService: UserService
    
    @Test
    fun testCreateUser() {
        // Test implementation
    }
}
```

### Frontend Tests (Playwright)

```bash
cd src/frontend

# Run unit tests
npm run test

# Run e2e tests
npm run test:e2e

# Run with UI
npm run test:ui

# Run specific test
npx playwright test login.spec.ts
```

**Writing tests:**
```typescript
import { test, expect } from '@playwright/test';

test('login page', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Login' })).toBeVisible();
});
```

## Database Management

### Migrations

Micronaut with Hibernate automatically handles schema updates:

```yaml
# application.yml
jpa:
  default:
    properties:
      hibernate:
        hbm2ddl:
          auto: update  # Creates/updates tables automatically
```

**Manual migrations (if needed):**
1. Create SQL files in `src/main/resources/db/migration/`
2. Use Flyway or Liquibase for versioned migrations

### Sample Data

```sql
-- Insert test users
INSERT INTO users (username, email, password_hash, role, created_at) VALUES
('adminuser', 'admin@secman.local', '$2a$10$...', 'ADMIN', NOW()),
('normaluser', 'user@secman.local', '$2a$10$...', 'USER', NOW());
```

## Environment Configuration

### Development Environment Variables

Create `.env` file:
```bash
# Database
DB_USERNAME=secman
DB_PASSWORD=CHANGEME
DB_URL=jdbc:mariadb://localhost:3306/secman

# Security
JWT_SECRET=your-256-bit-secret-key

# Optional: API Keys
OPENROUTER_API_KEY=your-openrouter-key
GITHUB_CLIENT_ID=your-github-client-id
GITHUB_CLIENT_SECRET=your-github-client-secret
```

### Application Profiles

**Development (`application-dev.yml`):**
```yaml
logger:
  levels:
    com.secman: DEBUG
    io.micronaut: INFO

micronaut:
  server:
    cors:
      enabled: true
      configurations:
        web:
          allowed-origins:
            - "http://localhost:4321"
```

**Production (`application-prod.yml`):**
```yaml
logger:
  levels:
    com.secman: INFO
    root: WARN

micronaut:
  server:
    cors:
      enabled: false
```

## Hot Reload Configuration

### Backend Hot Reload

Gradle continuous build:
```bash
cd src/backendng
gradle run --continuous
```

Or use IDE run configuration with:
- VM options: `-XX:+UseG1GC -Xmx1G`
- Enable hot swap

### Frontend Hot Reload

Astro development server automatically provides:
- Fast refresh for React components
- CSS hot module replacement
- TypeScript compilation
- Asset processing

## Performance Tips

### Development

1. **Use SSD storage** for better I/O performance
2. **Allocate sufficient memory** to JVM and Node.js
3. **Use Gradle daemon** for faster builds
4. **Enable parallel builds** in `gradle.properties`:
   ```properties
   org.gradle.parallel=true
   org.gradle.daemon=true
   org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
   ```

### Database

1. **Use local MariaDB** for faster queries
2. **Configure proper indexes** for development data
3. **Use connection pooling** (HikariCP is pre-configured)

## Troubleshooting

### Common Issues

**Backend won't start:**
- Check Java version (must be 17+)
- Verify database connectivity
- Check port 8080 availability
- Review application logs

**Frontend build fails:**
- Clear `node_modules` and reinstall
- Check Node.js version (must be 20+)
- Verify TypeScript configuration
- Clear browser cache

**Database connection issues:**
- Verify MariaDB is running
- Check credentials in application.yml
- Test connection manually
- Review firewall settings

### Debugging

**Backend debugging:**
1. Start with debug flag: `gradle run --debug-jvm`
2. Connect IDE to port 5005
3. Set breakpoints in Kotlin code

**Frontend debugging:**
1. Use browser dev tools
2. React dev tools extension
3. Console logging
4. Source maps are enabled by default

## Code Style and Formatting

### Backend (Kotlin)

Uses ktlint for formatting:
```bash
cd src/backendng
gradle ktlintFormat
```

### Frontend (TypeScript/React)

Uses Prettier and ESLint:
```bash
cd src/frontend
npm run format
npm run lint
```

## Additional Resources

- [Micronaut Documentation](https://docs.micronaut.io/)
- [Astro Documentation](https://docs.astro.build/)
- [React Documentation](https://react.dev/)
- [MariaDB Documentation](https://mariadb.org/documentation/)
- [Docker Documentation](https://docs.docker.com/)