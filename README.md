### ALPHA ALPHA ALPHA

![landing.png](docs/landing.png)

**A requirement management, risk assessment management tool**

---

### ALPHA ALPHA ALPHA

## Overview / background

secman was initially started as a security requirement formatter tool. The goal was to generate a beautiful looking MS word document out of a well formatted MS Excel sheet (it was just a helper tool for a repeating task).

Then the idea was born to extend this tooling for some other use cases like risk management or context specific document rendering.

The tool was also started as a test how good / well AI supported coding really works.

## Technology Stack

- **Backend**: Micronaut Framework with Kotlin
- **Frontend**: Astro with React integration
- **Database**: MariaDB 11.4
- **Build System**: Gradle (Kotlin DSL)
- **Containerization**: Docker with multi-architecture support
- **Authentication**: JWT with OAuth2 support

---

## Features

- **Requirements Management:** Create, edit, prioritize, and track requirements.
- **Export requirements** in a well formatted word file
- (untested/dummy code) **Risk Assessment:** Identify, evaluate, and document risks associated with requirements or processes.
- **User Roles:** Assign and manage different user roles (normaluser, adminuser) with appropriate permissions.
- (untested/dummy code) MCP server
- (untested/dummy code) E-Mail notification
- Automatic translation of requirements into other languages (via Openrouter API)
- (untested/dummy code) usage of external identity providers besides Github
- Login via Github
- **Asset management**

## Getting Started

### Prerequisites

**Option 1: Docker (Recommended)**
- Docker and Docker Compose
- Git

**Option 2: Local Development**
- Java 17 or higher
- Node.js 20 or higher
- MariaDB 11.4 or higher
- Gradle 8.14 or higher
- Git
- OpenRouter Key (optional for translation features)

### Installation

1. **Clone the repository:**

   ```
   git clone https://github.com/schmalle/secman.git
   cd secman
   ```
2. **Create database**

```cd
cd scripts/install
./install.sh
```

3. **Build the project (backend):**

```sh
sbt run dev
```

Please note: The Micronaut framework with Hibernate automatically creates database tables on startup.

2. **Build the project (frontend):**

   ```sh
   npm run dev
   ```

## Docker Deployment

**Development Environment:**
```bash
# Start all services
./docker/scripts/dev.sh up

# Start in detached mode
./docker/scripts/dev.sh up -d

# View logs
./docker/scripts/dev.sh logs

# Stop services
./docker/scripts/dev.sh down
```

**Production Deployment:**
```bash
# Configure environment
cp .env.example .env
# Edit .env with production values

# Deploy to production
./docker/scripts/deploy.sh deploy

# Check status
./docker/scripts/deploy.sh status
```

**Multi-Architecture Build:**
```bash
# Build for AMD64 and ARM64
./docker/scripts/build-multiarch.sh latest
```

To reset the database, use the script /scripts/`./reset_database.sh `.

## Usage

- **Access the Frontend:** http://localhost:4321
- **Access the Backend API:** http://localhost:8080/api
- **Database Access:** localhost:3306 (secman/CHANGEME)
- **Health Checks:** 
  - Frontend: http://localhost:4321/health
  - Backend: http://localhost:8080/health

## Roles / Default application users (pw password)

- **adminuser:** Full administrative rights, including user management and configuration.
- **normaluser:** Basic access for submitting and tracking requirements/risks.

---

## Testing

**Docker-based Testing (Recommended):**
```bash
# Start test environment
./docker/scripts/dev.sh up -d

# Run frontend tests
docker-compose -f docker-compose.dev.yml exec frontend npm run test

# Run backend tests
docker-compose -f docker-compose.dev.yml exec backend gradle test

# Run end-to-end tests
cd src/frontend
npm run test:e2e
```

**Local Testing:**
```bash
# Backend tests (Micronaut Test)
cd src/backendng
gradle test

# Frontend tests (Playwright)
cd src/frontend
npm run test

# End-to-end tests
npm run test:e2e
```

**Legacy Test Scripts:**
```
  Option 1: Simple Test Runner
  # Default credentials (adminuser/password)
  ./scripts/simple-e2e-test.sh

  # Custom credentials
  ./scripts/simple-e2e-test.sh --username=myuser --password=mypass

  Option 2: Comprehensive Test Runner
  # All tests with default credentials
  ./scripts/comprehensive-e2e-test.sh

  # Custom credentials
  ./scripts/comprehensive-e2e-test.sh --username=myuser --password=mypass

  # Just smoke tests
  ./scripts/comprehensive-e2e-test.sh --smoke-only

  Option 3: Direct Playwright
  cd src/frontend
  export PLAYWRIGHT_TEST_USERNAME=adminuser
  export PLAYWRIGHT_TEST_PASSWORD=password
  npx playwright test
```

## Database Details

- **Database:** secman
- **User:** secman/CHANGEME
- **Host:** localhost (or 'database' container in Docker)
- **Port:** 3306
- **Engine:** MariaDB 11.4

**Configuration:**
- Backend configuration: `src/backendng/src/main/resources/application.yml`
- Docker environment: `.env` file
- Database initialization: `docker/database/init/`

**Management:**
```bash
# Docker database access
docker-compose -f docker-compose.dev.yml exec database mysql -u secman -pCHANGEME secman

# Create backup
./docker/scripts/deploy.sh backup

# Reset database (development)
./scripts/reset_database.sh
```

## Contributing

We welcome contributions at a later stage. For the moment any idea / potential topic would be great.

---

## License

A-GPL 3.0 license

---

## Contact

- **Maintainer:** Markus "flake" Schmall
- Mastodon: flakedev@infosec.exchange
- Telegram: flakedev
- **Email:** markus@schmall.io
