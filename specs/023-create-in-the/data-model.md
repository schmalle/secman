# Data Model: CrowdStrike CLI - Vulnerability Query Tool

**Feature**: 023-create-in-the  
**Date**: October 16, 2025

## Overview

This CLI application uses a file-based configuration model (no database) and memory-resident domain models for API interactions. All data is transient except for configuration and export files.

---

## Domain Entities

### 1. Vulnerability

Represents a security vulnerability detected on a host.

**Properties**:

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `cveId` | String | Yes | CVE identifier (e.g., "CVE-2024-1234") | Regex: `^CVE-\d{4}-\d{4,}$` |
| `severity` | SeverityLevel | Yes | Vulnerability severity rating | Enum: CRITICAL, HIGH, MEDIUM, LOW |
| `affectedSoftware` | String | Yes | Software package/version affected | Non-empty string |
| `description` | String | Yes | Vulnerability description | Truncated to 100 chars for console display |
| `descriptionFull` | String | No | Full vulnerability description | Available in exports only |
| `cvssScore` | Double | No | CVSS base score (0.0-10.0) | Range: 0.0 ≤ score ≤ 10.0 |
| `publishedDate` | LocalDate | No | CVE publication date | ISO 8601 format |
| `remediation` | String | No | Remediation guidance | Available in exports only |

**State Transitions**: None (read-only data from API)

**Relationships**:
- Many Vulnerabilities belong to one Host (composition)

**Kotlin Representation**:
```kotlin
data class Vulnerability(
    val cveId: String,
    val severity: SeverityLevel,
    val affectedSoftware: String,
    val description: String,
    val descriptionFull: String? = null,
    val cvssScore: Double? = null,
    val publishedDate: LocalDate? = null,
    val remediation: String? = null
) {
    init {
        require(cveId.matches(Regex("^CVE-\\d{4}-\\d{4,}$"))) { "Invalid CVE ID format" }
        require(description.isNotBlank()) { "Description cannot be blank" }
        cvssScore?.let { 
            require(it in 0.0..10.0) { "CVSS score must be between 0.0 and 10.0" }
        }
    }
    
    fun toDisplayString(): String = description.take(100) + if (description.length > 100) "..." else ""
}

enum class SeverityLevel {
    CRITICAL, HIGH, MEDIUM, LOW
}
```

---

### 2. Host

Represents a system being monitored for vulnerabilities.

**Properties**:

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `hostname` | String | Yes | Fully qualified domain name or short hostname | Valid hostname format |
| `hostId` | String | No | CrowdStrike internal host identifier | UUID or CrowdStrike ID format |
| `operatingSystem` | String | No | OS name and version | Free-form string |
| `ipAddress` | String | No | Primary IP address | Valid IPv4/IPv6 format |
| `lastSeen` | Instant | No | Last time host was seen by CrowdStrike | ISO 8601 timestamp |
| `vulnerabilities` | List&lt;Vulnerability&gt; | Yes | List of vulnerabilities detected | Empty list if no vulnerabilities |

**State Transitions**: None (read-only data from API)

**Relationships**:
- One Host has many Vulnerabilities (composition)

**Kotlin Representation**:
```kotlin
data class Host(
    val hostname: String,
    val hostId: String? = null,
    val operatingSystem: String? = null,
    val ipAddress: String? = null,
    val lastSeen: Instant? = null,
    val vulnerabilities: List<Vulnerability> = emptyList()
) {
    init {
        require(hostname.isNotBlank()) { "Hostname cannot be blank" }
        require(hostname.matches(Regex("^[a-zA-Z0-9.-]+$"))) { 
            "Hostname must contain only letters, numbers, hyphens, and periods" 
        }
    }
    
    fun vulnerabilitiesBySeverity(): Map<SeverityLevel, Int> =
        vulnerabilities.groupingBy { it.severity }.eachCount()
}
```

---

### 3. QueryResult

Container for vulnerability query results with metadata.

**Properties**:

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `timestamp` | Instant | Yes | Query execution timestamp | ISO 8601 format |
| `queryParams` | QueryParameters | Yes | Original query parameters | Valid QueryParameters object |
| `results` | List&lt;Host&gt; | Yes | List of hosts with vulnerabilities | Can be empty |
| `summary` | QuerySummary | Yes | Aggregated statistics | Computed from results |
| `errors` | List&lt;QueryError&gt; | Yes | Errors encountered during query | Empty if all successful |

**State Transitions**: None (immutable after creation)

**Kotlin Representation**:
```kotlin
data class QueryResult(
    val timestamp: Instant,
    val queryParams: QueryParameters,
    val results: List<Host>,
    val summary: QuerySummary,
    val errors: List<QueryError> = emptyList()
) {
    companion object {
        fun fromHosts(
            params: QueryParameters,
            hosts: List<Host>,
            errors: List<QueryError> = emptyList()
        ): QueryResult {
            return QueryResult(
                timestamp = Instant.now(),
                queryParams = params,
                results = hosts,
                summary = QuerySummary.compute(hosts),
                errors = errors
            )
        }
    }
}
```

---

### 4. QueryParameters

Captures the parameters used for a vulnerability query.

**Properties**:

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `hosts` | List&lt;String&gt; | Yes | List of hostnames to query | At least one hostname |
| `severityFilter` | SeverityLevel | No | Filter by severity | Valid SeverityLevel enum |
| `includeRemediation` | Boolean | Yes | Include remediation guidance | Default: false |

**Kotlin Representation**:
```kotlin
data class QueryParameters(
    val hosts: List<String>,
    val severityFilter: SeverityLevel? = null,
    val includeRemediation: Boolean = false
) {
    init {
        require(hosts.isNotEmpty()) { "At least one host must be specified" }
        hosts.forEach { hostname ->
            require(hostname.matches(Regex("^[a-zA-Z0-9.-]+$"))) { 
                "Invalid hostname: $hostname" 
            }
        }
    }
}
```

---

### 5. QuerySummary

Aggregated statistics for query results.

**Properties**:

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `totalHosts` | Int | Yes | Number of hosts queried | ≥ 0 |
| `totalVulnerabilities` | Int | Yes | Total vulnerabilities found | ≥ 0 |
| `bySeverity` | Map&lt;SeverityLevel, Int&gt; | Yes | Vulnerability count per severity | All severities present with 0 or more |

**Kotlin Representation**:
```kotlin
data class QuerySummary(
    val totalHosts: Int,
    val totalVulnerabilities: Int,
    val bySeverity: Map<SeverityLevel, Int>
) {
    companion object {
        fun compute(hosts: List<Host>): QuerySummary {
            val allVulnerabilities = hosts.flatMap { it.vulnerabilities }
            val bySeverity = SeverityLevel.entries.associateWith { level ->
                allVulnerabilities.count { it.severity == level }
            }
            return QuerySummary(
                totalHosts = hosts.size,
                totalVulnerabilities = allVulnerabilities.size,
                bySeverity = bySeverity
            )
        }
    }
}
```

---

### 6. QueryError

Represents an error encountered during query execution.

**Properties**:

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `hostname` | String | Yes | Hostname that caused the error | Valid hostname |
| `errorType` | ErrorType | Yes | Type of error | Enum: AUTHENTICATION, NETWORK, NOT_FOUND, RATE_LIMIT, API_ERROR |
| `message` | String | Yes | User-friendly error message | Non-empty string |
| `retryable` | Boolean | Yes | Whether error is retryable | Based on errorType |

**Kotlin Representation**:
```kotlin
data class QueryError(
    val hostname: String,
    val errorType: ErrorType,
    val message: String,
    val retryable: Boolean
)

enum class ErrorType {
    AUTHENTICATION,  // 401, 403 - not retryable
    NETWORK,         // Connection refused, timeout - retryable
    NOT_FOUND,       // 404 - not retryable
    RATE_LIMIT,      // 429 - retryable
    API_ERROR        // 500, 502, 503 - retryable
}
```

---

### 7. CliConfig

Configuration loaded from ~/.secman/crowdstrike.conf.

**Properties**:

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `clientId` | String | Yes | CrowdStrike API client ID | Non-empty string |
| `clientSecret` | String | Yes | CrowdStrike API client secret | Non-empty string |
| `baseUrl` | String | Yes | CrowdStrike API base URL | Valid URL format |
| `timeout` | Duration | Yes | HTTP request timeout | Default: 30 seconds |

**State Transitions**: None (immutable after load)

**Security Constraints**:
- File must have permissions 600 or 400 (owner read-only)
- Values never logged or printed
- Stored in memory only during CLI execution

**Kotlin Representation**:
```kotlin
data class CliConfig(
    val clientId: String,
    val clientSecret: String,
    val baseUrl: String,
    val timeout: Duration = Duration.ofSeconds(30)
) {
    init {
        require(clientId.isNotBlank()) { "Client ID cannot be blank" }
        require(clientSecret.isNotBlank()) { "Client secret cannot be blank" }
        require(baseUrl.startsWith("https://")) { "Base URL must use HTTPS" }
    }
}
```

---

### 8. AuthToken

OAuth2 access token from CrowdStrike API.

**Properties**:

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `accessToken` | String | Yes | Bearer token for API requests | Non-empty string |
| `expiresAt` | Instant | Yes | Token expiration timestamp | Future timestamp |
| `tokenType` | String | Yes | Token type (usually "bearer") | Non-empty string |

**State Transitions**:
- VALID → EXPIRED (when current time > expiresAt)

**Kotlin Representation**:
```kotlin
data class AuthToken(
    val accessToken: String,
    val expiresAt: Instant,
    val tokenType: String = "bearer"
) {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
    
    fun isExpiringSoon(bufferSeconds: Long = 60): Boolean = 
        Instant.now().plusSeconds(bufferSeconds).isAfter(expiresAt)
}
```

---

## Data Flow

```text
1. Startup:
   ConfigLoader → reads ~/.secman/crowdstrike.conf → CliConfig

2. Authentication:
   CliConfig → AuthService → POST /oauth2/token → AuthToken

3. Query Single Host:
   hostname → VulnerabilityService → GET /vulnerabilities/combined → Host + List<Vulnerability>

4. Query Multiple Hosts:
   List<hostname> → VulnerabilityService (parallel) → List<Host> + List<QueryError>

5. Filter by Severity:
   QueryResult → filter vulnerabilities → QueryResult (filtered)

6. Export JSON:
   QueryResult → JsonExporter → writes to file → confirmation message

7. Export CSV:
   QueryResult → CsvExporter → writes to file → confirmation message
```

---

## Validation Rules

### Hostname Validation
- Must match regex: `^[a-zA-Z0-9.-]+$`
- Length: 1-253 characters
- No leading/trailing hyphens or periods

### CVE ID Validation
- Must match regex: `^CVE-\d{4}-\d{4,}$`
- Example: CVE-2024-1234

### File Path Validation
- Must be absolute or relative path
- Parent directory must exist or be creatable
- Must have write permissions
- If file exists, prompt for overwrite confirmation

### Config File Permissions
- POSIX permissions: 600 (rw-------) or 400 (r--------)
- Owner: current user
- If permissions too open: refuse to load, print warning

---

## Error Handling Strategy

| Scenario | Error Type | User Message | Exit Code | Retryable |
|----------|-----------|--------------|-----------|-----------|
| Invalid hostname format | Validation | "Invalid hostname: {hostname}" | 1 | No |
| Config file missing | File I/O | "Config file not found: ~/.secman/crowdstrike.conf" | 1 | No |
| Config file too permissive | Security | "Config file has insecure permissions. Run: chmod 600 ~/.secman/crowdstrike.conf" | 1 | No |
| Authentication failed | API - 401 | "Failed to authenticate. Verify credentials in config." | 1 | No |
| Host not found | API - 404 | "Host not found: {hostname}" | 1 | No |
| Rate limit exceeded | API - 429 | "Rate limit exceeded. Retrying in {delay}s..." | 0 (after retry) | Yes |
| Network timeout | Network | "Request timed out. Check network connection." | 1 | Yes |
| API error | API - 500 | "CrowdStrike API error. Try again later." | 1 | Yes |
| Export file exists | File I/O | "File exists: {path}. Overwrite? (y/n)" | 0 (after prompt) | No |
| Cannot write file | File I/O | "Cannot write to {path}. Check permissions." | 1 | No |

---

## Performance Considerations

- **Parallel Queries**: For bulk queries, use Kotlin coroutines to query multiple hosts in parallel (max 10 concurrent)
- **Streaming Exports**: Don't load entire result set into memory; stream JSON/CSV directly to disk
- **Token Caching**: Cache AuthToken in memory to avoid re-authentication for each query
- **Lazy Loading**: Only load full vulnerability descriptions when exporting, not for console display

---

## References

- CrowdStrike API Response Schemas: [API Documentation](https://falcon.crowdstrike.com/documentation/)
- Kotlin Data Class Validation: `init` blocks for invariant enforcement
