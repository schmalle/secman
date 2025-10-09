# Implementation Plan Execution: User Mapping Upload

**Feature**: 013-user-mapping-upload  
**Branch**: `013-user-mapping-upload`  
**Status**: Ready to Execute  
**Execution Date**: TBD  
**Developer**: TBD

---

## 🎯 Pre-Implementation Checklist

Before starting implementation, ensure:

- [ ] All specification documents reviewed (README.md, spec.md, data-model.md, plan.md)
- [ ] Development environment ready (Java 21, Kotlin 2.1, Node.js, MariaDB running)
- [ ] Feature branch created: `git checkout -b 013-user-mapping-upload`
- [ ] Tests are passing on main branch: `./gradlew test && npm test`
- [ ] Docker environment running: `docker-compose up -d`

---

## 📋 Execution Plan

This plan follows **TDD (Test-Driven Development)** methodology:
1. Write failing tests
2. Implement minimal code to pass tests
3. Refactor for quality
4. Commit with conventional commit message

---

## Phase 1: Backend Foundation (2 hours)

### Step 1.1: Create UserMapping Entity (30 min)

**Goal**: Create JPA entity with all fields, validations, and indexes.

**Actions**:
```bash
# 1. Create entity file
touch src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
```

**Implementation**:
```kotlin
package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * UserMapping entity - stores mappings between user emails, AWS account IDs, and domains
 * 
 * Feature: 013-user-mapping-upload
 * Purpose: Enable role-based access control across multiple AWS accounts and domains
 * 
 * Business Rules:
 * - One email can map to multiple AWS accounts
 * - One email can map to multiple domains
 * - Unique constraint on (email, awsAccountId, domain) prevents duplicates
 * - Email and domain are normalized to lowercase
 * - AWS account IDs must be exactly 12 numeric digits
 * 
 * Related to: Feature 013 (User Mapping Upload)
 */
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
    @Email(message = "Invalid email format")
    @NotBlank(message = "Email address is required")
    var email: String,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    @NotBlank(message = "AWS Account ID is required")
    @Pattern(regexp = "^\\d{12}$", message = "AWS Account ID must be exactly 12 numeric digits")
    var awsAccountId: String,

    @Column(nullable = false, length = 255)
    @NotBlank(message = "Domain is required")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "Domain must contain only lowercase letters, numbers, dots, and hyphens")
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

    override fun toString(): String {
        return "UserMapping(id=$id, email='$email', awsAccountId='$awsAccountId', domain='$domain')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserMapping) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
```

**Test File**:
```bash
touch src/backendng/src/test/kotlin/com/secman/domain/UserMappingTest.kt
```

**Test Implementation**:
```kotlin
package com.secman.domain

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach

@MicronautTest
@Transactional
class UserMappingTest {

    @Inject
    lateinit var entityManager: EntityManager

    @AfterEach
    fun cleanup() {
        entityManager.createQuery("DELETE FROM UserMapping").executeUpdate()
    }

    @Test
    fun `should create user mapping with all fields`() {
        val mapping = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        entityManager.persist(mapping)
        entityManager.flush()
        
        assertNotNull(mapping.id)
        assertNotNull(mapping.createdAt)
        assertNotNull(mapping.updatedAt)
    }

    @Test
    fun `should normalize email to lowercase on persist`() {
        val mapping = UserMapping(
            email = "Test@Example.COM",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        entityManager.persist(mapping)
        entityManager.flush()
        entityManager.clear()
        
        val saved = entityManager.find(UserMapping::class.java, mapping.id)
        assertEquals("test@example.com", saved.email)
    }

    @Test
    fun `should normalize domain to lowercase on persist`() {
        val mapping = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "Example.COM"
        )
        
        entityManager.persist(mapping)
        entityManager.flush()
        entityManager.clear()
        
        val saved = entityManager.find(UserMapping::class.java, mapping.id)
        assertEquals("example.com", saved.domain)
    }

    @Test
    fun `should trim whitespace from all fields on persist`() {
        val mapping = UserMapping(
            email = "  test@example.com  ",
            awsAccountId = "  123456789012  ",
            domain = "  example.com  "
        )
        
        entityManager.persist(mapping)
        entityManager.flush()
        entityManager.clear()
        
        val saved = entityManager.find(UserMapping::class.java, mapping.id)
        assertEquals("test@example.com", saved.email)
        assertEquals("123456789012", saved.awsAccountId)
        assertEquals("example.com", saved.domain)
    }

    @Test
    fun `should enforce unique constraint on composite key`() {
        val mapping1 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        entityManager.persist(mapping1)
        entityManager.flush()

        val mapping2 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        assertThrows(Exception::class.java) {
            entityManager.persist(mapping2)
            entityManager.flush()
        }
    }

    @Test
    fun `should allow same email with different AWS account`() {
        val mapping1 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        entityManager.persist(mapping1)
        entityManager.flush()

        val mapping2 = UserMapping(
            email = "test@example.com",
            awsAccountId = "987654321098",
            domain = "example.com"
        )
        entityManager.persist(mapping2)
        entityManager.flush()
        
        assertNotNull(mapping2.id)
    }

    @Test
    fun `should allow same email with different domain`() {
        val mapping1 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        entityManager.persist(mapping1)
        entityManager.flush()

        val mapping2 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "other.com"
        )
        entityManager.persist(mapping2)
        entityManager.flush()
        
        assertNotNull(mapping2.id)
    }
}
```

**Execute**:
```bash
./gradlew test --tests UserMappingTest
```

**Commit**:
```bash
git add src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
git add src/backendng/src/test/kotlin/com/secman/domain/UserMappingTest.kt
git commit -m "feat(user-mapping): add UserMapping entity with validation and tests"
```

---

### Step 1.2: Create UserMappingRepository (20 min)

**Goal**: Create repository interface with query methods.

**Actions**:
```bash
touch src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt
```

**Implementation**:
```kotlin
package com.secman.repository

import com.secman.domain.UserMapping
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for UserMapping entity
 * 
 * Feature: 013-user-mapping-upload
 * 
 * Provides CRUD operations and query methods for user-to-AWS-account-to-domain mappings.
 * Used by UserMappingImportService for bulk imports and by future RBAC features
 * for access control lookups.
 */
@Repository
interface UserMappingRepository : JpaRepository<UserMapping, Long> {

    /**
     * Find all mappings for a specific email address
     * 
     * Use case: Get all AWS accounts and domains a user has access to
     * 
     * @param email User's email address (case-insensitive, will be normalized)
     * @return List of mappings for the email
     */
    fun findByEmail(email: String): List<UserMapping>

    /**
     * Find all mappings for a specific AWS account
     * 
     * Use case: Get all users with access to an AWS account
     * 
     * @param awsAccountId AWS account identifier (12-digit string)
     * @return List of mappings for the AWS account
     */
    fun findByAwsAccountId(awsAccountId: String): List<UserMapping>

    /**
     * Find all mappings for a specific domain
     * 
     * Use case: Get all users within an organizational domain
     * 
     * @param domain Organizational domain name
     * @return List of mappings for the domain
     */
    fun findByDomain(domain: String): List<UserMapping>

    /**
     * Check if a specific mapping exists (duplicate detection)
     * 
     * Use case: Skip duplicate mappings during Excel import
     * 
     * @param email User's email address
     * @param awsAccountId AWS account identifier
     * @param domain Organizational domain name
     * @return true if mapping exists, false otherwise
     */
    fun existsByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String,
        domain: String
    ): Boolean

    /**
     * Find a specific mapping by composite key
     * 
     * Use case: Retrieve mapping for update or verification
     * 
     * @param email User's email address
     * @param awsAccountId AWS account identifier
     * @param domain Organizational domain name
     * @return Optional containing the mapping if found
     */
    fun findByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String,
        domain: String
    ): Optional<UserMapping>

    /**
     * Count total mappings for a user
     * 
     * Use case: Display user's total access scope
     * 
     * @param email User's email address
     * @return Number of mappings for the user
     */
    fun countByEmail(email: String): Long

    /**
     * Count total mappings for an AWS account
     * 
     * Use case: Display how many users have access to an account
     * 
     * @param awsAccountId AWS account identifier
     * @return Number of mappings for the account
     */
    fun countByAwsAccountId(awsAccountId: String): Long

    /**
     * Find distinct AWS account IDs for a user
     * 
     * Use case: Get list of AWS accounts a user can access
     * 
     * @param email User's email address
     * @return List of distinct AWS account IDs
     */
    fun findDistinctAwsAccountIdByEmail(email: String): List<String>

    /**
     * Find distinct domains for a user
     * 
     * Use case: Get list of domains a user has access to
     * 
     * @param email User's email address
     * @return List of distinct domains
     */
    fun findDistinctDomainByEmail(email: String): List<String>
}
```

**Test File**:
```bash
touch src/backendng/src/test/kotlin/com/secman/repository/UserMappingRepositoryTest.kt
```

**Test Implementation**:
```kotlin
package com.secman.repository

import com.secman.domain.UserMapping
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

@MicronautTest
class UserMappingRepositoryTest {

    @Inject
    lateinit var repository: UserMappingRepository

    @AfterEach
    fun cleanup() {
        repository.deleteAll()
    }

    @Test
    fun `should save and retrieve user mapping`() {
        val mapping = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        val saved = repository.save(mapping)
        
        assertNotNull(saved.id)
        assertEquals("test@example.com", saved.email)
        assertEquals("123456789012", saved.awsAccountId)
        assertEquals("example.com", saved.domain)
    }

    @Test
    fun `should find mappings by email`() {
        repository.save(UserMapping(email = "user@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "user@example.com", awsAccountId = "987654321098", domain = "example.com"))
        repository.save(UserMapping(email = "other@example.com", awsAccountId = "111111111111", domain = "example.com"))
        
        val mappings = repository.findByEmail("user@example.com")
        
        assertEquals(2, mappings.size)
        assertTrue(mappings.all { it.email == "user@example.com" })
    }

    @Test
    fun `should find mappings by AWS account ID`() {
        repository.save(UserMapping(email = "user1@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "user2@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "user3@example.com", awsAccountId = "987654321098", domain = "example.com"))
        
        val mappings = repository.findByAwsAccountId("123456789012")
        
        assertEquals(2, mappings.size)
        assertTrue(mappings.all { it.awsAccountId == "123456789012" })
    }

    @Test
    fun `should find mappings by domain`() {
        repository.save(UserMapping(email = "user1@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "user2@example.com", awsAccountId = "987654321098", domain = "example.com"))
        repository.save(UserMapping(email = "user3@example.com", awsAccountId = "111111111111", domain = "other.com"))
        
        val mappings = repository.findByDomain("example.com")
        
        assertEquals(2, mappings.size)
        assertTrue(mappings.all { it.domain == "example.com" })
    }

    @Test
    fun `should detect duplicate mappings`() {
        repository.save(UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        ))
        
        val exists = repository.existsByEmailAndAwsAccountIdAndDomain(
            "test@example.com",
            "123456789012",
            "example.com"
        )
        
        assertTrue(exists)
    }

    @Test
    fun `should return false for non-existent mapping`() {
        val exists = repository.existsByEmailAndAwsAccountIdAndDomain(
            "nonexistent@example.com",
            "123456789012",
            "example.com"
        )
        
        assertFalse(exists)
    }

    @Test
    fun `should find mapping by composite key`() {
        repository.save(UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        ))
        
        val found = repository.findByEmailAndAwsAccountIdAndDomain(
            "test@example.com",
            "123456789012",
            "example.com"
        )
        
        assertTrue(found.isPresent)
        assertEquals("test@example.com", found.get().email)
    }

    @Test
    fun `should count mappings by email`() {
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "987654321098", domain = "example.com"))
        repository.save(UserMapping(email = "other@example.com", awsAccountId = "111111111111", domain = "example.com"))
        
        val count = repository.countByEmail("test@example.com")
        
        assertEquals(2, count)
    }

    @Test
    fun `should find distinct AWS accounts for user`() {
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "987654321098", domain = "example.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "other.com"))
        
        val accounts = repository.findDistinctAwsAccountIdByEmail("test@example.com")
        
        assertEquals(2, accounts.size)
        assertTrue(accounts.contains("123456789012"))
        assertTrue(accounts.contains("987654321098"))
    }

    @Test
    fun `should find distinct domains for user`() {
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "987654321098", domain = "other.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "example.com"))
        
        val domains = repository.findDistinctDomainByEmail("test@example.com")
        
        assertEquals(2, domains.size)
        assertTrue(domains.contains("example.com"))
        assertTrue(domains.contains("other.com"))
    }
}
```

**Execute**:
```bash
./gradlew test --tests UserMappingRepositoryTest
```

**Commit**:
```bash
git add src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt
git add src/backendng/src/test/kotlin/com/secman/repository/UserMappingRepositoryTest.kt
git commit -m "feat(user-mapping): add UserMappingRepository with query methods and tests"
```

---

## Phase 2: Backend Service Layer (3 hours)

### Step 2.1: Create UserMappingImportService (2 hours)

**Goal**: Service to parse Excel files and import mappings with validation.

**Actions**:
```bash
touch src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt
```

**Implementation**: See plan.md lines 218-384 for complete code

**Key Points**:
- Parse Excel using Apache POI (XSSFWorkbook)
- Validate headers (case-insensitive matching)
- Validate each row (email, AWS account, domain)
- Skip invalid rows, continue with valid ones
- Check for duplicates before insert
- Return ImportResult with counts and errors

**Test File**:
```bash
touch src/backendng/src/test/kotlin/com/secman/service/UserMappingImportServiceTest.kt
mkdir -p src/backendng/src/test/resources/user-mapping-test-files
```

**Create Test Excel Files** (use LibreOffice or Python):
- `user-mappings-valid.xlsx` - 5 valid rows
- `user-mappings-invalid-email.xlsx` - mixed valid/invalid
- `user-mappings-invalid-aws.xlsx` - invalid AWS account ID
- `user-mappings-missing-column.xlsx` - missing required column
- `user-mappings-empty.xlsx` - only headers

**Execute**:
```bash
./gradlew test --tests UserMappingImportServiceTest
```

**Commit**:
```bash
git add src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt
git add src/backendng/src/test/kotlin/com/secman/service/UserMappingImportServiceTest.kt
git add src/backendng/src/test/resources/user-mapping-test-files/
git commit -m "feat(user-mapping): add UserMappingImportService with Excel parsing and validation"
```

---

## Phase 3: Backend API Controller (2 hours)

### Step 3.1: Add Upload Endpoint to ImportController (45 min)

**File**: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt` (modify existing)

**Add to class constructor**:
```kotlin
private val userMappingImportService: com.secman.service.UserMappingImportService
```

**Add method** (after uploadMasscanXml method):
```kotlin
/**
 * Upload user mapping Excel file
 *
 * Feature: 013-user-mapping-upload
 *
 * Endpoint: POST /api/import/upload-user-mappings
 * Request: multipart/form-data with xlsxFile
 * Response: ImportResult with counts (imported, skipped, errors)
 * Access: ADMIN only
 *
 * Expected Excel format:
 * - Column 1: Email Address (required, valid email)
 * - Column 2: AWS Account ID (required, 12 digits)
 * - Column 3: Domain (required, alphanumeric + dots + hyphens)
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

        // Validate file (reuse existing validation method)
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

**Test File**:
```bash
# Modify existing ImportControllerTest.kt
```

**Add tests**:
- Upload valid file as ADMIN → 200
- Upload as USER → 403
- Upload as unauthenticated → 401
- Upload CSV → 400 (wrong format)
- Upload >10MB → 400 (too large)

**Execute**:
```bash
./gradlew test --tests ImportControllerTest
```

**Commit**:
```bash
git add src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt
git add src/backendng/src/test/kotlin/com/secman/controller/ImportControllerTest.kt
git commit -m "feat(user-mapping): add upload endpoint to ImportController with ADMIN security"
```

---

## Phase 4: Frontend Service & Components (2.5 hours)

### Step 4.1: Create UserMapping Service (20 min)

**File**: Create `src/frontend/src/services/userMappingService.ts`

**Implementation**: See plan.md lines 495-523

**Commit**:
```bash
git add src/frontend/src/services/userMappingService.ts
git commit -m "feat(user-mapping): add frontend service for user mapping API"
```

---

### Step 4.2: Create UserMappingUpload Component (1.5 hours)

**File**: Create `src/frontend/src/components/UserMappingUpload.tsx`

**Implementation**: See plan.md lines 532-670

**Key Features**:
- File input (accept=".xlsx")
- Upload button with loading state
- File requirements card
- Sample file download link
- Success/error alerts
- Error list display

**Commit**:
```bash
git add src/frontend/src/components/UserMappingUpload.tsx
git commit -m "feat(user-mapping): add UserMappingUpload React component with file upload UI"
```

---

### Step 4.3: Add User Mappings Card to Admin Page (10 min)

**File**: Modify `src/frontend/src/components/AdminPage.tsx`

**Add after MCP API Keys card** (around line 167):
```tsx
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

**Commit**:
```bash
git add src/frontend/src/components/AdminPage.tsx
git commit -m "feat(user-mapping): add User Mappings card to Admin page"
```

---

### Step 4.4: Create Admin User Mappings Page (15 min)

**File**: Create `src/frontend/src/pages/admin/user-mappings.astro`

**Implementation**: See plan.md lines 684-700

**Commit**:
```bash
git add src/frontend/src/pages/admin/user-mappings.astro
git commit -m "feat(user-mapping): add admin user mappings page route"
```

---

## Phase 5: Test Data & E2E Tests (2 hours)

### Step 5.1: Create Sample Excel Template (30 min)

**File**: Create `src/frontend/public/sample-files/user-mapping-template.xlsx`

**Method**: Use Python script or LibreOffice Calc

Python script:
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

**Execute**:
```bash
python create_sample_template.py
```

**Verify**: Download file from frontend and open in Excel

**Commit**:
```bash
git add src/frontend/public/sample-files/user-mapping-template.xlsx
git commit -m "feat(user-mapping): add sample Excel template for download"
```

---

### Step 5.2: Create Test Data Files (30 min)

**Directory**: `testdata/`

Create files using Python script similar to above. See tasks.md Step 5.2 for file specifications.

**Commit**:
```bash
git add testdata/user-mappings-*.xlsx
git commit -m "test(user-mapping): add test Excel files for E2E testing"
```

---

### Step 5.3: Write E2E Tests (1 hour)

**File**: Create `src/frontend/tests/e2e/user-mapping-upload.spec.ts`

**Implementation skeleton**:
```typescript
import { test, expect } from '@playwright/test';
import path from 'path';

// Helper to login as admin
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

    test('should reject invalid file type', async ({ page }) => {
        const filePath = path.join(__dirname, '../../../testdata/user-mappings-large.csv');
        await page.setInputFiles('#userMappingFile', filePath);
        await page.click('button:has-text("Upload")');

        await expect(page.locator('.alert-danger')).toContainText('Only .xlsx files are supported');
    });

    // ... add remaining 8 test scenarios
});
```

**Execute**:
```bash
npm run test:e2e -- user-mapping-upload
```

**Commit**:
```bash
git add src/frontend/tests/e2e/user-mapping-upload.spec.ts
git commit -m "test(user-mapping): add E2E tests for upload functionality"
```

---

## Phase 6: Documentation & Polish (1.5 hours)

### Step 6.1: Update CLAUDE.md (20 min)

**File**: `CLAUDE.md`

**Add to "Key Entities" section** (around line 180):
```markdown
### UserMapping (NEW - Feature 013)
- **Fields**: id, email, awsAccountId (12 digits), domain, createdAt, updatedAt
- **Validation**: Email format, AWS account format, domain format
- **Relationships**: Independent (no FK to User entity)
- **Indexes**: Unique composite (email, awsAccountId, domain), individual indexes on each field
- **Access**: ADMIN role only for upload and view
```

**Add to "API Endpoints" section** (around line 450):
```markdown
### User Mapping Upload (NEW - Feature 013)
- `POST /api/import/upload-user-mappings` - Upload user mapping Excel file (ADMIN only)
```

**Add to "Recent Changes" section** (top of file):
```markdown
- 013-user-mapping-upload: User Mapping with AWS Account & Domain Upload (2025-10-07) - Excel upload for email-AWS-domain mappings, ADMIN-only access, validation and duplicate handling
```

**Commit**:
```bash
git add CLAUDE.md
git commit -m "docs(user-mapping): update CLAUDE.md with new entity and endpoints"
```

---

### Step 6.2: Add Inline Code Documentation (30 min)

**Review all files and add/improve**:
- Class-level KDoc/JSDoc
- Method-level KDoc/JSDoc for public methods
- Inline comments for complex logic

**Focus on WHY, not WHAT**.

**Commit**:
```bash
git add -u
git commit -m "docs(user-mapping): add comprehensive inline documentation"
```

---

### Step 6.3: Code Review & Cleanup (40 min)

**Checklist**:
- [ ] Remove any console.log statements
- [ ] Remove unused imports
- [ ] Resolve all TODOs
- [ ] Consistent naming conventions
- [ ] Error messages are user-friendly
- [ ] No hardcoded values (use constants)
- [ ] All nullable types handled
- [ ] All async operations have error handling

**Run linters**:
```bash
./gradlew ktlintCheck  # Backend Kotlin
npm run lint           # Frontend TypeScript
```

**Fix issues**:
```bash
./gradlew ktlintFormat
npm run lint:fix
```

**Commit**:
```bash
git add -u
git commit -m "refactor(user-mapping): code cleanup and linting fixes"
```

---

## Phase 7: Testing & QA (1 hour)

### Step 7.1: Manual Testing (30 min)

**Test Scenarios**:
1. Login as admin → Admin page → User Mappings card visible
2. Click "Manage Mappings" → Upload page loads
3. Download sample file → Opens in Excel correctly
4. Upload sample file → Success message with counts
5. Check database → Records exist
6. Upload same file again → Duplicates skipped
7. Upload file with 100 rows → Performance acceptable
8. Upload file with invalid data → Partial success, errors shown
9. Login as regular user → Access denied on /admin/user-mappings
10. Test in Chrome, Firefox

**Document any issues** in GitHub issues.

---

### Step 7.2: Performance Testing (15 min)

**Tests**:
```bash
# Create 1000-row test file
python scripts/generate_large_test_file.py --rows 1000

# Upload via UI and measure time
# Target: <10 seconds
```

**Document results** in performance.md

---

### Step 7.3: Security Testing (15 min)

**Tests**:
- Verify 401 for anonymous requests
- Verify 403 for non-admin users
- Verify file size limit (try 11MB file)
- Verify file type validation (try .csv, .txt)
- Verify input validation (SQL injection attempts)

**All tests should pass** (security controls effective).

---

## Phase 8: Deployment (1.5 hours)

### Step 8.1: Merge & Build (15 min)

**Prepare for merge**:
```bash
# Ensure all tests pass
./gradlew test
npm test

# Build production artifacts
./gradlew build
npm run build

# Verify Docker build
docker-compose build
```

**Merge to develop**:
```bash
git checkout develop
git merge 013-user-mapping-upload
git push origin develop
```

---

### Step 8.2: Deploy to Staging (30 min)

**Actions**:
```bash
# Deploy to staging environment
docker-compose -f docker-compose.staging.yml up -d

# Check logs
docker-compose logs -f backend
docker-compose logs -f frontend

# Verify database migration
docker-compose exec db mysql -u root -p secman -e "SHOW CREATE TABLE user_mapping;"

# Smoke test: Upload sample file via staging UI
```

**Verify**:
- Table created correctly
- Upload works
- No errors in logs

---

### Step 8.3: Deploy to Production (45 min)

**Pre-deployment**:
- [ ] All staging tests passed
- [ ] Database backup taken
- [ ] Rollback plan documented
- [ ] On-call support notified

**Deploy**:
```bash
# Merge to main
git checkout main
git merge develop
git tag v1.13.0
git push origin main --tags

# Deploy to production
docker-compose -f docker-compose.prod.yml up -d

# Monitor logs for 5 minutes
docker-compose logs -f

# Smoke test in production
```

**Post-deployment**:
- [ ] Upload sample file successfully
- [ ] Check database records
- [ ] Monitor for 24 hours
- [ ] Send announcement to admin users

---

## ✅ Definition of Done

**Implementation Complete** when:
- [x] All 24 tasks completed
- [x] All unit tests pass (>80% coverage)
- [x] All integration tests pass
- [x] All E2E tests pass (10 scenarios)
- [x] Manual testing complete
- [x] Performance targets met
- [x] Security testing passed
- [x] Code reviewed and approved
- [x] Documentation updated (CLAUDE.md)
- [x] Deployed to staging
- [x] QA sign-off
- [x] Deployed to production
- [x] Users notified

---

## 📊 Progress Tracking

Use this table to track progress:

| Phase | Tasks | Status | Completion Date | Notes |
|-------|-------|--------|-----------------|-------|
| 1. Backend Foundation | 3 | ⚪ Not Started | - | - |
| 2. Backend Service | 2 | ⚪ Not Started | - | - |
| 3. Backend API | 2 | ⚪ Not Started | - | - |
| 4. Frontend | 4 | ⚪ Not Started | - | - |
| 5. E2E Tests | 3 | ⚪ Not Started | - | - |
| 6. Documentation | 3 | ⚪ Not Started | - | - |
| 7. QA | 3 | ⚪ Not Started | - | - |
| 8. Deployment | 3 | ⚪ Not Started | - | - |

**Legend**: ⚪ Not Started | 🟡 In Progress | 🟢 Done | 🔴 Blocked

---

## 🆘 Troubleshooting

### Issue: Tests fail with "table not found"
**Solution**: Run `./gradlew clean build` to trigger Hibernate schema creation

### Issue: Excel parsing fails with scientific notation
**Solution**: Use `DataFormatter` instead of direct cell value reading

### Issue: Frontend build fails
**Solution**: Run `npm install` to ensure dependencies are up to date

### Issue: E2E tests flaky
**Solution**: Add explicit waits: `await page.waitForSelector('.alert-success')`

### Issue: File upload times out
**Solution**: Increase timeout in controller: `@ExecuteOn(TaskExecutors.BLOCKING)`

---

## 📞 Support Contacts

- **Backend Issues**: Tech Lead
- **Frontend Issues**: Frontend Lead
- **Database Issues**: DBA
- **Deployment Issues**: DevOps
- **Security Issues**: Security Team

---

**Plan Created**: 2025-10-07  
**Plan Version**: 1.0  
**Ready to Execute**: YES ✅

---

**Good luck with implementation! 🚀**
