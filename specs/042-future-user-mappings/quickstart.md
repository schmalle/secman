# Quickstart: Future User Mapping Support

**Feature**: 042-future-user-mappings | **Date**: 2025-11-07

## Overview

This guide helps developers quickly understand and implement the future user mapping feature. It provides the essential information needed to start coding.

## What This Feature Does

**Problem**: Administrators must manually configure user mappings after each new user is created, causing administrative overhead and potential security gaps during onboarding.

**Solution**: Allow administrators to upload user mappings for users who don't yet exist. When those users are eventually created (manually or via OAuth), their mappings are automatically applied, granting immediate asset access.

## Key Concepts

### Future User Mapping

A mapping entry with:
- **email**: User email (unique across all mappings)
- **user_id**: NULL (no user exists yet)
- **applied_at**: NULL (not yet applied)
- **aws_account_id** and/or **domain**: Access control values

### Applied Historical Mapping

A mapping entry with:
- **email**: User email
- **user_id**: Reference to created user
- **applied_at**: Timestamp when mapping was applied (NOT NULL)

### State Transition

```
Upload mapping for future user
   → User doesn't exist yet (user_id = NULL, applied_at = NULL)
   → User gets created (manual or OAuth)
   → Mapping automatically applied (user_id = <id>, applied_at = NOW)
   → Mapping retained as historical record
```

## Implementation Checklist

### Phase 1: Backend Data Model ✅ (Planned)

- [ ] Modify `UserMapping` entity:
  - [ ] Add `appliedAt: Instant?` field (nullable)
  - [ ] Make `user: User?` nullable (`@ManyToOne(optional = true)`)
  - [ ] Add unique constraint on `email`
  - [ ] Add index on `applied_at`
  - [ ] Add `isFutureMapping()` helper method
  - [ ] Add `isAppliedMapping()` helper method

- [ ] Extend `UserMappingRepository`:
  - [ ] Add `findByEmailIgnoreCase(email: String): UserMapping?`
  - [ ] Add `findByAppliedAtIsNull(pageable): Page<UserMapping>` (Current tab)
  - [ ] Add `findByAppliedAtIsNotNull(pageable): Page<UserMapping>` (History tab)
  - [ ] Add count methods for pagination

### Phase 2: Backend Business Logic

- [ ] Create `UserMappingService`:
  - [ ] Implement `applyFutureUserMapping(user: User): UserMapping?`
  - [ ] Implement case-insensitive email lookup
  - [ ] Implement conflict resolution (pre-existing wins)
  - [ ] Add minimal logging (timestamp + email)

- [ ] Modify `UserService`:
  - [ ] Create `UserCreatedEvent` class
  - [ ] Publish event after user creation
  - [ ] Add event listener for mapping application (`@EventListener`)
  - [ ] Call `UserMappingService.applyFutureUserMapping()`

- [ ] Extend `ImportService`:
  - [ ] Remove user existence check (allow future users)
  - [ ] Handle mixed uploads (existing + future users)
  - [ ] Maintain duplicate handling (last in file wins)

### Phase 3: Backend API (Optional - If New Endpoints Needed)

- [ ] Add endpoints (if not reusing existing):
  - [ ] `GET /api/user-mappings` (current mappings, paginated)
  - [ ] `GET /api/user-mappings/history` (applied history, paginated)
  - [ ] `DELETE /api/user-mappings/{id}` (delete future mapping)

- [ ] Update existing endpoints:
  - [ ] Verify `/api/import/upload-user-mappings` works with future users
  - [ ] Verify `/api/import/upload-user-mappings-csv` works with future users

### Phase 4: Frontend UI

- [ ] Modify `UserMappingManagement.tsx`:
  - [ ] Add tab state (`useState<'current' | 'history'>`)
  - [ ] Add Bootstrap nav-tabs component
  - [ ] Implement lazy loading for tabs
  - [ ] Add "User Exists" indicator column (badge or icon)
  - [ ] Add "Applied At" column for history tab
  - [ ] Add pagination controls

- [ ] Update `userMappingService.ts`:
  - [ ] Add `fetchCurrentMappings(page, size)` API call
  - [ ] Add `fetchAppliedHistory(page, size)` API call

### Phase 5: Testing (TDD - Write First!)

- [ ] Unit Tests:
  - [ ] `UserMappingService.applyFutureUserMapping()` tests
  - [ ] Email case-insensitive matching tests
  - [ ] Conflict resolution tests (pre-existing wins)
  - [ ] Mapping state lifecycle tests

- [ ] Integration Tests:
  - [ ] End-to-end user creation with mapping application
  - [ ] Mixed upload (existing + future users)
  - [ ] Tab data fetching (current + history)

- [ ] E2E Tests (Playwright):
  - [ ] Upload future user mappings
  - [ ] Create user and verify mapping applied
  - [ ] Switch tabs and verify data display
  - [ ] Delete future user mapping

## Critical Code Snippets

### 1. UserMapping Entity Extension

```kotlin
@Entity
@Table(
    name = "user_mapping",
    indexes = [
        Index(name = "idx_email", columnList = "email", unique = true),
        Index(name = "idx_applied_at", columnList = "applied_at")
    ]
)
data class UserMapping(
    // ... existing fields ...

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    val user: User? = null,  // NOW NULLABLE

    @Column(name = "applied_at", nullable = true)
    val appliedAt: Instant? = null,  // NEW FIELD

    // ... other fields ...
) {
    fun isFutureMapping(): Boolean = appliedAt == null && user == null
    fun isAppliedMapping(): Boolean = appliedAt != null
}
```

### 2. User Creation Event Listener

```kotlin
@Singleton
class UserMappingService(
    private val userMappingRepository: UserMappingRepository
) {
    private val logger = LoggerFactory.getLogger(UserMappingService::class.java)

    @EventListener
    @Async
    fun onUserCreated(event: UserCreatedEvent) {
        applyFutureUserMapping(event.user)
    }

    fun applyFutureUserMapping(user: User): UserMapping? {
        val mapping = userMappingRepository.findByEmailIgnoreCase(user.email)

        if (mapping != null && mapping.appliedAt == null) {
            val updated = mapping.copy(
                user = user,
                appliedAt = Instant.now()
            )
            val saved = userMappingRepository.save(updated)
            logger.info("Applied future user mapping: email=${user.email}, timestamp=${Instant.now()}")
            return saved
        }

        return null
    }
}
```

### 3. UI Tab Component

```typescript
const UserMappingManagement = () => {
  const [activeTab, setActiveTab] = useState<'current' | 'history'>('current');
  const [currentMappings, setCurrentMappings] = useState<UserMapping[]>([]);
  const [appliedHistory, setAppliedHistory] = useState<UserMapping[]>([]);

  useEffect(() => {
    if (activeTab === 'current') {
      userMappingService.fetchCurrentMappings(0, 50).then(setCurrentMappings);
    } else {
      userMappingService.fetchAppliedHistory(0, 50).then(setAppliedHistory);
    }
  }, [activeTab]);

  return (
    <div>
      <ul className="nav nav-tabs">
        <li className="nav-item">
          <button
            className={`nav-link ${activeTab === 'current' ? 'active' : ''}`}
            onClick={() => setActiveTab('current')}
          >
            Current Mappings
          </button>
        </li>
        <li className="nav-item">
          <button
            className={`nav-link ${activeTab === 'history' ? 'active' : ''}`}
            onClick={() => setActiveTab('history')}
          >
            Applied History
          </button>
        </li>
      </ul>
      {/* Table rendering... */}
    </div>
  );
};
```

## Common Pitfalls & Solutions

### Pitfall 1: Forgetting Nullable User FK

**Problem**: Hibernate throws constraint violation when trying to save mapping without user.

**Solution**: Use `@ManyToOne(optional = true)` and `@JoinColumn(nullable = true)`.

### Pitfall 2: Case-Sensitive Email Matching

**Problem**: Mapping not applied because "John@Example.com" != "john@example.com".

**Solution**:
- Use `findByEmailIgnoreCase()` repository method
- Normalize email to lowercase in service layer
- Ensure database collation is case-insensitive (utf8mb4_unicode_ci)

### Pitfall 3: Not Publishing User Created Event

**Problem**: Mapping never applied because event listener not triggered.

**Solution**:
- Always publish `UserCreatedEvent` after saving user
- Test event listener with integration tests

### Pitfall 4: Loading All Mappings Without Pagination

**Problem**: UI freezes when loading 10,000+ mappings.

**Solution**:
- Always use paginated repository methods
- Implement pagination UI (page size 50)
- Add indexes on filter columns

## Testing Strategy

### Unit Tests (Write First!)

Test each component in isolation:
- `UserMappingService.applyFutureUserMapping()` with mocked repository
- Email matching logic (case variations)
- Conflict resolution (pre-existing wins)
- State determination methods (`isFutureMapping()`, `isAppliedMapping()`)

### Integration Tests

Test cross-component interactions:
- Create user → Event published → Mapping applied
- Upload file with mixed users → Some applied, some future
- Tab queries return correct filtered data

### E2E Tests (Playwright)

Test complete user flows:
1. Admin uploads mapping for future user
2. Admin creates user manually
3. Admin verifies mapping applied in "Applied History" tab
4. Admin sees asset access granted immediately

## Performance Targets (NFR Requirements)

- ✅ **NFR-001**: Mapping application < 2 seconds after user creation
- ✅ **NFR-002**: Support 10,000+ future user mappings without degradation
- ✅ **NFR-003**: <1KB per mapping storage

**How to Achieve**:
- Database indexes on `email` and `applied_at`
- Pagination (50 items per page)
- Event-driven async processing (`@Async`)
- Lazy loading for tab data

## Quick Reference: File Locations

| Component | File Path |
|-----------|-----------|
| Entity | `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt` |
| Repository | `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt` |
| Service | `src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt` |
| User Service | `src/backendng/src/main/kotlin/com/secman/service/UserService.kt` |
| UI Component | `src/frontend/src/components/UserMappingManagement.tsx` |
| API Service | `src/frontend/src/services/userMappingService.ts` |
| Unit Tests | `src/backendng/src/test/kotlin/com/secman/service/UserMappingServiceTest.kt` |
| Integration Tests | `src/backendng/src/test/kotlin/com/secman/integration/UserMappingIntegrationTest.kt` |
| E2E Tests | `src/frontend/tests/e2e/user-mappings.spec.ts` |

## Dependencies & Integration Points

### Existing Features This Extends

- **Feature 013/016**: UserMapping entity and import functionality
- **Feature 041**: OAuth auto-provisioning (user creation flow)

### Integration Points

1. **User Creation Flow**: `UserService.createUser()` must publish `UserCreatedEvent`
2. **Import Flow**: `ImportService` must handle future users without throwing errors
3. **Access Control**: Unified access control system automatically uses mappings after application

## Next Steps

1. **Read** the full specifications:
   - [spec.md](./spec.md) - Feature requirements
   - [data-model.md](./data-model.md) - Detailed entity design
   - [research.md](./research.md) - Technical decisions

2. **Generate tasks**: Run `/speckit.tasks` to create implementation task list

3. **Start coding**: Follow TDD - write tests first, then implementation

4. **Verify compliance**: Re-check constitution gates after implementation

## Questions?

- Review [research.md](./research.md) for technical decisions and rationale
- Check [data-model.md](./data-model.md) for database schema details
- See [contracts/user-mapping-api.yaml](./contracts/user-mapping-api.yaml) for API specifications
