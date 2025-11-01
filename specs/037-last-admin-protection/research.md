# Research: Last Admin Protection

**Feature**: 037-last-admin-protection
**Date**: 2025-10-31
**Purpose**: Research implementation patterns, error handling strategies, and transaction safety for last admin protection

## Research Areas

### 1. Admin Count Query Strategy

**Decision**: Use UserRepository.findAll() with in-memory filtering by ADMIN role

**Rationale**:
- Existing codebase pattern: UserService.getUsersByRole() already uses findAll() + filtering (line 82-84 in UserService.kt)
- Simple and consistent with current architecture
- Performance acceptable: typical user count is <1000, admin count is <100
- No new repository methods needed
- Eager-loaded roles (FetchType.EAGER) make this efficient

**Alternatives considered**:
1. **Custom repository query with COUNT()**
   - Pro: More efficient for large user bases
   - Con: Adds complexity, not needed for current scale
   - Con: Requires new repository method

2. **Caching admin count**
   - Pro: Fastest lookup
   - Con: Complex cache invalidation on role changes
   - Con: Potential consistency issues
   - Con: Over-engineering for validation that runs <1/minute

**Implementation**:
```kotlin
fun countAdminUsers(): Int {
    return userRepository.findAll().count { user ->
        user.roles.contains(User.Role.ADMIN)
    }
}
```

### 2. Error Response Format

**Decision**: Extend existing UserDeletionValidator.ValidationResult pattern with new blocking reference type

**Rationale**:
- Consistent with existing codebase (UserDeletionValidator.kt lines 17-21)
- Already returns structured error with blockingReferences list
- Frontend already handles this format
- HTTP 409 Conflict is semantically correct for "cannot delete due to constraint"

**Alternatives considered**:
1. **New custom exception type**
   - Pro: Type-safe error handling
   - Con: Inconsistent with existing validation pattern
   - Con: More code changes required

2. **Simple string error message**
   - Pro: Simplest approach
   - Con: Less structured, harder for frontend to parse
   - Con: Doesn't follow existing pattern

**Implementation**:
```kotlin
// Add to ValidationResult:
BlockingReference(
    entityType = "SystemConstraint",
    count = 1,
    role = "last_admin",
    details = "Cannot delete the last administrator. At least one ADMIN user must remain in the system."
)
```

### 3. Concurrent Deletion Protection

**Decision**: Service-layer validation with @Transactional annotation, relying on database transaction isolation

**Rationale**:
- Existing UserController.delete() already uses @Transactional (line 264)
- Micronaut's default transaction isolation (READ_COMMITTED) is sufficient
- Validation happens inside transaction before delete
- If two admins deleted concurrently, transactions serialize at database level
- One will succeed, one will fail with constraint violation (admin count check)

**Alternatives considered**:
1. **Pessimistic locking (SELECT FOR UPDATE)**
   - Pro: Explicit lock on user rows
   - Con: Increased complexity, potential deadlocks
   - Con: Not needed given transaction isolation

2. **Optimistic locking with @Version**
   - Pro: Standard JPA pattern
   - Con: Adds version field to User entity (schema change)
   - Con: Overkill for this use case

3. **Distributed lock (Redis, database lock table)**
   - Pro: Strongest consistency guarantee
   - Con: Massive over-engineering for rare scenario
   - Con: Adds external dependency

**Implementation**:
- Keep existing @Transactional annotation
- Perform admin count check inside transaction
- Database serializes concurrent modifications automatically

### 4. Bulk Delete Validation Strategy

**Decision**: Validate entire bulk operation before execution, fail-fast with list of affected admins

**Rationale**:
- Follows existing pattern from AssetBulkDeleteService (atomic all-or-nothing)
- Better UX than partial success
- Simpler error handling
- Prevents accidental admin removal in bulk operations

**Alternatives considered**:
1. **Skip admin users during bulk delete**
   - Pro: Allows operation to proceed
   - Con: Surprising behavior - user expects deletion
   - Con: Silent failures are anti-pattern

2. **Delete non-admins first, then validate last admin**
   - Pro: Maximizes deletions
   - Con: Complex rollback logic if last admin would be deleted
   - Con: Partial success is confusing

**Implementation**:
```kotlin
fun validateBulkUserDeletion(userIds: List<Long>): ValidationResult {
    // Find all users to delete
    val usersToDelete = userIds.mapNotNull { userRepository.findById(it).orElse(null) }

    // Count admins in deletion set
    val adminsToDelete = usersToDelete.count { it.roles.contains(User.Role.ADMIN) }

    // Count total admins
    val totalAdmins = countAdminUsers()

    // Check if operation would remove all admins
    if (adminsToDelete >= totalAdmins) {
        return ValidationResult(
            canDelete = false,
            blockingReferences = listOf(...),
            message = "Bulk deletion would remove all ADMIN users..."
        )
    }

    return ValidationResult(canDelete = true, blockingReferences = emptyList(), message = "OK")
}
```

### 5. Role Update Protection

**Decision**: Extend UserController.update() to validate ADMIN role removal using same admin count logic

**Rationale**:
- Prevents indirect path to zero admins (remove role instead of delete user)
- Uses same validation logic as deletion
- Consistent error response format
- Minimal code changes (validation in service layer)

**Alternatives considered**:
1. **Only protect deletion, ignore role changes**
   - Pro: Simpler implementation
   - Con: Incomplete protection - same outcome via different path
   - Con: Spec explicitly requires role protection (FR-008)

2. **Separate validator for role changes**
   - Pro: Single responsibility principle
   - Con: Duplicate admin count logic
   - Con: More files to maintain

**Implementation**:
```kotlin
// In UserDeletionValidator (rename to UserAdminProtectionValidator):
fun validateAdminRoleRemoval(userId: Long, newRoles: Set<User.Role>): ValidationResult {
    val user = userRepository.findById(userId).orElse(null) ?: return ValidationResult(...)

    // Check if removing ADMIN role
    val hasAdminNow = user.roles.contains(User.Role.ADMIN)
    val willHaveAdmin = newRoles.contains(User.Role.ADMIN)

    if (hasAdminNow && !willHaveAdmin) {
        // Check if this is the last admin
        if (countAdminUsers() <= 1) {
            return ValidationResult(canDelete = false, ...)
        }
    }

    return ValidationResult(canDelete = true, ...)
}
```

### 6. Frontend Error Display

**Decision**: Update UserManagement.tsx to detect 409 status code and display prominent error alert

**Rationale**:
- Existing component already handles error responses
- Bootstrap Alert component available for prominent display
- 409 status clearly distinguishes this error from others (404, 500)
- Actionable error message guides user to add admin before deletion

**Alternatives considered**:
1. **Modal dialog for error**
   - Pro: Most prominent, blocks interaction
   - Con: Annoying for expected validation failures
   - Con: Requires modal infrastructure

2. **Toast notification**
   - Pro: Non-blocking, modern UX
   - Con: May disappear before user reads message
   - Con: Less prominent for critical system error

**Implementation**:
```tsx
// In UserManagement.tsx:
if (error.response?.status === 409) {
    setErrorMessage(
        error.response.data.message +
        " Please create another admin user before deleting this one."
    );
    setShowAlert(true); // Bootstrap danger alert
}
```

## Best Practices Applied

### Kotlin/Micronaut Patterns
1. **Service Layer Validation**: Business rules in service, not controller
2. **@Transactional Consistency**: Database transactions for atomic operations
3. **Singleton Services**: Micronaut dependency injection with @Singleton
4. **Structured Error Responses**: Data classes for type-safe error handling

### Testing Patterns
1. **Contract-First Testing**: HTTP contract tests before implementation
2. **MockK for Service Tests**: Idiomatic Kotlin mocking
3. **Integration Tests**: Full stack tests with test database

### Security Patterns
1. **Defense in Depth**: Validation at multiple layers (service + controller)
2. **Fail-Safe Defaults**: Block deletion if uncertain
3. **Clear Error Messages**: Help user understand and resolve issue

## Performance Considerations

### Current Scale
- Typical user count: <1000 users
- Typical admin count: 5-20 admins
- User deletion frequency: <1 per hour
- Bulk deletion frequency: rare (monthly/quarterly cleanup)

### Validation Performance
- Admin count query: O(n) where n = total users, typically <10ms for 1000 users
- findAll() with EAGER roles: Single query with JOIN, ~5ms
- Acceptable overhead for rare operation (deletion)

### Optimization Opportunities (Not Implemented - YAGNI)
1. Cache admin count (invalidate on role change)
2. Custom COUNT(*) query for large user bases (>10,000)
3. Database constraint (CHECK admin_count >= 1) - not portable across databases

## Dependencies

### Existing Code
- `User` entity (src/backendng/.../domain/User.kt)
- `UserRepository` (Micronaut Data)
- `UserService` (src/backendng/.../service/UserService.kt)
- `UserDeletionValidator` (src/backendng/.../service/UserDeletionValidator.kt)
- `UserController` (src/backendng/.../controller/UserController.kt)

### New Code Required
- `LastAdminProtectionException` (custom exception, optional)
- Enhanced validation methods in UserDeletionValidator
- Admin count helper method in UserService
- Frontend error handling in UserManagement.tsx

### No External Dependencies Added
- Uses existing Micronaut, Hibernate, MariaDB stack
- No new libraries required
- No schema changes required

## Risk Assessment

### Low Risk
- ✅ No schema changes - no migration risk
- ✅ Extends existing validation pattern - low refactoring risk
- ✅ Service layer validation - easy to test
- ✅ Consistent with existing architecture

### Medium Risk
- ⚠️ Concurrent deletion edge case - mitigated by transactions
- ⚠️ Bulk delete complexity - validated with integration tests

### Mitigations
1. Comprehensive unit tests for edge cases
2. Integration tests for concurrent scenarios
3. Clear error messages guide users
4. Validation happens before any database changes (fail-fast)

## Open Questions (Resolved)

1. **Q: Should we count only active/enabled users as admins?**
   - A: Current codebase has no "disabled" concept - all users in database are active
   - Action: Count all users with ADMIN role, no filtering

2. **Q: Should we validate during user creation (prevent removing ADMIN role)?**
   - A: No - creation always adds users, never removes. Focus on deletion and role updates.

3. **Q: What about OAuth/SSO users?**
   - A: OAuth users are still User entities with roles. Same protection applies.

## Conclusion

All research complete. No NEEDS CLARIFICATION items remaining. Ready to proceed to Phase 1 (Data Model & Contracts).
