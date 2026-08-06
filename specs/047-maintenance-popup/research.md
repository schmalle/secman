# Research: Maintenance Popup Banner

**Feature**: 047-maintenance-popup
**Date**: 2025-11-15
**Purpose**: Resolve technical unknowns and establish implementation patterns

## Research Questions

### 1. Timezone Handling in Micronaut/JPA

**Question**: What is the best practice for storing timestamps in UTC and converting to user's local timezone in Micronaut/Kotlin with JPA?

**Decision**: Use `Instant` type for database storage and timezone conversion in DTOs

**Rationale**:
- JPA/Hibernate automatically maps `Instant` to `TIMESTAMP` in MariaDB/MySQL
- `Instant` represents UTC time without timezone ambiguity
- Conversion to user timezone happens in presentation layer (DTOs and frontend)
- Micronaut's JSON serialization handles `Instant` to ISO-8601 string conversion
- Frontend can use JavaScript `Date` object with timezone-aware formatting

**Implementation Pattern**:
```kotlin
// Entity (storage in UTC)
@Entity
data class MaintenanceBanner(
    @Column(nullable = false)
    var startTime: Instant,

    @Column(nullable = false)
    var endTime: Instant,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
)

// DTO (exposes ISO-8601 strings)
data class MaintenanceBannerResponse(
    val id: Long,
    val startTime: Instant,  // Serialized as ISO-8601 string
    val endTime: Instant,     // Frontend converts to local timezone
    val message: String
)

// Frontend conversion
const localStartTime = new Date(banner.startTime).toLocaleString();
```

**Alternatives Considered**:
- `LocalDateTime`: Rejected because it doesn't store timezone, causing ambiguity
- `ZonedDateTime`: Rejected because it's more complex and not needed (UTC storage is sufficient)
- Store timezone with each timestamp: Rejected because UTC storage is cleaner

**References**:
- Micronaut Data documentation: Instant mapping
- Hibernate 6.x: Java 8 Date/Time API support

---

### 2. XSS Prevention for User-Generated Content

**Question**: How should we sanitize banner message text to prevent XSS attacks while allowing reasonable formatting?

**Decision**: Use server-side HTML sanitization with OWASP Java HTML Sanitizer library

**Rationale**:
- Assumption from spec: Plain text only for MVP (no HTML/markdown support)
- Even plain text needs escaping when rendered in HTML context
- Defense in depth: Sanitize on backend, escape on frontend
- OWASP Java HTML Sanitizer is industry-standard and well-maintained
- For MVP: Strip all HTML tags, preserve plain text only

**Implementation Pattern**:
```kotlin
// Backend service
import org.owasp.html.PolicyFactory
import org.owasp.html.Sanitizers

class MaintenanceBannerService {
    private val sanitizer: PolicyFactory = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS)

    fun createBanner(request: MaintenanceBannerRequest): MaintenanceBanner {
        val sanitizedMessage = sanitizer.sanitize(request.message)
        // ... save to database
    }
}

// Frontend display (React)
<div className="banner-message">
  {banner.message}  {/* Already sanitized by backend */}
</div>
```

**Alternatives Considered**:
- Frontend-only escaping: Rejected because backend should validate/sanitize data
- Allow limited HTML (bold, italic): Deferred to post-MVP (spec assumes plain text)
- Markdown support: Deferred to post-MVP

**Dependencies**:
- Add to build.gradle: `implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20220608.1")`

**References**:
- OWASP Java HTML Sanitizer: https://github.com/OWASP/java-html-sanitizer
- Micronaut Security best practices

---

### 3. Efficient Time-Range Queries for Active Banners

**Question**: What is the optimal database query pattern for finding all banners active at the current time?

**Decision**: Use JPA query with indexed time-range comparison and `@Query` annotation

**Rationale**:
- Need to find banners where `NOW() BETWEEN startTime AND endTime`
- Index on both `startTime` and `endTime` columns for performance
- Typical query returns 0-5 results (very selective)
- MariaDB query optimizer handles BETWEEN efficiently with indexes
- Use JPA native query for clarity and control

**Implementation Pattern**:
```kotlin
@Repository
interface MaintenanceBannerRepository : JpaRepository<MaintenanceBanner, Long> {

    @Query("""
        SELECT b FROM MaintenanceBanner b
        WHERE :currentTime BETWEEN b.startTime AND b.endTime
        ORDER BY b.createdAt DESC
    """)
    fun findActiveBanners(currentTime: Instant): List<MaintenanceBanner>

    // Also useful for admin UI
    @Query("""
        SELECT b FROM MaintenanceBanner b
        ORDER BY b.createdAt DESC
    """)
    fun findAllOrderByCreatedAtDesc(): List<MaintenanceBanner>
}

// Entity indexes
@Entity
@Table(
    name = "maintenance_banner",
    indexes = [
        Index(name = "idx_start_time", columnList = "start_time"),
        Index(name = "idx_end_time", columnList = "end_time")
    ]
)
data class MaintenanceBanner(...)
```

**Performance Analysis**:
- Expected query time: <10ms for 100 total banners
- Index seek on startTime + range scan on endTime
- Result set typically 0-5 rows
- Query executed on every page load (acceptable for read-heavy operation)

**Alternatives Considered**:
- Caching active banners: Deferred to post-MVP (premature optimization)
- Materialized view: Rejected because query is already fast enough
- Polling/background job: Rejected because real-time accuracy is required

---

### 4. Frontend Banner Display Pattern

**Question**: Should the banner be a static Astro component or a dynamic React island?

**Decision**: Use React island for dynamic banner display with real-time updates

**Rationale**:
- Need to fetch active banners on page load (API call)
- Banner state can change during user session (time-based activation)
- React provides better state management for conditional rendering
- Astro islands allow hydration only for interactive components
- Keep Astro page layout, embed React `<MaintenanceBanner>` island

**Implementation Pattern**:
```astro
<!-- src/frontend/src/pages/index.astro -->
---
import MaintenanceBanner from '../components/MaintenanceBanner';
---

<html>
  <body>
    <!-- React island: hydrates on client -->
    <MaintenanceBanner client:load />

    <!-- Rest of static Astro content -->
    <main>
      <!-- Login form, etc. -->
    </main>
  </body>
</html>
```

```tsx
// src/frontend/src/components/MaintenanceBanner.tsx
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
    // Optional: Poll every 60 seconds for updates
    const interval = setInterval(fetchBanners, 60000);
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

**Alternatives Considered**:
- Pure Astro component with server-side rendering: Rejected because banners can change during session
- Full SPA: Rejected because Astro static pages are faster for login/start page
- Inline script without framework: Rejected because React provides better maintainability

---

### 5. Bootstrap 5.3 Alert Styling for Banners

**Question**: What Bootstrap classes and customization should be used for visually prominent banners?

**Decision**: Use Bootstrap 5.3 Alert component with custom warning style and icons

**Rationale**:
- Bootstrap Alerts are designed for notifications (semantic match)
- `.alert-warning` provides attention-grabbing yellow/orange color
- Bootstrap icons (`bi-exclamation-triangle-fill`) add visual prominence
- Stacking multiple alerts is built-in Bootstrap pattern
- Responsive by default (works on all screen sizes)
- Can customize with additional CSS if needed

**Implementation Pattern**:
```css
/* Custom styling for maintenance banners */
.maintenance-banners {
  position: sticky;
  top: 0;
  z-index: 1030; /* Above most content, below modals */
  width: 100%;
}

.maintenance-banners .alert {
  margin-bottom: 0.5rem;
  border-radius: 0; /* Full-width appearance */
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.maintenance-banners .alert:last-child {
  margin-bottom: 0;
}

/* Icon styling */
.maintenance-banners .bi {
  font-size: 1.25rem;
  flex-shrink: 0;
}

/* Responsive: smaller padding on mobile */
@media (max-width: 576px) {
  .maintenance-banners .alert {
    padding: 0.75rem;
    font-size: 0.9rem;
  }
}
```

**Alternatives Considered**:
- Custom modal/overlay: Rejected because it's intrusive and blocks content
- Toast notifications: Rejected because they're dismissible and temporary
- Top navigation bar: Rejected because it conflicts with existing navigation
- Bootstrap Info/Danger alerts: Warning color is most appropriate for maintenance

**References**:
- Bootstrap 5.3 Alerts: https://getbootstrap.com/docs/5.3/components/alerts/
- Bootstrap Icons: https://icons.getbootstrap.com/

---

### 6. Admin UI Integration Pattern

**Question**: Should the maintenance banner management be a separate page or integrated into existing admin section?

**Decision**: Create new dedicated admin page at `/admin/maintenance-banners`

**Rationale**:
- Consistent with existing admin section structure (separate pages per feature)
- Banner management requires significant UI space (list + form)
- Easier to implement RBAC (dedicated route with role check)
- Better UX: Focused interface for banner management
- Can be linked from admin navigation menu

**Implementation Pattern**:
```astro
<!-- src/frontend/src/pages/admin/maintenance-banners.astro -->
---
// Server-side role check
import { checkAdminRole } from '../../utils/auth';
if (!checkAdminRole(Astro.request)) {
  return Astro.redirect('/login');
}
---

<Layout title="Maintenance Banners - Admin">
  <div className="container">
    <h1>Maintenance Banner Management</h1>

    <!-- React islands for admin UI -->
    <MaintenanceBannerForm client:load />
    <MaintenanceBannerList client:load />
  </div>
</Layout>
```

**Navigation Integration**:
- Add link to existing admin navigation menu
- Show only to users with ADMIN role
- Use Bootstrap nav pattern consistent with other admin pages

**Alternatives Considered**:
- Modal dialog from dashboard: Rejected because it's cramped for list + form
- Settings page section: Rejected because banners are important enough for dedicated page
- Inline editing on login page: Rejected because it's confusing for non-admin users

---

## Technology Decisions Summary

| Decision Area | Technology/Pattern | Rationale |
|--------------|-------------------|-----------|
| Timestamp Storage | `Instant` (UTC) | JPA-native, timezone-safe, ISO-8601 serialization |
| XSS Prevention | OWASP Java HTML Sanitizer | Industry standard, defense in depth |
| Time-Range Queries | JPA `@Query` with indexes | Efficient, type-safe, <10ms performance |
| Frontend Display | React island in Astro | Dynamic updates, maintains Astro performance |
| UI Styling | Bootstrap 5.3 Alerts | Semantic, responsive, customizable |
| Admin UI | Dedicated `/admin/maintenance-banners` page | Focused UX, clean RBAC, scalable |

---

## Dependencies to Add

### Backend (build.gradle.kts)
```kotlin
dependencies {
    // XSS prevention
    implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20220608.1")

    // Already have:
    // - Micronaut 4.10
    // - Hibernate JPA
    // - MariaDB driver
}
```

### Frontend (package.json)
```json
{
  "dependencies": {
    // Already have:
    // - astro 5.15
    // - react 19
    // - bootstrap 5.3
    // - bootstrap-icons (verify installed)
    // - axios
  }
}
```

---

## Security Considerations

1. **Input Validation**:
   - Max message length: 2000 characters (prevent database overflow)
   - Start time must be before end time (validated in service)
   - Future start times allowed (no restriction on scheduling)

2. **XSS Prevention**:
   - Server-side sanitization with OWASP library
   - Frontend escape (React handles by default)
   - No innerHTML usage

3. **RBAC Enforcement**:
   - `@Secured("ADMIN")` on all write endpoints
   - Public GET for active banners (no auth required)
   - Frontend role checks for admin UI visibility

4. **Rate Limiting** (Future Enhancement):
   - Not required for MVP (admin-only writes, infrequent)
   - Consider if abuse detected in production

---

## Performance Optimization

1. **Database Indexes**:
   - Index on `start_time` and `end_time` for time-range queries
   - Expected query time: <10ms for 100 total banners

2. **Caching** (Not Implemented in MVP):
   - Query is fast enough (<50ms including network)
   - Premature optimization avoided
   - Can add Redis cache if needed post-launch

3. **Frontend Polling**:
   - Poll every 60 seconds for banner updates
   - Reduces server load vs. WebSocket for rarely-changing data
   - Acceptable 1-minute latency for banner activation

---

## Open Questions for Implementation Phase

None - all technical unknowns resolved.

---

## References

1. Micronaut Data JPA Documentation
2. OWASP Java HTML Sanitizer GitHub
3. Bootstrap 5.3 Components Documentation
4. Astro Islands Architecture
5. React Hooks Best Practices
6. MariaDB Index Optimization Guide
