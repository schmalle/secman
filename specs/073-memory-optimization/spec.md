# Feature Specification: Memory and Heap Space Optimization

**Feature Branch**: `073-memory-optimization`
**Created**: 2026-02-03
**Status**: Draft
**Input**: User description: "i want to reduce the memory / heap space need from secman, if possible. Please analyze the entire application and identify areas for optimizations, always choose stability first"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Stable Vulnerability Queries Under Load (Priority: P1)

As an operations administrator, I need vulnerability listing queries to remain stable and responsive even when filtering large datasets (300K+ vulnerabilities), so that the system doesn't experience out-of-memory crashes or timeouts during normal use.

**Why this priority**: Vulnerability queries are the most memory-intensive operations in the application, with `findAll()` calls loading entire datasets into memory. Addressing this prevents production outages and is the highest-impact optimization.

**Independent Test**: Can be fully tested by querying vulnerabilities with exception status filters on a database with 100K+ records and monitoring heap usage - should never exceed baseline by more than 50MB.

**Acceptance Scenarios**:

1. **Given** a database with 300,000+ vulnerabilities, **When** a non-admin user queries with exception status filter, **Then** the query completes in under 30 seconds without memory spikes exceeding 50MB above baseline.

2. **Given** concurrent users (10+) querying vulnerabilities with different filters, **When** all queries execute simultaneously, **Then** no OutOfMemoryError occurs and all queries complete successfully.

3. **Given** a vulnerability cleanup operation is triggered, **When** duplicate detection runs, **Then** the operation uses batched processing and heap usage remains stable.

---

### User Story 2 - Memory-Efficient Vulnerability Export (Priority: P2)

As a security analyst, I need to export all vulnerabilities to Excel without the export operation consuming excessive memory, so that large exports don't crash the server or delay other users.

**Why this priority**: Export operations currently accumulate all data in memory before writing to Excel. With 300K+ records, this creates 179MB+ memory peaks that can destabilize the application.

**Independent Test**: Can be tested by triggering an export of 100K+ vulnerabilities while monitoring JVM heap - memory should remain under 100MB additional allocation during export.

**Acceptance Scenarios**:

1. **Given** a user initiates a vulnerability export of 300,000 records, **When** the export processes, **Then** memory usage remains stable (streaming to disk) rather than accumulating all records first.

2. **Given** multiple concurrent export jobs (3+), **When** all run simultaneously, **Then** the system remains responsive and no single export consumes more than 100MB heap.

3. **Given** an export is in progress, **When** the user navigates other features, **Then** the UI remains responsive with no degradation.

---

### User Story 3 - Optimized Entity Loading (Priority: P2)

As a backend system, I need entity relationships to be loaded efficiently based on actual usage patterns, so that list operations don't trigger unnecessary database queries and memory allocations.

**Why this priority**: EAGER loading of workgroups on Asset and User entities causes N+1 query patterns and loads unnecessary data into memory for every entity retrieval, even in list views where the data isn't displayed.

**Independent Test**: Can be tested by loading a page of 50 assets and verifying only 2 queries execute (assets + workgroups batch) instead of 51 queries (1 + N).

**Acceptance Scenarios**:

1. **Given** a user views the asset list (50 items), **When** the page loads, **Then** workgroups are not loaded until the user views asset details.

2. **Given** authentication checks access control, **When** user permissions are evaluated, **Then** only necessary permission data is loaded, not full entity graphs.

3. **Given** a DTO is returned from an API endpoint, **When** the response is serialized, **Then** no nested entity objects are included unless explicitly required.

---

### User Story 4 - Streamlined Data Transfer Objects (Priority: P3)

As an API consumer, I receive only the data fields necessary for my use case, so that network transfer size is reduced and frontend memory usage is minimized.

**Why this priority**: Current DTOs include redundant data (both full Asset objects and flat asset fields), increasing response size by 2-3x and contributing to both server and client memory overhead.

**Independent Test**: Can be tested by comparing API response sizes before and after optimization - responses should be 50%+ smaller.

**Acceptance Scenarios**:

1. **Given** a vulnerability list API response, **When** the response is serialized, **Then** asset data is represented only by flat fields (assetId, assetName, assetIp), not nested objects.

2. **Given** a frontend table displays vulnerabilities, **When** data is loaded, **Then** only display-relevant fields are transferred.

---

### User Story 5 - Efficient Access Control Queries (Priority: P3)

As a non-admin user, my access control checks complete quickly without loading duplicate data multiple times, so that page loads are fast and server memory is used efficiently.

**Why this priority**: Current access control runs 4-5 separate queries and concatenates results in memory with deduplication. This creates temporary memory overhead and unnecessary database round trips.

**Independent Test**: Can be tested by profiling access control for a user with multiple workgroups and cloud accounts - should execute as single unified query.

**Acceptance Scenarios**:

1. **Given** a user has access via workgroups, AWS accounts, and AD domains, **When** accessible assets are determined, **Then** a single database query returns the unified result set.

2. **Given** the same access check is performed multiple times per request, **When** results are needed, **Then** the computed result is cached for the request duration.

---

### Edge Cases

- What happens when a user exports vulnerabilities larger than available heap? System should fail gracefully with a clear error rather than OOM crash.
- How does the system handle concurrent large operations (export + vulnerability query + import)? Operations should queue or run with bounded memory each.
- What happens when filter options (products, domains) exceed 10,000 items? Autocomplete searches should use server-side filtering with limits.
- How does the system behave when connection pool is exhausted during peak load? Requests should timeout gracefully with retry guidance.

## Requirements *(mandatory)*

### Functional Requirements

**Query Optimization**

- **FR-001**: System MUST use SQL-level filtering for exception status instead of in-memory filtering for all user types.
- **FR-002**: System MUST replace `findAll()` calls in duplicate cleanup with window function SQL queries that process data in batches.
- **FR-003**: System MUST batch deletion operations in chunks of 1,000 records maximum.
- **FR-004**: System MUST provide server-side search filtering for distinct value queries (products, domains) with configurable limits.

**Entity Loading Optimization**

- **FR-005**: System MUST use LAZY fetch for Asset.workgroups relationship, with explicit JOIN FETCH only when workgroups are needed.
- **FR-006**: System MUST use LAZY fetch for User.workgroups relationship.
- **FR-007**: System MUST not trigger additional queries when serializing DTOs for list endpoints.
- **FR-018**: System MUST provide a feature flag to toggle entity loading strategy (EAGER/LAZY) at runtime via configuration for rollback capability.

**Export Optimization**

- **FR-008**: System MUST stream vulnerability exports directly to Excel output instead of accumulating all records in memory first.
- **FR-009**: System MUST process exports in batches of 1,000 records, writing each batch to the Excel file immediately.

**DTO Optimization**

- **FR-010**: VulnerabilityWithExceptionDto MUST NOT include full Asset objects; only flat asset fields (assetId, assetName, assetIp) are included.
- **FR-011**: API responses for list endpoints MUST include only display-relevant fields.

**Access Control Optimization**

- **FR-012**: Asset access control queries MUST be unified into a single SQL query using UNION DISTINCT or OR conditions.
- **FR-013**: Access control results MUST be cacheable per-request to avoid redundant computation.

**Configuration**

- **FR-014**: System MUST provide configurable batch sizes for large operations (default 1,000).
- **FR-015**: System MUST support tunable connection pool settings appropriate for expected concurrent load.
- **FR-016**: System MUST maintain existing cache configurations but allow size/TTL tuning.

**Observability**

- **FR-017**: System MUST expose JVM heap memory metrics via health endpoint for monitoring and validation of memory targets.

### Key Entities

- **VulnerabilityWithExceptionDto**: Data transfer object for vulnerability list responses; key attributes: id, cveId, assetId, assetName, assetIp, criticality, overdueStatus (no nested objects).
- **Asset**: Domain entity; workgroups relationship changes from EAGER to LAZY loading.
- **User**: Domain entity; workgroups relationship changes from EAGER to LAZY loading.
- **ExportJob**: Tracks background export operations; key attributes: id, status, progress, filePath.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Vulnerability list queries with exception filters complete without memory allocation exceeding 50MB above baseline for datasets up to 500,000 records.
- **SC-002**: Vulnerability exports of 300,000+ records complete with peak memory usage under 100MB additional allocation.
- **SC-003**: Asset list page loads execute no more than 3 database queries (main query + 2 batch fetches) regardless of result count.
- **SC-004**: API response payload sizes for vulnerability list endpoints reduce by 40% or more compared to current responses.
- **SC-005**: System maintains stability under concurrent load (10 users executing large queries simultaneously) without OutOfMemoryError.
- **SC-006**: All optimizations maintain backward compatibility with existing API contracts and frontend consumers.
- **SC-007**: Application startup memory footprint does not increase as a result of these optimizations.

## Assumptions

- Database indices are already optimized (as indicated by existing DENSE_RANK usage).
- Hibernate JDBC batch_size setting of 20 in application.yml is appropriate or can be tuned separately.
- SXSSFWorkbook (streaming Excel) is already in use for export output - optimization focuses on input data streaming.
- Frontend components using the APIs can handle payload structure changes (removing nested asset objects).
- Current cache configurations are reasonable starting points; monitoring will inform future tuning.
- Feature flag for LAZY loading will default to LAZY (optimized) but can be toggled to EAGER for rollback if LazyInitializationException issues are discovered.

## Clarifications

### Session 2026-02-03

- Q: How will memory targets be validated and monitored? → A: JVM metrics endpoint (expose heap usage via /health or actuator)
- Q: What is the rollback strategy for LAZY loading changes? → A: Feature flag (toggle between EAGER/LAZY at runtime via config)

## Scope Boundaries

**In Scope:**
- Backend memory optimization (Kotlin/Micronaut services, repositories, entities)
- DTO streamlining for reduced payload sizes
- Query optimization for large datasets
- Export streaming implementation

**Out of Scope:**
- Frontend React component optimization (browser memory)
- Database schema changes or index optimization
- Infrastructure changes (additional read replicas)
- CLI application memory optimization
- Cache size auto-tuning based on runtime metrics
