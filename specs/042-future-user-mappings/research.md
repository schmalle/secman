# Research: Future User Mapping Support

**Feature**: 042-future-user-mappings | **Date**: 2025-11-07

## Overview

This document consolidates research findings for extending UserMapping functionality to support future users (users who don't yet exist in the system). All technical unknowns from the Technical Context section have been investigated and resolved.

## Research Areas

### 1. Nullable Foreign Key Pattern in Hibernate JPA

**Decision**: Use nullable `@ManyToOne` relationship with optional fetch strategy

**Rationale**:
- Hibernate JPA fully supports nullable foreign keys via `@ManyToOne(optional = true)`
- This allows user_id column to be NULL in database while maintaining referential integrity when non-null
- Existing pattern in Secman codebase for optional relationships (used in other entities)
- No database constraints violated - FK constraint only enforces referential integrity when value is present

**Implementation Pattern**:
```kotlin
@Entity
@Table(name = "user_mapping")
data class UserMapping(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true, nullable = false)
    val email: String,

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    val user: User? = null,  // Nullable for future user mappings

    @Column(name = "applied_at", nullable = true)
    val appliedAt: Instant? = null,

    // ... other fields
)
```

**Alternatives Considered**:
- Separate table for future user mappings - Rejected: Adds complexity, requires data migration on application
- Store user_id as String instead of FK - Rejected: Loses referential integrity benefits
- Use inheritance/polymorphism - Rejected: Over-engineered for simple nullable relationship

### 2. User Creation Hook Points in Micronaut

**Decision**: Use Micronaut `@EventListener` for `UserCreatedEvent` to trigger mapping application

**Rationale**:
- Micronaut provides event-driven architecture via `@EventListener` annotation
- Decouples mapping application logic from user creation logic
- Non-blocking async execution possible via `@Async` annotation
- Existing pattern in Secman for entity lifecycle events (used in notification system Feature 035)
- Allows multiple listeners for future extensibility

**Implementation Pattern**:
```kotlin
// In UserService.kt
fun createUser(user: User): User {
    val savedUser = userRepository.save(user)
    eventPublisher.publishEvent(UserCreatedEvent(savedUser))
    return savedUser
}

// In UserMappingService.kt
@Singleton
class UserMappingService(
    private val userMappingRepository: UserMappingRepository
) {
    @EventListener
    @Async  // Non-blocking execution
    fun onUserCreated(event: UserCreatedEvent) {
        applyFutureUserMapping(event.user)
    }

    private fun applyFutureUserMapping(user: User) {
        val mapping = userMappingRepository.findByEmailIgnoreCase(user.email)
        if (mapping != null && mapping.appliedAt == null) {
            // Apply mapping logic
        }
    }
}
```

**Alternatives Considered**:
- Direct method call in UserService - Rejected: Creates tight coupling, hard to test
- AOP/Interceptors - Rejected: Overkill for simple lifecycle hook
- Database triggers - Rejected: Business logic should stay in application layer

### 3. Case-Insensitive Email Matching Strategies

**Decision**: Use database-level case-insensitive collation + application-level normalization

**Rationale**:
- MariaDB supports case-insensitive collation (utf8mb4_unicode_ci) which is faster than LOWER() functions
- Application-level normalization (toLowerCase()) provides defense-in-depth
- Existing email columns in Secman likely already use case-insensitive collation
- Repository query methods can use `IgnoreCase` suffix for clarity

**Implementation Pattern**:
```kotlin
interface UserMappingRepository : CrudRepository<UserMapping, Long> {
    fun findByEmailIgnoreCase(email: String): UserMapping?
    fun findByAppliedAtIsNull(): List<UserMapping>  // Future user mappings
    fun findByAppliedAtIsNotNull(): List<UserMapping>  // Applied history
}

// In service layer
fun findMappingForUser(email: String): UserMapping? {
    return userMappingRepository.findByEmailIgnoreCase(email.trim().lowercase())
}
```

**Alternatives Considered**:
- Always use LOWER() in SQL queries - Rejected: Performance penalty, doesn't leverage database collation
- Case-sensitive matching only - Rejected: Violates FR-012 requirement
- Full normalization library (Apache Commons EmailValidator) - Rejected: Overkill, existing validation sufficient

### 4. Conflict Resolution During User Creation

**Decision**: Implement precedence logic with explicit conflict detection and rejection

**Rationale**:
- Clarification Q2 established pre-existing mapping wins
- Explicit conflict detection provides better error messages for debugging
- Log conflicts for audit trail even when rejected (minimal logging per FR-013)
- Idempotent operation - repeated user creation with same email yields same result

**Implementation Pattern**:
```kotlin
fun applyFutureUserMapping(user: User, newMapping: UserMapping? = null): UserMapping? {
    val existingMapping = userMappingRepository.findByEmailIgnoreCase(user.email)

    if (existingMapping != null && existingMapping.appliedAt == null) {
        // Future user mapping exists - apply it
        if (newMapping != null && newMapping != existingMapping) {
            logger.warn("Conflict: Pre-existing mapping for ${user.email} takes precedence")
        }
        existingMapping.appliedAt = Instant.now()
        existingMapping.user = user
        return userMappingRepository.save(existingMapping)
    }

    return null  // No future user mapping found
}
```

**Alternatives Considered**:
- Throw exception on conflict - Rejected: Disrupts user creation flow
- Merge both mappings - Rejected: Violates clarification Q2 decision
- Last-write-wins - Rejected: Risk of data loss, violates clarification Q2

### 5. UI Tab Implementation in React

**Decision**: Use React state with conditional rendering for tab switching

**Rationale**:
- Bootstrap 5.3 provides built-in tab components (nav-tabs, tab-content)
- React state management for active tab selection
- Lazy loading for "Applied History" tab (only fetch data when tab is clicked)
- Existing pattern in Secman frontend (used in Asset Management, Vulnerability views)

**Implementation Pattern**:
```typescript
const UserMappingManagement = () => {
  const [activeTab, setActiveTab] = useState<'current' | 'history'>('current');
  const [currentMappings, setCurrentMappings] = useState([]);
  const [appliedHistory, setAppliedHistory] = useState([]);

  useEffect(() => {
    if (activeTab === 'current') {
      fetchCurrentMappings();
    } else {
      fetchAppliedHistory();
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
      <div className="tab-content">
        {activeTab === 'current' ? (
          <CurrentMappingsTable data={currentMappings} />
        ) : (
          <AppliedHistoryTable data={appliedHistory} />
        )}
      </div>
    </div>
  );
};
```

**Alternatives Considered**:
- React Router for tab URLs - Rejected: Over-engineered, adds routing complexity
- Third-party tab library - Rejected: Bootstrap tabs sufficient
- Single table with filter dropdown - Rejected: Violates clarification Q5 decision

### 6. Performance Optimization for 10,000+ Mappings

**Decision**: Database indexing strategy + pagination for UI

**Rationale**:
- Index on `email` (unique constraint implies index)
- Index on `applied_at` for efficient filtering (NULL vs NOT NULL)
- Pagination on frontend (show 50 mappings per page)
- Query optimization using covering indexes
- NFR-002 requires support for 10,000+ mappings without degradation

**Implementation**:
```kotlin
@Entity
@Table(
    name = "user_mapping",
    indexes = [
        Index(name = "idx_email", columnList = "email", unique = true),
        Index(name = "idx_applied_at", columnList = "applied_at")
    ]
)
data class UserMapping(...)

// Repository with pagination
interface UserMappingRepository : CrudRepository<UserMapping, Long> {
    fun findByAppliedAtIsNull(pageable: Pageable): Page<UserMapping>
    fun findByAppliedAtIsNotNull(pageable: Pageable): Page<UserMapping>
}
```

**Alternatives Considered**:
- Load all mappings in memory - Rejected: Doesn't scale beyond 10,000
- Elasticsearch for search - Rejected: Over-engineered, adds infrastructure dependency
- Virtual scrolling - Rejected: Pagination simpler and sufficient

### 7. Audit Logging Strategy

**Decision**: Minimal application logging (timestamp + email) using SLF4J

**Rationale**:
- Clarification Q3 established minimal logging requirement
- Use existing SLF4J logging framework (standard in Micronaut)
- Log to application logs (not dedicated audit table) per clarification
- INFO level for successful operations, WARN for conflicts

**Implementation**:
```kotlin
private val logger = LoggerFactory.getLogger(UserMappingService::class.java)

fun applyFutureUserMapping(user: User): UserMapping? {
    val mapping = userMappingRepository.findByEmailIgnoreCase(user.email)
    if (mapping != null && mapping.appliedAt == null) {
        mapping.appliedAt = Instant.now()
        mapping.user = user
        val saved = userMappingRepository.save(mapping)
        logger.info("Applied future user mapping: email=${user.email}, timestamp=${Instant.now()}")
        return saved
    }
    return null
}
```

**Alternatives Considered**:
- Dedicated audit table - Rejected: Violates clarification Q3 (minimal logging)
- Structured logging (JSON) - Rejected: Not required for minimal logging
- No logging - Rejected: Violates FR-013

## Best Practices Summary

1. **Nullable Relationships**: Use `@ManyToOne(optional = true)` for optional foreign keys
2. **Event-Driven Architecture**: Use `@EventListener` for entity lifecycle hooks
3. **Case-Insensitive Matching**: Combine database collation + application normalization
4. **Conflict Resolution**: Explicit detection with precedence logic
5. **UI State Management**: React state for tab switching, lazy loading for data
6. **Performance**: Database indexing + pagination for scalability
7. **Logging**: Application-level minimal logging with SLF4J

## Technology Validation

All technologies listed in Technical Context are validated:
- ✅ Kotlin 2.2.21 / Java 21: Compatible with nullable FK pattern, event listeners
- ✅ Micronaut 4.10: Provides @EventListener, @Async support
- ✅ Hibernate JPA: Supports nullable @ManyToOne, @Index annotations
- ✅ MariaDB 12: Supports nullable FK, case-insensitive collation, indexes
- ✅ React 19: Compatible with useState hook pattern
- ✅ Bootstrap 5.3: Provides nav-tabs component
- ✅ Axios: Handles paginated API responses

## Open Questions: NONE

All unknowns from Technical Context resolved. Ready for Phase 1 (Design & Contracts).
