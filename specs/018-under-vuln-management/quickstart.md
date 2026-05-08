# Quickstart: Account Vulns Development

**Feature**: Account Vulns - AWS Account-Based Vulnerability Overview
**Branch**: `018-under-vuln-management`
**Date**: 2025-10-14

## Prerequisites

- Repository cloned: `git clone https://github.com/schmalle/secman.git`
- Branch checked out: `git checkout 018-under-vuln-management`
- Docker installed (for database)
- Java 21 installed
- Node.js 18+ installed
- Development environment configured (see root README.md)

## Quick Setup (5 Minutes)

### 1. Start Database

```bash
# From repository root
docker-compose up -d mariadb
```

**Verify**: MariaDB running on `localhost:3306`

### 2. Start Backend

```bash
cd src/backendng
./gradlew run
```

**Verify**: Backend running on `http://localhost:8080`
**Test**: `curl http://localhost:8080/health` → `{"status": "UP"}`

### 3. Start Frontend

```bash
cd src/frontend
npm install  # First time only
npm run dev
```

**Verify**: Frontend running on `http://localhost:4321`
**Test**: Open `http://localhost:4321` in browser → See login page

### 4. Create Test Data

```bash
# From repository root
./scripts/tests/populate-testdata.sh
```

**Creates**:
- Test user: `test@example.com` / password: `test123`
- Test admin: `admin@example.com` / password: `admin123`
- User mappings: `test@example.com` → AWS accounts `123456789012`, `987654321098`
- Assets with cloudAccountId set to test accounts
- Vulnerabilities linked to assets

## Development Workflow

### Backend Development (TDD)

#### 1. Write Contract Test (FIRST)

```bash
cd src/backendng
touch src/test/kotlin/com/secman/contract/AccountVulnsContractTest.kt
```

**Template**:
```kotlin
@MicronautTest
class AccountVulnsContractTest {
    @Inject
    lateinit var client: HttpClient

    @Test
    fun `GET account-vulns returns 200 for non-admin user with mappings`() {
        // Arrange: Create test user + mappings + assets
        // Act: GET /api/account-vulns with JWT
        // Assert: Status 200, account groups present, sorted correctly
    }

    @Test
    fun `GET account-vulns returns 403 for admin user`() {
        // Arrange: Create admin user
        // Act: GET /api/account-vulns with admin JWT
        // Assert: Status 403, redirect message present
    }

    // ... more tests (see data-model.md for full list)
}
```

#### 2. Run Test (RED)

```bash
./gradlew test --tests AccountVulnsContractTest
```

**Expected**: Test fails (endpoint doesn't exist yet)

#### 3. Implement Minimal Code (GREEN)

Create files in order:
1. `src/main/kotlin/com/secman/dto/AssetVulnCountDto.kt`
2. `src/main/kotlin/com/secman/dto/AccountGroupDto.kt`
3. `src/main/kotlin/com/secman/dto/AccountVulnsSummaryDto.kt`
4. `src/main/kotlin/com/secman/service/AccountVulnsService.kt`
5. `src/main/kotlin/com/secman/controller/AccountVulnsController.kt`

#### 4. Run Test (GREEN)

```bash
./gradlew test --tests AccountVulnsContractTest
```

**Expected**: Test passes

#### 5. Refactor + Add Unit Tests

```bash
touch src/test/kotlin/com/secman/service/AccountVulnsServiceTest.kt
```

**Run all tests**:
```bash
./gradlew test
```

### Frontend Development

#### 1. Create API Service

```bash
cd src/frontend
touch src/services/accountVulnsService.ts
```

**Template**:
```typescript
import api from './api';  // Existing axios instance with JWT interceptor

export interface AccountVulnsSummary {
  accountGroups: AccountGroup[];
  totalAssets: number;
  totalVulnerabilities: number;
}

export interface AccountGroup {
  awsAccountId: string;
  assets: AssetVulnCount[];
  totalAssets: number;
  totalVulnerabilities: number;
}

export interface AssetVulnCount {
  id: number;
  name: string;
  type: string;
  vulnerabilityCount: number;
}

export const accountVulnsService = {
  async getAccountVulns(): Promise<AccountVulnsSummary> {
    const response = await api.get('/api/account-vulns');
    return response.data;
  }
};
```

#### 2. Create React Components

```bash
touch src/components/AccountVulnsView.tsx
touch src/components/AccountVulnGroup.tsx
touch src/components/AssetVulnTable.tsx
```

**Development Server**: Components hot-reload on save

#### 3. Create Astro Page

```bash
touch src/pages/account-vulns.astro
```

**Template**:
```astro
---
import MainLayout from '../layouts/MainLayout.astro';
import AccountVulnsView from '../components/AccountVulnsView';
---

<MainLayout title="Account Vulns">
  <AccountVulnsView client:load />
</MainLayout>
```

#### 4. Update Navigation

Edit `src/layouts/MainLayout.astro` to add menu item:

```astro
<!-- In navigation menu -->
<li class="nav-item">
  <a
    href="/account-vulns"
    class={`nav-link ${isAdmin ? 'disabled text-muted' : ''}`}
    title={isAdmin ? 'Admins should use System Vulns view' : ''}
  >
    Account Vulns
  </a>
</li>
```

#### 5. Write E2E Tests

```bash
touch tests/e2e/account-vulns.spec.ts
```

**Run tests**:
```bash
npm run test:e2e
```

## Manual Testing

### Test Scenario 1: Non-Admin User with Single Account

1. Login as `test@example.com` / `test123`
2. Navigate to "Vuln Management → Account Vulns"
3. **Verify**:
   - Page loads in <3 seconds
   - Single account group visible (AWS Account: 123456789012)
   - Assets listed with vulnerability counts
   - Assets sorted by vuln count (highest first)
   - Clicking asset name navigates to asset detail

### Test Scenario 2: Non-Admin User with Multiple Accounts

1. Add second AWS account mapping to test user (via admin UI or SQL):
   ```sql
   INSERT INTO user_mapping (email, aws_account_id) VALUES ('test@example.com', '987654321098');
   ```
2. Add assets with `cloudAccountId = '987654321098'`
3. Refresh Account Vulns page
4. **Verify**:
   - Two account groups visible
   - Groups sorted by account ID (123... before 987...)
   - Each group shows correct assets
   - Group summaries show correct totals

### Test Scenario 3: No AWS Account Mappings

1. Create new user without AWS account mappings
2. Login as new user
3. Navigate to Account Vulns
4. **Verify**:
   - Error message displayed: "No AWS accounts are mapped..."
   - Guidance text present
   - No page crash

### Test Scenario 4: Admin User Redirect

1. Login as `admin@example.com` / `admin123`
2. **Verify**:
   - "Account Vulns" menu item has disabled/grayed styling
   - Tooltip on hover: "Admins should use System Vulns view"
3. Click "Account Vulns" menu item
4. **Verify**:
   - Page shows redirect message: "Please use System Vulns view"
   - Link to System Vulns present and functional
   - No account data displayed

### Test Scenario 5: Per-Account Pagination

1. Login as test user
2. Add 25 assets to one AWS account (cloudAccountId = 123456789012)
3. Navigate to Account Vulns
4. **Verify**:
   - First 20 assets visible for that account
   - "Load More" button present
   - Click "Load More" → Next 5 assets appear
   - Button disappears (all 25 loaded)

## Debugging Tips

### Backend Debugging

**View SQL queries**:
```yaml
# src/backendng/src/main/resources/application.yml
jpa:
  show-sql: true  # Logs all SQL queries to console
```

**Enable debug logging**:
```yaml
logger:
  levels:
    com.secman: DEBUG
```

**Breakpoint locations**:
- `AccountVulnsController.getAccountVulns()` - Entry point
- `AccountVulnsService.getAccountVulnsSummary()` - Business logic
- `UserMappingRepository.findByEmail()` - AWS account lookup

### Frontend Debugging

**View API calls**:
- Open browser DevTools → Network tab
- Filter by "XHR"
- Look for `/api/account-vulns` request
- Check response status, headers, body

**React DevTools**:
- Install React DevTools browser extension
- Inspect `AccountVulnsView` component state
- Check pagination state per account

**Console logging**:
```typescript
// In AccountVulnsView.tsx
useEffect(() => {
  console.log('Fetching account vulns...');
  accountVulnsService.getAccountVulns()
    .then(data => console.log('Received:', data))
    .catch(err => console.error('Error:', err));
}, []);
```

### Database Debugging

**View user mappings**:
```sql
SELECT * FROM user_mapping WHERE email = 'test@example.com';
```

**View assets by account**:
```sql
SELECT id, name, type, cloud_account_id
FROM assets
WHERE cloud_account_id = '123456789012';
```

**Count vulnerabilities per asset**:
```sql
SELECT a.id, a.name, COUNT(v.id) as vuln_count
FROM assets a
LEFT JOIN vulnerabilities v ON v.asset_id = a.id
WHERE a.cloud_account_id = '123456789012'
GROUP BY a.id, a.name;
```

## Common Issues

### Issue: 401 Unauthorized

**Symptom**: API call returns 401, user appears logged in
**Cause**: JWT token expired or invalid
**Fix**:
1. Clear sessionStorage: `sessionStorage.clear()`
2. Logout and login again
3. Check JWT expiration time in backend config

### Issue: Empty Account Groups

**Symptom**: Page loads but no assets displayed
**Cause**: Assets have null cloudAccountId or don't match user's AWS accounts
**Fix**:
1. Verify user has AWS account mappings: `SELECT * FROM user_mapping WHERE email = 'test@example.com'`
2. Verify assets have cloudAccountId set: `SELECT * FROM assets WHERE cloud_account_id IS NOT NULL`
3. Ensure cloudAccountId matches user's AWS account IDs

### Issue: Incorrect Sorting

**Symptom**: Assets not sorted by vulnerability count
**Cause**: Sorting logic error in backend or frontend
**Debug**:
1. Check backend response in Network tab - is data sorted correctly in JSON?
2. If backend sorted: Frontend re-sorting incorrectly (check AccountVulnGroup.tsx)
3. If backend unsorted: Service layer not sorting (check AccountVulnsService.kt)

### Issue: Pagination Not Working

**Symptom**: "Load More" button doesn't show more assets
**Cause**: Frontend pagination state not updating
**Debug**:
1. Open React DevTools
2. Check AccountVulnsView state: `paginationState[accountId].currentPage`
3. Verify page increments on button click
4. Check slice calculation: `(currentPage + 1) * 20`

## Performance Testing

### Load Testing Script

```bash
# Install Apache Bench
brew install apache-bench  # macOS
apt-get install apache2-utils  # Linux

# Test endpoint with 100 concurrent requests
ab -n 1000 -c 100 -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/account-vulns
```

**Target**: p95 latency <200ms, p99 <500ms

### Database Performance

```sql
-- Verify index usage
EXPLAIN SELECT a.id, COUNT(v.id)
FROM assets a
LEFT JOIN vulnerabilities v ON v.asset_id = a.id
WHERE a.cloud_account_id IN ('123456789012', '987654321098')
GROUP BY a.id;
```

**Look for**: `Using index` in Extra column (good), `Using filesort` (bad - needs index)

## Next Steps

After completing development:
1. Run full test suite: `./gradlew test && npm run test:e2e`
2. Verify constitution compliance (see plan.md)
3. Create pull request
4. Request code review
5. Merge after approval

## Reference Documents

- **Specification**: [spec.md](./spec.md)
- **Implementation Plan**: [plan.md](./plan.md)
- **Data Model**: [data-model.md](./data-model.md)
- **API Contract**: [contracts/account-vulns-api.yaml](./contracts/account-vulns-api.yaml)
- **Research**: [research.md](./research.md)

## Support

- **Issues**: Report bugs to GitHub issues
- **Questions**: Ask in team Slack channel #secman-dev
- **Documentation**: Root README.md + CLAUDE.md for patterns
