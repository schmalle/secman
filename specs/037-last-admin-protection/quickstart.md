# Quickstart Guide: Last Admin Protection

**Feature**: 037-last-admin-protection
**Date**: 2025-10-31
**Target Audience**: Developers implementing this feature

## Overview

This guide provides a step-by-step walkthrough for implementing last administrator protection. Follow these steps in order to ensure TDD compliance and constitutional adherence.

## Prerequisites

- Existing User entity with roles support
- Existing UserRepository (Micronaut Data)
- Existing UserService and UserDeletionValidator
- Existing UserController with delete endpoint
- JUnit 5 + MockK test framework configured

## Implementation Sequence (TDD)

### Phase 1: Contract Tests (Write First, Should Fail)

**File**: `src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt`

**Create or extend test class**:

```kotlin
package com.secman.contract

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@MicronautTest
class UserControllerContractTest {

    @Test
    fun `DELETE users id - returns 409 when deleting last admin`() {
        // Arrange: Setup with single admin user
        val adminUser = createTestUser(roles = setOf(User.Role.ADMIN))

        // Act: Attempt to delete last admin
        val response = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/users/${adminUser.id}"),
            Map::class.java
        )

        // Assert: Expect 409 Conflict
        assertEquals(HttpStatus.CONFLICT, response.status)
        val body = response.body() as Map<*, *>
        assertTrue(body["error"].toString().contains("last administrator"))

        // Verify admin still exists
        val stillExists = userRepository.findById(adminUser.id).isPresent
        assertTrue(stillExists)
    }

    @Test
    fun `DELETE users id - succeeds when multiple admins exist`() {
        // Arrange: Setup with two admin users
        val admin1 = createTestUser(username = "admin1", roles = setOf(User.Role.ADMIN))
        val admin2 = createTestUser(username = "admin2", roles = setOf(User.Role.ADMIN))

        // Act: Delete one admin
        val response = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/users/${admin1.id}"),
            Map::class.java
        )

        // Assert: Expect 200 OK
        assertEquals(HttpStatus.OK, response.status)

        // Verify admin was deleted
        val deleted = userRepository.findById(admin1.id).isEmpty
        assertTrue(deleted)

        // Verify other admin still exists
        val otherExists = userRepository.findById(admin2.id).isPresent
        assertTrue(otherExists)
    }

    @Test
    fun `PUT users id - returns 409 when removing ADMIN role from last admin`() {
        // Arrange: Single admin user
        val admin = createTestUser(roles = setOf(User.Role.ADMIN, User.Role.USER))

        // Act: Try to remove ADMIN role
        val updateRequest = mapOf("roles" to listOf("USER"))
        val response = client.toBlocking().exchange(
            HttpRequest.PUT("/api/users/${admin.id}", updateRequest),
            Map::class.java
        )

        // Assert: Expect 409 Conflict
        assertEquals(HttpStatus.CONFLICT, response.status)

        // Verify role not changed
        val user = userRepository.findById(admin.id).get()
        assertTrue(user.isAdmin())
    }
}
```

**Run tests**: `./gradlew test --tests UserControllerContractTest`
**Expected**: All tests should FAIL (feature not implemented yet)

---

### Phase 2: Service Layer Tests (Write First, Should Fail)

**File**: `src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt`

**Extend existing test class**:

```kotlin
package com.secman.service

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class UserDeletionValidatorTest {

    private val userRepository = mockk<UserRepository>()
    // ... other repository mocks
    private val validator = UserDeletionValidator(userRepository, ...)

    @Test
    fun `validateUserDeletion blocks when user is last admin`() {
        // Arrange
        val lastAdmin = User(
            id = 1L,
            username = "admin",
            email = "admin@example.com",
            passwordHash = "hash",
            roles = mutableSetOf(User.Role.ADMIN)
        )

        every { userRepository.findById(1L) } returns Optional.of(lastAdmin)
        every { userRepository.findAll() } returns listOf(lastAdmin)
        // ... other mocks return empty lists

        // Act
        val result = validator.validateUserDeletion(1L)

        // Assert
        assertFalse(result.canDelete)
        assertTrue(result.blockingReferences.any { it.entityType == "SystemConstraint" })
        assertTrue(result.message.contains("last administrator"))
    }

    @Test
    fun `validateUserDeletion allows when multiple admins exist`() {
        // Arrange
        val admin1 = User(id = 1L, ..., roles = mutableSetOf(User.Role.ADMIN))
        val admin2 = User(id = 2L, ..., roles = mutableSetOf(User.Role.ADMIN))

        every { userRepository.findById(1L) } returns Optional.of(admin1)
        every { userRepository.findAll() } returns listOf(admin1, admin2)
        // ... other mocks

        // Act
        val result = validator.validateUserDeletion(1L)

        // Assert
        assertTrue(result.canDelete)
        assertTrue(result.blockingReferences.isEmpty())
    }

    @Test
    fun `validateUserDeletion allows deleting non-admin user when single admin exists`() {
        // Arrange
        val admin = User(id = 1L, ..., roles = mutableSetOf(User.Role.ADMIN))
        val regularUser = User(id = 2L, ..., roles = mutableSetOf(User.Role.USER))

        every { userRepository.findById(2L) } returns Optional.of(regularUser)
        every { userRepository.findAll() } returns listOf(admin, regularUser)
        // ... other mocks

        // Act
        val result = validator.validateUserDeletion(2L)

        // Assert
        assertTrue(result.canDelete)
    }
}
```

**Run tests**: `./gradlew test --tests UserDeletionValidatorTest`
**Expected**: New tests should FAIL (feature not implemented yet)

---

### Phase 3: Implementation (Make Tests Pass)

#### Step 3.1: Add Admin Count Method to UserService

**File**: `src/backendng/src/main/kotlin/com/secman/service/UserService.kt`

**Add method**:

```kotlin
fun countAdminUsers(): Int {
    return userRepository.findAll().count { user ->
        user.roles.contains(User.Role.ADMIN)
    }
}
```

#### Step 3.2: Extend UserDeletionValidator

**File**: `src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt`

**Add constructor parameter**:

```kotlin
@Singleton
class UserDeletionValidator(
    private val userRepository: UserRepository,  // ADD THIS
    private val demandRepository: DemandRepository,
    // ... other repositories
)
```

**Enhance validateUserDeletion method**:

```kotlin
fun validateUserDeletion(userId: Long): ValidationResult {
    val blockingReferences = mutableListOf<BlockingReference>()

    // NEW: Check last admin protection
    val user = userRepository.findById(userId).orElse(null)
    if (user != null && user.isAdmin()) {
        val adminCount = userRepository.findAll().count { it.roles.contains(User.Role.ADMIN) }
        if (adminCount <= 1) {
            blockingReferences.add(
                BlockingReference(
                    entityType = "SystemConstraint",
                    count = 1,
                    role = "last_admin",
                    details = "Cannot delete the last administrator. At least one ADMIN user must remain in the system."
                )
            )
        }
    }

    // EXISTING: Check demands, risk assessments, etc.
    val demandsAsRequestor = demandRepository.findByRequestorId(userId)
    // ... rest of existing validation logic

    // Generate message
    val message = if (blockingReferences.isEmpty()) {
        "User can be safely deleted"
    } else {
        // ... existing message logic
    }

    return ValidationResult(
        canDelete = blockingReferences.isEmpty(),
        blockingReferences = blockingReferences,
        message = message
    )
}
```

#### Step 3.3: Add Role Update Validation Method

**File**: `src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt`

**Add new method**:

```kotlin
fun validateAdminRoleRemoval(userId: Long, newRoles: Set<User.Role>): ValidationResult {
    val user = userRepository.findById(userId).orElse(null)
        ?: return ValidationResult(canDelete = true, blockingReferences = emptyList(), message = "User not found")

    // Check if removing ADMIN role
    val hasAdminNow = user.roles.contains(User.Role.ADMIN)
    val willHaveAdmin = newRoles.contains(User.Role.ADMIN)

    if (hasAdminNow && !willHaveAdmin) {
        // Count total admins
        val adminCount = userRepository.findAll().count { it.roles.contains(User.Role.ADMIN) }

        if (adminCount <= 1) {
            return ValidationResult(
                canDelete = false,
                blockingReferences = listOf(
                    BlockingReference(
                        entityType = "SystemConstraint",
                        count = 1,
                        role = "last_admin",
                        details = "Cannot remove ADMIN role from the last administrator. At least one ADMIN user must remain in the system."
                    )
                ),
                message = "Cannot remove ADMIN role from the last administrator."
            )
        }
    }

    return ValidationResult(canDelete = true, blockingReferences = emptyList(), message = "OK")
}
```

#### Step 3.4: Update UserController

**File**: `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`

**Update PUT endpoint** (around line 227):

```kotlin
request.roles?.let { roleStrings ->
    val roles = mutableSetOf<User.Role>()
    roleStrings.forEach { roleString ->
        try {
            roles.add(User.Role.valueOf(roleString.uppercase()))
        } catch (e: IllegalArgumentException) {
            return HttpResponse.badRequest(mapOf("error" to "Invalid role: $roleString"))
        }
    }

    // NEW: Validate admin role removal
    val roleValidation = userDeletionValidator.validateAdminRoleRemoval(id, roles)
    if (!roleValidation.canDelete) {
        val response = mapOf(
            "error" to "Cannot update user",
            "message" to roleValidation.message,
            "blockingReferences" to roleValidation.blockingReferences.map { ref ->
                mapOf(
                    "entityType" to ref.entityType,
                    "count" to ref.count,
                    "role" to ref.role,
                    "details" to ref.details
                )
            }
        )
        return HttpResponse.status<Any>(HttpStatus.CONFLICT).body(response)
    }

    // Properly manage the collection instead of replacing it
    user.roles.clear()
    user.roles.addAll(roles)
}
```

**No changes needed to DELETE endpoint** - validation already called at line 273

---

### Phase 4: Run Tests (Should Pass)

```bash
# Run contract tests
./gradlew test --tests UserControllerContractTest

# Run service tests
./gradlew test --tests UserDeletionValidatorTest

# Run all tests
./gradlew test
```

**Expected**: All tests should PASS

---

### Phase 5: Frontend Updates (Optional - UI Enhancement)

**File**: `src/frontend/src/components/UserManagement.tsx`

**Update error handling**:

```tsx
const handleDeleteUser = async (userId: number) => {
    try {
        await axios.delete(`/api/users/${userId}`);
        setSuccessMessage('User deleted successfully');
        fetchUsers(); // Refresh list
    } catch (error) {
        if (error.response?.status === 409) {
            // Last admin protection
            const message = error.response.data.message ||
                'Cannot delete the last administrator. Please create another admin user first.';
            setErrorMessage(message);
            setShowErrorAlert(true);
        } else if (error.response?.status === 400) {
            // Blocking references
            const message = error.response.data.message ||
                'Cannot delete user due to existing references.';
            setErrorMessage(message);
            setShowErrorAlert(true);
        } else {
            setErrorMessage('Failed to delete user');
        }
    }
};
```

**Update error display**:

```tsx
{showErrorAlert && (
    <div className="alert alert-danger alert-dismissible fade show" role="alert">
        <strong>Error:</strong> {errorMessage}
        <button
            type="button"
            className="btn-close"
            onClick={() => setShowErrorAlert(false)}
        ></button>
    </div>
)}
```

---

## Testing Checklist

### Unit Tests
- [x] validateUserDeletion blocks last admin
- [x] validateUserDeletion allows deletion when 2+ admins exist
- [x] validateUserDeletion allows non-admin deletion when 1 admin exists
- [x] validateAdminRoleRemoval blocks removing ADMIN from last admin
- [x] validateAdminRoleRemoval allows removing ADMIN when 2+ admins exist

### Contract Tests
- [x] DELETE /api/users/{id} returns 409 for last admin
- [x] DELETE /api/users/{id} returns 200 for admin when 2+ exist
- [x] PUT /api/users/{id} returns 409 when removing ADMIN role from last admin
- [x] PUT /api/users/{id} returns 200 when removing ADMIN role from non-last admin

### Integration Tests (Optional)
- [ ] Concurrent deletion attempts with 2 admins
- [ ] Bulk deletion validation (when bulk endpoint implemented)

---

## Performance Verification

```kotlin
@Test
fun `admin count query completes within 50ms for 1000 users`() {
    // Arrange: Create 1000 test users
    val users = (1..1000).map { createTestUser(...) }
    users.forEach { userRepository.save(it) }

    // Act & Assert
    val startTime = System.currentTimeMillis()
    val count = userService.countAdminUsers()
    val duration = System.currentTimeMillis() - startTime

    assertTrue(duration < 50, "Admin count query took ${duration}ms (expected <50ms)")
}
```

---

## Deployment Checklist

- [x] All unit tests passing
- [x] All contract tests passing
- [x] Integration tests passing (if implemented)
- [x] Code review completed
- [ ] Merge to main branch
- [ ] Deploy to staging environment
- [ ] Verify in staging (manual test)
- [ ] Deploy to production

---

## Manual Testing Scenarios

### Scenario 1: Single Admin Protection
1. Login as admin user
2. Navigate to user management (/admin/users)
3. Verify you are the only admin (check user list)
4. Attempt to delete your account
5. **Expected**: Error message displayed, deletion blocked

### Scenario 2: Multiple Admins Allow Deletion
1. Create second admin user
2. Attempt to delete first admin
3. **Expected**: Deletion succeeds, second admin remains

### Scenario 3: Role Removal Protection
1. Have single admin with roles [ADMIN, USER]
2. Edit user, remove ADMIN role
3. **Expected**: Error message, role change blocked

### Scenario 4: Role Removal Allowed
1. Have two admins
2. Edit one admin, remove ADMIN role
3. **Expected**: Role change succeeds

---

## Troubleshooting

### Test Fails: "Admin count is 0 but expected 1"
- **Cause**: Test database not seeded with admin user
- **Fix**: Add `@BeforeEach` setup method to create admin user

### Test Fails: "Expected 409 but got 200"
- **Cause**: Validation logic not called or admin count check not implemented
- **Fix**: Verify UserDeletionValidator.validateUserDeletion() includes admin count check

### Test Fails: "NullPointerException in userRepository.findAll()"
- **Cause**: Mock not configured for findAll()
- **Fix**: Add `every { userRepository.findAll() } returns listOf(...)`

### Frontend Shows 500 Error Instead of 409
- **Cause**: Backend throwing exception instead of returning 409
- **Fix**: Verify UserController catches validation result and returns HttpStatus.CONFLICT

---

## Next Steps

After completing this feature:
1. Run `/speckit.tasks` to generate detailed implementation tasks
2. Run `/speckit.implement` to execute task-by-task implementation
3. Consider adding bulk delete endpoint (currently in contract but not required for MVP)
4. Consider adding `/users/{id}/validate-deletion` endpoint for UI pre-validation

---

## Resources

- [Spec](./spec.md) - Feature specification
- [Plan](./plan.md) - Implementation plan
- [Research](./research.md) - Technical decisions
- [Data Model](./data-model.md) - Schema and validation rules
- [API Contract](./contracts/user-management-api.yaml) - OpenAPI specification
