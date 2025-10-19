# Quickstart: Asset Bulk Operations

**Feature**: 029-asset-bulk-operations
**Audience**: Developers implementing this feature
**Estimated Time**: 2-3 days (with testing)

---

## Prerequisites

Before starting implementation:

- [ ] Read [spec.md](spec.md) - Feature requirements and acceptance criteria
- [ ] Review [research.md](research.md) - Technical decisions and best practices
- [ ] Review [data-model.md](data-model.md) - Entities and DTOs
- [ ] Review [plan.md](plan.md) - Constitution check and project structure
- [ ] Reviewed existing patterns:
  - [ ] Feature 013/016: User mapping imports (`UserMappingImportService.kt`, `CSVUserMappingParser.kt`)
  - [ ] Feature 012: Release management UI (`ReleaseDeleteConfirm.tsx`)
  - [ ] Feature 008: Workgroup access control (`AssetService.kt`)

---

## Implementation Roadmap

### Phase 1: Backend - Bulk Delete (Day 1, 4-6 hours)

**Goal**: Implement transactional bulk delete with ADMIN-only access

**Steps**:

1. **Create Service Layer** (`AssetService.kt`)
   ```kotlin
   @Transactional(timeout = "PT60S")
   open fun deleteAllAssets(): BulkDeleteResult
   ```
   - Add `AtomicBoolean deletionInProgress` semaphore
   - Implement manual cascade delete (workgroups → vulnerabilities → scan results → assets)
   - Clear EntityManager cache after delete
   - Reference: [research.md#2-transactional-bulk-delete](research.md#2-transactional-bulk-delete)

2. **Create Controller Endpoint** (`AssetController.kt`)
   ```kotlin
   @Delete("/bulk")
   @Secured("ADMIN")
   fun bulkDeleteAssets(): HttpResponse<*>
   ```
   - Add error handling for `ConcurrentOperationException` (409 Conflict)
   - Return `BulkDeleteResult` with counts
   - Reference: [contracts/bulk-delete.yaml](contracts/bulk-delete.yaml)

3. **Create DTO** (`BulkDeleteResult.kt`)
   - Reference: [data-model.md#bulkdeleteresult](data-model.md#bulkdeleteresult)

4. **Write Tests** (TDD - write BEFORE implementation)
   - `AssetBulkDeleteContractTest.kt`: 200 success, 403 forbidden, 409 conflict, 500 rollback
   - `AssetServiceTest.kt`: Transaction rollback, cascade behavior, semaphore locking

**Verification**:
```bash
./gradlew test --tests AssetBulkDeleteContractTest
./gradlew test --tests AssetServiceTest
```

---

### Phase 2: Backend - Asset Export (Day 1, 4-6 hours)

**Goal**: Export assets to Excel with workgroup-based access control

**Steps**:

1. **Create DTOs**
   - `AssetExportDto.kt` - Flattened asset with workgroup names
   - Reference: [data-model.md#assetexportdto](data-model.md#assetexportdto)

2. **Create Export Service** (`AssetExportService.kt`)
   ```kotlin
   fun exportAssets(authentication: Authentication): List<AssetExportDto>
   fun writeToExcel(dtos: List<AssetExportDto>): ByteArrayOutputStream
   ```
   - Implement workgroup filtering (ADMIN vs non-ADMIN)
   - Use SXSSFWorkbook for performance
   - Create styles ONCE before loop
   - Use fixed column widths (not auto-sizing)
   - Reference: [research.md#1-excel-import-export](research.md#1-excel-import-export)

3. **Create Controller Endpoint** (`ExportController.kt` or add to `AssetController.kt`)
   ```kotlin
   @Get("/export")
   @Secured(SecurityRule.IS_AUTHENTICATED)
   fun exportAssets(authentication: Authentication): HttpResponse<*>
   ```
   - Return binary Excel file with proper headers
   - Handle empty asset list (400 with error message)
   - Reference: [contracts/asset-export.yaml](contracts/asset-export.yaml)

4. **Write Tests** (TDD)
   - `AssetExportContractTest.kt`: 200 success, 400 no data, 401 unauthorized
   - `AssetExportServiceTest.kt`: Workgroup filtering, ADMIN vs non-ADMIN, Excel format

**Verification**:
```bash
./gradlew test --tests AssetExportContractTest
./gradlew test --tests AssetExportServiceTest
curl -H "Authorization: Bearer $JWT_TOKEN" http://localhost:8080/api/assets/export -o test_export.xlsx
```

---

### Phase 3: Backend - Asset Import (Day 2, 4-6 hours)

**Goal**: Import assets from Excel with validation and duplicate handling

**Steps**:

1. **Create DTOs**
   - `AssetImportDto.kt` - Temporary DTO for parsed rows
   - Reuse `ImportResult.kt` (from Feature 013/016)
   - Reference: [data-model.md#assetimportdto](data-model.md#assetimportdto)

2. **Create Import Service** (`AssetImportService.kt`)
   ```kotlin
   @Transactional
   open fun importFromExcel(stream: InputStream, authentication: Authentication): ImportResult
   ```
   - Validate headers (case-insensitive)
   - Parse rows with error collection
   - Check duplicates by name (skip, don't update)
   - Resolve workgroup names to entities
   - Batch save with `repository.saveAll()`
   - Set `manualCreator` to importing user
   - Reference: [research.md#1-excel-import-export](research.md#1-excel-import-export)

3. **Update Controller** (`ImportController.kt`)
   ```kotlin
   @Post("/upload-assets-xlsx")
   @Secured(SecurityRule.IS_AUTHENTICATED)
   fun uploadAssetsExcel(@Part xlsxFile: CompletedFileUpload, authentication: Authentication): HttpResponse<*>
   ```
   - Validate file (size, format, content-type)
   - Reference existing pattern: `UserMappingImportService.kt`
   - Reference: [contracts/asset-import.yaml](contracts/asset-import.yaml)

4. **Write Tests** (TDD)
   - `AssetImportContractTest.kt`: 200 success, 400 validation errors, 401 unauthorized
   - `AssetImportServiceTest.kt`: Duplicate handling, validation, workgroup assignment

**Verification**:
```bash
./gradlew test --tests AssetImportContractTest
./gradlew test --tests AssetImportServiceTest
curl -X POST -H "Authorization: Bearer $JWT_TOKEN" -F "xlsxFile=@test_export.xlsx" http://localhost:8080/api/import/upload-assets-xlsx
```

---

### Phase 4: Frontend - Bulk Delete UI (Day 2, 4-5 hours)

**Goal**: Add "Delete All Assets" button with confirmation modal

**Steps**:

1. **Update AssetManagement Component** (`AssetManagement.tsx`)
   - Add state: `showBulkDeleteModal`, `isDeletingBulk`
   - Add button in header (conditional on ADMIN role + asset count > 0)
   - Implement `handleBulkDelete()` function
   - Reference: [research.md#3-react-confirmation-modal](research.md#3-react-confirmation-modal)

2. **Create Confirmation Modal** (`BulkDeleteConfirmModal.tsx`)
   - Checkbox acknowledgment pattern
   - Loading state with spinner
   - ESC key handling (disabled during deletion)
   - Reference existing pattern: `ReleaseDeleteConfirm.tsx`
   - Reference: [research.md#3-react-confirmation-modal](research.md#3-react-confirmation-modal)

3. **Update Asset Service** (`assetService.ts`)
   ```typescript
   export async function bulkDeleteAssets(): Promise<BulkDeleteResult>
   ```
   - Call `DELETE /api/assets/bulk`
   - Handle 403, 409, 500 errors

4. **Add Role Check Utility**
   - Use existing `isAdmin()` from `auth.ts` or `permissions.ts`
   - Conditionally render button based on role + asset count

**Verification**:
```bash
cd src/frontend
npm run dev
# Navigate to http://localhost:4321/assets
# Login as ADMIN
# Click "Delete All Assets" → Confirm → Verify all assets deleted
```

---

### Phase 5: Frontend - Import/Export UI (Day 3, 4-5 hours)

**Goal**: Add asset import/export handlers to Import/Export pages

**Steps**:

1. **Update Sidebar** (`Sidebar.tsx`)
   - Add "Assets" link under I/O > Import
   - Add "Assets" link under I/O > Export
   - Links should use query params: `/import?type=assets`, `/export?type=assets`

2. **Update Import Component** (`Import.tsx`)
   - Add `'assets'` to `ImportType` union
   - Add upload handler for assets (follow `'vulnerabilities'` pattern)
   - Display `ImportResult` with imported/skipped counts
   - Pre-select based on URL param

3. **Update Export Component** (`Export.tsx`)
   - Add `'assets'` to `ExportType` union
   - Add export handler calling `GET /api/assets/export`
   - Trigger file download
   - Pre-select based on URL param

4. **Update Asset Service** (`assetService.ts`)
   ```typescript
   export async function exportAssets(): Promise<Blob>
   export async function importAssets(file: File): Promise<ImportResult>
   ```

**Verification**:
```bash
# Export flow
# Navigate to http://localhost:4321/export?type=assets
# Click "Export Assets" → Verify .xlsx file downloads

# Import flow
# Navigate to http://localhost:4321/import?type=assets
# Upload previously exported file → Verify success message with counts
```

---

### Phase 6: End-to-End Testing (Day 3, 2-3 hours)

**Goal**: Validate complete export → delete → import workflow

**Steps**:

1. **Write E2E Test** (`asset-bulk-operations.spec.ts`)
   ```typescript
   test('complete workflow: export → delete → import', async ({ page }) => {
     // Login as ADMIN
     // Export assets → verify download
     // Delete all assets → verify confirmation modal → verify empty list
     // Import previously exported file → verify assets restored
   })
   ```

2. **Manual Testing Checklist**:
   - [ ] ADMIN user sees "Delete All Assets" button
   - [ ] Non-ADMIN user does NOT see button
   - [ ] Button hidden when asset count = 0
   - [ ] Confirmation modal requires checkbox before enabling "Delete" button
   - [ ] ESC key closes modal (but not during deletion)
   - [ ] Spinner shows during deletion
   - [ ] Success message shows deleted count
   - [ ] Export downloads .xlsx file with all fields
   - [ ] Export respects workgroup access control
   - [ ] Export shows "No assets available" when list is empty
   - [ ] Import validates file format and size
   - [ ] Import skips duplicates without modifying existing assets
   - [ ] Import shows summary with imported/skipped counts
   - [ ] Import displays error messages for invalid rows
   - [ ] Sidebar links pre-select correct import/export type

**Verification**:
```bash
cd src/frontend
npx playwright test asset-bulk-operations.spec.ts
```

---

## Configuration Checklist

### Backend Configuration

Add to `src/backendng/src/main/resources/application.yml`:

```yaml
jpa:
  default:
    properties:
      hibernate:
        jdbc:
          batch_size: 100  # Optimize bulk operations
        order_inserts: true
        order_updates: true

datasources:
  default:
    url: jdbc:mariadb://localhost:3306/secman?connectTimeout=10000&socketTimeout=60000
    hikari:
      connection-timeout: 10000
      maximum-pool-size: 10
```

### Database Configuration (Optional - For Performance)

Run in MariaDB:

```sql
SET GLOBAL innodb_lock_wait_timeout = 60;
SET GLOBAL max_execution_time = 60000;
```

---

## Common Pitfalls & Solutions

### Pitfall 1: Creating CellStyle per Cell
**Symptom**: Export takes 3 minutes for 10K rows
**Solution**: Create styles ONCE before loop, reuse for all cells
**Reference**: [research.md#5-workbook-creation](research.md#5-workbook-creation)

### Pitfall 2: Using Auto-Size Column
**Symptom**: Export adds 3 minutes overhead
**Solution**: Use fixed column widths instead
**Reference**: [research.md#6-auto-column-sizing](research.md#6-auto-column-sizing)

### Pitfall 3: @Transactional on Controller
**Symptom**: Transaction timeout tied to HTTP request duration
**Solution**: Move @Transactional to service layer
**Reference**: [research.md#1-transaction-strategy](research.md#1-transaction-strategy)

### Pitfall 4: Forgetting EntityManager.clear()
**Symptom**: Stale data in UI after bulk delete
**Solution**: Call `entityManager.clear()` after JPQL delete
**Reference**: [research.md#2-bulk-delete-methods](research.md#2-bulk-delete-methods)

### Pitfall 5: Not Disposing SXSSFWorkbook
**Symptom**: Temp files accumulate in /tmp directory
**Solution**: Always call `workbook.dispose()` in finally block
**Reference**: [research.md#2-memory-management](research.md#2-memory-management)

---

## Performance Benchmarks

### Expected Performance

| Operation | Target | Notes |
|-----------|--------|-------|
| Bulk delete 10K assets | <30 seconds | With manual cascade delete |
| Export 10K assets | <15 seconds | With SXSSFWorkbook + fixed widths |
| Import 5K assets | <60 seconds | With batch saveAll() |

### Performance Testing

```bash
# Backend: Measure bulk delete time
time curl -X DELETE -H "Authorization: Bearer $JWT_TOKEN" http://localhost:8080/api/assets/bulk

# Backend: Measure export time
time curl -H "Authorization: Bearer $JWT_TOKEN" http://localhost:8080/api/assets/export -o test.xlsx

# Backend: Measure import time
time curl -X POST -H "Authorization: Bearer $JWT_TOKEN" -F "xlsxFile=@test.xlsx" http://localhost:8080/api/import/upload-assets-xlsx
```

---

## Code Review Checklist

Before submitting PR:

- [ ] All contract tests pass (`./gradlew test`)
- [ ] All E2E tests pass (`npx playwright test`)
- [ ] Backend build succeeds (`./gradlew build`)
- [ ] Frontend build succeeds (`npm run build`)
- [ ] Linting passes (`npm run lint`)
- [ ] Constitution check passes (all 6 principles)
- [ ] API contracts match OpenAPI specs
- [ ] No console.log statements in production code
- [ ] Error messages are user-friendly (no stack traces exposed)
- [ ] ADMIN-only endpoints have `@Secured("ADMIN")` annotation
- [ ] Excel files <10MB validated before processing
- [ ] Workgroup access control applied to export
- [ ] Duplicate assets skipped (not updated) on import
- [ ] Transaction timeout set to 60 seconds
- [ ] SXSSFWorkbook disposed properly
- [ ] EntityManager cleared after JPQL deletes

---

## Troubleshooting

### Issue: Bulk delete returns 409 Conflict
**Cause**: Another bulk delete is in progress
**Solution**: Wait for first operation to complete or cancel it

### Issue: Export returns 400 "No assets available"
**Cause**: User has no access to any assets (workgroup filtering)
**Solution**: Assign user to workgroups or create owned assets

### Issue: Import skips all rows with "Duplicate asset name"
**Cause**: Assets already exist in database
**Solution**: Delete existing assets first or use different names

### Issue: Import fails with "Missing required columns"
**Cause**: Excel headers don't match expected names
**Solution**: Ensure columns named "Name", "Type", "Owner" (case-insensitive)

### Issue: Transaction timeout after 60 seconds
**Cause**: Too many assets (>20K) or slow database
**Solution**: Optimize database indexes or increase timeout to 120 seconds

---

## Next Steps

After implementing this feature:

1. Run `/speckit.tasks` to generate detailed task breakdown
2. Commit code with conventional commit: `feat(assets): add bulk operations (delete, export, import)`
3. Create PR with reference to spec: `Implements #029-asset-bulk-operations`
4. Update CLAUDE.md with new endpoints and patterns
5. Add feature to release notes

---

## Support & References

- **Spec**: [spec.md](spec.md)
- **Research**: [research.md](research.md)
- **Data Model**: [data-model.md](data-model.md)
- **API Contracts**: [contracts/](contracts/)
- **Existing Patterns**:
  - User Mapping Import: `src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt`
  - Release Delete Confirm: `src/frontend/src/components/ReleaseDeleteConfirm.tsx`
  - Workgroup Filtering: `src/backendng/src/main/kotlin/com/secman/service/AssetService.kt`
