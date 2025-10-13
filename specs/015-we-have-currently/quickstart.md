# Quickstart Guide: CrowdStrike System Vulnerability Lookup

**Feature**: 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
**Date**: 2025-10-11

## Overview

This feature enables real-time vulnerability lookups from CrowdStrike Falcon API with optional persistence to the local database. Security analysts can search for a system by hostname, view vulnerabilities from the last 40 days (OPEN status), and save results for historical tracking.

## For End Users

### Prerequisites

- User account with ADMIN or VULN role
- System hostname as it appears in CrowdStrike Falcon

### How to Use

1. **Navigate to CrowdStrike Lookup Page**
   - Go to: `Vuln Management` → `CrowdStrike Lookup` (or `/crowdstrike-lookup`)

2. **Search for Vulnerabilities**
   - Enter system hostname (e.g., `web-server-01`, `app-db-prod`)
   - Click **Search** button
   - Wait 2-10 seconds for results from CrowdStrike API

3. **View Results**
   - Table displays: System, IP, CVE, Severity, Product, Days Open, Scan Date, Exception Status
   - Results include only OPEN vulnerabilities from last 40 days
   - Severity badges: Critical (red), High (orange), Medium (blue), Low (green)
   - Exception badges: Excepted (green) or Not Excepted (red)

4. **Filter and Sort** (optional)
   - **Filter by Severity**: Dropdown to select Critical/High/Medium/Low
   - **Filter by Exception Status**: Dropdown to select Excepted/Not Excepted
   - **Filter by Product**: Text field to search product names
   - **Sort**: Click any column header to sort (click again to reverse)

5. **Save to Database** (optional)
   - Click **Save to Database** button above the table
   - Vulnerabilities saved to local database with current timestamp
   - Success message confirms number of vulnerabilities saved
   - Navigate to `Vuln Management` → `Vuln Overview` to see saved vulnerabilities

6. **Refresh Results** (optional)
   - Click **Refresh** button to re-query CrowdStrike API
   - Useful to check if vulnerabilities have changed

### Troubleshooting

| Error Message | Meaning | Solution |
|---------------|---------|----------|
| "System '{name}' not found in CrowdStrike" | Hostname doesn't exist in CrowdStrike | Verify hostname spelling, check CrowdStrike console |
| "CrowdStrike API rate limit exceeded" | Too many requests | Wait 30-60 seconds and try again |
| "CrowdStrike authentication failed" | API credentials invalid | Contact administrator |
| "Insufficient permissions" | User lacks ADMIN/VULN role | Request role from administrator |
| "Unable to reach CrowdStrike API" | Network or service issue | Check network, try again later |

## For Developers

### Prerequisites

- Kotlin 2.1.0 / Java 21
- Micronaut 4.4
- Docker Compose (for local dev environment)
- CrowdStrike API credentials (Client ID, Client Secret, Cloud Region)

### Environment Setup

1. **Configure CrowdStrike Credentials**

   Add to `.env` file (create if doesn't exist):
   ```bash
   CROWDSTRIKE_CLIENT_ID=your_client_id_here
   CROWDSTRIKE_CLIENT_SECRET=your_client_secret_here
   CROWDSTRIKE_CLOUD_REGION=us-1  # or us-2, eu-1, etc.
   ```

2. **Start Development Environment**
   ```bash
   docker-compose up -d
   ```

3. **Verify Backend Running**
   ```bash
   curl http://localhost:8080/health
   # Expected: {"status":"UP"}
   ```

4. **Verify Frontend Running**
   ```bash
   curl http://localhost:4321
   # Expected: HTML response
   ```

### API Endpoints

#### Query Vulnerabilities

**Request**:
```bash
curl -X GET "http://localhost:8080/api/crowdstrike/vulnerabilities?hostname=web-server-01" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

**Response** (200 OK):
```json
{
  "hostname": "web-server-01",
  "vulnerabilities": [
    {
      "id": "cs-vuln-12345",
      "hostname": "web-server-01",
      "ip": "192.168.1.100",
      "cveId": "CVE-2021-44228",
      "severity": "Critical",
      "cvssScore": 10.0,
      "affectedProduct": "Apache Log4j 2.14.1",
      "daysOpen": "15 days",
      "detectedAt": "2025-09-26T10:30:00Z",
      "status": "open",
      "hasException": false,
      "exceptionReason": null
    }
  ],
  "totalCount": 1,
  "queriedAt": "2025-10-11T12:00:00Z"
}
```

#### Save Vulnerabilities

**Request**:
```bash
curl -X POST "http://localhost:8080/api/crowdstrike/vulnerabilities/save" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "hostname": "web-server-01",
    "vulnerabilities": [
      {
        "id": "cs-vuln-12345",
        "hostname": "web-server-01",
        "ip": "192.168.1.100",
        "cveId": "CVE-2021-44228",
        "severity": "Critical",
        "cvssScore": 10.0,
        "affectedProduct": "Apache Log4j 2.14.1",
        "daysOpen": "15 days",
        "detectedAt": "2025-09-26T10:30:00Z",
        "status": "open",
        "hasException": false,
        "exceptionReason": null
      }
    ]
  }'
```

**Response** (200 OK):
```json
{
  "message": "Saved 1 vulnerability for system 'web-server-01'",
  "vulnerabilitiesSaved": 1,
  "assetsCreated": 0,
  "errors": []
}
```

### Development Workflow (TDD)

This project follows **strict Test-Driven Development**. Tests MUST be written before implementation.

#### 1. Write Contract Tests First

```bash
# Create test file
touch src/backendng/src/test/kotlin/com/secman/contract/CrowdStrikeContractTest.kt
```

```kotlin
@MicronautTest
class CrowdStrikeContractTest {

    @Inject
    lateinit var client: HttpClient

    @Test
    fun `GET query endpoint returns 401 without authentication`() {
        // Test endpoint contract without JWT
        val request = HttpRequest.GET<Any>("/api/crowdstrike/vulnerabilities?hostname=test")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `POST save endpoint requires valid request body`() {
        // Test request validation
        // ... implement contract test
    }
}
```

Run tests (should FAIL):
```bash
./gradlew test --tests CrowdStrikeContractTest
```

#### 2. Write Integration Tests

```bash
touch src/backendng/src/test/kotlin/com/secman/integration/CrowdStrikeIntegrationTest.kt
```

```kotlin
@MicronautTest
class CrowdStrikeIntegrationTest {

    @Inject
    lateinit var crowdStrikeService: CrowdStrikeVulnerabilityService

    @MockBean(CrowdStrikeVulnerabilityService::class)
    fun crowdStrikeService(): CrowdStrikeVulnerabilityService {
        return mockk()
    }

    @Test
    fun `queryByHostname returns mapped vulnerabilities`() {
        // Mock CrowdStrike API response
        val mockResponse = listOf(/* mock CrowdStrike vulnerabilities */)
        every { crowdStrikeService.queryByHostname(any()) } returns mockResponse

        val result = crowdStrikeService.queryByHostname("web-server-01")

        // Assertions
        assertNotNull(result)
        assertEquals(1, result.size)
    }
}
```

Run tests (should FAIL):
```bash
./gradlew test --tests CrowdStrikeIntegrationTest
```

#### 3. Write Unit Tests

```bash
touch src/backendng/src/test/kotlin/com/secman/unit/CrowdStrikeServiceTest.kt
```

```kotlin
class CrowdStrikeServiceTest {

    @Test
    fun `mapCvssScoreToSeverity converts scores correctly`() {
        assertEquals("Critical", mapCvssScore(10.0))
        assertEquals("Critical", mapCvssScore(9.0))
        assertEquals("High", mapCvssScore(8.9))
        assertEquals("High", mapCvssScore(7.0))
        assertEquals("Medium", mapCvssScore(6.9))
        assertEquals("Low", mapCvssScore(3.9))
    }

    @Test
    fun `calculateDaysOpen computes correct duration`() {
        val detectedAt = LocalDateTime.now().minusDays(15)
        assertEquals("15 days", calculateDaysOpen(detectedAt))
    }
}
```

Run tests (should FAIL):
```bash
./gradlew test --tests CrowdStrikeServiceTest
```

#### 4. Implement to Pass Tests (Green)

Now implement the actual code:

```kotlin
// src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt
@Controller("/api/crowdstrike")
@Secured("ADMIN", "VULN")
class CrowdStrikeController(
    private val crowdStrikeService: CrowdStrikeVulnerabilityService
) {

    @Get("/vulnerabilities")
    fun queryVulnerabilities(@QueryValue hostname: String): CrowdStrikeQueryResponse {
        return crowdStrikeService.queryByHostname(hostname)
    }

    @Post("/vulnerabilities/save")
    fun saveVulnerabilities(@Body request: CrowdStrikeSaveRequest): CrowdStrikeSaveResponse {
        return crowdStrikeService.saveToDatabase(request.hostname, request.vulnerabilities)
    }
}
```

```kotlin
// src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
@Singleton
class CrowdStrikeVulnerabilityService(
    @Client("\${crowdstrike.api.url}") private val crowdStrikeClient: HttpClient,
    @Property(name = "crowdstrike.client.id") private val clientId: String,
    @Property(name = "crowdstrike.client.secret") private val clientSecret: String,
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository
) {

    fun queryByHostname(hostname: String): CrowdStrikeQueryResponse {
        // 1. Authenticate with CrowdStrike
        val token = authenticateWithCrowdStrike()

        // 2. Query Spotlight API
        val vulnerabilities = queryCrowdStrikeApi(hostname, token)

        // 3. Map to DTOs
        return CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = vulnerabilities.map { mapToDto(it) },
            totalCount = vulnerabilities.size,
            queriedAt = LocalDateTime.now()
        )
    }

    fun saveToDatabase(hostname: String, vulnerabilities: List<CrowdStrikeVulnerabilityDto>): CrowdStrikeSaveResponse {
        // Implementation...
    }
}
```

Run tests (should PASS):
```bash
./gradlew test
```

#### 5. Write Frontend E2E Tests

```bash
touch src/frontend/tests/e2e/crowdstrike-lookup.spec.ts
```

```typescript
import { test, expect } from '@playwright/test';

test.describe('CrowdStrike Vulnerability Lookup', () => {

  test('should display search form', async ({ page }) => {
    await page.goto('/crowdstrike-lookup');

    await expect(page.locator('input[name="hostname"]')).toBeVisible();
    await expect(page.locator('button:has-text("Search")')).toBeVisible();
  });

  test('should query and display results', async ({ page }) => {
    // Mock API response
    await page.route('**/api/crowdstrike/vulnerabilities*', async route => {
      await route.fulfill({
        status: 200,
        body: JSON.stringify({
          hostname: 'web-server-01',
          vulnerabilities: [/* mock data */],
          totalCount: 1,
          queriedAt: new Date().toISOString()
        })
      });
    });

    await page.goto('/crowdstrike-lookup');
    await page.fill('input[name="hostname"]', 'web-server-01');
    await page.click('button:has-text("Search")');

    await expect(page.locator('table tbody tr')).toHaveCount(1);
  });

  test('should filter results by severity', async ({ page }) => {
    // Test implementation...
  });

  test('should save vulnerabilities to database', async ({ page }) => {
    // Test implementation...
  });
});
```

Run E2E tests (should FAIL initially):
```bash
npm run test:e2e
```

#### 6. Implement Frontend

Implement React component and Astro page to pass E2E tests.

### Running Tests

```bash
# Backend tests (contract + integration + unit)
./gradlew test

# Frontend E2E tests
npm run test:e2e

# All tests
./gradlew test && npm run test:e2e
```

### Building and Deployment

```bash
# Build backend
./gradlew build

# Build frontend
npm run build

# Build Docker images
docker-compose build

# Deploy
docker-compose up -d
```

## Architecture Overview

### Backend Flow

```
User → Frontend → CrowdStrikeController
                        ↓
                  CrowdStrikeVulnerabilityService
                        ↓
                  1. Authenticate (OAuth2)
                  2. Query Spotlight API
                  3. Map to DTOs
                  4. Check exceptions
                        ↓
                  Return CrowdStrikeQueryResponse
```

### Save Flow

```
User clicks Save → Frontend → CrowdStrikeController.saveVulnerabilities()
                                    ↓
                              CrowdStrikeVulnerabilityService.saveToDatabase()
                                    ↓
                              For each vulnerability:
                                1. Find/create Asset
                                2. Create Vulnerability entity
                                3. Save to database
                                    ↓
                              Return CrowdStrikeSaveResponse
```

## References

- **Spec**: [spec.md](./spec.md)
- **Research**: [research.md](./research.md)
- **Data Model**: [data-model.md](./data-model.md)
- **API Contracts**:
  - [Query Endpoint](./contracts/crowdstrike-query.openapi.yaml)
  - [Save Endpoint](./contracts/crowdstrike-save.openapi.yaml)
- **CrowdStrike API Docs**: https://falconpy.io/Service-Collections/Spotlight-Vulnerabilities.html

## Support

- For CrowdStrike API issues: Contact CrowdStrike support or check API status
- For feature bugs: Create GitHub issue with label `feature/015-we-have-currently`
- For questions: Slack channel #secman-dev
