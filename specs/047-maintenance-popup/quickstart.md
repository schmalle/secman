# Quickstart Guide: Maintenance Popup Banner

**Feature**: 047-maintenance-popup
**Branch**: `047-maintenance-popup`
**Date**: 2025-11-15

## Overview

This guide helps developers quickly understand and implement the Maintenance Popup Banner feature. It covers the essential patterns, key files, and development workflow.

## 5-Minute Architecture Overview

### What This Feature Does

Allows administrators to create, schedule, and manage maintenance notifications that appear on the start/login page during specified time windows. Banners are time-aware, support multiple concurrent messages, and are visible to all users (authenticated and unauthenticated).

### Key Components

```
Backend (Kotlin/Micronaut):
- MaintenanceBanner entity (JPA)
- MaintenanceBannerService (business logic + XSS sanitization)
- MaintenanceBannerController (REST API with RBAC)
- MaintenanceBannerRepository (time-range queries)

Frontend (Astro + React):
- MaintenanceBanner.tsx (public display component)
- MaintenanceBannerList.tsx (admin list view)
- MaintenanceBannerForm.tsx (admin create/edit form)
- maintenanceBannerService.ts (Axios API client)
```

### Data Flow

```
User visits start page
    ↓
Frontend: MaintenanceBanner.tsx loads
    ↓
API: GET /api/maintenance-banners/active
    ↓
Backend: Query active banners (NOW() BETWEEN start_time AND end_time)
    ↓
Return: List of active banners (newest first)
    ↓
Frontend: Render Bootstrap alerts stacked vertically
```

```
Admin creates banner
    ↓
Frontend: MaintenanceBannerForm.tsx submits
    ↓
API: POST /api/maintenance-banners (ADMIN role required)
    ↓
Backend: Validate time range, sanitize message, save to DB
    ↓
Return: Created banner with ID
    ↓
Frontend: Refresh list, show success message
```

---

## Quick Setup

### Prerequisites

- Kotlin 2.2.21 / Java 21
- Micronaut 4.10
- MariaDB 12
- Node.js 18+ (for frontend)
- Git

### 1. Backend Setup

**Add dependency** (build.gradle.kts):
```kotlin
dependencies {
    // XSS prevention
    implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20220608.1")
}
```

**Create entity** (src/backendng/src/main/kotlin/com/secman/domain/MaintenanceBanner.kt):
```kotlin
@Entity
@Table(name = "maintenance_banner", indexes = [
    Index(name = "idx_start_time", columnList = "start_time"),
    Index(name = "idx_end_time", columnList = "end_time")
])
data class MaintenanceBanner(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 2000)
    var message: String,

    @Column(nullable = false, name = "start_time")
    var startTime: Instant,

    @Column(nullable = false, name = "end_time")
    var endTime: Instant,

    @Column(nullable = false, name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = true)
    var createdBy: User? = null
)
```

**Run backend**:
```bash
cd src/backendng
./gradlew run
```

Hibernate will auto-create the `maintenance_banner` table on first run.

### 2. Frontend Setup

**Install dependencies** (if not already installed):
```bash
cd src/frontend
npm install
```

**Create API service** (src/frontend/src/services/maintenanceBannerService.ts):
```typescript
import axios from 'axios';

const API_BASE = import.meta.env.PUBLIC_API_URL || 'http://localhost:8080/api';

export async function getActiveBanners() {
  const response = await axios.get(`${API_BASE}/maintenance-banners/active`);
  return response.data;
}

export async function getAllBanners() {
  const response = await axios.get(`${API_BASE}/maintenance-banners`, {
    headers: { Authorization: `Bearer ${sessionStorage.getItem('jwt')}` }
  });
  return response.data;
}

// ... other CRUD methods
```

**Add to start page** (src/frontend/src/pages/index.astro):
```astro
---
import MaintenanceBanner from '../components/MaintenanceBanner';
---

<html>
  <body>
    <MaintenanceBanner client:load />
    <!-- Rest of page -->
  </body>
</html>
```

**Run frontend**:
```bash
npm run dev
```

Visit http://localhost:4321 to see the start page with banner display.

---

## Key Development Patterns

### 1. Time-Based Activation

**Backend query**:
```kotlin
@Query("""
    SELECT b FROM MaintenanceBanner b
    WHERE :currentTime BETWEEN b.startTime AND b.endTime
    ORDER BY b.createdAt DESC
""")
fun findActiveBanners(currentTime: Instant): List<MaintenanceBanner>
```

**Usage**:
```kotlin
val activeBanners = repository.findActiveBanners(Instant.now())
```

### 2. XSS Prevention

**Service layer sanitization**:
```kotlin
import org.owasp.html.PolicyFactory
import org.owasp.html.Sanitizers

class MaintenanceBannerService(
    private val repository: MaintenanceBannerRepository,
    private val sanitizer: PolicyFactory = Sanitizers.FORMATTING
) {
    fun createBanner(request: MaintenanceBannerRequest, user: User): MaintenanceBanner {
        val sanitizedMessage = sanitizer.sanitize(request.message)
        val banner = MaintenanceBanner(
            message = sanitizedMessage,
            startTime = request.startTime,
            endTime = request.endTime,
            createdBy = user
        )
        return repository.save(banner)
    }
}
```

### 3. RBAC Enforcement

**Controller**:
```kotlin
@Controller("/api/maintenance-banners")
class MaintenanceBannerController(
    private val service: MaintenanceBannerService
) {
    @Get("/active")
    fun getActiveBanners(): List<MaintenanceBannerResponse> {
        // Public endpoint - no auth required
        return service.getActiveBanners().map { MaintenanceBannerResponse.from(it) }
    }

    @Post
    @Secured("ADMIN")  // Admin only
    fun createBanner(
        @Body request: MaintenanceBannerRequest,
        authentication: Authentication
    ): HttpResponse<MaintenanceBannerResponse> {
        val user = authentication.principal as User
        val banner = service.createBanner(request, user)
        return HttpResponse.created(MaintenanceBannerResponse.from(banner))
    }
}
```

### 4. Timezone Conversion

**Backend**: Store in UTC (Instant), serialize as ISO-8601
```kotlin
data class MaintenanceBannerResponse(
    val startTime: Instant,  // e.g., "2025-11-15T20:00:00Z"
    val endTime: Instant
)
```

**Frontend**: Convert to user's local timezone
```typescript
const localStartTime = new Date(banner.startTime).toLocaleString();
// "11/15/2025, 3:00:00 PM" (if user in EST)
```

### 5. Frontend Display Pattern

**React component** (MaintenanceBanner.tsx):
```tsx
import { useEffect, useState } from 'react';
import { getActiveBanners } from '../services/maintenanceBannerService';

export default function MaintenanceBanner() {
  const [banners, setBanners] = useState([]);

  useEffect(() => {
    const fetchBanners = async () => {
      const active = await getActiveBanners();
      setBanners(active);
    };

    fetchBanners();
    const interval = setInterval(fetchBanners, 60000); // Poll every minute
    return () => clearInterval(interval);
  }, []);

  if (banners.length === 0) return null;

  return (
    <div className="maintenance-banners">
      {banners.map(banner => (
        <div key={banner.id} className="alert alert-warning" role="alert">
          <i className="bi bi-exclamation-triangle-fill"></i> {banner.message}
        </div>
      ))}
    </div>
  );
}
```

---

## Testing Workflow (TDD)

### 1. Write Failing Test

**Example**: Repository test
```kotlin
@Test
fun `should find active banners within time range`() {
    // Arrange
    val now = Instant.now()
    val activeBanner = MaintenanceBanner(
        message = "Active",
        startTime = now.minusSeconds(3600),
        endTime = now.plusSeconds(3600)
    )
    repository.save(activeBanner)

    // Act
    val result = repository.findActiveBanners(now)

    // Assert
    assertEquals(1, result.size)
    assertEquals("Active", result[0].message)
}
```

Run: `./gradlew test` → **FAILS** (method doesn't exist yet)

### 2. Implement to Pass

Add `findActiveBanners()` to repository (see Pattern 1 above).

Run: `./gradlew test` → **PASSES**

### 3. Frontend E2E Test

**Example**: Playwright test
```typescript
import { test, expect } from '@playwright/test';

test('displays active maintenance banners', async ({ page }) => {
  // Setup: Create active banner via API (test fixture)
  await createTestBanner({
    message: 'Test maintenance',
    startTime: new Date(Date.now() - 3600000).toISOString(),
    endTime: new Date(Date.now() + 3600000).toISOString()
  });

  // Act
  await page.goto('http://localhost:4321');

  // Assert
  const banner = page.locator('.maintenance-banners .alert');
  await expect(banner).toBeVisible();
  await expect(banner).toContainText('Test maintenance');
});
```

---

## Common Tasks

### Create a Test Banner (curl)

```bash
curl -X POST http://localhost:8080/api/maintenance-banners \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "message": "Test maintenance banner",
    "startTime": "2025-11-15T20:00:00Z",
    "endTime": "2025-11-15T23:00:00Z"
  }'
```

### Query Active Banners (SQL)

```sql
SELECT * FROM maintenance_banner
WHERE NOW() BETWEEN start_time AND end_time
ORDER BY created_at DESC;
```

### Check Frontend Banner Display

1. Visit http://localhost:4321
2. Open browser DevTools → Network tab
3. Look for request to `/api/maintenance-banners/active`
4. Verify response contains active banners
5. Check DOM for `.maintenance-banners` element

---

## File Checklist

Before starting implementation, ensure these files exist:

### Backend
- [ ] `src/backendng/src/main/kotlin/com/secman/domain/MaintenanceBanner.kt`
- [ ] `src/backendng/src/main/kotlin/com/secman/repository/MaintenanceBannerRepository.kt`
- [ ] `src/backendng/src/main/kotlin/com/secman/service/MaintenanceBannerService.kt`
- [ ] `src/backendng/src/main/kotlin/com/secman/controller/MaintenanceBannerController.kt`
- [ ] `src/backendng/src/main/kotlin/com/secman/dto/MaintenanceBannerRequest.kt`
- [ ] `src/backendng/src/main/kotlin/com/secman/dto/MaintenanceBannerResponse.kt`

### Backend Tests
- [ ] `src/backendng/src/test/kotlin/com/secman/controller/MaintenanceBannerControllerTest.kt`
- [ ] `src/backendng/src/test/kotlin/com/secman/service/MaintenanceBannerServiceTest.kt`
- [ ] `src/backendng/src/test/kotlin/com/secman/repository/MaintenanceBannerRepositoryTest.kt`

### Frontend
- [ ] `src/frontend/src/components/MaintenanceBanner.tsx`
- [ ] `src/frontend/src/components/admin/MaintenanceBannerList.tsx`
- [ ] `src/frontend/src/components/admin/MaintenanceBannerForm.tsx`
- [ ] `src/frontend/src/pages/admin/maintenance-banners.astro`
- [ ] `src/frontend/src/services/maintenanceBannerService.ts`

### Frontend Tests
- [ ] `src/frontend/tests/maintenance-banner.spec.ts`

---

## Debugging Tips

### Banner Not Displaying

1. **Check API response**:
   ```bash
   curl http://localhost:8080/api/maintenance-banners/active
   ```
   Should return array of active banners.

2. **Verify time range**:
   ```sql
   SELECT id, message, start_time, end_time, NOW() as current_time
   FROM maintenance_banner;
   ```
   Ensure `NOW()` is between `start_time` and `end_time`.

3. **Check frontend console**: Look for API errors or React errors.

### RBAC Issues

1. **Verify JWT token**: Check `sessionStorage.getItem('jwt')` in browser console.
2. **Check user roles**: Query `user` table to verify ADMIN role.
3. **Backend logs**: Look for `@Secured` authorization failures.

### Timezone Confusion

1. **Verify UTC storage**: All times in DB should be UTC.
2. **Check frontend conversion**: `new Date(isoString).toLocaleString()` should show local time.
3. **Test with specific timezone**: Set browser timezone in DevTools Settings.

---

## Next Steps

After completing quickstart:

1. **Read**: `data-model.md` for detailed schema and validation rules
2. **Review**: `contracts/maintenance-banner-api.yaml` for full API specification
3. **Study**: `research.md` for implementation patterns and best practices
4. **Plan**: Use `/speckit.tasks` to generate implementation task list
5. **Implement**: Follow TDD workflow (write tests first!)

---

## Useful Commands

```bash
# Backend
cd src/backendng
./gradlew build          # Build and run tests
./gradlew test           # Run tests only
./gradlew run            # Start backend server

# Frontend
cd src/frontend
npm run dev              # Start dev server
npm run build            # Production build
npx playwright test      # Run E2E tests

# Database
mysql -u root -p secman  # Connect to database
SHOW TABLES;             # List tables
DESC maintenance_banner; # Show table structure
```

---

## Resources

- **Spec**: [spec.md](spec.md)
- **Planning**: [plan.md](plan.md)
- **Research**: [research.md](research.md)
- **Data Model**: [data-model.md](data-model.md)
- **API Contract**: [contracts/maintenance-banner-api.yaml](contracts/maintenance-banner-api.yaml)
- **Constitution**: `.specify/memory/constitution.md`
- **Project Context**: `CLAUDE.md`

---

## FAQ

**Q: Can banners support HTML formatting?**
A: Not in MVP. Plain text only. Rich text deferred to post-MVP.

**Q: How do I test timezone conversion?**
A: Change browser timezone in DevTools Settings → Sensors → Location.

**Q: Can users dismiss banners?**
A: Not in MVP. Banners always display when active. Dismissal deferred to post-MVP.

**Q: What happens with 10+ active banners?**
A: They stack vertically. No limit in MVP, but consider limiting in production.

**Q: How often does frontend poll for updates?**
A: Every 60 seconds. Adjustable in `MaintenanceBanner.tsx`.

**Q: Can I schedule recurring banners?**
A: Not in MVP. Create separate banners for each occurrence. Recurrence deferred to post-MVP.

---

**Last Updated**: 2025-11-15
**Maintainer**: SecMan Development Team
