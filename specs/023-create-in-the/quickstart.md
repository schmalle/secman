# Quickstart Guide: CrowdStrike CLI - Vulnerability Query Tool

**Feature**: 023-create-in-the  
**Audience**: Developers implementing Feature 023  
**Last Updated**: October 16, 2025

## Overview

This quickstart guide helps developers set up the CrowdStrike CLI development environment, understand the architecture, and begin implementing the feature using TDD principles.

---

## Prerequisites

- **Java 21** (JDK 21 or later)
- **Gradle 8.5+** (wrapper included)
- **Kotlin 2.1.0** (managed by Gradle)
- **Git** (for version control)
- **CrowdStrike API Credentials** (client ID and secret for testing)

---

## Project Setup

### 1. Create Project Structure

```bash
cd /Users/flake/sources/misc/secman
mkdir -p src/cli
cd src/cli
```

### 2. Initialize Gradle Project

Create `build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.allopen") version "2.1.0"
    id("com.google.devtools.ksp") version "2.1.0-1.0.28"
    id("io.micronaut.application") version "4.4.2"
}

version = "0.1.0"
group = "com.secman.cli"

repositories {
    mavenCentral()
}

dependencies {
    // Micronaut core
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    
    // Picocli for CLI
    implementation("info.picocli:picocli")
    implementation("io.micronaut.picocli:micronaut-picocli")
    
    // HTTP client for CrowdStrike API
    implementation("io.micronaut:micronaut-http-client")
    
    // Configuration
    implementation("io.micronaut:micronaut-context")
    
    // CSV export
    implementation("org.apache.commons:commons-csv:1.11.0")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    
    // Testing
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

application {
    mainClass.set("com.secman.cli.CrowdStrikeCliApplicationKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "21"
        }
    }
    compileTestKotlin {
        kotlinOptions {
            jvmTarget = "21"
        }
    }
    test {
        useJUnitPlatform()
    }
}

graalvmNative.toolchainDetection = false

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.secman.cli.*")
    }
}
```

Create `settings.gradle.kts`:

```kotlin
rootProject.name = "crowdstrike-cli"
```

Create `gradle.properties`:

```properties
micronautVersion=4.4.2
kotlinVersion=2.1.0
```

### 3. Create Micronaut Configuration

Create `src/main/resources/application.yml`:

```yaml
micronaut:
  application:
    name: crowdstrike-cli
  http:
    client:
      read-timeout: 30s
      connect-timeout: 10s

logger:
  levels:
    com.secman.cli: INFO
```

Create `src/main/resources/logback.xml`:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="info">
        <appender-ref ref="STDOUT" />
    </root>
    
    <logger name="com.secman.cli" level="debug"/>
</configuration>
```

---

## Architecture Overview

### Directory Structure

```
src/cli/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── src/
    ├── main/
    │   ├── kotlin/com/secman/cli/
    │   │   ├── CrowdStrikeCliApplication.kt      # Main entry point
    │   │   ├── commands/                          # Picocli commands
    │   │   ├── client/                            # CrowdStrike API client
    │   │   ├── config/                            # Configuration management
    │   │   ├── model/                             # Domain models
    │   │   ├── export/                            # Export functionality
    │   │   └── util/                              # Utilities
    │   └── resources/
    │       ├── application.yml
    │       └── logback.xml
    └── test/
        ├── kotlin/com/secman/cli/
        │   ├── contract/                          # Contract tests (API mocking)
        │   ├── integration/                       # Integration tests (full commands)
        │   └── unit/                              # Unit tests (isolated logic)
        └── resources/
            └── test-application.yml
```

### Component Responsibilities

| Component | Responsibility |
|-----------|---------------|
| **CrowdStrikeCliApplication** | Micronaut application entry point, command execution |
| **commands/** | Picocli command implementations (thin layer, delegates to services) |
| **client/AuthService** | OAuth2 authentication, token management |
| **client/VulnerabilityService** | Vulnerability query operations, API interaction |
| **config/ConfigLoader** | Load and validate config file from ~/.secman/crowdstrike.conf |
| **model/** | Domain entities (Vulnerability, Host, QueryResult, etc.) |
| **export/JsonExporter** | Export results to JSON format |
| **export/CsvExporter** | Export results to CSV format |
| **util/InputValidator** | Validate hostnames, file paths |
| **util/RetryHandler** | Exponential backoff retry logic for API errors |

---

## Development Workflow (TDD)

### Step 1: Write Contract Tests FIRST

**Example: Authentication Contract Test**

Create `src/test/kotlin/com/secman/cli/contract/AuthServiceContractTest.kt`:

```kotlin
package com.secman.cli.contract

import com.secman.cli.client.AuthService
import com.secman.cli.model.AuthToken
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@MicronautTest
class AuthServiceContractTest {
    
    private lateinit var mockServer: MockWebServer
    
    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
    }
    
    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }
    
    @Test
    fun `should authenticate and return valid token`() {
        // Given: Mock CrowdStrike API success response
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                  "access_token": "eyJhbGci...token123",
                  "token_type": "bearer",
                  "expires_in": 1799
                }
            """.trimIndent())
        )
        
        // When: Authenticate with valid credentials
        val authService = AuthService(mockServer.url("/").toString())
        val token = authService.authenticate("client-id", "client-secret")
        
        // Then: Token is valid
        assertEquals("eyJhbGci...token123", token.accessToken)
        assertEquals("bearer", token.tokenType)
        assertFalse(token.isExpired())
    }
    
    @Test
    fun `should throw exception on 401 Unauthorized`() {
        // Given: Mock 401 response
        mockServer.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"errors":[{"code":401,"message":"access denied"}]}""")
        )
        
        // When/Then: Should throw AuthenticationException
        val authService = AuthService(mockServer.url("/").toString())
        assertThrows<AuthenticationException> {
            authService.authenticate("invalid", "credentials")
        }
    }
}
```

**Run the test** - it should FAIL (Red):
```bash
./gradlew test --tests AuthServiceContractTest
```

### Step 2: Implement MINIMUM Code to Pass

Create `src/main/kotlin/com/secman/cli/client/AuthService.kt`:

```kotlin
package com.secman.cli.client

import com.secman.cli.model.AuthToken
import jakarta.inject.Singleton
import java.time.Instant

@Singleton
class AuthService(private val baseUrl: String) {
    
    fun authenticate(clientId: String, clientSecret: String): AuthToken {
        // TODO: Implement actual HTTP request
        return AuthToken(
            accessToken = "eyJhbGci...token123",
            expiresAt = Instant.now().plusSeconds(1799),
            tokenType = "bearer"
        )
    }
}

class AuthenticationException(message: String) : RuntimeException(message)
```

**Run the test again** - it should PASS (Green).

### Step 3: Refactor and Complete Implementation

Add real HTTP client implementation:

```kotlin
@Singleton
class AuthService(
    @Client("\${crowdstrike.api.base-url}") private val httpClient: HttpClient
) {
    
    companion object {
        private val logger = LoggerFactory.getLogger(AuthService::class.java)
    }
    
    fun authenticate(clientId: String, clientSecret: String): AuthToken {
        logger.info("Authenticating with CrowdStrike API...")
        
        val body = "client_id=$clientId&client_secret=$clientSecret&grant_type=client_credentials"
        
        return try {
            val response: HttpResponse<AuthTokenResponse> = httpClient.toBlocking().exchange(
                HttpRequest.POST("/oauth2/token", body)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON),
                AuthTokenResponse::class.java
            )
            
            response.body()?.let { tokenResponse ->
                AuthToken(
                    accessToken = tokenResponse.accessToken,
                    expiresAt = Instant.now().plusSeconds(tokenResponse.expiresIn),
                    tokenType = tokenResponse.tokenType
                )
            } ?: throw AuthenticationException("Empty response from API")
            
        } catch (e: HttpClientResponseException) {
            when (e.status.code) {
                401 -> throw AuthenticationException("Invalid credentials")
                429 -> throw RateLimitException("Rate limit exceeded")
                else -> throw AuthenticationException("API error: ${e.message}")
            }
        }
    }
}

@Introspected
data class AuthTokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("token_type") val tokenType: String,
    @JsonProperty("expires_in") val expiresIn: Long
)
```

**Run tests again** - all should PASS.

---

## TDD Cycle for Each Component

### Test Order (per Constitution requirement):

1. **Contract Tests** (external API interactions)
   - `AuthServiceContractTest` ✅ (shown above)
   - `VulnerabilityServiceContractTest` (query API)

2. **Unit Tests** (business logic)
   - `ConfigLoaderTest` (file loading, permission validation)
   - `InputValidatorTest` (hostname validation)
   - `RetryHandlerTest` (exponential backoff logic)
   - `VulnerabilityTest` (data class validation)
   - `JsonExporterTest` (JSON structure)
   - `CsvExporterTest` (CSV formatting)

3. **Integration Tests** (end-to-end commands)
   - `QueryCommandTest` (full command execution)
   - `BulkQueryCommandTest` (multiple hosts)
   - `ExportCommandTest` (file creation)

---

## Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests AuthServiceContractTest

# Run with coverage
./gradlew test jacocoTestReport

# View coverage report
open build/reports/jacoco/test/html/index.html
```

---

## Building the CLI

```bash
# Build JAR
./gradlew build

# Run locally
./gradlew run --args="query --hostname=web-server-01"

# Create executable JAR
./gradlew shadowJar
java -jar build/libs/crowdstrike-cli-0.1.0-all.jar query --hostname=web-server-01
```

---

## Configuration for Development

Create `~/.secman/crowdstrike.conf` for testing:

```hocon
crowdstrike {
  client-id = "YOUR_DEV_CLIENT_ID"
  client-secret = "YOUR_DEV_CLIENT_SECRET"
  base-url = "https://api.crowdstrike.com"
  timeout = 30s
}
```

Set permissions:
```bash
chmod 600 ~/.secman/crowdstrike.conf
```

---

## Next Steps

1. **Review Specification**: Read `spec.md` for all requirements
2. **Review Research**: Read `research.md` for technology decisions
3. **Review Data Model**: Read `data-model.md` for entity definitions
4. **Review Contracts**: Read `contracts/*.md` for API specifications
5. **Start TDD Cycle**: Begin with `AuthServiceContractTest` (example shown above)
6. **Implement Features**: Follow Red-Green-Refactor for each component
7. **Run `/speckit.tasks`**: Generate detailed task breakdown after planning complete

---

## Useful Commands

```bash
# Format code
./gradlew ktlintFormat

# Check for outdated dependencies
./gradlew dependencyUpdates

# Run with debug logging
./gradlew run --args="query --hostname=test" -Dlogback.configurationFile=src/test/resources/logback-test.xml

# Package for distribution
./gradlew distZip
```

---

## Troubleshooting

### Issue: "Unsupported class file major version"
- **Solution**: Ensure Java 21 is active: `java -version`

### Issue: "Config file has insecure permissions"
- **Solution**: `chmod 600 ~/.secman/crowdstrike.conf`

### Issue: "Authentication failed"
- **Solution**: Verify credentials in config file, check network connectivity to api.crowdstrike.com

### Issue: Tests fail with "Connection refused"
- **Solution**: MockWebServer not started or wrong URL - check `@BeforeEach` setup

---

## References

- [Micronaut Documentation](https://docs.micronaut.io/latest/guide/)
- [Picocli Documentation](https://picocli.info/)
- [CrowdStrike API Docs](https://falcon.crowdstrike.com/documentation/)
- [Feature Specification](./spec.md)
- [Research Decisions](./research.md)
- [Data Model](./data-model.md)
