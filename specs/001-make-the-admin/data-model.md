# Data Model: Admin Sidebar Visibility Control

**Feature**: Role-based visibility for Admin sidebar menu item
**Date**: 2025-10-02
**Status**: Complete

## Overview

This feature does not introduce new data models. It leverages existing User and Role entities from the backend, accessed through the frontend authentication layer.

## Existing Entities (No Changes Required)

### User Entity
**Source**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`
**Type**: JPA Entity (backend) / TypeScript interface (frontend)

**Backend (Kotlin)**:
```kotlin
@Entity
@Table(name = "users")
data class User(
    var id: Long? = null,
    var username: String,
    var email: String,
    var passwordHash: String,
    var roles: MutableSet<Role> = mutableSetOf(Role.USER),
    var createdAt: Instant? = null,
    var updatedAt: Instant? = null
) {
    enum class Role {
        USER, ADMIN
    }

    fun hasRole(role: Role): Boolean = roles.contains(role)
    fun isAdmin(): Boolean = hasRole(Role.ADMIN)
}
```

**Frontend (TypeScript)**:
```typescript
interface User {
    id: number;
    username: string;
    email: string;
    roles: string[];  // Serialized as array of strings: ["ADMIN", "USER"]
}
```

**Storage**:
- **Backend**: MariaDB `users` table + `user_roles` join table
- **Frontend**:
  - `localStorage.user` (JSON serialized)
  - `window.currentUser` (runtime JavaScript object)

**Lifecycle**:
1. User logs in → Backend validates credentials
2. Backend queries user entity with roles from database
3. Backend serializes User to JSON (roles as string array)
4. Frontend stores in localStorage and `window.currentUser`
5. Components access `window.currentUser.roles` for authorization

### Role Enum
**Source**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt:44-46`

**Values**:
- `USER`: Standard user role (default for all users)
- `ADMIN`: Administrative role (required for admin UI access)

**Frontend Representation**: String literals `"ADMIN"` and `"USER"`

## UI State Model (Component-Level)

### Sidebar Component State

**Astro Component** (`Sidebar.astro`):
- **State**: Client-side JavaScript variable (not reactive)
- **Data Source**: `window.currentUser.roles`
- **Update Trigger**: `userLoaded` event or page navigation

**React Component** (`Sidebar.tsx`):
- **State**: `isAdmin: boolean` (React state hook)
- **Data Source**: `window.currentUser.roles`
- **Update Trigger**:
  - Component mount (`useEffect` initial run)
  - `userLoaded` event listener
  - Component re-render on navigation

**State Flow**:
```
Layout.astro loads user
    ↓
Sets window.currentUser
    ↓
Dispatches 'userLoaded' event
    ↓
Sidebar components check roles
    ↓
Update visibility state
    ↓
Re-render with/without Admin menu item
```

## Data Validation Rules

### Role Check Logic

**Input**: `window.currentUser` object
**Output**: Boolean (show/hide Admin menu item)

**Validation Rules**:
1. **FR-001**: If `window.currentUser` is `null` → Hide Admin menu
2. **FR-002**: If `window.currentUser.roles` is `undefined` → Hide Admin menu
3. **FR-003**: If `window.currentUser.roles` is empty array → Hide Admin menu
4. **FR-004**: If `window.currentUser.roles.includes('ADMIN')` is `false` → Hide Admin menu
5. **FR-005**: If `window.currentUser.roles.includes('ADMIN')` is `true` → Show Admin menu

**Edge Case Handling**:
- Missing user data → Default to `false` (hidden)
- Malformed roles array → Default to `false` (hidden)
- Type mismatches → Default to `false` (hidden)
- Auth timeout → Handled by Layout.astro (redirects to login)

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                      Backend (Existing)                      │
│  ┌────────────┐         ┌──────────────┐                   │
│  │   MariaDB  │────────▶│ User Entity  │                   │
│  │   users    │         │ + roles enum │                   │
│  │ user_roles │         └──────┬───────┘                   │
│  └────────────┘                │                            │
└────────────────────────────────┼────────────────────────────┘
                                 │ JSON via /api/auth/status
                                 ▼
┌─────────────────────────────────────────────────────────────┐
│                    Frontend (Modified)                       │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Layout.astro (Existing)                 │   │
│  │  1. Fetch /api/auth/status                          │   │
│  │  2. Parse user JSON                                 │   │
│  │  3. Store in window.currentUser                     │   │
│  │  4. Dispatch 'userLoaded' event                     │   │
│  └──────────────────┬──────────────────────────────────┘   │
│                     │                                        │
│                     ▼                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │         Sidebar.astro (NEW: Role Check)             │   │
│  │  1. Listen for 'userLoaded' event                   │   │
│  │  2. Read window.currentUser.roles                   │   │
│  │  3. Filter sidebarItems array                       │   │
│  │  4. Render filtered items                           │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐   │
│  │         Sidebar.tsx (NEW: Role Check)               │   │
│  │  1. useState([isAdmin, setIsAdmin])                 │   │
│  │  2. useEffect: check window.currentUser.roles       │   │
│  │  3. Listen for 'userLoaded' event                   │   │
│  │  4. Update isAdmin state                            │   │
│  │  5. Conditional render: {isAdmin && <AdminMenu/>}   │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## No Database Changes Required

This feature is purely a frontend UI change. No migrations, schema changes, or backend API modifications are needed.

**Existing Infrastructure Used**:
- ✅ User table and user_roles table (unchanged)
- ✅ `/api/auth/status` endpoint (unchanged)
- ✅ JWT authentication (unchanged)
- ✅ Role enum values (unchanged)

## Testing Data Requirements

### Test Users

**Admin User** (existing in test data):
- Username: `adminuser`
- Password: `password`
- Roles: `["ADMIN", "USER"]`
- Expected: Should see Admin menu item

**Regular User** (existing in test data):
- Username: `regularuser` (or similar)
- Password: `password`
- Roles: `["USER"]`
- Expected: Should NOT see Admin menu item

**Test Data Sources**:
- Production: MariaDB with seeded users
- E2E Tests: Test database with fixture users
- Reference: `scripts/tests/kotlin/PopulateTestData.kt`

## Summary

- **No new data models required**
- **No database changes needed**
- **Leverages existing User entity and Role enum**
- **Frontend-only state management for visibility**
- **All validation logic client-side**
- **Fail-safe default: hide admin menu on any error**
