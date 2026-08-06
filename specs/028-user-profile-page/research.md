# Phase 0: Technical Research - User Profile Page

**Feature**: User Profile Page
**Branch**: 028-user-profile-page
**Date**: 2025-10-19

## Research Summary

This document consolidates technical research for implementing a read-only user profile page. All research focuses on leveraging existing patterns from features 001-027 to maintain consistency and minimize complexity.

## Backend Research

### Decision: Profile Endpoint Design

**What was chosen**: GET /api/users/profile endpoint returning current user's data from authenticated session

**Rationale**:
- Follows REST conventions (GET for read operations)
- Aligns with existing authentication patterns (JWT session-based)
- Consistent with other user-related endpoints in the system
- No URL parameter needed (implicitly "current user")
- Simpler and more secure than GET /api/users/{id} with ID from JWT

**Alternatives considered**:
1. **GET /api/users/{userId}** - Rejected because:
   - Requires extracting user ID from JWT and passing in URL
   - More complex authorization logic (verify URL ID matches session ID)
   - Increases attack surface (potential for ID manipulation attempts)
   - Not needed since users only view their own profile

2. **GET /api/users/me** - Rejected because:
   - "/me" convention not used elsewhere in codebase
   - "/profile" more explicitly communicates intent
   - Consistency with existing patterns is higher priority

### Decision: DTO vs Entity Response

**What was chosen**: Create UserProfileDto for API response (separate from User entity)

**Rationale**:
- Follows existing pattern (see Feature 027: AdminNotificationConfigDto, Feature 011: ReleaseDto)
- Prevents accidental exposure of sensitive fields (passwordHash, internal IDs)
- Allows response shape optimization (only username, email, roles needed)
- Easier to version independently of database schema
- Better API contract clarity

**Alternatives considered**:
1. **Return User entity directly** - Rejected because:
   - Violates security-first principle (could expose passwordHash, createdAt, updatedAt)
   - Couples API contract to database schema
   - Harder to exclude fields (requires @JsonIgnore annotations)
   - Not aligned with existing DTO pattern in codebase

### Decision: Authentication Strategy

**What was chosen**: Use @Secured(SecurityRule.IS_AUTHENTICATED) with Micronaut Security's Authentication object

**Rationale**:
- Consistent with existing endpoints (see Feature 004: VulnerabilityExceptionController, Feature 008: WorkgroupController)
- Micronaut Security automatically populates Authentication from JWT
- No custom authentication logic needed
- Proven pattern across 27 existing features

**Implementation Pattern** (from existing code):
```kotlin
@Controller("/api/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
class UserProfileController(private val userRepository: UserRepository) {

    @Get("/profile")
    fun getCurrentUserProfile(authentication: Authentication): UserProfileDto {
        val username = authentication.name
        val user = userRepository.findByUsername(username)
            ?: throw NotFoundException("User not found")
        return UserProfileDto.fromUser(user)
    }
}
```

**Alternatives considered**:
1. **Custom JWT parsing** - Rejected because:
   - Reinvents the wheel (Micronaut Security handles this)
   - Error-prone (token expiry, signature validation)
   - Not consistent with existing patterns

## Frontend Research

### Decision: React Component Architecture

**What was chosen**: Single UserProfile.tsx component with internal state management for loading/error/data states

**Rationale**:
- Follows existing pattern (see Feature 018: AccountVulnsView.tsx, Feature 027: AdminNotificationSettings.tsx)
- Simple feature doesn't require complex state management (no Redux/Context needed)
- React hooks (useState, useEffect) sufficient for API call + loading/error states
- Component reusability not needed (profile is single-use page)

**Component Structure**:
```typescript
// UserProfile.tsx
export default function UserProfile() {
  const [profile, setProfile] = useState<UserProfileData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchProfile();
  }, []);

  const fetchProfile = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await userProfileService.getProfile();
      setProfile(data);
    } catch (err) {
      setError("Failed to load profile");
    } finally {
      setLoading(false);
    }
  };

  if (loading) return <LoadingSpinner />;
  if (error) return <ErrorMessage message={error} onRetry={fetchProfile} />;
  return <ProfileContent profile={profile} />;
}
```

**Alternatives considered**:
1. **Separate components for loading/error/content** - Rejected because:
   - Over-engineering for simple feature
   - Adds file complexity without reusability benefit
   - Existing features use inline rendering (if/return pattern)

### Decision: Astro Page Structure

**What was chosen**: Create profile.astro page with client:load directive for React component

**Rationale**:
- Follows Astro islands pattern used throughout frontend
- Consistent with existing pages (see Feature 012: release-details.astro, Feature 018: account-vulns.astro)
- SSR for layout, CSR for interactive profile component
- Astro handles authentication redirect via existing middleware

**Page Template**:
```astro
---
// profile.astro
import Layout from '../layouts/Layout.astro';
import UserProfile from '../components/UserProfile';
---

<Layout title="User Profile">
  <UserProfile client:load />
</Layout>
```

**Alternatives considered**:
1. **Pure React SPA** - Rejected because:
   - Breaks Astro architecture used across all pages
   - Loses SSR benefits (SEO, performance)
   - Inconsistent with existing patterns

### Decision: Loading State UI

**What was chosen**: Bootstrap spinner with skeleton layout (placeholder cards)

**Rationale**:
- Consistent with Bootstrap 5.3 UI library used throughout frontend
- Skeleton UI prevents layout shift when data loads (better UX per SC-002)
- Existing components use Bootstrap spinners (Feature 027, Feature 018)
- Accessible (ARIA labels for screen readers)

**Implementation**:
```typescript
function LoadingSpinner() {
  return (
    <div className="text-center my-5">
      <div className="spinner-border text-primary" role="status">
        <span className="visually-hidden">Loading profile...</span>
      </div>
    </div>
  );
}
```

**Alternatives considered**:
1. **Custom CSS animation** - Rejected because:
   - Bootstrap spinner already available
   - Consistency more important than custom design
   - Accessibility already built-in to Bootstrap

### Decision: Error Handling UI

**What was chosen**: Bootstrap alert with retry button in content area

**Rationale**:
- Aligns with clarification decision (user chose Option A: error message with retry button)
- Follows Bootstrap alert pattern used in existing features
- Non-blocking (doesn't navigate away from page)
- Allows recovery without page reload

**Implementation**:
```typescript
function ErrorMessage({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="alert alert-danger d-flex align-items-center justify-content-between" role="alert">
      <div>
        <i className="bi bi-exclamation-triangle-fill me-2"></i>
        {message}
      </div>
      <button className="btn btn-outline-danger btn-sm" onClick={onRetry}>
        <i className="bi bi-arrow-clockwise me-1"></i>
        Retry
      </button>
    </div>
  );
}
```

**Alternatives considered**:
1. **Toast notification** - Rejected because:
   - User explicitly chose "error message with retry button in content area"
   - Toasts auto-dismiss (retry button would disappear)
   - Less persistent than inline alert

### Decision: Role Badge Styling

**What was chosen**: Bootstrap badge-pill with role-specific colors matching user management table

**Rationale**:
- Aligns with clarification decision (user chose Option A: colored badge pills)
- Consistent with existing UI in user management table (screenshot shows badges)
- Bootstrap provides built-in badge styles
- Role colors should match existing conventions for consistency

**Color Mapping** (inferred from screenshot and Bootstrap conventions):
- ADMIN: `badge bg-danger` (red - highest privilege)
- RELEASE_MANAGER: `badge bg-warning text-dark` (yellow - moderate privilege)
- VULN: `badge bg-info` (blue - specialized privilege)
- USER: `badge bg-secondary` (gray - base privilege)

**Implementation**:
```typescript
function RoleBadge({ role }: { role: string }) {
  const colorMap: Record<string, string> = {
    ADMIN: 'bg-danger',
    RELEASE_MANAGER: 'bg-warning text-dark',
    VULN: 'bg-info',
    USER: 'bg-secondary'
  };

  return (
    <span className={`badge ${colorMap[role] || 'bg-secondary'} me-2`}>
      {role}
    </span>
  );
}
```

**Alternatives considered**:
1. **Uniform color badges** - Rejected because:
   - User chose "colored badges" implying differentiation
   - Less informative (color aids quick recognition)
   - Screenshot shows varied colors in user management

## Navigation Research

### Decision: Header Dropdown Modification

**What was chosen**: Add "Profile" menu item to existing user dropdown (above "Settings", below username)

**Rationale**:
- User screenshot shows dropdown with "Profile", "Settings", "Logout"
- Logical order: Profile (personal) → Settings (configuration) → Logout (session end)
- Minimal code change (add one menu item to existing component)
- No new dropdown or navigation structure needed

**Implementation Location**:
- File: `src/frontend/src/layouts/Header.tsx` (or similar navigation component)
- Pattern: Follow existing menu items (Settings, Logout)

**Alternatives considered**:
1. **Top-level navigation item** - Rejected because:
   - User explicitly specified "Profile item on the upper right" (dropdown location)
   - Top-level would clutter primary navigation
   - Screenshot shows dropdown pattern

## API Contract Research

### Decision: OpenAPI/REST Contract Format

**What was chosen**: OpenAPI 3.0 YAML specification for GET /api/users/profile

**Rationale**:
- Consistent with existing API documentation pattern (Constitution Principle III: API-First)
- Allows contract-first development (tests written from contract)
- Auto-generated client code possible (if needed in future)
- Standard format for REST API documentation

**Contract Structure**:
```yaml
openapi: 3.0.0
paths:
  /api/users/profile:
    get:
      summary: Get current user profile
      security:
        - bearerAuth: []
      responses:
        '200':
          description: User profile retrieved successfully
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserProfileDto'
        '401':
          description: Unauthorized (not authenticated)
        '500':
          description: Internal server error
```

**Alternatives considered**:
1. **GraphQL schema** - Rejected because:
   - REST is established pattern across all existing endpoints
   - No GraphQL infrastructure in codebase
   - Overkill for single-field query

## Testing Strategy Research

### Decision: Three-Layer Testing Approach

**What was chosen**: Contract tests (backend) + Component tests (frontend) + E2E tests (full flow)

**Rationale**:
- Follows TDD principle (Constitution Principle II)
- Covers all layers: API contract, UI behavior, integration
- Consistent with existing test patterns (Features 003, 004, 016)
- Achieves ≥80% coverage target

**Test Breakdown**:

1. **Backend Contract Tests** (UserProfileContractTest.kt):
   - Authenticated request returns 200 + correct profile data
   - Unauthenticated request returns 401
   - User not found returns 404
   - DTO excludes sensitive fields (no passwordHash)

2. **Frontend Component Tests** (UserProfile.test.tsx - optional, E2E may suffice):
   - Loading state displays spinner
   - Error state displays alert with retry button
   - Success state displays username, email, role badges
   - Retry button re-fetches data

3. **E2E Tests** (profile.spec.ts):
   - Click "Profile" menu item navigates to /profile
   - Profile page displays correct user data
   - Unauthenticated user redirects to login
   - Error handling with retry works end-to-end

**Alternatives considered**:
1. **Only E2E tests** - Rejected because:
   - Slower feedback loop
   - Harder to isolate failures
   - TDD principle requires unit/contract tests

## Performance Considerations

### Decision: No Caching Strategy (Initial Implementation)

**What was chosen**: Direct API call on page load, no client-side caching

**Rationale**:
- Profile data rarely changes (roles updated infrequently)
- Page load <1s target achievable without cache (simple query)
- Premature optimization (YAGNI principle)
- Can add cache later if performance metrics show need

**Monitoring Approach**:
- Measure actual load times against SC-002 (<1s for 95% of requests)
- Add caching only if threshold violated

**Alternatives considered**:
1. **LocalStorage caching** - Rejected because:
   - Adds complexity without proven need
   - Stale data risk (roles change in admin panel)
   - SC-002 likely achievable without cache

## Accessibility Research

### Decision: Bootstrap 5 Accessibility Features

**What was chosen**: Use Bootstrap's built-in ARIA attributes and semantic HTML

**Rationale**:
- Bootstrap 5.3 includes WCAG 2.1 Level AA compliance
- Existing features rely on Bootstrap accessibility
- No custom ARIA needed for simple read-only page

**Accessibility Checklist**:
- ✅ Semantic HTML (h1 for "User Profile", proper heading hierarchy)
- ✅ ARIA labels for loading spinner (`aria-label="Loading profile"`)
- ✅ Role attributes for alerts (`role="alert"`)
- ✅ Keyboard navigation for retry button (Bootstrap default)
- ✅ Color contrast for badges (Bootstrap defaults meet WCAG AA)

**Alternatives considered**:
1. **Custom ARIA implementation** - Rejected because:
   - Bootstrap provides sufficient accessibility
   - Risk of incorrect ARIA usage
   - Not needed for simple page structure

## Summary of Key Decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| **Backend Endpoint** | GET /api/users/profile | REST convention, session-based, no ID needed |
| **Response Format** | UserProfileDto (username, email, roles) | Security-first, API-first, schema independence |
| **Authentication** | @Secured(IS_AUTHENTICATED) + Authentication object | Consistent pattern, Micronaut Security built-in |
| **Frontend Component** | React UserProfile.tsx with hooks | Simple state management, existing pattern |
| **Page Structure** | profile.astro with client:load | Astro islands, SSR + CSR hybrid |
| **Loading State** | Bootstrap spinner + skeleton | Prevents layout shift, Bootstrap consistency |
| **Error Handling** | Alert with retry button | User clarification choice, non-blocking |
| **Role Display** | Colored Bootstrap badge pills | User clarification choice, visual hierarchy |
| **Navigation** | Add to existing user dropdown | User screenshot pattern, minimal change |
| **Testing** | Contract + E2E (component optional) | TDD principle, three-layer coverage |
| **Performance** | No caching (initial) | YAGNI, measure first |
| **Accessibility** | Bootstrap 5 defaults | WCAG 2.1 AA, existing pattern |

## Next Steps

Proceed to **Phase 1: Design & Contracts**:
1. Generate data-model.md (entity mappings)
2. Create OpenAPI contract (contracts/user-profile-api.yaml)
3. Write quickstart.md (developer guide)
4. Update agent context (CLAUDE.md)
