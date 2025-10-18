# Implementation Plan: CrowdStrike CLI - Vulnerability Query Tool

**Branch**: `023-create-in-the` | **Date**: October 16, 2025 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/023-create-in-the/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Build a Micronaut-based command-line application in Kotlin that queries the CrowdStrike Falcon API for host vulnerability data, reusing existing CrowdStrike API client code from backendng. The project uses a multi-project Gradle build with a shared module (`src/shared/`) containing CrowdStrike API client, authentication, and DTOs extracted from backendng. Both `backendng` and `cli` depend on this shared module for maximum code reuse. CLI authenticates with API credentials, retrieves vulnerability information for single or multiple hosts, supports filtering by severity, and exports results to JSON/CSV formats. For database persistence, CLI calls backendng REST API.

## Technical Context

**Language/Version**: Kotlin 2.1.0 (JVM target 21)  
**Primary Dependencies**: Micronaut 4.4 (CLI framework, HTTP client), NEEDS CLARIFICATION (CrowdStrike SDK vs raw HTTP)  
**Storage**: File-based (configuration: ~/.secman/crowdstrike.conf, exports: JSON/CSV to user-specified paths)  
**Testing**: JUnit 5 + MockK (unit tests), Micronaut Test (integration tests for HTTP client)  
**Target Platform**: Linux/Unix/macOS command-line environments (Java 21 runtime required)  
**Project Type**: Single standalone CLI application with independent Gradle build  
**Performance Goals**: Query response < 5 seconds (excluding network latency), handle bulk queries for 100 hosts without memory issues  
**Constraints**: Config file permissions (chmod 600), no sensitive data in logs, retry logic for API rate limiting  
**Scale/Scope**: Single-user CLI tool, 5 main commands (authenticate, query single, query bulk, filter, export), ~10-15 source files estimated

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Initial Check (Pre-Phase 0)

| Principle | Requirement | Status | Notes |
|-----------|-------------|--------|-------|
| **I. Security-First** | Input sanitization, secure credential storage, no sensitive logging | ✅ PASS | Config file permissions enforced (chmod 600), credentials not logged, API tokens in memory only |
| **II. Test-Driven Development** | Tests written first, JUnit 5 + MockK, ≥80% coverage | ✅ PASS | Contract tests for CrowdStrike API, unit tests for business logic, integration tests for CLI commands |
| **III. API-First** | RESTful API design, OpenAPI docs | ⚠️ N/A | CLI consumes external API (CrowdStrike), does not expose its own API |
| **IV. Docker-First** | Dockerfile, docker-compose | ⚠️ PARTIAL | Gradle-built JAR can be containerized, but CLI is designed for direct execution. Docker support optional for deployment |
| **V. RBAC** | @Secured annotations, role checking | ⚠️ N/A | Single-user CLI tool, no multi-tenant access control needed. CrowdStrike API credentials provide authorization |
| **VI. Schema Evolution** | Hibernate auto-migration, DB constraints | ⚠️ N/A | No database - file-based configuration only |

**Gate Decision**: ✅ **PROCEED** - N/A items justified by CLI nature (no API exposure, no database, single-user). Core principles (Security, TDD) fully satisfied.

---

### Post-Design Check (After Phase 1)

| Principle | Requirement | Status | Notes |
|-----------|-------------|--------|-------|
| **I. Security-First** | Input sanitization, secure credential storage, no sensitive logging | ✅ PASS | ✅ ConfigLoader validates file permissions (600/400 only)<br>✅ InputValidator sanitizes hostnames (regex validation)<br>✅ Logging excludes credentials, tokens, API responses<br>✅ HTTPS-only communication with CrowdStrike API |
| **II. Test-Driven Development** | Tests written first, JUnit 5 + MockK, ≥80% coverage | ✅ PASS | ✅ Contract tests defined in contracts/*.md<br>✅ Test structure documented in quickstart.md<br>✅ TDD workflow with Red-Green-Refactor cycle<br>✅ MockWebServer for API mocking |
| **III. API-First** | RESTful API design, OpenAPI docs | ✅ N/A (Justified) | CLI consumer only - contracts/*.md documents consumed APIs |
| **IV. Docker-First** | Dockerfile, docker-compose | ⚠️ DEFERRED | Can be added post-MVP for containerized deployment. JAR is portable across environments |
| **V. RBAC** | @Secured annotations, role checking | ✅ N/A (Justified) | Single-user tool - authorization via CrowdStrike API credentials |
| **VI. Schema Evolution** | Hibernate auto-migration, DB constraints | ✅ N/A (Justified) | No database - all data transient except config file |

**Final Gate Decision**: ✅ **APPROVED FOR IMPLEMENTATION**

**Justifications**:
- **API-First N/A**: CLI is a consumer of CrowdStrike API, not a provider. API contracts fully documented.
- **Docker-First Deferred**: Not blocking for MVP. Executable JAR is sufficient for CLI deployment. Docker wrapper can be added in future iteration if needed.
- **RBAC N/A**: Single-user CLI tool. No multi-tenant concerns. CrowdStrike API handles authorization.
- **Schema Evolution N/A**: No database persistence. Configuration is file-based with explicit validation.

## Project Structure

### Documentation (this feature)

```text
specs/023-create-in-the/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
settings.gradle.kts                # Root multi-project settings (includes 'shared', 'cli', 'backendng')
build.gradle.kts                   # Root build configuration

src/shared/                        # NEW: Shared CrowdStrike API module
├── build.gradle.kts               # Shared module build (no web dependencies)
├── src/
│   ├── main/
│   │   └── kotlin/
│   │       └── com/secman/crowdstrike/
│   │           ├── client/                    # CrowdStrike API client (extracted from backendng)
│   │           │   ├── CrowdStrikeClient.kt   # HTTP client interface
│   │           │   └── CrowdStrikeApiService.kt # API implementation
│   │           ├── auth/                       # Authentication (extracted from backendng)
│   │           │   ├── AuthService.kt         # OAuth2 token management
│   │           │   └── TokenCache.kt          # Token caching logic
│   │           ├── dto/                        # DTOs (extracted from backendng)
│   │           │   ├── CrowdStrikeVulnerabilityDto.kt
│   │           │   ├── CrowdStrikeQueryResponse.kt
│   │           │   └── FalconConfigDto.kt
│   │           └── model/                      # Domain models
│   │               ├── Vulnerability.kt
│   │               ├── Host.kt
│   │               └── Severity.kt
│   └── test/
│       └── kotlin/
│           └── com/secman/crowdstrike/
│               ├── contract/                   # Contract tests for CrowdStrike API
│               └── unit/                       # Unit tests for auth/client

src/cli/                           # CLI application (depends on shared module)
├── build.gradle.kts               # CLI build (depends on ':shared')
├── gradle.properties              # Gradle properties
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/secman/cli/
│   │   │       ├── CrowdStrikeCliApplication.kt    # Micronaut CLI entry point
│   │   │       ├── commands/                        # Picocli command implementations
│   │   │       │   ├── QueryCommand.kt             # Single host query
│   │   │       │   ├── BulkQueryCommand.kt         # Multiple host query
│   │   │       │   ├── FilterCommand.kt            # Severity filtering
│   │   │       │   └── ExportCommand.kt            # JSON/CSV export
│   │   │       ├── client/                          # CrowdStrike API client
│   │   │       │   ├── CrowdStrikeClient.kt        # HTTP client interface
│   │   │       │   ├── AuthService.kt              # Authentication & token management
│   │   │       │   └── VulnerabilityService.kt     # Vulnerability query operations
│   │   │       ├── config/                          # Configuration management
│   │   │       │   ├── CliConfig.kt                # Configuration data class
│   │   │       │   └── ConfigLoader.kt             # Load from ~/.secman/crowdstrike.conf
│   │   │       ├── model/                           # Domain models
│   │   │       │   ├── Vulnerability.kt            # Vulnerability entity
│   │   │       │   ├── Host.kt                     # Host entity
│   │   │       │   └── QueryResult.kt              # Query result container
│   │   │       ├── export/                          # Export functionality
│   │   │       │   ├── JsonExporter.kt             # JSON format export
│   │   │       │   └── CsvExporter.kt              # CSV format export
│   │   │       └── util/                            # Utilities
│   │   │           ├── InputValidator.kt           # Input validation
│   │   │           └── RetryHandler.kt             # API rate limit retry logic
│   │   └── resources/
│   │       ├── application.yml                      # Micronaut configuration
│   │       └── logback.xml                          # Logging configuration
│   └── test/
│       ├── kotlin/
│       │   └── com/secman/cli/
│       │       ├── contract/                        # Contract tests for CrowdStrike API
│       │       ├── integration/                     # Integration tests for commands
│       │       └── unit/                            # Unit tests for services/utilities
│       └── resources/
│           └── test-application.yml                 # Test configuration
└── README.md                                        # CLI documentation
```

**Structure Decision**: Multi-project Gradle build with three modules: `shared` (CrowdStrike API client), `cli` (command-line app), and `backendng` (web service). The shared module extracts CrowdStrike API code from backendng (authentication, HTTP client, DTOs) to eliminate duplication. Both `backendng` and `cli` depend on `shared` module. This maximizes code reuse per user requirement while maintaining separation of concerns. CLI structure follows standard Micronaut CLI application patterns with Picocli command framework.

## Complexity Tracking

**No constitutional violations requiring justification.**

All N/A principles (API-First, RBAC, Schema Evolution) are appropriately excluded due to the CLI nature of this feature (single-user tool consuming external API with file-based configuration).

---

## Phase Completion Summary

### Phase 0: Outline & Research ✅ COMPLETE

**Artifacts Generated**:
- `research.md` - 8 research decisions documented:
  1. CrowdStrike API Client approach (Micronaut HTTP Client - REUSED from backendng)
  2. CLI Framework selection (Picocli with Micronaut)
  3. Configuration file format (HOCON)
  4. Export formats structure (JSON/CSV)
  5. Error handling strategy
  6. API rate limiting approach
  7. Testing strategy
  8. **Multi-project Gradle architecture** (shared module for code reuse)

**Key Decisions**:
- Extract CrowdStrike API client from backendng into shared module (`src/shared/`)
- Both backendng and cli depend on shared module for API integration
- HOCON configuration format with file permission validation
- Exponential backoff with jitter for rate limiting
- TDD approach: Contract tests → Unit tests → Integration tests

---

### Phase 1: Design & Contracts ✅ COMPLETE

**Artifacts Generated**:
- `data-model.md` - 8 domain entities with validation rules:
  - Vulnerability (CVE data with severity levels)
  - Host (system information with vulnerability list)
  - QueryResult (container with metadata)
  - QueryParameters (query configuration)
  - QuerySummary (aggregated statistics)
  - QueryError (error details)
  - CliConfig (configuration data)
  - AuthToken (OAuth2 token with expiration)

- `contracts/` - 2 API contract specifications:
  - `crowdstrike-auth-api.md` - OAuth2 authentication flow
  - `crowdstrike-vulnerability-api.md` - Vulnerability query endpoints

- `quickstart.md` - Developer onboarding guide with:
  - Project setup instructions
  - Gradle build configuration
  - Architecture overview
  - TDD workflow examples
  - Sample contract tests

- `.github/copilot-instructions.md` - Updated agent context with:
  - Kotlin 2.1.0 (JVM target 21)
  - Multi-project Gradle build (shared + cli + backendng)
  - Shared CrowdStrike API module for code reuse

**Constitution Re-Check**: ✅ APPROVED - All applicable principles satisfied

---

## Next Steps

1. **Run `/speckit.tasks`** to generate detailed task breakdown
2. **Begin TDD implementation** following quickstart.md guide
3. **Create feature branch** if not already on `023-create-in-the`
4. **Start with contract tests** for AuthService
5. **Implement incrementally** following Red-Green-Refactor cycle

---

## Implementation Readiness Checklist

- [x] Technical context defined (language, dependencies, platform)
- [x] Constitution check passed (security, TDD requirements met)
- [x] Research completed (all NEEDS CLARIFICATION resolved)
- [x] Data model designed (8 entities with validation)
- [x] API contracts documented (2 external APIs)
- [x] Project structure defined (directory layout specified)
- [x] Quickstart guide created (TDD examples included)
- [x] Agent context updated (Copilot instructions)
- [ ] Tasks generated (run `/speckit.tasks` next)
- [ ] Implementation started (pending task breakdown)

**Status**: ✅ **READY FOR TASK GENERATION** - Run `/speckit.tasks` to proceed.
