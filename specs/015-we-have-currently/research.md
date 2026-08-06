# Research: CrowdStrike System Vulnerability Lookup

**Date**: 2025-10-11
**Feature**: CrowdStrike System Vulnerability Lookup
**Branch**: 015-we-have-currently

## Research Questions & Decisions

### 1. CrowdStrike Falcon API Integration Method

**Decision**: Use Micronaut HTTP client to directly call CrowdStrike Falcon REST API

**Rationale**:
- The existing helper tool (`src/helper/`) uses Python's FalconPy library, but the backend is Kotlin/Micronaut
- FalconPy is Python-specific and cannot be directly used in Kotlin backend
- Micronaut provides a declarative HTTP client that can easily integrate with third-party REST APIs
- CrowdStrike Falcon API has well-documented REST endpoints that can be called directly
- This approach maintains consistency with the backend technology stack

**Alternatives Considered**:
1. **FalconPy via Python microservice**: Create a Python service wrapper around FalconPy and call it from Kotlin
   - Rejected: Adds unnecessary complexity, additional deployment container, inter-service communication overhead
2. **Kotlin CrowdStrike SDK**: Use a Kotlin-native CrowdStrike SDK if available
   - Rejected: No official Kotlin SDK exists; would require third-party library with unknown maintenance status
3. **Reuse helper tool**: Call the existing Python helper tool as a subprocess
   - Rejected: Brittle integration, difficult error handling, performance overhead

### 2. CrowdStrike API Endpoints

**Decision**: Use CrowdStrike Spotlight Vulnerabilities API

**Key Endpoints**:
- **Authentication**: `POST /oauth2/token` - Get OAuth2 bearer token using client credentials
- **Query Vulnerabilities**: `GET /spotlight/combined/vulnerabilities/v2` - Get vulnerabilities by hostname
  - Query parameters: `filter` (hostname, status, date range), `limit`, `offset`
  - Filter syntax: `hostname:'system-name'+status:'open'+created_timestamp:>='2025-09-01T00:00:00Z'`

**Rationale**:
- CrowdStrike Spotlight is the vulnerability management module of Falcon
- Combined API returns all data in single request (no need for separate detail lookups)
- Supports filtering by hostname, status (OPEN), and date range (last 40 days)
- Returns structured JSON with CVE IDs, severity, affected products, detection dates

**Reference**: [CrowdStrike Falcon API Documentation](https://falconpy.io/Service-Collections/Spotlight-Vulnerabilities.html)

### 3. Authentication & Credentials Management

**Decision**: Use OAuth2 client credentials flow with credentials from environment variables

**Implementation**:
- Store `CROWDSTRIKE_CLIENT_ID`, `CROWDSTRIKE_CLIENT_SECRET`, `CROWDSTRIKE_CLOUD_REGION` in `.env`
- Inject credentials via Micronaut's `@Property` annotations
- Implement token caching with expiration check (tokens valid 30 minutes)
- Refresh token automatically when expired

**Rationale**:
- CrowdStrike requires OAuth2 client credentials flow for API access
- Environment variables align with Docker-First principle (no hardcoded secrets)
- Token caching reduces API calls and improves performance
- Micronaut's property injection provides type-safe configuration access

**Security Considerations**:
- Credentials never logged or exposed in error messages
- Token refresh failures trigger clear error messages without exposing credentials
- API calls use HTTPS only

### 4. Error Handling Strategy

**Decision**: Implement three-tier error handling with user-friendly messages

**Tiers**:
1. **API Errors** (4xx, 5xx from CrowdStrike)
   - 401 Unauthorized → "CrowdStrike authentication failed. Please contact administrator."
   - 403 Forbidden → "Insufficient permissions to access CrowdStrike API."
   - 404 Not Found → "System '{hostname}' not found in CrowdStrike."
   - 429 Rate Limited → "CrowdStrike API rate limit exceeded. Please try again in {seconds} seconds."
   - 5xx Server Error → "CrowdStrike service temporarily unavailable. Please try again later."

2. **Network Errors** (timeouts, connection failures)
   - Connection timeout → "Unable to reach CrowdStrike API. Please check network connectivity."
   - Read timeout → "CrowdStrike API request timed out. Please try again."

3. **Business Logic Errors** (validation, data issues)
   - Invalid system name → "System name contains invalid characters."
   - Empty results → "No vulnerabilities found for system '{hostname}' in the last 40 days."

**Rationale**:
- User-friendly messages improve user experience and reduce support burden
- Error categorization enables appropriate handling (retry for 429, alert for 401)
- No sensitive information leakage (FR-014 compliance)

**Implementation Pattern**:
```kotlin
sealed class CrowdStrikeError {
    data class AuthenticationError(val message: String) : CrowdStrikeError()
    data class NotFoundError(val hostname: String) : CrowdStrikeError()
    data class RateLimitError(val retryAfterSeconds: Int) : CrowdStrikeError()
    data class NetworkError(val message: String) : CrowdStrikeError()
    data class ServerError(val message: String) : CrowdStrikeError()
}
```

### 5. Rate Limiting & Retry Strategy

**Decision**: Implement exponential backoff for rate limit errors, fail fast for other errors

**Strategy**:
- **429 Rate Limited**: Retry with exponential backoff (1s, 2s, 4s) up to 3 attempts
  - Use `Retry-After` header if provided by CrowdStrike
  - Return user-friendly error after 3 failures
- **5xx Server Errors**: Retry once after 2 seconds, then fail
- **4xx Client Errors** (except 429): Fail immediately (no retry)
- **Network Timeouts**: Retry once after 1 second, then fail

**Rationale**:
- Rate limits are transient and likely to resolve quickly (exponential backoff appropriate)
- Server errors may be transient but shouldn't retry indefinitely (single retry balances UX and performance)
- Client errors indicate bad requests that won't succeed on retry (fail fast)
- Prevents cascading failures and excessive API calls

**Implementation**:
- Use Micronaut's `@Retryable` annotation with custom retry policy
- Log retry attempts for observability

### 6. Data Mapping Strategy

**Decision**: Map CrowdStrike API response to existing Vulnerability entity structure

**Mapping**:
| CrowdStrike Field | Vulnerability Entity Field | Transformation |
|-------------------|---------------------------|----------------|
| `cve.id` | `vulnerabilityId` | Direct (e.g., "CVE-2021-44228") |
| `score` → severity | `cvssSeverity` | Map numeric score to text: 9.0-10.0="Critical", 7.0-8.9="High", 4.0-6.9="Medium", 0.1-3.9="Low" |
| `apps[].product_name_version` | `vulnerableProductVersions` | Join array with ", " (e.g., "OpenSSL 1.1.1k, Apache 2.4.48") |
| `created_timestamp` | `scanTimestamp` | Parse ISO 8601 to LocalDateTime |
| `status` | N/A (filter only) | Filter for "open" status, not stored |
| `hostname` | `asset.name` | Match existing Asset by hostname (case-insensitive) or create new |
| `local_ip` | `asset.ip` | Use for asset matching and creation |

**Days Open Calculation**:
- Compute from `created_timestamp` to current date
- Format as "{n} days" (e.g., "15 days", "2 days")
- Store as string in `daysOpen` field

**Asset Matching/Creation**:
1. Search for existing Asset by hostname (case-insensitive)
2. If not found, search by IP address
3. If still not found, create new Asset:
   - `name` = hostname from CrowdStrike
   - `ip` = local_ip from CrowdStrike
   - `type` = "Endpoint"
   - `owner` = "CrowdStrike"
   - `description` = ""

**Rationale**:
- Reuses existing entity structure (no schema changes)
- Maintains consistency with imported vulnerabilities
- Asset matching prevents duplicates
- CVSS severity mapping aligns with industry standards

### 7. Performance Optimization

**Decision**: Implement client-side filtering/sorting, server-side pagination for large result sets

**Strategy**:
- **Initial Query**: Fetch all vulnerabilities for hostname (up to 1000 results)
- **Filtering/Sorting**: Perform in React component (client-side) for responsive UX
- **Pagination**: If CrowdStrike returns > 1000 results, warn user to refine search
  - Display: "System has {total} vulnerabilities. Showing first 1000. Please refine your search or filter results."

**Rationale**:
- Most systems will have < 1000 vulnerabilities in 40-day window
- Client-side filtering provides instant response (no network latency)
- 1000 result limit prevents memory issues and excessive API calls
- Users can refine search by saving subset and querying again if needed

**Future Enhancement** (out of scope for v1):
- Server-side pagination with offset/limit parameters
- Incremental loading for systems with > 1000 vulnerabilities

### 8. Testing Strategy

**Decision**: Four-layer testing approach with mocked CrowdStrike API

**Test Layers**:

1. **Contract Tests** (TDD - write first):
   - Endpoint: `GET /api/crowdstrike/vulnerabilities?hostname={name}`
   - Endpoint: `POST /api/crowdstrike/vulnerabilities/save` (body: { hostname, vulnerabilities[] })
   - Verify request/response schemas
   - Verify authentication required (401 without JWT)
   - Verify authorization (ADMIN/VULN roles only)

2. **Integration Tests** (TDD - write first):
   - Mock CrowdStrike API responses (WireMock or Micronaut Test HTTP server)
   - Test successful query flow: hostname → CrowdStrike → mapped DTOs
   - Test error scenarios: 401, 404, 429, 500, network timeout
   - Test retry logic for rate limits
   - Test token refresh flow

3. **Unit Tests** (TDD - write first):
   - Data mapping functions (CrowdStrike response → Vulnerability DTOs)
   - Asset matching logic (hostname → existing Asset or create new)
   - CVSS score to severity text conversion
   - Days open calculation

4. **E2E Tests** (Playwright - write first):
   - Search flow: enter hostname → click search → results displayed
   - Filter flow: apply severity filter → table updates
   - Sort flow: click column header → sort order changes
   - Save flow: click save → success message → verify in Vuln Overview
   - Error flow: invalid hostname → error message displayed

**Mock Data**:
- Create fixture files with sample CrowdStrike API responses
- Cover scenarios: 0 results, 5 results, 100 results, error responses
- Include various CVEs, severities, products for comprehensive testing

## Summary of Technical Decisions

| Decision Area | Choice | Key Benefit |
|---------------|--------|-------------|
| API Integration | Micronaut HTTP client with direct REST calls | No new dependencies, uses existing stack |
| Authentication | OAuth2 client credentials with token caching | Secure, performant, aligns with Docker-First |
| Error Handling | Three-tier strategy with user-friendly messages | Better UX, security compliance |
| Rate Limiting | Exponential backoff for 429, fail fast for others | Resilient, prevents cascading failures |
| Data Mapping | Map to existing Vulnerability entity | No schema changes, consistency with imports |
| Performance | Client-side filtering, 1000 result limit | Responsive UX, prevents memory issues |
| Testing | Four-layer TDD approach with mocked API | Comprehensive coverage, no external dependencies |

## Open Questions (None)

All research questions have been resolved. No NEEDS CLARIFICATION items remain. Ready to proceed to Phase 1 (Design & Contracts).
