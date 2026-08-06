# Quickstart Guide: User Mapping Management

**Feature**: 017-user-mapping-management  
**Estimated Time**: 4-6 hours  
**Prerequisites**: Feature 013 (UserMapping entity), Micronaut 4.4, React 19

## Overview

This guide provides a step-by-step implementation plan for adding user mapping management capabilities to the user edit interface. Follow the test-first workflow to ensure quality and maintainability.

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────┐
│                       Frontend (React)                       │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  UserEditPage.tsx                                     │  │
│  │    └─ UserMappingList (inline component)             │  │
│  │         ├─ Display mappings table                     │  │
│  │         ├─ Add button + form                          │  │
│  │         ├─ Edit inline + validation                   │  │
│  │         └─ Delete confirmation                        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  api/userMappings.ts (API client)                     │  │
│  │    ├─ getUserMappings(userId)                         │  │
│  │    ├─ createMapping(userId, data)                     │  │
│  │    ├─ updateMapping(userId, mappingId, data)          │  │
│  │    └─ deleteMapping(userId, mappingId)                │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            ▲ │
                  REST API  │ │ JSON
                            │ ▼
┌─────────────────────────────────────────────────────────────┐
│                    Backend (Kotlin/Micronaut)                │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  UserController (extended)                            │  │
│  │    ├─ GET    /api/users/{id}/mappings                 │  │
│  │    ├─ POST   /api/users/{id}/mappings                 │  │
│  │    ├─ PUT    /api/users/{id}/mappings/{mappingId}     │  │
│  │    └─ DELETE /api/users/{id}/mappings/{mappingId}     │  │
│  │  [@Secured("ADMIN")]                                   │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  UserMappingService (new)                             │  │
│  │    ├─ Business validation                             │  │
│  │    ├─ Duplicate detection                             │  │
│  │    ├─ CRUD operations                                 │  │
│  │    └─ DTO transformation                              │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  UserMappingRepository (existing - Feature 013)       │  │
│  │    ├─ findByEmail()                                   │  │
│  │    ├─ existsByEmailAndAwsAccountIdAndDomain()         │  │
│  │    └─ countByEmail()                                  │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Implementation Phases

### Phase 1: Backend Foundation (TDD)

**Duration**: 2-3 hours

#### Step 1.1: Create DTOs

**File**: `src/backendng/src/main/kotlin/com/secman/dto/UserMappingDto.kt`

```kotlin
package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import com.secman.domain.UserMapping
import java.time.Instant

@Serdeable
data class UserMappingResponse(
    val id: Long,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val createdAt: String,
    val updatedAt: String
)

@Serdeable
data class CreateUserMappingRequest(
    val awsAccountId: String?,
    val domain: String?
)

@Serdeable
data class UpdateUserMappingRequest(
    val awsAccountId: String?,
    val domain: String?
)

fun UserMapping.toResponse(): UserMappingResponse {
    return UserMappingResponse(
        id = this.id!!,
        email = this.email,
        awsAccountId = this.awsAccountId,
        domain = this.domain,
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString()
    )
}
```

**Validation**: Compile with `./gradlew compileKotlin`

#### Step 1.2: Write Service Tests (TDD)

**File**: `src/backendng/src/test/kotlin/com/secman/service/UserMappingServiceTest.kt`

```kotlin
package com.secman.service

import com.secman.domain.UserMapping
import com.secman.dto.CreateUserMappingRequest
import com.secman.dto.UpdateUserMappingRequest
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import java.util.Optional

class UserMappingServiceTest {
    
    private val userRepository: UserRepository = mockk()
    private val userMappingRepository: UserMappingRepository = mockk()
    private val service = UserMappingService(userRepository, userMappingRepository)
    
    @Test
    fun `getUserMappings returns mappings for valid user`() {
        // Given
        val userId = 1L
        val user = mockk<com.secman.domain.User> {
            every { email } returns "user@example.com"
        }
        val mappings = listOf(
            mockk<UserMapping> {
                every { id } returns 1L
                every { email } returns "user@example.com"
                every { awsAccountId } returns "123456789012"
                every { domain } returns null
                every { createdAt } returns mockk()
                every { updatedAt } returns mockk()
            }
        )
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userMappingRepository.findByEmail("user@example.com") } returns mappings
        
        // When
        val result = service.getUserMappings(userId)
        
        // Then
        assertEquals(1, result.size)
        assertEquals("user@example.com", result[0].email)
        verify { userRepository.findById(userId) }
        verify { userMappingRepository.findByEmail("user@example.com") }
    }
    
    @Test
    fun `createMapping validates at least one field`() {
        // Given
        val userId = 1L
        val request = CreateUserMappingRequest(null, null)
        
        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            service.createMapping(userId, request)
        }
        assertTrue(exception.message!!.contains("at least one"))
    }
    
    @Test
    fun `createMapping detects duplicates`() {
        // Given
        val userId = 1L
        val user = mockk<com.secman.domain.User> {
            every { email } returns "user@example.com"
        }
        val request = CreateUserMappingRequest("123456789012", null)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { 
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                "user@example.com", "123456789012", null
            ) 
        } returns true
        
        // When/Then
        val exception = assertThrows<IllegalStateException> {
            service.createMapping(userId, request)
        }
        assertTrue(exception.message!!.contains("already exists"))
    }
    
    @Test
    fun `createMapping succeeds with valid data`() {
        // Given
        val userId = 1L
        val user = mockk<com.secman.domain.User> {
            every { email } returns "user@example.com"
        }
        val request = CreateUserMappingRequest("123456789012", null)
        val savedMapping = mockk<UserMapping> {
            every { id } returns 1L
            every { email } returns "user@example.com"
            every { awsAccountId } returns "123456789012"
            every { domain } returns null
            every { createdAt } returns mockk()
            every { updatedAt } returns mockk()
        }
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { 
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any()) 
        } returns false
        every { userMappingRepository.save(any()) } returns savedMapping
        
        // When
        val result = service.createMapping(userId, request)
        
        // Then
        assertEquals("user@example.com", result.email)
        assertEquals("123456789012", result.awsAccountId)
        verify { userMappingRepository.save(any()) }
    }
    
    // Additional tests for updateMapping, deleteMapping...
}
```

**Run Tests**: `./gradlew test --tests UserMappingServiceTest`  
**Expected**: All tests FAIL (service not implemented yet)

#### Step 1.3: Implement Service

**File**: `src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt`

```kotlin
package com.secman.service

import com.secman.domain.UserMapping
import com.secman.dto.CreateUserMappingRequest
import com.secman.dto.UpdateUserMappingRequest
import com.secman.dto.UserMappingResponse
import com.secman.dto.toResponse
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class UserMappingService(
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository
) {
    
    fun getUserMappings(userId: Long): List<UserMappingResponse> {
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        val mappings = userMappingRepository.findByEmail(user.email)
        return mappings.map { it.toResponse() }
    }
    
    @Transactional
    fun createMapping(userId: Long, request: CreateUserMappingRequest): UserMappingResponse {
        // Validate at least one field
        if (request.awsAccountId == null && request.domain == null) {
            throw IllegalArgumentException("At least one of Domain or AWS Account ID must be provided")
        }
        
        // Get user email
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        // Check for duplicates
        if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                user.email, request.awsAccountId, request.domain
            )) {
            throw IllegalStateException("This mapping already exists")
        }
        
        // Create and save
        val mapping = UserMapping().apply {
            email = user.email
            awsAccountId = request.awsAccountId
            domain = request.domain
        }
        
        val savedMapping = userMappingRepository.save(mapping)
        return savedMapping.toResponse()
    }
    
    @Transactional
    fun updateMapping(userId: Long, mappingId: Long, request: UpdateUserMappingRequest): UserMappingResponse {
        // Validate at least one field
        if (request.awsAccountId == null && request.domain == null) {
            throw IllegalArgumentException("At least one of Domain or AWS Account ID must be provided")
        }
        
        // Get user
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        // Get mapping and verify ownership
        val mapping = userMappingRepository.findById(mappingId)
            .orElseThrow { NoSuchElementException("Mapping not found") }
        
        if (mapping.email != user.email) {
            throw IllegalArgumentException("Mapping does not belong to user")
        }
        
        // Check for duplicates (excluding current mapping)
        val isDuplicate = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
            user.email, request.awsAccountId, request.domain
        ) && (mapping.awsAccountId != request.awsAccountId || mapping.domain != request.domain)
        
        if (isDuplicate) {
            throw IllegalStateException("This mapping already exists")
        }
        
        // Update
        mapping.awsAccountId = request.awsAccountId
        mapping.domain = request.domain
        
        val updated = userMappingRepository.update(mapping)
        return updated.toResponse()
    }
    
    @Transactional
    fun deleteMapping(userId: Long, mappingId: Long): Boolean {
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        val mapping = userMappingRepository.findById(mappingId)
            .orElseThrow { NoSuchElementException("Mapping not found") }
        
        if (mapping.email != user.email) {
            throw IllegalArgumentException("Mapping does not belong to user")
        }
        
        userMappingRepository.delete(mapping)
        return true
    }
}
```

**Run Tests**: `./gradlew test --tests UserMappingServiceTest`  
**Expected**: All tests PASS ✅

#### Step 1.4: Write Controller Tests

**File**: `src/backendng/src/test/kotlin/com/secman/controller/UserControllerMappingTest.kt`

```kotlin
package com.secman.controller

import com.secman.dto.CreateUserMappingRequest
import com.secman.dto.UserMappingResponse
import com.secman.service.UserMappingService
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.*
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@MicronautTest
class UserControllerMappingTest {
    
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient
    
    @Inject
    lateinit var userMappingService: UserMappingService
    
    @Test
    fun `GET users-userId-mappings returns 200 with mappings`() {
        // Given
        val userId = 1L
        val mappings = listOf(
            UserMappingResponse(1, "user@example.com", "123456789012", null, "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        )
        every { userMappingService.getUserMappings(userId) } returns mappings
        
        // When
        val request = HttpRequest.GET<List<UserMappingResponse>>("/api/users/$userId/mappings")
            .bearerAuth("mock-admin-token")
        val response = client.toBlocking().exchange(request, List::class.java)
        
        // Then
        assertEquals(HttpStatus.OK, response.status)
        verify { userMappingService.getUserMappings(userId) }
    }
    
    @Test
    fun `POST users-userId-mappings creates mapping and returns 201`() {
        // Given
        val userId = 1L
        val requestBody = CreateUserMappingRequest("123456789012", null)
        val created = UserMappingResponse(1, "user@example.com", "123456789012", null, "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
        every { userMappingService.createMapping(userId, requestBody) } returns created
        
        // When
        val request = HttpRequest.POST("/api/users/$userId/mappings", requestBody)
            .bearerAuth("mock-admin-token")
        val response = client.toBlocking().exchange(request, UserMappingResponse::class.java)
        
        // Then
        assertEquals(HttpStatus.CREATED, response.status)
        verify { userMappingService.createMapping(userId, requestBody) }
    }
    
    // Additional tests for PUT, DELETE...
}
```

**Run Tests**: `./gradlew test --tests UserControllerMappingTest`  
**Expected**: Tests FAIL (endpoints not implemented)

#### Step 1.5: Implement Controller Endpoints

**File**: `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`

Add to existing UserController:

```kotlin
import com.secman.dto.*
import com.secman.service.UserMappingService

@Controller("/api/users")
@Secured("ADMIN")
class UserController(
    private val userService: UserService,
    private val userMappingService: UserMappingService  // Add injection
) {
    
    // ... existing endpoints ...
    
    @Get("/{userId}/mappings")
    fun getUserMappings(@PathVariable userId: Long): List<UserMappingResponse> {
        return userMappingService.getUserMappings(userId)
    }
    
    @Post("/{userId}/mappings")
    @Status(HttpStatus.CREATED)
    fun createUserMapping(
        @PathVariable userId: Long,
        @Body request: CreateUserMappingRequest
    ): UserMappingResponse {
        return userMappingService.createMapping(userId, request)
    }
    
    @Put("/{userId}/mappings/{mappingId}")
    fun updateUserMapping(
        @PathVariable userId: Long,
        @PathVariable mappingId: Long,
        @Body request: UpdateUserMappingRequest
    ): UserMappingResponse {
        return userMappingService.updateMapping(userId, mappingId, request)
    }
    
    @Delete("/{userId}/mappings/{mappingId}")
    @Status(HttpStatus.NO_CONTENT)
    fun deleteUserMapping(
        @PathVariable userId: Long,
        @PathVariable mappingId: Long
    ) {
        userMappingService.deleteMapping(userId, mappingId)
    }
}
```

**Run Tests**: `./gradlew test --tests UserControllerMappingTest`  
**Expected**: All tests PASS ✅

**Run All Tests**: `./gradlew test`  
**Expected**: Full test suite passes

### Phase 2: Frontend Implementation

**Duration**: 2-3 hours

#### Step 2.1: Create API Client

**File**: `src/frontend/src/api/userMappings.ts`

```typescript
import { csrfPost, csrfDelete } from '../utils/csrf';
import { authenticatedFetch } from '../utils/auth';

export interface UserMapping {
  id: number;
  email: string;
  awsAccountId: string | null;
  domain: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateMappingRequest {
  awsAccountId: string | null;
  domain: string | null;
}

export interface UpdateMappingRequest {
  awsAccountId: string | null;
  domain: string | null;
}

const API_BASE = 'http://localhost:8080/api';

export async function getUserMappings(userId: number): Promise<UserMapping[]> {
  const response = await authenticatedFetch(`${API_BASE}/users/${userId}/mappings`);
  if (!response.ok) {
    throw new Error('Failed to fetch mappings');
  }
  return response.json();
}

export async function createMapping(userId: number, data: CreateMappingRequest): Promise<UserMapping> {
  const response = await csrfPost(`${API_BASE}/users/${userId}/mappings`, data);
  if (!response.ok) {
    const errorData = await response.json();
    throw new Error(errorData.error || 'Failed to create mapping');
  }
  return response.json();
}

export async function updateMapping(
  userId: number,
  mappingId: number,
  data: UpdateMappingRequest
): Promise<UserMapping> {
  const response = await fetch(`${API_BASE}/users/${userId}/mappings/${mappingId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${localStorage.getItem('authToken')}`
    },
    body: JSON.stringify(data)
  });
  if (!response.ok) {
    const errorData = await response.json();
    throw new Error(errorData.error || 'Failed to update mapping');
  }
  return response.json();
}

export async function deleteMapping(userId: number, mappingId: number): Promise<void> {
  const response = await csrfDelete(`${API_BASE}/users/${userId}/mappings/${mappingId}`);
  if (!response.ok) {
    throw new Error('Failed to delete mapping');
  }
}
```

**Validation**: TypeScript compilation `npm run build`

#### Step 2.2: Add UI Component to User Edit Page

**File**: `src/frontend/src/components/UserEditPage.tsx` (extend existing)

Add inline component at bottom of user edit form:

```typescript
import { getUserMappings, createMapping, updateMapping, deleteMapping, UserMapping } from '../api/userMappings';

// Inside UserEditPage component
const [mappings, setMappings] = useState<UserMapping[]>([]);
const [isAddingMapping, setIsAddingMapping] = useState(false);
const [newMapping, setNewMapping] = useState({ awsAccountId: '', domain: '' });
const [editingMappingId, setEditingMappingId] = useState<number | null>(null);
const [error, setError] = useState<string | null>(null);

useEffect(() => {
  if (userId) {
    loadMappings();
  }
}, [userId]);

async function loadMappings() {
  try {
    const data = await getUserMappings(userId);
    setMappings(data);
  } catch (err) {
    console.error('Failed to load mappings:', err);
  }
}

async function handleAddMapping() {
  if (!newMapping.awsAccountId && !newMapping.domain) {
    setError('At least one of AWS Account ID or Domain must be provided');
    return;
  }
  
  try {
    await createMapping(userId, {
      awsAccountId: newMapping.awsAccountId || null,
      domain: newMapping.domain || null
    });
    setNewMapping({ awsAccountId: '', domain: '' });
    setIsAddingMapping(false);
    setError(null);
    await loadMappings();
  } catch (err) {
    setError(err.message);
  }
}

async function handleDeleteMapping(mappingId: number) {
  if (!confirm('Are you sure you want to delete this mapping?')) {
    return;
  }
  
  try {
    await deleteMapping(userId, mappingId);
    await loadMappings();
  } catch (err) {
    setError(err.message);
  }
}

// JSX at bottom of form
return (
  <div>
    {/* Existing user edit fields... */}
    
    <div className="mt-4">
      <h3>Access Mappings</h3>
      
      {error && <div className="alert alert-danger">{error}</div>}
      
      <table className="table">
        <thead>
          <tr>
            <th>AWS Account ID</th>
            <th>Domain</th>
            <th>Created</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {mappings.map(mapping => (
            <tr key={mapping.id}>
              <td>{mapping.awsAccountId || '-'}</td>
              <td>{mapping.domain || '-'}</td>
              <td>{new Date(mapping.createdAt).toLocaleDateString()}</td>
              <td>
                <button 
                  className="btn btn-sm btn-danger"
                  onClick={() => handleDeleteMapping(mapping.id)}
                >
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      
      {!isAddingMapping && (
        <button 
          className="btn btn-primary"
          onClick={() => setIsAddingMapping(true)}
        >
          Add Mapping
        </button>
      )}
      
      {isAddingMapping && (
        <div className="card mt-2">
          <div className="card-body">
            <div className="mb-2">
              <label>AWS Account ID (12 digits)</label>
              <input
                type="text"
                className="form-control"
                value={newMapping.awsAccountId}
                onChange={e => setNewMapping({...newMapping, awsAccountId: e.target.value})}
                pattern="\d{12}"
              />
            </div>
            <div className="mb-2">
              <label>Domain</label>
              <input
                type="text"
                className="form-control"
                value={newMapping.domain}
                onChange={e => setNewMapping({...newMapping, domain: e.target.value})}
              />
            </div>
            <button className="btn btn-success me-2" onClick={handleAddMapping}>
              Save
            </button>
            <button 
              className="btn btn-secondary" 
              onClick={() => {
                setIsAddingMapping(false);
                setNewMapping({ awsAccountId: '', domain: '' });
                setError(null);
              }}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  </div>
);
```

**Test Manually**: 
1. Start backend: `./gradlew run`
2. Start frontend: `npm run dev`
3. Navigate to user edit page
4. Verify mappings display, add, delete

### Phase 3: E2E Testing

**Duration**: 1 hour

#### Step 3.1: Write E2E Test

**File**: `tests/user-mapping-management-e2e.spec.ts`

```typescript
import { test, expect } from '@playwright/test';

test.describe('User Mapping Management', () => {
  test.beforeEach(async ({ page }) => {
    // Login as admin
    await page.goto('http://localhost:4321/login');
    await page.fill('input[name="email"]', 'admin@secman.local');
    await page.fill('input[name="password"]', 'admin123');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/dashboard');
    
    // Navigate to user edit
    await page.goto('http://localhost:4321/admin/users/1/edit');
  });
  
  test('displays existing mappings', async ({ page }) => {
    await expect(page.locator('h3:has-text("Access Mappings")')).toBeVisible();
    // Verify table headers
    await expect(page.locator('th:has-text("AWS Account ID")')).toBeVisible();
    await expect(page.locator('th:has-text("Domain")')).toBeVisible();
  });
  
  test('creates new AWS account mapping', async ({ page }) => {
    await page.click('button:has-text("Add Mapping")');
    await page.fill('input[type="text"]:near(:text("AWS Account ID"))', '123456789012');
    await page.click('button:has-text("Save")');
    
    await expect(page.locator('td:has-text("123456789012")')).toBeVisible();
  });
  
  test('creates new domain mapping', async ({ page }) => {
    await page.click('button:has-text("Add Mapping")');
    await page.fill('input[type="text"]:near(:text("Domain"))', 'engineering.example.com');
    await page.click('button:has-text("Save")');
    
    await expect(page.locator('td:has-text("engineering.example.com")')).toBeVisible();
  });
  
  test('shows error when no fields provided', async ({ page }) => {
    await page.click('button:has-text("Add Mapping")');
    await page.click('button:has-text("Save")');
    
    await expect(page.locator('.alert-danger')).toContainText('at least one');
  });
  
  test('deletes mapping with confirmation', async ({ page }) => {
    // Assuming mapping exists
    const deleteButton = page.locator('button:has-text("Delete")').first();
    
    page.on('dialog', dialog => dialog.accept());
    await deleteButton.click();
    
    await page.waitForTimeout(500); // Wait for deletion
    // Verify mapping removed (check table row count decreased)
  });
});
```

**Run E2E**: `npx playwright test tests/user-mapping-management-e2e.spec.ts`

## Verification Checklist

### Backend

- [ ] DTOs created and compile successfully
- [ ] Service tests written and pass
- [ ] Service implementation passes all tests
- [ ] Controller tests written and pass
- [ ] Controller endpoints registered correctly
- [ ] All unit tests pass: `./gradlew test`
- [ ] Manual API test with curl or Postman

### Frontend

- [ ] API client created with TypeScript types
- [ ] Component integrated into user edit page
- [ ] Mappings display correctly
- [ ] Add mapping form works
- [ ] Delete confirmation works
- [ ] Error messages display
- [ ] No console errors
- [ ] Frontend builds: `npm run build`

### Integration

- [ ] Backend serves responses correctly
- [ ] Frontend calls correct endpoints
- [ ] Authentication works (admin only)
- [ ] CORS headers configured
- [ ] E2E tests pass

### Quality

- [ ] Code follows existing patterns
- [ ] No hardcoded values
- [ ] Error handling implemented
- [ ] Validation on both frontend and backend
- [ ] No security vulnerabilities introduced

## Troubleshooting

### Backend Issues

**Problem**: Tests fail with "UserMappingService not found"  
**Solution**: Check `@Singleton` annotation and injection in UserController

**Problem**: Duplicate detection not working  
**Solution**: Verify repository method signature matches exactly

**Problem**: 403 Forbidden on endpoints  
**Solution**: Ensure `@Secured("ADMIN")` on controller and valid JWT token

### Frontend Issues

**Problem**: CORS errors  
**Solution**: Verify `allowed-origins` in `application.yml` includes `http://localhost:4321`

**Problem**: 401 Unauthorized  
**Solution**: Check Authorization header in API client functions

**Problem**: TypeScript errors on UserMapping type  
**Solution**: Ensure interface matches backend response exactly

## Next Steps

After completing this quickstart:

1. **Performance Testing**: Test with 100+ mappings per user
2. **Accessibility**: Add ARIA labels and keyboard navigation
3. **Internationalization**: Add translation keys for error messages
4. **Pagination**: If needed, implement pagination for large mapping lists
5. **Audit Logging**: Add logging for mapping changes (Feature 018)

## References

- [OpenAPI Contract](./contracts/user-mappings-api.yml)
- [Data Model](./data-model.md)
- [Feature Specification](./spec.md)
- [Micronaut Security Docs](https://micronaut-projects.github.io/micronaut-security/latest/guide/)
- [React useState Hook](https://react.dev/reference/react/useState)
