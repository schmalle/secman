# Feature 013: User Mapping Upload

**Status**: Planning Complete ✅  
**Branch**: `013-user-mapping-upload`  
**Created**: 2025-10-07  
**Estimated Effort**: 12-15 hours (2 days)

---

## 📖 Overview

Enable system administrators to upload Excel files containing mappings between user email addresses, AWS account IDs, and organizational domains. These mappings will support future role-based access control features for multi-tenant environments.

**Core Functionality**:
- Upload Excel (.xlsx) files with 3 columns: Email Address, AWS Account ID, Domain
- Validate data (email format, 12-digit AWS account IDs, domain format)
- Skip invalid/duplicate rows, continue processing valid ones
- Admin-only access (ADMIN role required)
- Comprehensive error reporting

---

## 🎯 Business Value

- **Multi-Tenant Support**: Enable users to access resources across different AWS accounts and domains
- **Bulk Configuration**: Import hundreds of mappings at once via Excel
- **Future RBAC**: Foundation for advanced role-based access control
- **Audit Trail**: Track when mappings were created for compliance

---

## 📂 Documentation

This feature specification includes:

1. **[spec.md](./spec.md)** (16.7 KB)
   - Complete feature specification
   - User scenarios and acceptance criteria
   - Functional requirements (FR-DM, FR-UL, FR-VAL, FR-AC, FR-UI)
   - Success metrics and approval checklist

2. **[data-model.md](./data-model.md)** (13.9 KB)
   - UserMapping entity schema definition
   - Validation rules and constraints
   - Indexes for query optimization
   - Sample data and test cases

3. **[plan.md](./plan.md)** (30.5 KB)
   - Implementation phases (8 phases)
   - Detailed component specifications (entity, repository, service, controller, UI)
   - Test strategy (unit, integration, E2E)
   - Risk analysis and rollback plan

4. **[quickstart.md](./quickstart.md)** (13.3 KB)
   - Developer-focused implementation guide
   - Step-by-step checklist (12 steps)
   - Code snippets and patterns
   - Common issues and solutions

5. **[tasks.md](./tasks.md)** (28.3 KB)
   - Granular task breakdown (24 tasks)
   - Task dependencies and effort estimates
   - Acceptance criteria per task
   - Deployment and QA checklist

**Total Documentation**: ~103 KB, 5 files

---

## 🏗️ Architecture

### Data Flow
```
┌───────────────────────────────────────────────────────────┐
│  Admin User (Browser)                                     │
└───────────────────────────────────────────────────────────┘
                         │
                         │ Upload Excel File
                         ↓
┌───────────────────────────────────────────────────────────┐
│  Frontend (Astro + React)                                 │
│  - /admin/user-mappings page                              │
│  - UserMappingUpload component                            │
│  - userMappingService.uploadUserMappings()                │
└───────────────────────────────────────────────────────────┘
                         │
                         │ HTTP POST multipart/form-data
                         ↓
┌───────────────────────────────────────────────────────────┐
│  Backend (Micronaut + Kotlin)                             │
│  - ImportController.uploadUserMappings()                  │
│    └─> @Secured("ADMIN")                                  │
│  - UserMappingImportService.importFromExcel()             │
│    └─> Parse Excel (Apache POI)                           │
│    └─> Validate rows (email, AWS account, domain)         │
│    └─> Check duplicates                                   │
│  - UserMappingRepository.save()                           │
└───────────────────────────────────────────────────────────┘
                         │
                         │ JPA/Hibernate
                         ↓
┌───────────────────────────────────────────────────────────┐
│  Database (MariaDB)                                       │
│  - user_mapping table                                     │
│    - Unique constraint: (email, aws_account_id, domain)  │
│    - Indexes: email, aws_account_id, domain              │
└───────────────────────────────────────────────────────────┘
```

---

## 🗂️ Key Entity: UserMapping

```kotlin
@Entity
@Table(name = "user_mapping")
data class UserMapping(
    @Id @GeneratedValue var id: Long? = null,
    @Column(nullable = false) var email: String,           // user@example.com (lowercase)
    @Column(name = "aws_account_id") var awsAccountId: String, // 123456789012 (12 digits)
    @Column(nullable = false) var domain: String,           // example.com (lowercase)
    @Column(name = "created_at") var createdAt: Instant? = null
)
```

**Constraints**:
- Unique composite key: (email, awsAccountId, domain)
- Email: valid format, normalized to lowercase
- AWS Account ID: exactly 12 numeric digits
- Domain: alphanumeric + dots + hyphens, normalized to lowercase

---

## 🔌 API Endpoint

```
POST /api/import/upload-user-mappings
Authorization: Bearer <jwt-token>
Content-Type: multipart/form-data
Role Required: ADMIN

Request Body:
  xlsxFile: <Excel file>

Response (200 OK):
{
  "message": "Imported 5 mappings, skipped 2",
  "imported": 5,
  "skipped": 2,
  "errors": [
    "Row 7: Invalid email format: notanemail",
    "Row 10: AWS Account ID must be 12 digits: 12345"
  ]
}

Error Response (400 Bad Request):
{
  "error": "Missing required column: Domain"
}

Error Response (403 Forbidden):
{
  "error": "Access denied: Admin privileges required"
}
```

---

## 📊 Excel File Format

### Required Columns (case-insensitive)
1. **Email Address** - User's email (e.g., `john@example.com`)
2. **AWS Account ID** - 12-digit AWS account (e.g., `123456789012`)
3. **Domain** - Organizational domain (e.g., `example.com`)

### Example
```
| Email Address         | AWS Account ID | Domain      |
|-----------------------|----------------|-------------|
| john@example.com      | 123456789012   | example.com |
| jane@example.com      | 123456789012   | example.com |
| john@example.com      | 987654321098   | other.com   |
| consultant@agency.com | 555555555555   | clientA.com |
```

### Rules
- One mapping per row (repeat email for multiple accounts/domains)
- Headers can be in any order
- Duplicate mappings are automatically skipped
- Invalid rows are skipped with error details
- Max file size: 10MB
- Recommended max rows: 10,000

---

## 🎨 UI Components

### Admin Page Card
New card in Admin section (`/admin`):
- **Title**: User Mappings
- **Icon**: bi-diagram-3-fill
- **Link**: `/admin/user-mappings`

### Upload Page (`/admin/user-mappings`)
- **File Requirements Card**: Format, size, columns, sample download
- **File Input**: Accept .xlsx only
- **Upload Button**: With loading state
- **Results Display**: Success/error alerts with counts and details

---

## ✅ Acceptance Criteria

### Must Have (P0)
- [x] Specification complete with all functional requirements
- [x] Data model documented with schema and constraints
- [x] Implementation plan with 8 phases defined
- [x] 24 tasks broken down with effort estimates
- [x] Test strategy defined (unit, integration, E2E)

### Implementation Phase (TBD)
- [ ] UserMapping entity created with JPA annotations
- [ ] UserMappingRepository with CRUD and query methods
- [ ] UserMappingImportService with Excel parsing and validation
- [ ] POST /api/import/upload-user-mappings endpoint (ADMIN-only)
- [ ] UserMappingUpload React component
- [ ] Admin page card and route
- [ ] Sample Excel template file
- [ ] 10 E2E test scenarios
- [ ] Documentation updated (CLAUDE.md)

---

## 🧪 Test Coverage

### Unit Tests (Backend)
- UserMapping entity validation
- UserMappingRepository queries
- UserMappingImportService validation logic
- **Target Coverage**: >80%

### Integration Tests (Backend)
- Controller endpoint with valid/invalid files
- Security enforcement (ADMIN role)
- Error response formats

### E2E Tests (Frontend)
- Upload valid file → success
- Upload invalid data → partial success with errors
- Upload wrong file type → error
- Non-admin access → access denied
- Download sample file → file downloads
- **Total Scenarios**: 10

---

## 📈 Success Metrics

- **Data Quality**: >95% of uploaded rows successfully imported on first attempt
- **User Adoption**: Admins can upload without documentation after viewing sample
- **Performance**: 1000-row file processes in <10 seconds
- **Error Handling**: All skipped rows have clear, actionable error messages

---

## 🚀 Implementation Timeline

| Phase | Tasks | Effort | Duration |
|-------|-------|--------|----------|
| 1. Backend Foundation | 3 | 2h | Day 1 AM |
| 2. Backend Service | 2 | 3h | Day 1 PM |
| 3. Backend API | 2 | 2h | Day 2 AM |
| 4. Frontend | 4 | 2.5h | Day 2 PM |
| 5. E2E Tests | 3 | 2h | Day 3 AM |
| 6. Documentation | 4 | 1.5h | Day 3 PM |
| 7. QA | 3 | 1h | Day 3 PM |
| 8. Deployment | 3 | 1.5h | Day 3 PM |
| **Total** | **24** | **15h** | **3 days** |

---

## 🔒 Security Considerations

- **Authentication**: JWT required on all endpoints
- **Authorization**: ADMIN role enforced via @Secured annotation
- **File Validation**: Size limit (10MB), type check (.xlsx only)
- **Input Sanitization**: Email, AWS account, domain validation
- **No Foreign Keys**: UserMapping is independent (no cascade issues)
- **Audit Trail**: createdAt timestamp for all records

---

## 📦 Dependencies

### Existing (No New Dependencies)
- Apache POI (Excel parsing) - ✅ Already included
- Bootstrap 5 (UI components) - ✅ Already included
- Axios (HTTP client) - ✅ Already included
- JWT authentication - ✅ Already implemented
- ADMIN role system - ✅ Already implemented

### New Database Table
- `user_mapping` (auto-created by Hibernate)

---

## 🔮 Future Enhancements (Post-Phase 1)

1. **CRUD UI**: Browse, search, edit, delete individual mappings
2. **Export**: Download current mappings as Excel
3. **RBAC Integration**: Use mappings in asset/vulnerability access control
4. **User Sync**: Link mappings to User entities, show in user profile
5. **AWS Validation**: Verify account IDs exist in AWS Organization
6. **Domain Validation**: Check domains against corporate directory
7. **Analytics**: Dashboard showing mapping distribution
8. **CSV Support**: Accept CSV files in addition to Excel
9. **Expiration**: Add validFrom/validTo for time-bound access
10. **Bulk Delete**: Delete mappings by criteria (domain, AWS account, etc.)

---

## 🛠️ Quick Start for Developers

1. **Read Specifications**
   ```bash
   cd specs/013-user-mapping-upload/
   cat spec.md          # Feature requirements
   cat data-model.md    # Entity schema
   cat plan.md          # Implementation approach
   ```

2. **Follow Implementation Guide**
   ```bash
   cat quickstart.md    # Step-by-step developer guide
   cat tasks.md         # Granular task list
   ```

3. **Create Feature Branch**
   ```bash
   git checkout -b 013-user-mapping-upload
   ```

4. **Start with Tests (TDD)**
   ```bash
   # Create test file first
   touch src/backendng/src/test/kotlin/com/secman/domain/UserMappingTest.kt
   # Write failing tests
   # Implement to make tests pass
   ./gradlew test
   ```

5. **Verify Progress**
   ```bash
   ./gradlew test              # Backend unit tests
   npm run test:e2e            # Frontend E2E tests
   ./gradlew build && npm run build  # Full build
   ```

---

## 📞 Support & Questions

- **Specification Questions**: Review spec.md or ask Product Owner
- **Technical Questions**: Review plan.md or ask Tech Lead
- **Implementation Help**: Check quickstart.md or existing similar features:
  - VulnerabilityImportService (Excel parsing pattern)
  - ImportController (file upload pattern)
  - Workgroup entity (entity + repository pattern)

---

## 📝 Notes

- **Pattern**: Follow existing import patterns (Vulnerability, Requirements)
- **Testing**: TDD approach - write tests first, then implementation
- **Documentation**: Update CLAUDE.md as you implement
- **Commits**: Use conventional commits: `feat(user-mapping): add entity`
- **Review**: Self-review against checklist before PR

---

## ✍️ Approval & Sign-off

**Planning Approval**:
- [x] Specification reviewed and approved
- [x] Data model reviewed and approved
- [x] Implementation plan reviewed and approved
- [x] Tasks broken down with estimates
- [x] Ready for implementation

**Implementation Sign-off** (TBD):
- [ ] All 24 tasks completed
- [ ] All tests passing (unit, integration, E2E)
- [ ] Code review approved
- [ ] Documentation updated
- [ ] Deployed to staging
- [ ] QA sign-off
- [ ] Deployed to production

---

**Document Version**: 1.0  
**Last Updated**: 2025-10-07  
**Next Review**: After implementation completion
