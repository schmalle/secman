# Implementation Plan: User Mapping Upload

**Feature**: 013-user-mapping-upload
**Created**: 2025-10-07
**Status**: Draft

---

## Overview

This plan outlines the implementation steps for the User Mapping Upload feature, following TDD principles and the project's existing architectural patterns.

---

## Architecture Overview

### Component Stack
```
┌─────────────────────────────────────────┐
│  Frontend (Astro + React)               │
│  - Admin Page: User Mappings Card       │
│  - Upload Component: File selector      │
│  - Results Display: Import summary      │
└─────────────────────────────────────────┘
                  ↓ HTTP POST
┌─────────────────────────────────────────┐
│  Backend (Micronaut + Kotlin)           │
│  - ImportController: Upload endpoint    │
│  - UserMappingImportService: Logic      │
│  - UserMappingRepository: Data access   │
│  - UserMapping Entity: Domain model     │
└─────────────────────────────────────────┘
                  ↓ JPA
┌─────────────────────────────────────────┐
│  Database (MariaDB)                     │
│  - user_mapping table                   │
│  - Indexes for query optimization       │
└─────────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: Backend Foundation (Domain & Data)
**Duration**: ~2 hours
**Dependencies**: None

#### Tasks
1. Create UserMapping entity (JPA)
2. Create UserMappingRepository interface (Micronaut Data)
3. Write unit tests for entity constraints
4. Database migration script (if needed, Hibernate will auto-create)

#### Success Criteria
- Entity can be persisted and retrieved
- Unique constraint enforced
- Validation rules work as expected

---

### Phase 2: Backend Service Layer
**Duration**: ~3 hours
**Dependencies**: Phase 1

#### Tasks
1. Create UserMappingImportService
   - Parse Excel file (Apache POI)
   - Validate rows (email, AWS account ID, domain)
   - Bulk insert with duplicate handling
   - Return import summary
2. Write unit tests for service methods
   - Valid file processing
   - Invalid data handling
   - Duplicate detection
   - Empty file handling

#### Success Criteria
- Service can parse Excel with 3 columns
- Validation catches all invalid formats
- Duplicates are skipped gracefully
- Import summary is accurate

---

### Phase 3: Backend API Controller
**Duration**: ~2 hours
**Dependencies**: Phase 2

#### Tasks
1. Add endpoint to ImportController: `POST /api/import/upload-user-mappings`
2. File validation (size, format, content-type)
3. ADMIN role security annotation
4. Exception handling and error responses
5. Write controller integration tests

#### Success Criteria
- Endpoint accepts .xlsx files
- Returns 403 for non-admin users
- Returns structured import summary
- Error responses are clear

---

### Phase 4: Frontend Admin UI
**Duration**: ~2 hours
**Dependencies**: Phase 3

#### Tasks
1. Add "User Mappings" card to AdminPage.tsx
2. Create UserMappingUpload.tsx component
   - File upload form
   - Sample file download link
   - Import results display
3. Add frontend service: userMappingService.ts
4. Add admin page route: /admin/user-mappings.astro

#### Success Criteria
- Admin page has new card
- Upload form is intuitive
- Sample file downloads correctly
- Results display is clear

---

### Phase 5: E2E Testing
**Duration**: ~2 hours
**Dependencies**: Phase 4

#### Tasks
1. Create E2E test file: user-mapping-upload.spec.ts
2. Test scenarios:
   - Valid file upload
   - Invalid file types
   - Invalid data rows
   - Duplicate handling
   - Non-admin access denied
   - Empty file handling
3. Generate test data files (valid/invalid Excel files)

#### Success Criteria
- All acceptance scenarios pass
- Edge cases covered
- Tests are stable and reproducible

---

### Phase 6: Documentation & Polish
**Duration**: ~1 hour
**Dependencies**: Phase 5

#### Tasks
1. Update CLAUDE.md with new entity and endpoints
2. Create sample Excel template file
3. Add inline code documentation
4. Update API documentation (if OpenAPI spec exists)
5. Code review and cleanup

#### Success Criteria
- Documentation is complete and accurate
- Sample file is user-friendly
- Code is clean and well-commented

---

## Detailed Component Specifications

### 1. UserMapping Entity
**File**: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`

```kotlin
@Entity
@Table(
    name = "user_mapping",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_mapping_composite",
            columnNames = ["email", "aws_account_id", "domain"]
        )
    ],
    indexes = [
        Index(name = "idx_user_mapping_email", columnList = "email"),
        Index(name = "idx_user_mapping_aws_account", columnList = "aws_account_id"),
        Index(name = "idx_user_mapping_domain", columnList = "domain"),
        Index(name = "idx_user_mapping_email_aws", columnList = "email,aws_account_id")
    ]
)
@Serdeable
data class UserMapping(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 255)
    @Email
    @NotBlank
    var email: String,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    @NotBlank
    @Pattern(regexp = "^\\d{12}$")
    var awsAccountId: String,

    @Column(nullable = false, length = 255)
    @NotBlank
    @Pattern(regexp = "^[a-z0-9.-]+$")
    var domain: String,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
        // Normalize email and domain to lowercase
        email = email.lowercase().trim()
        domain = domain.lowercase().trim()
        awsAccountId = awsAccountId.trim()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
```

---

### 2. UserMappingRepository
**File**: `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`

```kotlin
@Repository
interface UserMappingRepository : JpaRepository<UserMapping, Long> {
    
    /**
     * Find all mappings for a specific email address
     * Used for user access control lookups
     */
    fun findByEmail(email: String): List<UserMapping>
    
    /**
     * Find all mappings for a specific AWS account
     * Used for account-based access control
     */
    fun findByAwsAccountId(awsAccountId: String): List<UserMapping>
    
    /**
     * Find all mappings for a specific domain
     * Used for domain-based access control
     */
    fun findByDomain(domain: String): List<UserMapping>
    
    /**
     * Check if specific mapping exists
     * Used for duplicate detection during upload
     */
    fun existsByEmailAndAwsAccountIdAndDomain(
        email: String, 
        awsAccountId: String, 
        domain: String
    ): Boolean
    
    /**
     * Find specific mapping (for update scenarios in future)
     */
    fun findByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String,
        domain: String
    ): Optional<UserMapping>
}
```

---

### 3. UserMappingImportService
**File**: `src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt`

```kotlin
@Singleton
open class UserMappingImportService(
    private val userMappingRepository: UserMappingRepository
) {
    private val log = LoggerFactory.getLogger(UserMappingImportService::class.java)
    
    companion object {
        private val REQUIRED_HEADERS = listOf(
            "Email Address", "AWS Account ID", "Domain"
        )
        private const val AWS_ACCOUNT_ID_PATTERN = "^\\d{12}$"
        private const val DOMAIN_PATTERN = "^[a-z0-9.-]+$"
    }

    @Serdeable
    data class ImportResult(
        val message: String,
        val imported: Int,
        val skipped: Int,
        val errors: List<String> = emptyList()
    )

    @Transactional
    open fun importFromExcel(inputStream: InputStream): ImportResult {
        val workbook = XSSFWorkbook(inputStream)
        val sheet = workbook.getSheetAt(0) 
            ?: throw IllegalArgumentException("Excel file has no sheets")

        // Validate headers
        val headerValidation = validateHeaders(sheet)
        if (headerValidation != null) {
            throw IllegalArgumentException(headerValidation)
        }

        val headerMap = getHeaderMapping(sheet)
        val errors = mutableListOf<String>()
        var imported = 0
        var skipped = 0

        // Process rows (skip header row 0)
        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            
            try {
                val mapping = parseRowToUserMapping(row, headerMap)
                if (mapping != null) {
                    // Check for duplicate
                    if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                        mapping.email, mapping.awsAccountId, mapping.domain
                    )) {
                        skipped++
                        log.debug("Skipped duplicate mapping: {}", mapping)
                    } else {
                        userMappingRepository.save(mapping)
                        imported++
                    }
                }
            } catch (e: Exception) {
                skipped++
                errors.add("Row ${rowIndex + 1}: ${e.message}")
                log.warn("Failed to parse row {}: {}", rowIndex + 1, e.message)
            }
        }

        workbook.close()

        return ImportResult(
            message = "Imported $imported mappings, skipped $skipped",
            imported = imported,
            skipped = skipped,
            errors = errors.take(20) // Limit error list to first 20
        )
    }

    private fun validateHeaders(sheet: Sheet): String? {
        val headerRow = sheet.getRow(0) ?: return "Header row not found"
        
        val actualHeaders = mutableListOf<String>()
        for (cellIndex in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(cellIndex)
            if (cell != null) {
                actualHeaders.add(getCellValueAsString(cell).trim())
            }
        }

        val missingHeaders = REQUIRED_HEADERS.filter { required ->
            actualHeaders.none { it.equals(required, ignoreCase = true) }
        }

        if (missingHeaders.isNotEmpty()) {
            return "Missing required headers: ${missingHeaders.joinToString(", ")}"
        }

        return null
    }

    private fun getHeaderMapping(sheet: Sheet): Map<String, Int> {
        val headerRow = sheet.getRow(0)
        val headerMap = mutableMapOf<String, Int>()

        for (cellIndex in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(cellIndex)
            if (cell != null) {
                val headerName = getCellValueAsString(cell).trim()
                REQUIRED_HEADERS.forEach { required ->
                    if (headerName.equals(required, ignoreCase = true)) {
                        headerMap[required] = cellIndex
                    }
                }
            }
        }

        return headerMap
    }

    private fun parseRowToUserMapping(row: Row, headerMap: Map<String, Int>): UserMapping? {
        val email = getCellValue(row, headerMap, "Email Address")?.trim()
        val awsAccountId = getCellValue(row, headerMap, "AWS Account ID")?.trim()
        val domain = getCellValue(row, headerMap, "Domain")?.trim()

        // Validate required fields
        if (email.isNullOrBlank()) {
            throw IllegalArgumentException("Email address is required")
        }
        if (awsAccountId.isNullOrBlank()) {
            throw IllegalArgumentException("AWS Account ID is required")
        }
        if (domain.isNullOrBlank()) {
            throw IllegalArgumentException("Domain is required")
        }

        // Validate email format
        if (!email.contains("@")) {
            throw IllegalArgumentException("Invalid email format: $email")
        }

        // Validate AWS account ID format (12 digits)
        if (!awsAccountId.matches(Regex(AWS_ACCOUNT_ID_PATTERN))) {
            throw IllegalArgumentException("Invalid AWS Account ID: must be 12 numeric digits")
        }

        // Validate domain format
        val normalizedDomain = domain.lowercase()
        if (!normalizedDomain.matches(Regex(DOMAIN_PATTERN))) {
            throw IllegalArgumentException("Invalid domain format: $domain")
        }

        return UserMapping(
            email = email.lowercase(),
            awsAccountId = awsAccountId,
            domain = normalizedDomain
        )
    }

    private fun getCellValue(row: Row, headerMap: Map<String, Int>, headerName: String): String? {
        val cellIndex = headerMap[headerName] ?: return null
        val cell = row.getCell(cellIndex) ?: return null
        return getCellValueAsString(cell)
    }

    private fun getCellValueAsString(cell: Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                // Format numbers to preserve precision (important for AWS account IDs)
                val formatter = DataFormatter()
                formatter.formatCellValue(cell)
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    val evaluator = cell.sheet.workbook.creationHelper.createFormulaEvaluator()
                    val result = evaluator.evaluate(cell)
                    when (result.cellType) {
                        CellType.STRING -> result.stringValue
                        CellType.NUMERIC -> result.numberValue.toLong().toString()
                        CellType.BOOLEAN -> result.booleanValue.toString()
                        else -> ""
                    }
                } catch (e: Exception) {
                    ""
                }
            }
            else -> ""
        }
    }
}
```

---

### 4. ImportController Extension
**File**: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt`

Add to existing ImportController:

```kotlin
@Inject
private lateinit var userMappingImportService: UserMappingImportService

/**
 * Upload user mapping Excel file
 * 
 * Related to: Feature 013 (User Mapping Upload)
 * 
 * Endpoint: POST /api/import/upload-user-mappings
 * Request: multipart/form-data with xlsxFile
 * Response: ImportResult with counts (imported, skipped, errors)
 * Access: ADMIN only
 * 
 * Expected Excel format:
 * - Column 1: Email Address (required)
 * - Column 2: AWS Account ID (required, 12 digits)
 * - Column 3: Domain (required)
 * 
 * @param xlsxFile Excel file containing user mappings
 * @return Import response with counts and any error messages
 */
@Post("/upload-user-mappings")
@Consumes(MediaType.MULTIPART_FORM_DATA)
@Transactional
@Secured("ADMIN")
open fun uploadUserMappings(
    @Part xlsxFile: CompletedFileUpload
): HttpResponse<*> {
    return try {
        log.debug("Processing user mapping Excel file upload: {}", xlsxFile.filename)

        // Validate file
        val validation = validateVulnerabilityFile(xlsxFile) // Reuse existing validation
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

---

### 5. Frontend Service
**File**: `src/frontend/src/services/userMappingService.ts`

```typescript
import axios from 'axios';

interface ImportResult {
  message: string;
  imported: number;
  skipped: number;
  errors?: string[];
}

/**
 * Upload user mapping Excel file
 * 
 * @param file - Excel file containing user mappings
 * @returns Import result with counts
 * @throws Error if upload fails or user lacks permissions
 */
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

/**
 * Generate sample Excel file URL
 * 
 * @returns URL to download sample Excel template
 */
export function getSampleFileUrl(): string {
  return '/sample-files/user-mapping-template.xlsx';
}
```

---

### 6. Frontend Component
**File**: `src/frontend/src/components/UserMappingUpload.tsx`

```typescript
import React, { useState } from 'react';
import { uploadUserMappings, getSampleFileUrl } from '../services/userMappingService';

interface ImportResult {
  message: string;
  imported: number;
  skipped: number;
  errors?: string[];
}

const UserMappingUpload: React.FC = () => {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      setFile(e.target.files[0]);
      setResult(null);
      setError(null);
    }
  };

  const handleUpload = async () => {
    if (!file) {
      setError('Please select a file');
      return;
    }

    setUploading(true);
    setError(null);
    setResult(null);

    try {
      const importResult = await uploadUserMappings(file);
      setResult(importResult);
      setFile(null);
      // Reset file input
      const fileInput = document.getElementById('userMappingFile') as HTMLInputElement;
      if (fileInput) fileInput.value = '';
    } catch (err: any) {
      setError(err.response?.data?.error || err.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="container mt-4">
      <h2>User Mapping Upload</h2>
      <p className="text-muted">
        Upload an Excel file to create mappings between users, AWS accounts, and domains.
      </p>

      <div className="card mb-4">
        <div className="card-body">
          <h5 className="card-title">File Requirements</h5>
          <ul>
            <li>Format: Excel (.xlsx) only</li>
            <li>Max size: 10MB</li>
            <li>Required columns (case-insensitive):
              <ul>
                <li><strong>Email Address</strong>: User's email (e.g., john@example.com)</li>
                <li><strong>AWS Account ID</strong>: 12-digit numeric AWS account (e.g., 123456789012)</li>
                <li><strong>Domain</strong>: Organizational domain (e.g., example.com)</li>
              </ul>
            </li>
            <li>One mapping per row (repeat email for multiple mappings)</li>
            <li>Duplicate mappings will be skipped</li>
          </ul>
          <a 
            href={getSampleFileUrl()} 
            className="btn btn-outline-primary btn-sm"
            download="user-mapping-template.xlsx"
          >
            <i className="bi bi-download me-2"></i>
            Download Sample File
          </a>
        </div>
      </div>

      <div className="mb-3">
        <label htmlFor="userMappingFile" className="form-label">Select Excel File</label>
        <input
          type="file"
          className="form-control"
          id="userMappingFile"
          accept=".xlsx"
          onChange={handleFileChange}
          disabled={uploading}
        />
      </div>

      <button
        className="btn btn-primary"
        onClick={handleUpload}
        disabled={!file || uploading}
      >
        {uploading ? (
          <>
            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
            Uploading...
          </>
        ) : (
          <>
            <i className="bi bi-upload me-2"></i>
            Upload
          </>
        )}
      </button>

      {error && (
        <div className="alert alert-danger mt-3" role="alert">
          <i className="bi bi-exclamation-triangle me-2"></i>
          {error}
        </div>
      )}

      {result && (
        <div className="alert alert-success mt-3" role="alert">
          <h5 className="alert-heading">Import Complete</h5>
          <p>{result.message}</p>
          <hr />
          <p className="mb-0">
            <strong>Imported:</strong> {result.imported} mappings<br />
            <strong>Skipped:</strong> {result.skipped} (duplicates or invalid data)
          </p>
          {result.errors && result.errors.length > 0 && (
            <div className="mt-3">
              <strong>Errors:</strong>
              <ul className="mb-0">
                {result.errors.map((err, idx) => (
                  <li key={idx}><small>{err}</small></li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default UserMappingUpload;
```

---

### 7. Admin Page Update
**File**: `src/frontend/src/components/AdminPage.tsx`

Add new card (after MCP API Keys card):

```typescript
<div className="col-md-4 mb-3">
    <div className="card">
        <div className="card-body">
            <h5 className="card-title">
                <i className="bi bi-diagram-3-fill me-2"></i>
                User Mappings
            </h5>
            <p className="card-text">Upload and manage user-to-AWS-account-to-domain mappings for role-based access control.</p>
            <a href="/admin/user-mappings" className="btn btn-primary">
                Manage Mappings
            </a>
        </div>
    </div>
</div>
```

---

### 8. Admin Page Route
**File**: `src/frontend/src/pages/admin/user-mappings.astro`

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

---

## Testing Strategy

### Unit Tests (Backend)

#### UserMappingTest.kt
- Test entity validation (email format, AWS account ID format, domain format)
- Test unique constraint enforcement
- Test normalization (lowercase email/domain)

#### UserMappingRepositoryTest.kt
- Test CRUD operations
- Test query methods (findByEmail, findByAwsAccountId, findByDomain)
- Test duplicate detection

#### UserMappingImportServiceTest.kt
- Test valid file parsing
- Test invalid email handling
- Test invalid AWS account ID handling
- Test invalid domain handling
- Test duplicate detection
- Test empty file handling
- Test missing header detection

### Integration Tests (Backend)

#### ImportControllerTest.kt
- Test endpoint with valid file
- Test endpoint with invalid file types
- Test endpoint security (admin-only)
- Test error responses

### E2E Tests (Frontend)

#### user-mapping-upload.spec.ts
```typescript
test.describe('User Mapping Upload', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/user-mappings');
  });

  test('should upload valid user mapping file', async ({ page }) => {
    // Upload test file
    await page.setInputFiles('#userMappingFile', 'testdata/user-mappings-valid.xlsx');
    await page.click('button:has-text("Upload")');

    // Verify success message
    await expect(page.locator('.alert-success')).toContainText('Imported 5 mappings');
  });

  test('should reject invalid file type', async ({ page }) => {
    // Attempt to upload CSV file
    await page.setInputFiles('#userMappingFile', 'testdata/user-mappings.csv');
    await page.click('button:has-text("Upload")');

    // Verify error message
    await expect(page.locator('.alert-danger')).toContainText('Only .xlsx files are supported');
  });

  test('should skip duplicate mappings', async ({ page }) => {
    // Upload file twice
    await page.setInputFiles('#userMappingFile', 'testdata/user-mappings-valid.xlsx');
    await page.click('button:has-text("Upload")');
    await page.waitForSelector('.alert-success');

    // Upload again
    await page.setInputFiles('#userMappingFile', 'testdata/user-mappings-valid.xlsx');
    await page.click('button:has-text("Upload")');

    // Verify skipped message
    await expect(page.locator('.alert-success')).toContainText('Skipped: 5');
  });

  test('should deny access to non-admin users', async ({ page }) => {
    await loginAsUser(page);
    await page.goto('/admin/user-mappings');

    // Verify access denied
    await expect(page.locator('.alert-danger')).toContainText('Access Denied');
  });
});
```

---

## Test Data Files

### testdata/user-mappings-valid.xlsx
```
Email Address         | AWS Account ID | Domain
john@example.com      | 123456789012   | example.com
jane@example.com      | 123456789012   | example.com
john@example.com      | 987654321098   | example.com
admin@corp.com        | 111111111111   | corp.com
consultant@agency.com | 555555555555   | clientA.com
```

### testdata/user-mappings-invalid-email.xlsx
```
Email Address    | AWS Account ID | Domain
notanemail       | 123456789012   | example.com
john@example.com | 123456789012   | example.com
```

### testdata/user-mappings-invalid-aws-account.xlsx
```
Email Address    | AWS Account ID | Domain
john@example.com | 12345          | example.com
jane@example.com | ABC123456789   | example.com
```

### testdata/user-mappings-missing-column.xlsx
```
Email Address    | AWS Account ID
john@example.com | 123456789012
```

---

## Sample Excel Template

Create file: `src/frontend/public/sample-files/user-mapping-template.xlsx`

Content:
```
Email Address         | AWS Account ID | Domain
user1@example.com     | 123456789012   | example.com
user2@example.com     | 987654321098   | example.com
consultant@agency.com | 555555555555   | clientA.com
```

Instructions sheet:
```
INSTRUCTIONS

This template is used to upload user-to-AWS-account-to-domain mappings.

FORMAT:
- Column 1: Email Address (required) - User's email address
- Column 2: AWS Account ID (required) - 12-digit numeric AWS account identifier
- Column 3: Domain (required) - Organizational domain name

RULES:
1. One mapping per row
2. To map one user to multiple AWS accounts or domains, create multiple rows with the same email
3. Email addresses are case-insensitive and will be normalized to lowercase
4. AWS Account IDs must be exactly 12 numeric digits
5. Domain names must contain only letters, numbers, dots, and hyphens
6. Duplicate mappings (same email + AWS account + domain) will be automatically skipped
7. Invalid rows will be skipped, but the upload will continue for valid rows

LIMITS:
- Maximum file size: 10MB
- Recommended max rows: 10,000

EXAMPLES:
✅ Valid: john@example.com, 123456789012, example.com
✅ Valid: jane@example.com, 987654321098, sub.example.com
❌ Invalid: notanemail, 123456789012, example.com (bad email)
❌ Invalid: john@example.com, 12345, example.com (AWS account ID too short)
❌ Invalid: john@example.com, 123456789012, example .com (space in domain)
```

---

## Risk Analysis

### High Risk
- **Data corruption from invalid Excel parsing**: Mitigation - extensive validation and error handling
- **Performance issues with large files**: Mitigation - file size limit, batch processing

### Medium Risk
- **Duplicate handling ambiguity**: Mitigation - clear documentation and logging
- **AWS account ID format variations**: Mitigation - strict validation with clear error messages

### Low Risk
- **Email validation edge cases**: Mitigation - basic validation sufficient for Phase 1
- **Domain validation complexity**: Mitigation - simplified regex pattern

---

## Rollback Plan

If critical issues are discovered post-deployment:

1. **Disable upload endpoint**: Set feature flag or temporarily remove @Secured annotation
2. **Database rollback**: Drop `user_mapping` table (no foreign key dependencies)
3. **Frontend rollback**: Hide "User Mappings" card in AdminPage.tsx
4. **Communication**: Notify admins of temporary unavailability

---

## Success Criteria

### Functional
- [ ] Admin can upload valid Excel file successfully
- [ ] Invalid rows are skipped with clear error messages
- [ ] Duplicate mappings are handled gracefully
- [ ] Non-admin users cannot access upload page
- [ ] Sample file downloads correctly

### Non-Functional
- [ ] 1000-row file uploads in <10 seconds
- [ ] All unit tests pass (>80% coverage)
- [ ] All E2E tests pass
- [ ] No console errors or warnings
- [ ] Code follows existing project patterns

### Documentation
- [ ] CLAUDE.md updated
- [ ] API documentation complete
- [ ] Sample file created
- [ ] Inline code comments added

---

## Timeline

| Phase | Duration | Start | End |
|-------|----------|-------|-----|
| Phase 1: Backend Foundation | 2h | Day 1 | Day 1 |
| Phase 2: Backend Service | 3h | Day 1 | Day 2 |
| Phase 3: Backend API | 2h | Day 2 | Day 2 |
| Phase 4: Frontend UI | 2h | Day 2 | Day 2 |
| Phase 5: E2E Testing | 2h | Day 3 | Day 3 |
| Phase 6: Documentation | 1h | Day 3 | Day 3 |
| **Total** | **12h** | **Day 1** | **Day 3** |

---

## Dependencies

- Apache POI library (already included for Excel parsing)
- Existing authentication/authorization infrastructure
- Existing ImportController pattern
- Existing AdminPage structure

---

## Approval

- [ ] Tech Lead: _______________
- [ ] Backend Developer: _______________
- [ ] Frontend Developer: _______________
- [ ] QA Engineer: _______________
- [ ] Date: _______________
