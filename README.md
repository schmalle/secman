### ALPHA ALPHA ALPHA

![landing.png](docs/landing.png)

**A requirement management, risk assessment management tool**

---

### ALPHA ALPHA ALPHA

## Overview / Background

secman was initially started as a security requirement formatter tool. The goal was to generate a beautiful looking MS word document out of a well formatted MS Excel sheet (it was just a helper tool for a repeating task).

Then the idea was born to extend this tooling for some other use cases like risk management or context specific document rendering.

The tool was also started as a test how good / well AI supported coding really works.

### Technology Stack

Secman has been completely migrated to a modern, containerized architecture:

- **Backend**: Micronaut 4.4.3 with Kotlin 2.0.21 (previously Play Framework with Java)
- **Frontend**: Astro with React components  
- **Database**: MariaDB 10.11
- **Build System**: Gradle 8.5+ (replacing sbt)
- **Deployment**: Docker with multi-architecture support
- **Development**: Container-first workflow with hot reload

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

- Docker 20.10+ with Buildx support (recommended)
- Docker Compose 2.0+ (recommended)
- **OR** for manual setup:
  - Java 17+ (tested with version 17)
  - Node.js 18+ (tested with version 18)
  - MariaDB 10.11+
  - Gradle 8.5+ (not sbt)
- OpenRouter API Key (optional)
- GitHub OAuth credentials (optional)

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

Please note: The play framework ensures that all tables are existing, which are neeeded.

1. **Build the project (frontend):**

   ```sh
   npm run dev
   ```

To reset the database, use the script /scripts/`./reset_database.sh `.

## 

Usage

- **Access the UI:** [URL or command to access the user interface]
- **Add requirements:** [Brief instructions]
- **Perform risk assessment:** [Brief instructions]
- **Generate reports:** [Brief instructions]

## Roles / Default application users (pw password)

- **adminuser:** Full administrative rights, including user management and configuration.
- **normaluser:** Basic access for submitting and tracking requirements/risks.

---

## Testing

### Docker-based Testing (Recommended)

```bash
# Start test environment
cd docker/compose
docker compose -f docker-compose.dev.yml up -d

# Wait for services to be healthy
docker compose -f docker-compose.dev.yml ps

# Run frontend tests
docker compose -f docker-compose.dev.yml exec frontend npm run test

# Run backend tests  
docker compose -f docker-compose.dev.yml exec backend gradle test
```

### Manual Testing

```bash
# Option 1: Simple Test Runner
./scripts/simple-e2e-test.sh --username=adminuser --password=password

# Option 2: Comprehensive Test Runner
./scripts/comprehensive-e2e-test.sh --username=adminuser --password=password

# Option 3: Direct Playwright (requires manual backend/frontend startup)
cd src/frontend
export PLAYWRIGHT_TEST_USERNAME=adminuser
export PLAYWRIGHT_TEST_PASSWORD=password
npx playwright test
```

### Backend Unit Tests (Micronaut/Kotlin)

```bash
cd src/backendng
gradle test
gradle integrationTest
```

## Database Details

- **Database:** secman
- **User:** secman/CHANGEME
- **Host:** localhost (or `database` container in Docker)
- **Port:** 3306 (production) / 3307 (development)

### Configuration

Database configuration is located in:
- Docker: Environment variables in `docker/compose/.env`
- Manual: `src/backendng/src/main/resources/application.yml`
- Docker override: `src/backendng/src/main/resources/application-docker.yml`

### Docker Database Management

```bash
# Connect to database
docker compose exec database mysql -u secman -pCHANGEME secman

# View database logs
docker compose logs database

# Reset database (destroys all data)
docker compose down -v
docker compose up -d
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
