# Quickstart Guide: User Profile Page

**Feature**: 028-user-profile-page
**Date**: 2025-10-19

## Overview

This guide provides step-by-step instructions for implementing the user profile page feature using Test-Driven Development (TDD).

## Prerequisites

- **Development Environment**: JDK 21, Node.js 18+, npm installed
- **Running Services**: MariaDB 11.4 running on localhost:3306
- **Authentication**: Existing JWT authentication system operational
- **Branch**: Currently on `028-user-profile-page` branch

## TDD Implementation Order

Follow this sequence to maintain TDD principles (Red-Green-Refactor):

### Phase 1: Backend (Kotlin/Micronaut)

#### Step 1: Write Backend Contract Tests (RED)

**File**: `src/backendng/src/test/kotlin/com/secman/contract/UserProfileContractTest.kt`

```kotlin
@MicronautTest
@Property(name = "spec.name", value = "UserProfileContractTest")
class UserProfileContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `GET profile returns 200 with valid user data when authenticated`() {
        // Test implementation: authenticated request returns profile
        // Expected: 200 OK, JSON with username/email/roles
    }

    @Test
    fun `GET profile returns 401 when not authenticated`() {
        // Test implementation: request without JWT
        // Expected: 401 Unauthorized
    }

    @Test
    fun `GET profile returns 404 when user not found`() {
        // Test implementation: valid JWT but user deleted
        // Expected: 404 Not Found
    }

    @Test
    fun `GET profile excludes sensitive fields`() {
        // Test implementation: verify passwordHash not in response
        // Expected: response contains only username, email, roles
    }
}
```

**Run tests** (should FAIL):
```bash
cd src/backendng
./gradlew test --tests UserProfileContractTest
```

#### Step 2: Create DTO (GREEN - Partial)

**File**: `src/backendng/src/main/kotlin/com/secman/dto/UserProfileDto.kt`

```kotlin
package com.secman.dto

import com.secman.domain.User
import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class UserProfileDto(
    val username: String,
    val email: String,
    val roles: Set<String>
) {
    companion object {
        fun fromUser(user: User): UserProfileDto {
            return UserProfileDto(
                username = user.username,
                email = user.email ?: "Not set",
                roles = user.roles.toSet()
            )
        }
    }
}
```

#### Step 3: Create Controller (GREEN - Complete)

**File**: `src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt`

```kotlin
package com.secman.controller

import com.secman.dto.UserProfileDto
import com.secman.repository.UserRepository
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.exceptions.NotFoundException
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule

@Controller("/api/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
class UserProfileController(
    private val userRepository: UserRepository
) {

    @Get("/profile")
    fun getCurrentUserProfile(authentication: Authentication): UserProfileDto {
        val username = authentication.name
        val user = userRepository.findByUsername(username)
            ?: throw NotFoundException("User not found")

        return UserProfileDto.fromUser(user)
    }
}
```

**Run tests** (should PASS):
```bash
./gradlew test --tests UserProfileContractTest
```

#### Step 4: Refactor (if needed)

- Review code for clarity and maintainability
- Ensure proper error handling
- Verify DTO factory method logic

---

### Phase 2: Frontend (React/Astro)

#### Step 5: Write Frontend Service (Test Optional, Focus on E2E)

**File**: `src/frontend/src/services/userProfileService.ts`

```typescript
import axios from 'axios';

export interface UserProfileData {
  username: string;
  email: string;
  roles: string[];
}

class UserProfileService {
  private readonly baseUrl = '/api/users';

  async getProfile(): Promise<UserProfileData> {
    const response = await axios.get<UserProfileData>(`${this.baseUrl}/profile`);
    return response.data;
  }
}

export default new UserProfileService();
```

#### Step 6: Create Profile Component (Implementation)

**File**: `src/frontend/src/components/UserProfile.tsx`

```typescript
import React, { useState, useEffect } from 'react';
import userProfileService, { UserProfileData } from '../services/userProfileService';

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
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load profile');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="text-center my-5">
        <div className="spinner-border text-primary" role="status">
          <span className="visually-hidden">Loading profile...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="alert alert-danger d-flex align-items-center justify-content-between" role="alert">
        <div>
          <i className="bi bi-exclamation-triangle-fill me-2"></i>
          {error}
        </div>
        <button className="btn btn-outline-danger btn-sm" onClick={fetchProfile}>
          <i className="bi bi-arrow-clockwise me-1"></i>
          Retry
        </button>
      </div>
    );
  }

  const getRoleBadgeClass = (role: string): string => {
    const colorMap: Record<string, string> = {
      ADMIN: 'bg-danger',
      RELEASE_MANAGER: 'bg-warning text-dark',
      VULN: 'bg-info',
      USER: 'bg-secondary'
    };
    return colorMap[role] || 'bg-secondary';
  };

  return (
    <div className="container mt-4">
      <h1>User Profile</h1>
      <div className="card mt-3">
        <div className="card-body">
          <div className="mb-3">
            <h5 className="card-title">Username</h5>
            <p className="card-text">{profile?.username}</p>
          </div>
          <div className="mb-3">
            <h5 className="card-title">Email</h5>
            <p className="card-text">{profile?.email}</p>
          </div>
          <div className="mb-3">
            <h5 className="card-title">Roles</h5>
            <div>
              {profile?.roles.map((role) => (
                <span key={role} className={`badge ${getRoleBadgeClass(role)} me-2`}>
                  {role}
                </span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
```

#### Step 7: Create Astro Page

**File**: `src/frontend/src/pages/profile.astro`

```astro
---
import Layout from '../layouts/Layout.astro';
import UserProfile from '../components/UserProfile';
---

<Layout title="User Profile">
  <UserProfile client:load />
</Layout>
```

#### Step 8: Update Navigation Menu

**File**: `src/frontend/src/layouts/Header.tsx` (or similar)

Add "Profile" menu item to user dropdown:

```typescript
// Inside user dropdown menu
<a className="dropdown-item" href="/profile">
  <i className="bi bi-person-circle me-2"></i>
  Profile
</a>
```

Position: Above "Settings", below username display

---

### Phase 3: E2E Tests (RED → GREEN)

#### Step 9: Write E2E Tests (RED)

**File**: `src/frontend/tests/e2e/profile.spec.ts`

```typescript
import { test, expect } from '@playwright/test';

test.describe('User Profile Page', () => {
  test.beforeEach(async ({ page }) => {
    // Login as test user
    await page.goto('/login');
    await page.fill('input[name="username"]', 'adminuser');
    await page.fill('input[name="password"]', 'testpassword');
    await page.click('button[type="submit"]');
    await page.waitForURL('/dashboard');
  });

  test('should navigate to profile page from user menu', async ({ page }) => {
    // Click username dropdown
    await page.click('[data-testid="user-dropdown"]');

    // Click Profile menu item
    await page.click('a[href="/profile"]');

    // Verify navigation
    await expect(page).toHaveURL('/profile');
    await expect(page.locator('h1')).toHaveText('User Profile');
  });

  test('should display user profile information', async ({ page }) => {
    await page.goto('/profile');

    // Wait for loading to complete
    await page.waitForSelector('.card-body', { timeout: 2000 });

    // Verify username displayed
    await expect(page.locator('text=Username')).toBeVisible();
    await expect(page.locator('text=adminuser')).toBeVisible();

    // Verify email displayed
    await expect(page.locator('text=Email')).toBeVisible();
    await expect(page.locator('text=secmanadmin@schmall.io')).toBeVisible();

    // Verify roles displayed as badges
    await expect(page.locator('.badge.bg-danger:has-text("ADMIN")')).toBeVisible();
    await expect(page.locator('.badge.bg-secondary:has-text("USER")')).toBeVisible();
  });

  test('should show error message and retry on API failure', async ({ page }) => {
    // Intercept API and force failure
    await page.route('/api/users/profile', (route) =>
      route.abort('failed')
    );

    await page.goto('/profile');

    // Verify error message displayed
    await expect(page.locator('.alert-danger')).toBeVisible();
    await expect(page.locator('text=Failed to load profile')).toBeVisible();

    // Verify retry button present
    await expect(page.locator('button:has-text("Retry")')).toBeVisible();
  });

  test('should redirect unauthenticated users to login', async ({ page }) => {
    // Clear session storage (logout)
    await page.evaluate(() => sessionStorage.clear());

    // Attempt to access profile
    await page.goto('/profile');

    // Verify redirect to login
    await expect(page).toHaveURL('/login');
  });
});
```

**Run E2E tests** (should FAIL initially, PASS after implementation):
```bash
cd src/frontend
npm run test:e2e -- profile.spec.ts
```

---

## Running the Feature

### Start Backend

```bash
cd src/backendng
./gradlew run
```

Backend available at: http://localhost:8080

### Start Frontend

```bash
cd src/frontend
npm run dev
```

Frontend available at: http://localhost:4321

### Test the Feature Manually

1. Navigate to http://localhost:4321
2. Log in with test credentials (e.g., adminuser/testpassword)
3. Click on your username in the upper-right corner
4. Click "Profile" from the dropdown menu
5. Verify you see:
   - Page heading: "User Profile"
   - Your username displayed
   - Your email displayed
   - Your roles displayed as colored badges

### Test Error Handling

1. While on profile page, stop the backend server
2. Click the "Retry" button
3. Verify error message appears
4. Restart backend and click "Retry" again
5. Verify profile loads successfully

---

## Verification Checklist

Before considering the feature complete, verify:

### Backend
- [ ] Contract tests pass (UserProfileContractTest)
- [ ] GET /api/users/profile returns 200 with authenticated request
- [ ] GET /api/users/profile returns 401 without authentication
- [ ] Response excludes passwordHash and internal fields
- [ ] Backend builds without errors: `./gradlew build`

### Frontend
- [ ] Profile page renders at /profile route
- [ ] Loading spinner displays during API call
- [ ] Profile data displays correctly (username, email, roles)
- [ ] Roles display as colored badges
- [ ] Error message with retry button appears on API failure
- [ ] Retry button re-fetches data
- [ ] Frontend builds without errors: `npm run build`

### E2E
- [ ] All E2E tests pass: `npm run test:e2e`
- [ ] Navigation from user menu works
- [ ] Unauthenticated users redirect to login
- [ ] Profile displays correct user data

### Manual Testing
- [ ] Profile page loads in <1 second (SC-002)
- [ ] Page is responsive on mobile, tablet, desktop (SC-004)
- [ ] "Profile" menu item visible in user dropdown
- [ ] Multiple roles display as separate badges
- [ ] OAuth users see their OAuth-provided email

---

## Troubleshooting

### Backend Issues

**Problem**: Tests fail with "User not found"
- **Solution**: Ensure test user exists in database (run data population script)

**Problem**: 401 Unauthorized in tests
- **Solution**: Verify JWT token generation in test setup, check @Secured annotation

**Problem**: DTO serialization errors
- **Solution**: Verify @Serdeable annotation on UserProfileDto, check Micronaut serialization config

### Frontend Issues

**Problem**: Profile page shows blank/white screen
- **Solution**: Check browser console for errors, verify API endpoint reachable, check axios configuration

**Problem**: Roles not displaying as badges
- **Solution**: Verify Bootstrap CSS loaded, check getRoleBadgeClass function, inspect role data structure

**Problem**: Error message not disappearing after retry
- **Solution**: Verify setError(null) called in fetchProfile, check state management logic

### E2E Issues

**Problem**: Tests timeout waiting for elements
- **Solution**: Increase timeout values, verify test user login succeeds, check selector accuracy

**Problem**: Badge selectors not finding elements
- **Solution**: Use `page.locator('.badge:has-text("ADMIN")')` syntax, verify Bootstrap classes applied

---

## Next Steps

After completing this quickstart:

1. **Code Review**: Submit PR with contract tests, implementation, and E2E tests
2. **Performance Testing**: Verify SC-002 (<1s load time for 95% of requests)
3. **Accessibility Audit**: Run axe-core or similar tool to verify WCAG 2.1 AA compliance
4. **Documentation**: Update CLAUDE.md with new endpoint and DTO

---

## Reference Files

- **Spec**: [spec.md](./spec.md)
- **Plan**: [plan.md](./plan.md)
- **Data Model**: [data-model.md](./data-model.md)
- **API Contract**: [contracts/user-profile-api.yaml](./contracts/user-profile-api.yaml)
- **Research**: [research.md](./research.md)
