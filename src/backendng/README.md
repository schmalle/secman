# SecMan Backend NG (Kotlin Micronaut)

This is the new Kotlin Micronaut backend for SecMan, migrated from the Java Play Framework backend.

## Setup

### Prerequisites
- Java 17+
- MariaDB (reusing existing database schema)

### Configuration
- Port: 9001 (different from Java backend on 9000)
- Database: Same MariaDB instance as Java backend
- CORS: Configured for frontend on localhost:4321

### Development

```bash
# Build the project
./gradlew build

# Run the application
./gradlew run

# Run tests
./gradlew test
```

### Migration Status

#### ✅ Completed
- [x] Project structure setup
- [x] Gradle build configuration
- [x] Basic Micronaut application

#### 🚧 In Progress
- [ ] Database configuration
- [ ] Core domain entities migration

#### 📋 Planned
- [ ] Repository layer with Micronaut Data
- [ ] Security configuration (JWT/OAuth)
- [ ] Controller migration
- [ ] Service layer migration
- [ ] Integration tests

### Architecture

```
src/main/kotlin/com/secman/
├── Application.kt              # Main application entry point
├── controller/                 # REST controllers
├── service/                    # Business logic services
├── repository/                 # Data access layer
├── domain/                     # Entity classes
├── config/                     # Configuration classes
└── security/                   # Security configuration
```

### Key Features to Migrate
- User authentication and authorization
- Requirements management with translations
- Risk assessment workflow
- Document export (DOCX/Excel)
- Email notifications
- OAuth/OIDC integration