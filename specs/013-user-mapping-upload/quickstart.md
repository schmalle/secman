# Quickstart: User Mapping Upload

**Feature**: 013-user-mapping-upload
**For**: Developers implementing this feature
**Time to implement**: ~12 hours

---

## 🎯 Goal

Enable administrators to upload Excel files containing user-to-AWS-account-to-domain mappings for future role-based access control.

---

## 📋 Prerequisites

- Backend: Micronaut 4.4, Kotlin 2.1, Java 21, Apache POI
- Frontend: Astro 5.14, React 19, TypeScript
- Database: MariaDB 11.4 (Hibernate auto-migration)
- Authentication: Existing JWT + ADMIN role system

---

## 🚀 Implementation Checklist

### Step 1: Create Domain Entity (30 min)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`

```kotlin
@Entity
@Table(name = "user_mapping", 
    uniqueConstraints = [UniqueConstraint(columnNames = ["email", "aws_account_id", "domain"])])
@Serdeable
data class UserMapping(
    @Id @GeneratedValue var id: Long? = null,
    @Column(nullable = false) @Email @NotBlank var email: String,
    @Column(name = "aws_account_id", nullable = false) @Pattern(regexp = "^\\d{12}$") var awsAccountId: String,
    @Column(nullable = false) @NotBlank var domain: String,
    @Column(name = "created_at", updatable = false) var createdAt: Instant? = null
) {
    @PrePersist fun onCreate() { 
        createdAt = Instant.now()
        email = email.lowercase().trim()
        domain = domain.lowercase().trim()
    }
}
```

**Test**: Create `UserMappingTest.kt` and verify entity creation, unique constraint, normalization.

---

### Step 2: Create Repository (15 min)

**File**: `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`

```kotlin
@Repository
interface UserMappingRepository : JpaRepository<UserMapping, Long> {
    fun findByEmail(email: String): List<UserMapping>
    fun existsByEmailAndAwsAccountIdAndDomain(email: String, awsAccountId: String, domain: String): Boolean
}
```

**Test**: Create `UserMappingRepositoryTest.kt` and verify CRUD, queries, duplicate detection.

---

### Step 3: Create Import Service (2 hours)

**File**: `src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt`

Key methods:
- `importFromExcel(InputStream): ImportResult` - Main entry point
- `validateHeaders(Sheet): String?` - Check required columns
- `parseRowToUserMapping(Row, Map<String, Int>): UserMapping?` - Parse and validate row
- `validateEmail(String): Boolean` - Email format check
- `validateAwsAccountId(String): Boolean` - 12-digit numeric check
- `validateDomain(String): Boolean` - Domain format check

**Test**: Create `UserMappingImportServiceTest.kt` with scenarios:
- Valid file import
- Invalid email/AWS account/domain handling
- Duplicate detection
- Empty file handling
- Missing headers

---

### Step 4: Add Controller Endpoint (30 min)

**File**: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt`

Add method:
```kotlin
@Post("/upload-user-mappings")
@Consumes(MediaType.MULTIPART_FORM_DATA)
@Secured("ADMIN")
open fun uploadUserMappings(@Part xlsxFile: CompletedFileUpload): HttpResponse<*>
```

Reuse existing validation methods:
- `validateVulnerabilityFile(CompletedFileUpload)` for file checks
- Follow existing error handling pattern

**Test**: Create `ImportControllerTest.kt` and verify:
- Successful upload returns 200 with ImportResult
- Non-admin returns 403
- Invalid file returns 400
- Large file returns 400

---

### Step 5: Create Frontend Service (30 min)

**File**: `src/frontend/src/services/userMappingService.ts`

```typescript
export async function uploadUserMappings(file: File): Promise<ImportResult> {
  const formData = new FormData();
  formData.append('xlsxFile', file);
  const response = await axios.post('/api/import/upload-user-mappings', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  });
  return response.data;
}
```

---

### Step 6: Create Upload Component (1.5 hours)

**File**: `src/frontend/src/components/UserMappingUpload.tsx`

Features:
- File input (accept=".xlsx")
- Upload button with loading state
- File requirements display
- Sample file download link
- Result display (imported/skipped counts, error list)
- Error handling (permission denied, file too large, invalid format)

**Design**: Follow existing patterns from RequirementsAdmin.tsx and VulnerabilityImport components.

---

### Step 7: Add Admin Page Card (15 min)

**File**: `src/frontend/src/components/AdminPage.tsx`

Add card:
```tsx
<div className="col-md-4 mb-3">
  <div className="card">
    <div className="card-body">
      <h5 className="card-title">
        <i className="bi bi-diagram-3-fill me-2"></i>User Mappings
      </h5>
      <p className="card-text">Upload user-to-AWS-account-to-domain mappings.</p>
      <a href="/admin/user-mappings" className="btn btn-primary">Manage Mappings</a>
    </div>
  </div>
</div>
```

---

### Step 8: Create Admin Route (15 min)

**File**: `src/frontend/src/pages/admin/user-mappings.astro`

```astro
---
import Layout from '../../layouts/Layout.astro';
import UserMappingUpload from '../../components/UserMappingUpload';
---
<Layout title="User Mappings - Admin">
  <div class="container-fluid">
    <nav aria-label="breadcrumb">...</nav>
    <UserMappingUpload client:load />
  </div>
</Layout>
```

---

### Step 9: Create Sample Excel File (30 min)

**File**: `src/frontend/public/sample-files/user-mapping-template.xlsx`

Create Excel with:
- **Sheet 1 "Mappings"**: Headers (Email Address, AWS Account ID, Domain) + 3 sample rows
- **Sheet 2 "Instructions"**: Detailed usage instructions

Test that file downloads correctly from frontend.

---

### Step 10: Create Test Data Files (30 min)

**Directory**: `testdata/`

Create:
- `user-mappings-valid.xlsx` - 5 valid rows
- `user-mappings-invalid-email.xlsx` - 1 invalid, 1 valid
- `user-mappings-invalid-aws.xlsx` - 1 invalid AWS account ID
- `user-mappings-duplicates.xlsx` - 3 rows, 1 duplicate
- `user-mappings-missing-column.xlsx` - Missing "Domain" column
- `user-mappings-empty.xlsx` - Only headers, no data

---

### Step 11: Write E2E Tests (2 hours)

**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts`

Test scenarios (10 tests):
1. Upload valid file → success message with counts
2. Upload file with invalid email → partial import, error details
3. Upload file with invalid AWS account ID → partial import
4. Upload file with missing column → error, no import
5. Upload duplicate mappings → skipped message
6. Upload empty file → error message
7. Upload oversized file (>10MB) → error message
8. Upload CSV instead of XLSX → error message
9. Non-admin user access → 403 access denied
10. Download sample file → verify file downloads

**Run**: `npm run test:e2e` and ensure all tests pass.

---

### Step 12: Update Documentation (1 hour)

#### Update CLAUDE.md

Add to **Key Entities**:
```markdown
### UserMapping (NEW - Feature 013)
- **Fields**: id, email, awsAccountId, domain, createdAt
- **Validation**: Email format, AWS account (12 digits), domain format
- **Relationships**: Independent (no FK to User)
- **Indexes**: Unique (email, awsAccountId, domain), individual indexes on each field
- **Access**: ADMIN role only for upload and view
```

Add to **API Endpoints**:
```markdown
### User Mapping Upload (NEW - Feature 013)
- `POST /api/import/upload-user-mappings` - Upload mapping Excel file (ADMIN only)
```

#### Create README in specs directory

**File**: `specs/013-user-mapping-upload/README.md`

Summary of feature, links to spec.md, data-model.md, plan.md.

---

## 🧪 Testing Checklist

### Unit Tests
- [ ] UserMapping entity validation
- [ ] UserMappingRepository queries
- [ ] UserMappingImportService parsing logic
- [ ] UserMappingImportService validation rules
- [ ] ImportController endpoint security

### Integration Tests
- [ ] Upload valid file end-to-end (controller → service → repository)
- [ ] Upload invalid file end-to-end
- [ ] Admin-only access enforcement

### E2E Tests
- [ ] All 10 scenarios pass
- [ ] Tests are stable (no flakiness)
- [ ] Playwright artifacts generated

### Manual Testing
- [ ] Upload sample file via UI
- [ ] Verify database records created
- [ ] Test with 100+ rows
- [ ] Test with max file size (10MB)
- [ ] Test as non-admin user

---

## 📊 Validation Patterns

### Email Validation
```kotlin
fun isValidEmail(email: String): Boolean {
    return email.contains("@") && 
           email.length >= 3 && 
           email.length <= 255
}
```

### AWS Account ID Validation
```kotlin
fun isValidAwsAccountId(accountId: String): Boolean {
    return accountId.matches(Regex("^\\d{12}$"))
}
```

### Domain Validation
```kotlin
fun isValidDomain(domain: String): Boolean {
    return domain.matches(Regex("^[a-z0-9.-]+$")) &&
           !domain.startsWith(".") &&
           !domain.endsWith(".") &&
           !domain.startsWith("-") &&
           !domain.endsWith("-")
}
```

---

## 🛠️ Common Issues & Solutions

### Issue 1: Excel Cell Type Handling
**Problem**: AWS account IDs get converted to scientific notation (1.23E+11)
**Solution**: Use `DataFormatter` to preserve exact string value
```kotlin
val formatter = DataFormatter()
val cellValue = formatter.formatCellValue(cell)
```

### Issue 2: Unique Constraint Violation
**Problem**: Batch insert fails on duplicate
**Solution**: Check existence before insert
```kotlin
if (!repository.existsByEmailAndAwsAccountIdAndDomain(...)) {
    repository.save(mapping)
}
```

### Issue 3: File Upload Size Limit
**Problem**: Files >10MB silently fail
**Solution**: Add explicit validation with clear error message
```kotlin
if (file.size > MAX_FILE_SIZE) {
    return HttpResponse.badRequest(ErrorResponse("File too large"))
}
```

### Issue 4: Empty Rows in Excel
**Problem**: Excel files often have trailing empty rows
**Solution**: Skip null rows
```kotlin
val row = sheet.getRow(rowIndex) ?: continue
```

---

## 🎨 UI/UX Guidelines

### Upload Button States
- **Idle**: Blue button "Upload" with upload icon
- **Loading**: Grey button "Uploading..." with spinner
- **Success**: Green alert with import summary
- **Error**: Red alert with error message

### Error Message Format
```
❌ Upload Failed

File contains invalid data:
• Row 5: Invalid email format: "notanemail"
• Row 8: AWS Account ID must be 12 digits: "12345"
• Row 12: Domain contains invalid characters: "example .com"

3 rows imported successfully, 3 rows skipped.
```

### Success Message Format
```
✅ Import Complete

5 mappings imported successfully
2 mappings skipped (duplicates or invalid data)

Details:
• Imported: 5 mappings
• Skipped: 2 (1 duplicate, 1 invalid email)
```

---

## 📈 Performance Targets

| Metric | Target | Measured |
|--------|--------|----------|
| Upload 100 rows | <2s | __ |
| Upload 1000 rows | <10s | __ |
| Upload 10MB file | <20s | __ |
| Database query (by email) | <10ms | __ |
| Page load time | <1s | __ |

---

## 🔍 Code Review Checklist

- [ ] Entity has all required JPA annotations
- [ ] Repository methods are tested
- [ ] Service validates all inputs
- [ ] Controller has @Secured("ADMIN") annotation
- [ ] Frontend component handles all error cases
- [ ] E2E tests cover all acceptance scenarios
- [ ] No console.log statements left in code
- [ ] Error messages are user-friendly
- [ ] Sample file is valid and downloadable
- [ ] Documentation is complete and accurate

---

## 🚢 Deployment Steps

1. Merge feature branch to main
2. Verify database migration runs (Hibernate auto-creates table)
3. Deploy backend (no config changes needed)
4. Deploy frontend (new admin page)
5. Upload sample file to `/sample-files/` directory
6. Test in production with small file (5 rows)
7. Monitor logs for errors
8. Announce feature to admins

---

## 📚 Reference Implementation

Look at existing similar features for patterns:
- **VulnerabilityImportService**: Excel parsing, validation, batch insert
- **ImportController**: File upload, multipart handling, error responses
- **RequirementsAdmin.tsx**: Admin page structure, file upload UI
- **Asset entity**: JPA annotations, indexes, validation

---

## 💡 Tips

1. **Start with TDD**: Write tests first, then implement
2. **Reuse existing code**: Don't reinvent validation/parsing logic
3. **Test with real data**: Create realistic test Excel files
4. **Handle errors gracefully**: Partial success is better than all-or-nothing
5. **Provide clear feedback**: Users need to know what went wrong and where
6. **Document as you go**: Update CLAUDE.md immediately after changes

---

## 🆘 Need Help?

- **Excel parsing issues**: Check ImportController.kt for existing patterns
- **JPA constraints**: Check Workgroup.kt for unique constraint example
- **Frontend file upload**: Check RequirementsAdmin.tsx component
- **E2E testing**: Check existing tests in `tests/e2e/` directory

---

## ✅ Definition of Done

- [ ] All unit tests pass (>80% coverage)
- [ ] All integration tests pass
- [ ] All E2E tests pass (10 scenarios)
- [ ] Manual testing complete (valid + invalid files)
- [ ] CLAUDE.md updated
- [ ] Sample file created and downloadable
- [ ] Code reviewed and approved
- [ ] Feature deployed to production
- [ ] Admin users notified

---

**Estimated Total Time**: 12 hours (1.5 days)
**Actual Time**: _____ hours

Good luck! 🚀
