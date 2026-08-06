# Research: User Mapping Management in User Edit Interface

**Feature**: 017-user-mapping-management  
**Phase**: 0 - Research & Design Decisions  
**Date**: 2025-10-13

## Overview

This document consolidates research findings and design decisions for implementing user mapping management within the user edit interface. All technical unknowns have been resolved through analysis of the existing codebase and application architecture.

## Research Tasks

### 1. Backend API Design Patterns

**Research Question**: What RESTful patterns should be used for nested resource management (user → mappings)?

**Decision**: Use nested resource URLs under `/api/users/{userId}/mappings`

**Rationale**:
- Aligns with RESTful conventions for sub-resources
- Clearly expresses parent-child relationship (mappings belong to user context)
- Existing Secman backend uses similar patterns (e.g., risk assessments → requirements → files)
- Simplifies authorization (userId in path enables easy access control)

**Alternatives Considered**:
1. **Flat structure** (`/api/mappings?email={email}`): Rejected because it obscures the relationship and makes authorization more complex
2. **Separate controller** (`/api/user-mappings/{userId}`): Rejected because it fragments user management; better to keep all user operations in UserController

**Implementation Pattern**:
```
GET    /api/users/{userId}/mappings          # List all mappings for user
POST   /api/users/{userId}/mappings          # Add new mapping
PUT    /api/users/{userId}/mappings/{id}     # Update existing mapping
DELETE /api/users/{userId}/mappings/{id}     # Delete mapping
```

### 2. Frontend Component Architecture

**Research Question**: Should mapping management be inline in UserManagement.tsx or a separate routed page?

**Decision**: Inline component within user edit dialog

**Rationale**:
- Feature spec explicitly calls for management "within the user edit dialog"
- Maintains context - admin is already viewing/editing the user
- Reduces navigation complexity - no need to leave edit dialog
- Follows existing pattern - user edit dialog already includes role and workgroup management inline
- Better UX for bulk operations - can see user details while managing mappings

**Alternatives Considered**:
1. **Separate page/route** (`/admin/users/{id}/mappings`): Rejected because it requires navigation away from user edit context
2. **Modal within modal**: Rejected due to poor UX (nested modals are confusing)

**Implementation Pattern**:
- Create reusable `UserMappingManager` component
- Embed it as a section within the existing user edit dialog (after roles/workgroups)
- Component accepts `userId` and `userEmail` as props
- Component manages its own state for CRUD operations

### 3. Validation Strategy

**Research Question**: Where should mapping validation occur (frontend, backend, or both)?

**Decision**: Validation at both frontend (immediate feedback) and backend (security/integrity)

**Rationale**:
- **Frontend validation**: Provides immediate user feedback, prevents unnecessary API calls
- **Backend validation**: Enforces security and data integrity at the source of truth
- Existing UserMapping entity already has validation annotations (@Email, @Pattern)
- Follows defense-in-depth security principle

**Validation Rules** (existing from UserMapping entity):
- **Email**: Standard email format validation via Jakarta `@Email` annotation
- **AWS Account ID**: Exactly 12 numeric digits via `@Pattern(regexp = "^\\d{12}$")`
- **Domain**: Lowercase letters, numbers, dots, hyphens via `@Pattern(regexp = "^[a-z0-9.-]+$")`
- **Business rule**: At least one of awsAccountId or domain must be non-null (checked in service layer)
- **Uniqueness**: Composite unique constraint on (email, awsAccountId, domain) enforced by database

**Implementation**:
- Frontend: Reuse validation patterns from existing forms, add real-time field validation
- Backend: Leverage existing domain annotations, add service-level business rule check

### 4. Duplicate Detection Strategy

**Research Question**: How to prevent duplicate mappings efficiently?

**Decision**: Use existing repository method `existsByEmailAndAwsAccountIdAndDomain()`

**Rationale**:
- UserMappingRepository already provides this exact method (implemented in Feature 013)
- Database has unique constraint as final enforcement
- Check before save prevents database constraint violation errors
- Provides better error messages to user

**Implementation**:
- Service layer calls `existsByEmailAndAwsAccountIdAndDomain()` before save/update
- Return specific error message "This mapping already exists" to frontend
- Frontend displays error inline in form

### 5. Performance Optimization for Large Mapping Lists

**Research Question**: How to handle users with 100+ mappings without performance degradation?

**Decision**: Implement pagination in backend API, with frontend load-more or virtual scrolling

**Rationale**:
- Spec requires support for up to 100 mappings per user
- Loading 100+ items upfront is acceptable for initial load (<2s requirement)
- Pagination provides future scalability if limits increase
- Micronaut provides built-in pagination support via `Pageable` parameter

**Alternatives Considered**:
1. **Load all always**: Acceptable for current scale (100 items), but limits future growth
2. **Virtual scrolling only**: More complex implementation, not needed unless list grows significantly

**Implementation**:
```kotlin
// Backend endpoint signature
@Get("/users/{userId}/mappings")
fun listMappings(
    userId: Long,
    @QueryValue(defaultValue = "0") page: Int,
    @QueryValue(defaultValue = "100") size: Int
): Page<UserMapping>
```

Frontend initially loads all (page=0, size=100), can implement pagination if needed.

### 6. Concurrent Edit Handling

**Research Question**: What strategy for handling concurrent edits to mappings by multiple admins?

**Decision**: Last-write-wins with no explicit locking, relying on database unique constraint

**Rationale**:
- Optimistic locking (versioning) adds complexity for minimal benefit in this use case
- Mapping additions/deletions are idempotent operations
- Database unique constraint prevents duplicate creation
- Delete operations are inherently safe (404 if already deleted)
- Admin operations are relatively infrequent
- Spec specifies "handle gracefully" not "prevent entirely"

**Edge Case Handling**:
- If Mapping A is deleted while Admin B is editing it: Backend returns 404, frontend shows "Mapping no longer exists"
- If two admins add same mapping simultaneously: One succeeds, other gets "Already exists" error
- Frontend refreshes mapping list after each operation to show current state

**Implementation**: No special concurrent edit handling beyond standard error responses

### 7. Error Handling Patterns

**Research Question**: What error response format and user messaging strategy?

**Decision**: Use existing Secman error response pattern, show user-friendly messages in UI

**Rationale**:
- Consistency with existing backend error handling
- UserController already uses structured error responses
- Frontend already has error display patterns

**Error Response Format** (existing pattern):
```kotlin
return HttpResponse.badRequest(mapOf(
    "error" to "Human-readable error message"
))
```

**Common Errors**:
- 400 Bad Request: Validation failure, duplicate mapping, missing required fields
- 403 Forbidden: Non-admin attempting mapping operations
- 404 Not Found: User or mapping doesn't exist
- 500 Internal Server Error: Database or unexpected errors

**Frontend Error Display**:
- Inline field errors for validation
- Toast/alert for operation errors
- Graceful degradation if mappings fail to load

## Technology Decisions

### Backend Service Layer

**Decision**: Create dedicated `UserMappingService` for business logic

**Rationale**:
- Separates concerns (controller handles HTTP, service handles business logic)
- Makes testing easier (can test service independently)
- Reusable across multiple controllers if needed
- Follows existing Secman architecture (e.g., FileService, SecurityService)

**Service Responsibilities**:
- Validate business rules (at least one of domain/AWS ID required)
- Check for duplicates before save
- Perform CRUD operations via repository
- Format/transform data for API responses

### Frontend State Management

**Decision**: Local component state with React hooks (useState, useEffect)

**Rationale**:
- Mapping data is scoped to single user being edited (no global state needed)
- React hooks sufficient for CRUD operations
- Matches existing patterns in UserManagement component
- No need for Redux/Context API for this isolated feature

**State Structure**:
```typescript
const [mappings, setMappings] = useState<UserMapping[]>([])
const [loading, setLoading] = useState(false)
const [error, setError] = useState<string | null>(null)
const [editingMapping, setEditingMapping] = useState<UserMapping | null>(null)
```

### API Communication

**Decision**: Extend existing axios-based API utilities in utils/api.ts

**Rationale**:
- Frontend already uses axios for API calls
- Existing auth token injection via interceptors
- Consistent error handling patterns
- Reusable across components

**API Utility Functions**:
```typescript
// Add to src/frontend/src/utils/api.ts
export const userMappingAPI = {
  list: (userId: number) => axios.get(`/api/users/${userId}/mappings`),
  create: (userId: number, mapping: CreateMappingRequest) => 
    axios.post(`/api/users/${userId}/mappings`, mapping),
  update: (userId: number, mappingId: number, mapping: UpdateMappingRequest) => 
    axios.put(`/api/users/${userId}/mappings/${mappingId}`, mapping),
  delete: (userId: number, mappingId: number) => 
    axios.delete(`/api/users/${userId}/mappings/${mappingId}`)
}
```

## Best Practices Applied

### RESTful API Design
- Resource-oriented URLs
- Appropriate HTTP verbs (GET, POST, PUT, DELETE)
- Meaningful HTTP status codes
- Consistent error response format
- Nested resources for parent-child relationships

### Security
- RBAC enforcement via @Secured("ADMIN")
- Input validation at multiple layers
- Sanitization via domain annotations
- Database constraints as final enforcement
- No sensitive data in error messages

### Testing Strategy
- **Contract Tests**: OpenAPI spec defines expected API behavior
- **Integration Tests**: Test controller → service → repository flow
- **Unit Tests**: Test service business logic in isolation
- **E2E Tests**: Test complete user workflow with Playwright

### Code Organization
- Separation of concerns (controller/service/repository)
- Single Responsibility Principle for each class
- Reuse existing components where possible
- DRY principle (don't duplicate validation logic)

## Dependencies

### Existing Dependencies (No Changes Required)
- Micronaut 4.4 (web framework)
- Hibernate JPA (ORM)
- MariaDB driver (database)
- Jackson/Micronaut Serde (JSON serialization)
- JUnit 5 + MockK (testing)
- Axios (frontend HTTP client)
- React 19 (frontend UI library)
- Bootstrap 5.3 (frontend styling)

### No New Dependencies Required
All implementation can be done with existing libraries and frameworks.

## Summary

All technical unknowns have been resolved:

1. ✅ API design pattern selected (nested resources)
2. ✅ Component architecture defined (inline within edit dialog)
3. ✅ Validation strategy established (both frontend and backend)
4. ✅ Duplicate detection approach confirmed (existing repository method)
5. ✅ Performance strategy defined (load all with pagination option)
6. ✅ Concurrent edit handling decided (last-write-wins with constraint protection)
7. ✅ Error handling patterns documented (existing Secman patterns)

All decisions align with:
- Secman Constitution principles (Security-First, TDD, API-First, RBAC)
- Existing codebase patterns and architecture
- Feature specification requirements
- Performance and scale constraints

**Ready for Phase 1: Design & Contracts**
