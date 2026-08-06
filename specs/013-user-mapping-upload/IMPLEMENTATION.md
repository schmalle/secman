# Implementation Guide: User Mapping Upload

**Feature ID**: 013-user-mapping-upload  
**Created**: 2025-10-07  
**Status**: 📋 Ready for Implementation  
**Estimated Effort**: 16 hours (2-3 days)

---

## 🎯 Executive Summary

This feature enables system administrators to upload Excel files containing mappings between user email addresses, AWS account IDs, and organizational domains. These mappings serve as foundational data for future role-based access control (RBAC) features in multi-tenant environments.

### Business Value
- **Multi-Tenant Support**: Enable users to access resources across different AWS accounts and domains
- **Bulk Configuration**: Import hundreds of mappings at once via Excel (vs manual entry)
- **Future RBAC Foundation**: Required infrastructure for advanced access control features
- **Audit Trail**: Track when mappings were created for compliance

### Key Features
- Excel (.xlsx) file upload with 3 columns: Email Address, AWS Account ID, Domain
- Comprehensive validation: email format, 12-digit AWS accounts, domain format
- Defensive error handling: skip invalid rows, continue with valid ones
- Duplicate detection: idempotent uploads (same mapping uploaded twice = skipped)
- ADMIN-only access with JWT authentication
- Sample template download for users
- Detailed error reporting per row

---

## 📚 Documentation Structure

This implementation is supported by comprehensive planning documentation:

| Document | Purpose | Use During |
|----------|---------|------------|
| **IMPLEMENTATION.md** (this file) | **Complete implementation guide** | **Primary reference** |
| [tasks.md](./tasks.md) | 38 granular tasks with effort estimates | Project tracking |
| [PLAN_EXECUTION.md](./PLAN_EXECUTION.md) | Step-by-step with complete code examples | Implementation |
| [spec.md](./spec.md) | 28 functional requirements | Requirements validation |
| [data-model.md](./data-model.md) | Database schema and validation rules | Data structure reference |
| [plan.md](./plan.md) | Technical architecture and approach | Design decisions |
| [quickstart.md](./quickstart.md) | Developer fast-track guide | Quick reference |
| [README.md](./README.md) | Feature overview and business context | Stakeholder communication |

**Total Documentation**: 6,208 lines across 8 files

---

## 🏗️ Architecture Overview

### System Context

```
┌──────────────────────────────────────────────────────────────┐
│  Admin User (Browser)                                        │
│  - Uploads Excel file with user mappings                     │
│  - Downloads sample template                                 │
└──────────────────────────────────────────────────────────────┘
                           ↓
                    HTTP POST (multipart/form-data)
                           ↓
┌──────────────────────────────────────────────────────────────┐
│  Frontend (Astro 5.14 + React 19 + Bootstrap 5)             │
│                                                               │
│  Components:                                                  │
│  - UserMappingUpload.tsx (file upload UI)                   │
│  - AdminPage.tsx (adds "User Mappings" card)                │
│                                                               │
│  Services:                                                    │
│  - userMappingService.ts (API wrapper)                       │
│                                                               │
│  Routes:                                                      │
│  - /admin/user-mappings (main upload page)                   │
└──────────────────────────────────────────────────────────────┘
                           ↓
                    Axios HTTP POST
                           ↓
┌──────────────────────────────────────────────────────────────┐
│  Backend (Micronaut 4.4 + Kotlin 2.1 + Java 21)            │
│                                                               │
│  Controller:                                                  │
│  - ImportController.uploadUserMappings()                     │
│    └─> @Secured("ADMIN") - Role enforcement                 │
│                                                               │
│  Service:                                                     │
│  - UserMappingImportService.importFromExcel()               │
│    ├─> Parse Excel with Apache POI                          │
│    ├─> Validate rows (email, AWS account, domain)           │
│    ├─> Check duplicates                                      │
│    └─> Bulk insert via repository                           │
│                                                               │
│  Repository:                                                  │
│  - UserMappingRepository (Micronaut Data JPA)               │
│    └─> Query methods for access control lookups             │
│                                                               │
│  Domain:                                                      │
│  - UserMapping entity (JPA with validation)                 │
└──────────────────────────────────────────────────────────────┘
                           ↓
                    JPA/Hibernate ORM
                           ↓
┌──────────────────────────────────────────────────────────────┐
│  Database (MariaDB 11.4)                                     │
│                                                               │
│  Table: user_mapping                                          │
│  - Unique constraint: (email, aws_account_id, domain)       │
│  - Indexes: email, aws_account_id, domain, (email+aws)      │
│  - Auto-created by Hibernate on first startup                │
└──────────────────────────────────────────────────────────────┘
```

### Data Flow

1. **Upload Request**: Admin uploads Excel via browser
2. **Frontend Validation**: Check file type (.xlsx), size (<10MB)
3. **API Call**: POST /api/import/upload-user-mappings with multipart form
4. **Authentication**: JWT token validated
5. **Authorization**: Check ADMIN role (403 if not authorized)
6. **File Validation**: Backend validates file size, type, not empty
7. **Excel Parsing**: Apache POI parses XLSX, validates headers
8. **Row Processing**: For each row:
   - Validate email format
   - Validate AWS account ID (12 digits)
   - Validate domain format
   - Check for duplicate (skip if exists)
   - Insert into database
9. **Response**: Return ImportResult with counts (imported, skipped, errors)
10. **UI Update**: Display success/error alert with details

---

## 🗂️ Data Model

### UserMapping Entity

**Purpose**: Store many-to-many mappings between user emails, AWS account IDs, and organizational domains

**Table**: `user_mapping`

```kotlin
@Entity
@Table(
    name = "user_mapping",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["email", "aws_account_id", "domain"])
    ],
    indexes = [
        Index(name = "idx_user_mapping_email", columnList = "email"),
        Index(name = "idx_user_mapping_aws_account", columnList = "aws_account_id"),
        Index(name = "idx_user_mapping_domain", columnList = "domain"),
        Index(name = "idx_user_mapping_email_aws", columnList = "email,aws_account_id")
    ]
)
data class UserMapping(
    @Id @GeneratedValue var id: Long? = null,
    @Email @NotBlank var email: String,                    // user@example.com
    @Pattern(regexp = "^\\d{12}$") var awsAccountId: String, // 123456789012
    @NotBlank var domain: String,                          // example.com
    var createdAt: Instant? = null,
    var updatedAt: Instant? = null
)
```

**Key Design Decisions**:
- **No Foreign Key to User**: UserMapping is independent (may reference future users)
- **Denormalized Storage**: Each mapping is a separate row (email repeated for multiple accounts/domains)
- **Composite Unique Key**: Prevents duplicate (email, awsAccountId, domain) combinations
- **4 Indexes**: Optimizes queries by email, AWS account, domain, and email+AWS account
- **Lowercase Normalization**: Email and domain stored lowercase for case-insensitive matching
- **Validation Annotations**: JPA Bean Validation ensures data quality

### Cardinality

- **Email ↔ AWS Account**: Many-to-Many (user can access multiple AWS accounts)
- **Email ↔ Domain**: Many-to-Many (user can operate across multiple domains)
- **AWS Account ↔ Domain**: Many-to-Many (implicitly through email)

**Example Mappings**:
```
john@example.com → 123456789012 → example.com
john@example.com → 987654321098 → example.com    (same user, different AWS account)
john@example.com → 123456789012 → other.com      (same user, different domain)
consultant@agency.com → 555555555555 → clientA.com (different user)
```

---

## 🔧 Implementation Guide

### Phase 1: Backend Foundation (2 hours)

#### Step 1: Create UserMapping Entity

**File**: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt` (new)

**Complete Implementation**: See [PLAN_EXECUTION.md lines 28-94](./PLAN_EXECUTION.md#L28-L94)

**Key Points**:
- Add all JPA annotations (@Entity, @Table, @Column)
- Define unique constraint on (email, awsAccountId, domain)
- Define 4 indexes for query optimization
- Add validation annotations (@Email, @Pattern, @NotBlank)
- Implement @PrePersist to normalize email/domain to lowercase
- Implement @PreUpdate to update timestamp

**Test First (TDD)**: Write `UserMappingTest.kt` before implementing entity
- Test cases: creation, normalization, unique constraint, timestamps

**Validation**:
```bash
./gradlew test --tests UserMappingTest
# All 8 tests should PASS
```

**Commit**:
```bash
git add src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
git add src/backendng/src/test/kotlin/com/secman/domain/UserMappingTest.kt
git commit -m "feat(user-mapping): add UserMapping entity with validation and tests"
```

---

#### Step 2: Create UserMappingRepository

**File**: `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt` (new)

**Complete Implementation**: See [PLAN_EXECUTION.md lines 152-232](./PLAN_EXECUTION.md#L152-L232)

**Query Methods**:
- `findByEmail(email: String): List<UserMapping>` - Get all mappings for a user
- `findByAwsAccountId(accountId: String): List<UserMapping>` - Get all users for an AWS account
- `findByDomain(domain: String): List<UserMapping>` - Get all users for a domain
- `existsByEmailAndAwsAccountIdAndDomain(...)`: Boolean` - Duplicate detection
- `findByEmailAndAwsAccountIdAndDomain(...): Optional<UserMapping>` - Find specific mapping
- `countByEmail(email: String): Long` - Count mappings for a user
- `findDistinctAwsAccountIdByEmail(email: String): List<String>` - AWS accounts for user
- `findDistinctDomainByEmail(email: String): List<String>` - Domains for user

**Test First (TDD)**: Write `UserMappingRepositoryTest.kt` before implementing repository
- Test cases: CRUD, queries, duplicate detection, counts

**Validation**:
```bash
./gradlew test --tests UserMappingRepositoryTest
# All 9 tests should PASS
```

**Commit**:
```bash
git add src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt
git add src/backendng/src/test/kotlin/com/secman/repository/UserMappingRepositoryTest.kt
git commit -m "feat(user-mapping): add UserMappingRepository with query methods and tests"
```

---

### Phase 2: Backend Service Layer (3 hours)

#### Step 3: Create UserMappingImportService

**File**: `src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt` (new)

**Complete Implementation**: See [plan.md lines 218-384](./plan.md#L218-L384)

**Key Responsibilities**:
1. **Parse Excel**: Use Apache POI (XSSFWorkbook) to read .xlsx files
2. **Validate Headers**: Check for required columns (case-insensitive)
3. **Validate Rows**: For each row:
   - Email format (contains @)
   - AWS account ID format (exactly 12 numeric digits)
   - Domain format (alphanumeric + dots + hyphens)
4. **Handle Errors**: Skip invalid rows, continue with valid ones
5. **Detect Duplicates**: Check `existsByEmailAndAwsAccountIdAndDomain` before insert
6. **Return Results**: ImportResult with counts (imported, skipped, errors list)

**Excel Cell Type Handling**:
```kotlin
// CRITICAL: Use DataFormatter to preserve AWS account ID precision
// Without this, "123456789012" becomes "1.23E+11" in scientific notation
val formatter = DataFormatter()
val cellValue = formatter.formatCellValue(cell)
```

**Validation Logic**:
```kotlin
// Email validation
fun validateEmail(email: String): Boolean {
    return email.contains("@") && email.length in 3..255
}

// AWS account ID validation
fun validateAwsAccountId(accountId: String): Boolean {
    return accountId.matches(Regex("^\\d{12}$"))
}

// Domain validation
fun validateDomain(domain: String): Boolean {
    val normalized = domain.lowercase()
    return normalized.matches(Regex("^[a-z0-9.-]+$")) &&
           !normalized.startsWith(".") && !normalized.endsWith(".") &&
           !normalized.startsWith("-") && !normalized.endsWith("-")
}
```

**Test First (TDD)**: Create test Excel files and write `UserMappingImportServiceTest.kt`
- Test cases: valid file, invalid email, invalid AWS account, invalid domain, duplicates, missing column, empty file

**Test Data Files** (create in `src/backendng/src/test/resources/user-mapping-test-files/`):
- `user-mappings-valid.xlsx` - 5 valid rows
- `user-mappings-invalid-email.xlsx` - mixed valid/invalid
- `user-mappings-invalid-aws.xlsx` - invalid AWS account ID
- `user-mappings-duplicates.xlsx` - contains duplicates
- `user-mappings-missing-column.xlsx` - missing "Domain" column
- `user-mappings-empty.xlsx` - only headers

**Validation**:
```bash
./gradlew test --tests UserMappingImportServiceTest
# All 10 tests should PASS
```

**Commit**:
```bash
git add src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt
git add src/backendng/src/test/kotlin/com/secman/service/UserMappingImportServiceTest.kt
git add src/backendng/src/test/resources/user-mapping-test-files/
git commit -m "feat(user-mapping): add UserMappingImportService with Excel parsing and validation"
```

---

### Phase 3: Backend API Controller (2 hours)

#### Step 4: Add Upload Endpoint to ImportController

**File**: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt` (modify existing)

**Complete Implementation**: See [PLAN_EXECUTION.md lines 556-597](./PLAN_EXECUTION.md#L556-L597)

**Add to Constructor**:
```kotlin
private val userMappingImportService: com.secman.service.UserMappingImportService
```

**Add Method**:
```kotlin
@Post("/upload-user-mappings")
@Consumes(MediaType.MULTIPART_FORM_DATA)
@Transactional
@Secured("ADMIN")
open fun uploadUserMappings(@Part xlsxFile: CompletedFileUpload): HttpResponse<*> {
    return try {
        log.debug("Processing user mapping Excel file upload: {}", xlsxFile.filename)

        // Validate file (reuse existing method)
        val validation = validateVulnerabilityFile(xlsxFile)
        if (validation != null) {
            return HttpResponse.badRequest(ErrorResponse(validation))
        }

        // Import user mappings
        val response = xlsxFile.inputStream.use { inputStream ->
            userMappingImportService.importFromExcel(inputStream)
        }

        log.info("Successfully imported user mappings: {}", response.message)
        HttpResponse.ok(response)

    } catch (e: Exception) {
        log.error("Error processing user mapping Excel file", e)
        HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("Error processing file: ${e.message}"))
    }
}
```

**Security**:
- `@Secured("ADMIN")` - Only ADMIN role can access
- JWT authentication required (from SecurityRule.IS_AUTHENTICATED)
- Returns 403 Forbidden for non-admin users
- Returns 401 Unauthorized for unauthenticated requests

**File Validation** (reuses existing `validateVulnerabilityFile`):
- Max size: 10MB
- File extension: .xlsx only
- Content type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
- Not empty

**Test First (TDD)**: Write tests in `ImportControllerTest.kt`
- Test cases: ADMIN success, USER forbidden, unauthenticated, wrong file type, too large

**Validation**:
```bash
./gradlew test --tests ImportControllerTest.uploadUserMappings
# All 7 tests should PASS
```

**Commit**:
```bash
git add src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt
git add src/backendng/src/test/kotlin/com/secman/controller/ImportControllerTest.kt
git commit -m "feat(user-mapping): add upload endpoint to ImportController with ADMIN security"
```

---

### Phase 4: Frontend Implementation (2.5 hours)

#### Step 5: Create Frontend Service

**File**: `src/frontend/src/services/userMappingService.ts` (new)

**Complete Implementation**: See [PLAN_EXECUTION.md lines 636-656](./PLAN_EXECUTION.md#L636-L656)

```typescript
import axios from 'axios';

interface ImportResult {
  message: string;
  imported: number;
  skipped: number;
  errors?: string[];
}

export async function uploadUserMappings(file: File): Promise<ImportResult> {
  const formData = new FormData();
  formData.append('xlsxFile', file);

  const response = await axios.post<ImportResult>(
    '/api/import/upload-user-mappings',
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    }
  );

  return response.data;
}

export function getSampleFileUrl(): string {
  return '/sample-files/user-mapping-template.xlsx';
}
```

**Commit**:
```bash
git add src/frontend/src/services/userMappingService.ts
git commit -m "feat(user-mapping): add frontend service for user mapping API"
```

---

#### Step 6: Create Upload Component

**File**: `src/frontend/src/components/UserMappingUpload.tsx` (new)

**Complete Implementation**: See [PLAN_EXECUTION.md lines 660-741](./PLAN_EXECUTION.md#L660-L741)

**Key Features**:
- File input field (accept=".xlsx")
- Upload button with loading state (disabled during upload, shows spinner)
- File requirements card with format, size, columns
- Sample file download link
- Success alert (green) with imported/skipped counts
- Error alert (red) with error message
- Error list display (if errors present)

**Component Structure**:
```tsx
const UserMappingUpload: React.FC = () => {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleUpload = async () => {
    // ... upload logic with error handling
  };

  return (
    <div className="container mt-4">
      <h2>User Mapping Upload</h2>
      {/* File requirements card */}
      {/* File input */}
      {/* Upload button */}
      {/* Error alert */}
      {/* Success alert */}
    </div>
  );
};
```

**Validation**:
```bash
cd src/frontend
npm run dev
# Navigate to /admin/user-mappings (after completing Step 8)
# Component should render without errors
```

**Commit**:
```bash
git add src/frontend/src/components/UserMappingUpload.tsx
git commit -m "feat(user-mapping): add UserMappingUpload React component with file upload UI"
```

---

#### Step 7: Add User Mappings Card to Admin Page

**File**: `src/frontend/src/components/AdminPage.tsx` (modify existing)

**Add after MCP API Keys card** (around line 167):
```tsx
<div className="col-md-4 mb-3">
    <div className="card">
        <div className="card-body">
            <h5 className="card-title">
                <i className="bi bi-diagram-3-fill me-2"></i>
                User Mappings
            </h5>
            <p className="card-text">
                Upload and manage user-to-AWS-account-to-domain mappings for role-based access control.
            </p>
            <a href="/admin/user-mappings" className="btn btn-primary">
                Manage Mappings
            </a>
        </div>
    </div>
</div>
```

**Commit**:
```bash
git add src/frontend/src/components/AdminPage.tsx
git commit -m "feat(user-mapping): add User Mappings card to Admin page"
```

---

#### Step 8: Create Admin Page Route

**File**: `src/frontend/src/pages/admin/user-mappings.astro` (new)

```astro
---
import Layout from '../../layouts/Layout.astro';
import UserMappingUpload from '../../components/UserMappingUpload';
---

<Layout title="User Mappings - Admin">
    <div class="container-fluid">
        <nav aria-label="breadcrumb">
            <ol class="breadcrumb">
                <li class="breadcrumb-item"><a href="/">Home</a></li>
                <li class="breadcrumb-item"><a href="/admin">Admin</a></li>
                <li class="breadcrumb-item active" aria-current="page">User Mappings</li>
            </ol>
        </nav>

        <UserMappingUpload client:load />
    </div>
</Layout>
```

**Validation**:
```bash
npm run dev
# Navigate to http://localhost:4321/admin/user-mappings
# Page should render with UserMappingUpload component
```

**Commit**:
```bash
git add src/frontend/src/pages/admin/user-mappings.astro
git commit -m "feat(user-mapping): add admin user mappings page route"
```

---

### Phase 5: Test Data & E2E Tests (2 hours)

#### Step 9: Create Sample Excel Template

**File**: `src/frontend/public/sample-files/user-mapping-template.xlsx` (new)

**Method 1: Python Script**
```python
import openpyxl

wb = openpyxl.Workbook()
ws = wb.active
ws.title = "Mappings"

# Headers
ws['A1'] = "Email Address"
ws['B1'] = "AWS Account ID"
ws['C1'] = "Domain"

# Sample data
data = [
    ["user1@example.com", "123456789012", "example.com"],
    ["user2@example.com", "987654321098", "example.com"],
    ["consultant@agency.com", "555555555555", "clientA.com"],
]

for idx, row in enumerate(data, start=2):
    ws[f'A{idx}'] = row[0]
    ws[f'B{idx}'] = row[1]
    ws[f'C{idx}'] = row[2]

# Instructions sheet
ws2 = wb.create_sheet("Instructions")
ws2['A1'] = "INSTRUCTIONS"
ws2['A3'] = "This template is used to upload user-to-AWS-account-to-domain mappings."
# ... add more instructions

wb.save("src/frontend/public/sample-files/user-mapping-template.xlsx")
```

**Method 2: LibreOffice Calc**
1. Open LibreOffice Calc
2. Sheet 1 "Mappings": Add headers and 3 sample rows
3. Sheet 2 "Instructions": Add detailed instructions
4. Save as: `src/frontend/public/sample-files/user-mapping-template.xlsx`

**Validation**:
- File size <50KB
- Opens correctly in Excel/LibreOffice
- Downloads from frontend at `/sample-files/user-mapping-template.xlsx`

**Commit**:
```bash
git add src/frontend/public/sample-files/user-mapping-template.xlsx
git commit -m "feat(user-mapping): add sample Excel template for download"
```

---

#### Step 10: Create E2E Test Data Files

**Directory**: `testdata/` (project root)

**Files to Create** (8 files):
1. `user-mappings-valid.xlsx` - 5 valid rows
2. `user-mappings-invalid-email.xlsx` - 1 invalid email, 1 valid
3. `user-mappings-invalid-aws.xlsx` - 1 invalid AWS account (too short)
4. `user-mappings-invalid-aws-nonnumeric.xlsx` - 1 non-numeric AWS account
5. `user-mappings-invalid-domain.xlsx` - 1 invalid domain (contains space)
6. `user-mappings-duplicates.xlsx` - 3 rows with 1 duplicate
7. `user-mappings-missing-column.xlsx` - Missing "Domain" column
8. `user-mappings-empty.xlsx` - Only headers, no data
9. `user-mappings-large.csv` - CSV file (wrong format)

**Use Python Script**: See [tasks.md Step T018](./tasks.md#T018)

**Commit**:
```bash
git add testdata/user-mappings-*.xlsx testdata/user-mappings-*.csv
git commit -m "test(user-mapping): add test Excel files for E2E testing"
```

---

#### Step 11: Write E2E Tests

**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (new)

**Complete Implementation**: See [PLAN_EXECUTION.md lines 862-876](./PLAN_EXECUTION.md#L862-L876)

**10 Test Scenarios**:
1. Upload valid file → success message with counts
2. Upload file with invalid email → partial success with error details
3. Upload file with invalid AWS account → partial success
4. Upload file with missing column → error message
5. Upload duplicate mappings → skipped message
6. Upload empty file → error message
7. Upload CSV file → error "Only .xlsx supported"
8. Non-admin access → "Access Denied"
9. Download sample file → file downloads successfully
10. Upload oversized file → error "File too large"

**Test Structure**:
```typescript
import { test, expect } from '@playwright/test';
import path from 'path';

async function loginAsAdmin(page) {
    await page.goto('/login');
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForURL('/');
}

test.describe('User Mapping Upload', () => {
    test.beforeEach(async ({ page }) => {
        await loginAsAdmin(page);
        await page.goto('/admin/user-mappings');
    });

    test('should upload valid user mapping file', async ({ page }) => {
        const filePath = path.join(__dirname, '../../../testdata/user-mappings-valid.xlsx');
        await page.setInputFiles('#userMappingFile', filePath);
        await page.click('button:has-text("Upload")');
        await expect(page.locator('.alert-success')).toContainText('Imported 5 mappings');
    });

    // ... 9 more test scenarios
});
```

**Validation**:
```bash
npm run test:e2e -- user-mapping-upload
# All 10 tests should PASS
```

**Commit**:
```bash
git add src/frontend/tests/e2e/user-mapping-upload.spec.ts
git commit -m "test(user-mapping): add E2E tests for upload functionality (10 scenarios)"
```

---

### Phase 6: Documentation & Polish (1.5 hours)

#### Step 12: Update CLAUDE.md

**File**: `CLAUDE.md` (modify existing)

**Add to "Key Entities" section** (around line 180):
```markdown
### UserMapping (NEW - Feature 013)
- **Fields**: id, email, awsAccountId (12 digits), domain, createdAt, updatedAt
- **Validation**: Email format, AWS account format (12 digits), domain format
- **Relationships**: Independent (no FK to User entity - may reference future users)
- **Indexes**: Unique composite (email, awsAccountId, domain), individual indexes on each field
- **Access**: ADMIN role only for upload and view
- **Purpose**: Foundation for multi-tenant RBAC across AWS accounts and domains
```

**Add to "API Endpoints" section** (around line 450):
```markdown
### User Mapping Upload (NEW - Feature 013)
- `POST /api/import/upload-user-mappings` - Upload user mapping Excel file (ADMIN only)
  - Request: multipart/form-data with xlsxFile
  - Response: ImportResult { message, imported, skipped, errors[] }
  - Validation: Email format, AWS account (12 digits), domain format
  - Behavior: Skip invalid/duplicate rows, continue with valid ones
```

**Add to "Recent Changes" section** (top of file):
```markdown
- 013-user-mapping-upload: User Mapping with AWS Account & Domain Upload (2025-10-07) - Excel upload for email-AWS-domain mappings, ADMIN-only access, comprehensive validation and duplicate handling, foundation for future RBAC
```

**Commit**:
```bash
git add CLAUDE.md
git commit -m "docs(user-mapping): update CLAUDE.md with new entity and endpoints"
```

---

#### Step 13: Add Inline Documentation

**Review all files and add/improve**:
- **UserMapping.kt**: Class-level KDoc describing purpose and business rules
- **UserMappingRepository.kt**: Method-level KDoc for each query method
- **UserMappingImportService.kt**: Class-level and method-level KDoc
- **ImportController.uploadUserMappings**: Method-level KDoc with endpoint details
- **userMappingService.ts**: JSDoc for uploadUserMappings function
- **UserMappingUpload.tsx**: Component-level JSDoc

**Example KDoc**:
```kotlin
/**
 * Import user mappings from Excel file.
 * 
 * Parses Excel file with columns: Email Address, AWS Account ID, Domain.
 * Validates each row and creates UserMapping records.
 * Skips invalid rows and duplicates, continues processing valid rows.
 * 
 * @param inputStream Excel file input stream (.xlsx format)
 * @return ImportResult with counts of imported/skipped records and error details
 * @throws IllegalArgumentException if file format is invalid or headers are missing
 */
@Transactional
open fun importFromExcel(inputStream: InputStream): ImportResult
```

**Commit**:
```bash
git add -u
git commit -m "docs(user-mapping): add comprehensive inline documentation (KDoc/JSDoc)"
```

---

#### Step 14: Code Review & Linting

**Checklist**:
- [ ] Remove any console.log statements from production code
- [ ] Remove unused imports
- [ ] Resolve all TODOs or document as future work
- [ ] Consistent naming conventions (lowerCamelCase, UpperCamelCase)
- [ ] Error messages are user-friendly (not technical stack traces)
- [ ] No hardcoded values (use constants: MAX_FILE_SIZE, REQUIRED_HEADERS)
- [ ] All nullable types handled correctly (safe calls, elvis operator)
- [ ] All async operations have error handling (try-catch)
- [ ] Code follows project style guide (4-space indent for Kotlin, 2-space for TypeScript)

**Run Linters**:
```bash
# Backend
./gradlew ktlintCheck
./gradlew ktlintFormat

# Frontend
npm run lint
npm run lint:fix
```

**Commit**:
```bash
git add -u
git commit -m "refactor(user-mapping): code cleanup and linting fixes"
```

---

### Phase 7: QA & Testing (1 hour)

#### Step 15: Manual Testing

**Test Scenarios** (13 scenarios):
1. Login as admin → Admin page → "User Mappings" card visible ✓
2. Click "Manage Mappings" → Upload page loads ✓
3. Download sample file → Opens in Excel correctly ✓
4. Upload sample file → Success message with counts ✓
5. Check database → Records exist with correct data ✓
6. Upload same file again → Duplicates skipped ✓
7. Upload file with 100 rows → Performance acceptable (<5s) ✓
8. Upload file with invalid data → Partial success, errors shown ✓
9. Login as regular user → Access denied on /admin/user-mappings ✓
10. Test in Chrome → Works ✓
11. Test in Firefox → Works ✓
12. Test in Safari → Works ✓
13. Test responsive layout (mobile, tablet) → Works ✓

**Database Verification**:
```sql
-- Connect to database
mysql -u root -p secman

-- Check table was created
SHOW CREATE TABLE user_mapping;

-- Verify indexes
SHOW INDEX FROM user_mapping;

-- Check sample data
SELECT * FROM user_mapping LIMIT 10;

-- Verify unique constraint
-- Try inserting duplicate manually (should fail)
INSERT INTO user_mapping (email, aws_account_id, domain, created_at, updated_at)
VALUES ('test@example.com', '123456789012', 'example.com', NOW(), NOW());
-- Should succeed

INSERT INTO user_mapping (email, aws_account_id, domain, created_at, updated_at)
VALUES ('test@example.com', '123456789012', 'example.com', NOW(), NOW());
-- Should FAIL with duplicate key error
```

---

#### Step 16: Performance Testing

**Test Scenarios**:
```bash
# 1. Create 100-row test file
python scripts/generate_test_file.py --rows 100 --output testdata/user-mappings-100.xlsx

# 2. Upload via UI and measure time
# Expected: <2 seconds
# Actual: ___ seconds

# 3. Create 1000-row test file
python scripts/generate_test_file.py --rows 1000 --output testdata/user-mappings-1000.xlsx

# 4. Upload via UI and measure time
# Expected: <10 seconds
# Actual: ___ seconds

# 5. Test database query performance
mysql -u root -p secman -e "
SET profiling = 1;
SELECT * FROM user_mapping WHERE email = 'test@example.com';
SHOW PROFILES;
"
# Expected: <10ms
# Actual: ___ ms
```

**Document Results**:
```markdown
# Performance Test Results

| Test | Target | Actual | Status |
|------|--------|--------|--------|
| 100 rows upload | <2s | 1.2s | ✅ PASS |
| 1000 rows upload | <10s | 8.5s | ✅ PASS |
| Query by email | <10ms | 3ms | ✅ PASS |
| Page load time | <1s | 0.8s | ✅ PASS |
```

---

#### Step 17: Security Testing

**Test Scenarios**:
1. Anonymous request → 401 Unauthorized ✓
2. USER role request → 403 Forbidden ✓
3. ADMIN role request → 200 OK ✓
4. File size limit → Rejection of 11MB file ✓
5. File type validation → Rejection of .csv file ✓
6. SQL injection in email field → No database impact ✓
7. XSS attempt in domain field → No script execution ✓
8. Path traversal in filename → No file system access ✓

**Security Checklist**:
- [ ] All endpoints require authentication
- [ ] ADMIN role properly enforced
- [ ] File upload size limit enforced (10MB)
- [ ] File type validation enforced (.xlsx only)
- [ ] Input validation effective (email, AWS account, domain)
- [ ] No sensitive data in logs (no password, JWT token logging)
- [ ] Error messages don't leak system information
- [ ] CORS properly configured (if needed)

---

### Phase 8: Deployment

**Pre-Deployment Checklist**:
- [ ] All 38 tasks completed (see tasks.md)
- [ ] All tests passing (unit: 27, integration: 7, E2E: 10)
- [ ] Code reviewed and approved
- [ ] Documentation updated (CLAUDE.md)
- [ ] Performance targets met
- [ ] Security testing passed
- [ ] Manual testing completed
- [ ] Linting passed (no warnings)

**Deployment Steps**:

#### Step 1: Merge Feature Branch
```bash
# Ensure all tests pass
./gradlew test
npm test

# Build production artifacts
./gradlew build
npm run build

# Merge to develop
git checkout develop
git merge 013-user-mapping-upload
git push origin develop
```

#### Step 2: Deploy to Staging
```bash
# Deploy to staging environment
docker-compose -f docker-compose.staging.yml up -d

# Check logs
docker-compose logs -f backend
docker-compose logs -f frontend

# Verify database migration
docker-compose exec db mysql -u root -p secman -e "SHOW CREATE TABLE user_mapping;"

# Smoke test: Upload sample file via staging UI
# Navigate to https://staging.secman.example.com/admin/user-mappings
# Upload sample file
# Verify success
```

#### Step 3: QA Sign-off
- [ ] QA team tests all scenarios on staging
- [ ] No critical bugs found
- [ ] Performance acceptable
- [ ] Security controls verified
- [ ] QA sign-off obtained

#### Step 4: Deploy to Production
```bash
# Merge to main
git checkout main
git merge develop
git tag v1.13.0
git push origin main --tags

# Deploy to production
docker-compose -f docker-compose.prod.yml up -d

# Monitor logs for 5 minutes
docker-compose logs -f --tail=100

# Smoke test in production
# Upload sample file via production UI
# Verify database records created

# Monitor for 24 hours
# Check error logs
# Check performance metrics
# Respond to any issues
```

#### Step 5: Post-Deployment
```bash
# Send announcement to admin users
Subject: New Feature: User Mapping Upload

Body:
We've added a new feature to manage user-to-AWS-account-to-domain mappings.

What's New:
- Upload Excel files with user mappings
- Admin → User Mappings
- Download sample template
- Comprehensive validation and error reporting

Documentation: [link to CLAUDE.md]

Questions? Contact support.

# Monitor usage
# Check database for uploaded mappings
# Collect feedback from admins
```

---

## 🧪 Test Summary

### Test Coverage

| Test Type | File | Scenarios | Status |
|-----------|------|-----------|--------|
| **Backend Unit Tests** | | | |
| Entity | UserMappingTest.kt | 8 | 📋 Ready |
| Repository | UserMappingRepositoryTest.kt | 9 | 📋 Ready |
| Service | UserMappingImportServiceTest.kt | 10 | 📋 Ready |
| **Backend Integration Tests** | | | |
| Controller | ImportControllerTest.kt | 7 | 📋 Ready |
| **Frontend E2E Tests** | | | |
| Upload | user-mapping-upload.spec.ts | 10 | 📋 Ready |
| **Total** | **5 files** | **44 scenarios** | **100% Planned** |

### Test Data Files

**Backend Test Files** (`src/backendng/src/test/resources/user-mapping-test-files/`):
- user-mappings-valid.xlsx
- user-mappings-invalid-email.xlsx
- user-mappings-invalid-aws.xlsx
- user-mappings-duplicates.xlsx
- user-mappings-missing-column.xlsx
- user-mappings-empty.xlsx

**Frontend Test Files** (`testdata/`):
- user-mappings-valid.xlsx
- user-mappings-invalid-email.xlsx
- user-mappings-invalid-aws.xlsx
- user-mappings-invalid-aws-nonnumeric.xlsx
- user-mappings-invalid-domain.xlsx
- user-mappings-duplicates.xlsx
- user-mappings-missing-column.xlsx
- user-mappings-empty.xlsx
- user-mappings-large.csv

**Production Sample** (`src/frontend/public/sample-files/`):
- user-mapping-template.xlsx

---

## 📊 Implementation Statistics

### Code Metrics (Estimated)

| Component | Files | Lines of Code | Tests | Test Lines |
|-----------|-------|---------------|-------|------------|
| **Backend** | | | | |
| Domain | 1 | 95 | 1 | 145 |
| Repository | 1 | 40 | 1 | 200 |
| Service | 1 | 350 | 1 | 400 |
| Controller | 1 (modify) | 50 | 1 (modify) | 150 |
| **Frontend** | | | | |
| Service | 1 | 25 | - | - |
| Component | 1 | 140 | - | - |
| Page | 1 | 20 | - | - |
| Admin Card | 1 (modify) | 15 | - | - |
| **E2E Tests** | 1 | - | - | 350 |
| **Total** | **10** | **~735** | **5** | **~1,245** |

### Effort Breakdown

| Phase | Tasks | Estimated | Actual | Notes |
|-------|-------|-----------|--------|-------|
| 1. Backend Foundation | 3 | 2h | - | Entity + Repository |
| 2. Backend Service | 2 | 3h | - | Import Service |
| 3. Backend API | 2 | 2h | - | Controller |
| 4. Frontend | 4 | 2.5h | - | Service + Component + Integration |
| 5. Tests & Data | 12 | 2.5h | - | Sample + E2E |
| 6. Documentation | 7 | 1.5h | - | Docs + Linting |
| 7. QA | 3 | 1h | - | Manual + Performance + Security |
| 8. Deployment | - | 1.5h | - | Staging + Production |
| **Total** | **38** | **16h** | **__h** | **2-3 days** |

---

## 🔒 Security Considerations

### Authentication & Authorization
- **JWT Required**: All endpoints require valid JWT token
- **Role Enforcement**: @Secured("ADMIN") prevents non-admin access
- **Session Management**: JWT stored in sessionStorage (frontend)

### Input Validation
- **File Validation**: Size (10MB), type (.xlsx), not empty
- **Email Validation**: Basic format check (contains @)
- **AWS Account ID**: Exactly 12 numeric digits
- **Domain Validation**: Alphanumeric + dots + hyphens only

### Data Protection
- **No Sensitive Data**: Mappings are configuration data (not passwords/secrets)
- **Audit Trail**: createdAt timestamp for all records
- **No Personal Data**: Email is business identifier, not PII in this context

### Error Handling
- **No Stack Traces**: User-friendly error messages only
- **Partial Success**: Invalid rows skipped, processing continues
- **Detailed Logging**: Server-side logging for debugging (not exposed to users)

---

## 🚨 Known Limitations & Future Enhancements

### Phase 1 Limitations (Current)
- **Upload Only**: No UI to browse, search, edit, or delete individual mappings
- **No Export**: Cannot download current mappings as Excel
- **No User Sync**: UserMapping.email not validated against User table
- **No AWS Validation**: AWS account IDs not verified against actual AWS Organization
- **No Domain Validation**: Domains not verified against DNS or corporate directory
- **No Expiration**: Mappings are permanent (no validFrom/validTo dates)
- **No Bulk Delete**: Cannot delete mappings by criteria (e.g., all for domain)

### Future Enhancements (Post-Phase 1)
1. **CRUD UI** (P1): Browse, search, filter, edit, delete individual mappings
2. **Export** (P2): Download current mappings as Excel/CSV
3. **RBAC Integration** (P0): Use mappings in asset/vulnerability access control
4. **User Sync** (P2): Link mappings to User entities, show in user profile
5. **AWS Integration** (P3): Validate AWS account IDs against AWS Organizations API
6. **Domain Validation** (P3): Verify domains against corporate Active Directory
7. **Analytics Dashboard** (P3): Show mapping distribution, coverage gaps
8. **CSV Support** (P3): Accept CSV files in addition to Excel
9. **Mapping Expiration** (P2): Add validFrom/validTo for time-bound access
10. **Audit History** (P2): Track who created/modified/deleted mappings

---

## 📞 Troubleshooting Guide

### Issue: Tests fail with "table not found"
**Symptom**: JPA tests fail with SQL exception about user_mapping table
**Solution**:
```bash
./gradlew clean build
# Hibernate will auto-create table on next test run
```

### Issue: Excel parsing fails with scientific notation
**Symptom**: AWS account "123456789012" becomes "1.23E+11"
**Solution**: Use DataFormatter in service
```kotlin
val formatter = DataFormatter()
val cellValue = formatter.formatCellValue(cell)
// NOT: cell.numericCellValue.toLong().toString()
```

### Issue: Frontend build fails
**Symptom**: npm run build fails with module not found
**Solution**:
```bash
cd src/frontend
rm -rf node_modules package-lock.json
npm install
npm run build
```

### Issue: E2E tests flaky
**Symptom**: Tests pass locally, fail in CI
**Solution**: Add explicit waits
```typescript
await page.waitForSelector('.alert-success', { timeout: 10000 });
await expect(page.locator('.alert-success')).toContainText('Imported');
```

### Issue: File upload times out
**Symptom**: Large files timeout after 30 seconds
**Solution**: Increase timeout in controller
```kotlin
@Post("/upload-user-mappings", timeout = "2m")
```

### Issue: Unique constraint violation in production
**Symptom**: Users report "duplicate key error" when uploading
**Solution**: This is expected behavior (duplicate skipped). Verify duplicate detection works:
```kotlin
// Should skip duplicates gracefully
if (!repository.existsByEmailAndAwsAccountIdAndDomain(...)) {
    repository.save(mapping)
}
```

---

## 📋 Checklist: Ready for Production

### Code Quality
- [ ] All 38 tasks completed (see tasks.md)
- [ ] Code reviewed by Tech Lead
- [ ] Linting passed (ktlint + eslint)
- [ ] No console.log statements in production code
- [ ] No TODOs left unresolved
- [ ] Error messages are user-friendly

### Testing
- [ ] All unit tests pass (27 scenarios)
- [ ] All integration tests pass (7 scenarios)
- [ ] All E2E tests pass (10 scenarios)
- [ ] Manual testing completed (13 scenarios)
- [ ] Performance testing passed (4 targets met)
- [ ] Security testing passed (8 controls verified)
- [ ] Test coverage >80% (measured)

### Documentation
- [ ] CLAUDE.md updated (entity, endpoints, recent changes)
- [ ] Inline KDoc/JSDoc added to all public APIs
- [ ] Sample Excel template created and downloadable
- [ ] README.md updated with feature overview
- [ ] This IMPLEMENTATION.md completed

### Infrastructure
- [ ] Database migration tested (Hibernate auto-create)
- [ ] Docker build succeeds (backend + frontend)
- [ ] Staging deployment successful
- [ ] Smoke test passed on staging
- [ ] Rollback plan documented

### Operations
- [ ] Monitoring alerts configured
- [ ] Error tracking enabled (Sentry/similar)
- [ ] Performance metrics tracked
- [ ] Backup strategy confirmed
- [ ] On-call support notified

### Communication
- [ ] Product Owner approved
- [ ] QA sign-off obtained
- [ ] Admin users notified of new feature
- [ ] Support team trained
- [ ] Documentation link shared

---

## 🎓 Learning Resources

### Before Implementation
Study these existing features for patterns:

1. **VulnerabilityImportService** - Excel parsing reference
   - File: `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityImportService.kt`
   - Patterns: Apache POI usage, row validation, error handling

2. **ImportController** - File upload reference
   - File: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt`
   - Patterns: Multipart handling, file validation, @Secured annotation

3. **Workgroup Entity** - JPA entity reference
   - File: `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`
   - Patterns: Indexes, unique constraints, validation annotations

4. **RequirementsAdmin Component** - File upload UI reference
   - File: `src/frontend/src/components/RequirementsAdmin.tsx`
   - Patterns: File input, upload button, result display

---

## ✅ Definition of Done

**Feature is COMPLETE when**:
- ✅ All 38 tasks marked complete in tasks.md
- ✅ All tests passing (unit + integration + E2E = 44 scenarios)
- ✅ Code reviewed and approved by Tech Lead
- ✅ Linting passed with no warnings
- ✅ Documentation updated (CLAUDE.md + inline docs)
- ✅ Manual testing completed (13 scenarios passed)
- ✅ Performance targets met (<10s for 1000 rows)
- ✅ Security testing passed (8 controls verified)
- ✅ Deployed to staging and smoke tested
- ✅ QA sign-off obtained
- ✅ Deployed to production
- ✅ Admin users notified
- ✅ Monitoring confirms feature working

---

## 📝 Version History

- **v1.0** (2025-10-07): Initial implementation guide created
  - Complete planning documentation (6,208 lines)
  - 38 granular tasks defined
  - Step-by-step execution guide
  - Test strategy with 44 scenarios
  - Security and performance considerations

---

## ✍️ Sign-off

### Development
- [ ] Backend Developer: _______________ Date: _______
- [ ] Frontend Developer: _______________ Date: _______

### Quality Assurance
- [ ] QA Lead: _______________ Date: _______
- [ ] Performance: ✅ Meets targets
- [ ] Security: ✅ Controls verified

### Operations
- [ ] DevOps: _______________ Date: _______
- [ ] Staging: ✅ Deployed
- [ ] Production: ✅ Deployed

### Management
- [ ] Product Owner: _______________ Date: _______
- [ ] Tech Lead: _______________ Date: _______

**Status**: 📋 **READY FOR IMPLEMENTATION**

---

**Document Created**: 2025-10-07  
**Last Updated**: 2025-10-07  
**Implementation Guide Version**: 1.0

---

# 🚀 Ready to Start?

This implementation guide provides everything you need. Follow these steps:

1. **Read this document** (IMPLEMENTATION.md) - You are here ✓
2. **Review tasks.md** - See 38 granular tasks
3. **Create feature branch**: `git checkout -b 013-user-mapping-upload`
4. **Start with T001** (Setup & Verification)
5. **Follow TDD**: Phase 2 (tests) BEFORE Phase 3 (implementation)
6. **Reference PLAN_EXECUTION.md** for complete code examples
7. **Track progress** in tasks.md Progress Tracking Table
8. **Commit frequently** with conventional commit messages

**Good luck! 🎉**
