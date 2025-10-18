# Research: CrowdStrike CLI - Vulnerability Query Tool

**Feature**: 023-create-in-the  
**Date**: October 16, 2025  
**Status**: Complete

## Research Questions

### 1. CrowdStrike API Client: SDK vs Raw HTTP

**Decision**: Use Micronaut HTTP Client with manual REST API calls (no official Kotlin SDK)

**Rationale**:
- CrowdStrike provides official SDKs for Python (falconpy), Go, and JavaScript, but **no official Kotlin/Java SDK**
- Third-party Java libraries exist but are unmaintained or have limited CrowdStrike Falcon coverage
- Micronaut's declarative HTTP client (`@Client` annotation) provides excellent support for:
  - OAuth2 token management (automatic refresh)
  - Retry logic with exponential backoff
  - Request/response logging
  - Type-safe request/response mapping
- CrowdStrike Falcon API is well-documented RESTful API with OpenAPI specs available
- Manual HTTP client implementation gives full control over error handling and rate limiting

**Alternatives Considered**:
- **falconpy (Python SDK)**: Official and well-maintained, but would require Python interop from Kotlin (JNI/subprocess), adding complexity
- **Third-party Java libraries**: Sparse ecosystem, most libraries focus on Falcon Sensor management, not vulnerability queries
- **Plain OkHttp/Retrofit**: Would work but requires more boilerplate than Micronaut's declarative client

**Implementation Approach**:
- Use Micronaut's `@Client` annotation with CrowdStrike base URL
- Define service interfaces for authentication and vulnerability endpoints
- Use `@Post`, `@Get` annotations for API operations
- Implement custom `@Recoverable` for retry logic on rate limits (429 responses)
- Store OAuth2 tokens in memory with automatic refresh before expiration

---

### 2. CLI Framework: Picocli Integration with Micronaut

**Decision**: Use Picocli with Micronaut CLI framework

**Rationale**:
- Micronaut has first-class Picocli integration via `micronaut-picocli` dependency
- Picocli provides:
  - Annotation-based command definitions (`@Command`, `@Option`, `@Parameters`)
  - Automatic help generation
  - Subcommand support for future extensibility
  - Input validation
  - ANSI colors for terminal output
- Micronaut CLI scaffold automatically generates Picocli command structure
- Dependency injection works seamlessly in Picocli commands (inject services)

**Best Practices**:
- Single main command class (`CrowdStrikeCliCommand`) with subcommands
- Keep command classes thin - delegate to service layer
- Use `@Option` for optional flags (severity filter, output path)
- Use `@Parameters` for required arguments (hostname)
- Implement `Callable<Integer>` to return exit codes (0 = success, 1 = error)

---

### 3. Configuration File Format & Security

**Decision**: Use HOCON format (Human-Optimized Config Object Notation) with file permission validation

**Rationale**:
- HOCON is native to Lightbend Config library (used by Micronaut)
- More user-friendly than JSON (supports comments, easier syntax)
- Less verbose than YAML, less error-prone
- Micronaut can load HOCON files with `ConfigurationProperties`

**Configuration Structure**:
```hocon
crowdstrike {
  client-id = "YOUR_CLIENT_ID"
  client-secret = "YOUR_CLIENT_SECRET"
  base-url = "https://api.crowdstrike.com"
  timeout = 30s
}
```

**Security Implementation**:
- Check file permissions on startup: MUST be readable only by owner (chmod 600 or 400)
- If permissions are too open (world-readable), print warning and refuse to load
- Config file location: `~/.secman/crowdstrike.conf` (XDG_CONFIG_HOME compatible)
- Never log config values - only log "config loaded successfully"
- Store credentials in memory only, never written to disk after initial read

---

### 4. Export Formats: JSON & CSV Structure

**Decision**: Use Jackson for JSON, Apache Commons CSV for CSV export

**Rationale**:
- Jackson is already included with Micronaut (no extra dependency)
- Apache Commons CSV is battle-tested and handles edge cases (quotes, escaping)
- Both libraries support streaming for large result sets (memory efficient)

**JSON Structure** (example):
```json
{
  "timestamp": "2025-10-16T14:23:45Z",
  "query": {
    "hosts": ["host1.example.com"],
    "severity_filter": "critical"
  },
  "results": [
    {
      "host": "host1.example.com",
      "vulnerabilities": [
        {
          "cve_id": "CVE-2024-1234",
          "severity": "CRITICAL",
          "affected_software": "openssl 1.1.1",
          "description": "Remote code execution vulnerability in OpenSSL...",
          "cvss_score": 9.8,
          "published_date": "2024-03-15"
        }
      ]
    }
  ],
  "summary": {
    "total_hosts": 1,
    "total_vulnerabilities": 1,
    "by_severity": {
      "critical": 1,
      "high": 0,
      "medium": 0,
      "low": 0
    }
  }
}
```

**CSV Structure**:
```csv
Host,CVE ID,Severity,Affected Software,Description,CVSS Score,Published Date
host1.example.com,CVE-2024-1234,CRITICAL,openssl 1.1.1,"Remote code execution vulnerability...",9.8,2024-03-15
```

**Best Practices**:
- JSON: Pretty-print by default for readability (add `--compact` flag for machine consumption)
- CSV: Include header row, escape commas/quotes in description field
- Both: Write to temp file first, then atomic rename to prevent partial writes
- Streaming: Don't load entire result set into memory - stream to disk

---

### 5. Error Handling & Logging Strategy

**Decision**: Structured logging with SLF4J + Logback, user-friendly CLI error messages

**Rationale**:
- SLF4J is standard in Micronaut ecosystem
- Logback provides flexible configuration (file appenders, log rotation)
- Separate concerns: structured logs for debugging, simple messages for users

**Logging Levels**:
- **DEBUG**: HTTP request/response details (headers, URLs - NOT body with sensitive data)
- **INFO**: High-level operations (authentication started, query initiated)
- **WARN**: Recoverable errors (rate limit hit, retrying), file permission warnings
- **ERROR**: Unrecoverable failures (authentication failed, network timeout)

**User-Facing Error Messages**:
- Authentication failure: "Failed to authenticate with CrowdStrike API. Please verify your credentials in ~/.secman/crowdstrike.conf"
- Network error: "Unable to connect to CrowdStrike API. Check your network connection and try again."
- Invalid hostname: "Invalid hostname format: 'invalid@host'. Hostnames must contain only letters, numbers, hyphens, and periods."
- Rate limit: "CrowdStrike API rate limit exceeded. Retrying in 30 seconds... (attempt 2 of 5)"

**What NOT to log**:
- API credentials (client ID/secret, access tokens)
- Full API response bodies (may contain sensitive vulnerability details)
- User input that might contain secrets

---

### 6. API Rate Limiting & Retry Logic

**Decision**: Exponential backoff with jitter, respect Retry-After header

**Rationale**:
- CrowdStrike APIs use standard HTTP 429 (Too Many Requests) responses
- Best practice: respect `Retry-After` header if present, otherwise use exponential backoff
- Jitter prevents thundering herd when multiple instances retry simultaneously

**Implementation**:
- Base delay: 1 second
- Max delay: 60 seconds
- Max retries: 5 attempts
- Backoff formula: `min(60, 2^attempt * 1000ms) + random(0, 1000ms)` (jitter)
- If `Retry-After` header present: use that value instead of calculated backoff
- Non-retriable errors (401, 403): fail immediately, don't retry
- Retriable errors (429, 500, 502, 503, 504, network timeouts)

**User Experience**:
- Show progress for retries: "Rate limit hit. Retrying in 2 seconds (attempt 2 of 5)..."
- For bulk queries: continue with next host if one host fails after max retries
- Exit code: 0 if all succeeded, 1 if any failed

---

### 7. Testing Strategy

**Decision**: Contract tests first, then unit tests, finally integration tests

**Rationale**: 
- Aligns with TDD principle from constitution
- Contract tests define API expectations before implementation
- Integration tests validate end-to-end CLI behavior

**Test Structure**:

**Contract Tests** (`src/test/kotlin/com/secman/cli/contract/`):
- Mock CrowdStrike API responses using WireMock or MockWebServer
- Test authentication flow (token request/response)
- Test vulnerability query responses (various formats)
- Test error responses (401, 429, 500)
- Verify request format (headers, OAuth2 bearer tokens)

**Unit Tests** (`src/test/kotlin/com/secman/cli/unit/`):
- `ConfigLoaderTest`: Config file parsing, permission validation
- `InputValidatorTest`: Hostname validation, file path validation
- `RetryHandlerTest`: Backoff calculation, retry logic
- `JsonExporterTest`: JSON structure, pretty-print vs compact
- `CsvExporterTest`: CSV formatting, escaping

**Integration Tests** (`src/test/kotlin/com/secman/cli/integration/`):
- `QueryCommandTest`: Full command execution with mocked API
- `BulkQueryCommandTest`: Multiple hosts, partial failures
- `ExportCommandTest`: File creation, overwrite prompts
- `FilterCommandTest`: Severity filtering logic

**Coverage Target**: ≥80% per constitution requirement

---

### 8. Multi-Project Gradle Architecture for Code Reuse

**Decision**: Extract CrowdStrike API client from backendng into shared Gradle module at `src/shared/`

**Rationale**:
- Backendng already has complete CrowdStrike API integration (Feature 015: `CrowdStrikeVulnerabilityService`)
- User requirement: "reuse already implemented CrowdStrike API code from backendng folder"
- Shared module eliminates code duplication between backendng and CLI
- Both projects can evolve independently while sharing core API logic

**What Gets Shared** (extracted from backendng):
1. **CrowdStrike API Client**: HTTP client interface and implementation
2. **Authentication**: OAuth2 token management, caching, refresh logic
3. **DTOs**: `CrowdStrikeVulnerabilityDto`, `CrowdStrikeQueryResponse`, `FalconConfigDto`
4. **Domain Models**: `Vulnerability`, `Host`, `Severity` enums
5. **Error Handling**: CrowdStrike-specific exceptions and error codes

**What Stays Separate**:
- **Backendng**: REST API controllers, database entities (Asset, Vulnerability tables), RBAC, JPA repositories
- **CLI**: Picocli commands, file-based config, export functionality, console output formatting

**Gradle Configuration**:
```kotlin
// settings.gradle.kts (root)
include("shared", "cli", "backendng")

// src/shared/build.gradle.kts
dependencies {
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-retry")
    // NO database, NO web server dependencies
}

// src/cli/build.gradle.kts
dependencies {
    implementation(project(":shared"))  // Reuse CrowdStrike API code
    implementation("info.picocli:picocli")
    implementation("org.apache.commons:commons-csv:1.11.0")
}

// src/backendng/build.gradle.kts
dependencies {
    implementation(project(":shared"))  // Reuse CrowdStrike API code
    implementation("io.micronaut.data:micronaut-data-hibernate-jpa")
    implementation("io.micronaut.security:micronaut-security-jwt")
    // ... existing database, security dependencies
}
```

**Migration Path**:
1. Create `src/shared/` module with clean build.gradle.kts
2. Extract `CrowdStrikeVulnerabilityService` → `CrowdStrikeApiService` (remove DB dependencies)
3. Move DTOs from `backendng/dto/` → `shared/dto/`
4. Move authentication logic from `backendng/service/` → `shared/auth/`
5. Update backendng imports to use `com.secman.crowdstrike.*` from shared module
6. CLI depends on shared module from day one (no duplication)

**Benefits**:
- ✅ Maximum code reuse (authentication, HTTP client, retry logic, DTOs)
- ✅ Single source of truth for CrowdStrike API integration
- ✅ Easier testing (contract tests in shared module apply to both projects)
- ✅ Backendng and CLI remain independent (separate deployments, different concerns)
- ✅ Shared module has no web/database dependencies (lightweight, focused)

**Trade-offs**:
- Initial refactoring effort to extract backendng code into shared module
- Need to coordinate breaking changes in shared module (affects both consumers)
- Multi-project build adds slight complexity to Gradle configuration

---

## Technology Stack Summary

| Component | Technology | Version | Justification |
|-----------|-----------|---------|---------------|
| Language | Kotlin | 2.1.0 | Matches backendng, mature ecosystem |
| Framework | Micronaut | 4.4 | Lightweight, fast startup for CLI, excellent HTTP client |
| CLI Framework | Picocli | 4.7+ | Micronaut integration, annotation-based commands |
| HTTP Client | Micronaut HTTP Client | (bundled) | Declarative client, OAuth2 support, retry logic |
| JSON Processing | Jackson | (bundled) | Standard with Micronaut, streaming support |
| CSV Export | Apache Commons CSV | 1.11+ | Robust CSV handling, edge case coverage |
| Configuration | Lightbend Config (HOCON) | (bundled) | User-friendly format, Micronaut native |
| Testing | JUnit 5 | 5.10+ | Constitution requirement |
| Mocking | MockK | 1.13+ | Kotlin-friendly mocking, constitution requirement |
| API Mocking | WireMock or MockWebServer | Latest | Contract test support |
| Logging | SLF4J + Logback | (bundled) | Standard logging, flexible configuration |
| Build | Gradle | 8.5+ | Matches backendng, Kotlin DSL |

---

## Open Questions / Deferred Decisions

None - all NEEDS CLARIFICATION items resolved.

---

## References

- [CrowdStrike Falcon API Documentation](https://falcon.crowdstrike.com/documentation/page/a2a7fc0e/crowdstrike-oauth2-based-apis)
- [Micronaut Picocli Documentation](https://micronaut-projects.github.io/micronaut-picocli/latest/guide/)
- [Micronaut HTTP Client Guide](https://docs.micronaut.io/latest/guide/#httpClient)
- [Picocli User Manual](https://picocli.info/)
- [Apache Commons CSV](https://commons.apache.org/proper/commons-csv/)
