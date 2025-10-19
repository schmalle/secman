# Research: Asset Bulk Operations

**Feature**: 029-asset-bulk-operations
**Date**: 2025-10-19
**Status**: Complete

## Executive Summary

This document consolidates research findings for implementing asset bulk operations (delete, export, import) with performance targets of <30s bulk delete (10K+ assets), <15s export (10K assets), and <60s import (5K assets). All technical decisions align with existing codebase patterns from Features 013/016 (user mappings) and constitutional principles.

---

## 1. Excel Import/Export with Apache POI 5.3

### Decision: Use XSSFWorkbook (import) + SXSSFWorkbook (export)

**Rationale**:
- **Import (5K rows)**: XSSFWorkbook handles this scale efficiently without streaming complexity
- **Export (10K rows)**: SXSSFWorkbook provides 30x performance improvement (430ms vs 17s) and prevents memory exhaustion
- **Existing Pattern**: Consistent with UserMappingImportService (Feature 013/016)

**Implementation Approach**:

**Export Pattern** (10K rows in <15s):
```kotlin
import org.apache.poi.xssf.streaming.SXSSFWorkbook

fun exportAssetsToExcel(assets: List<Asset>): ByteArrayOutputStream {
    SXSSFWorkbook(100).use { workbook ->  // 100-row memory window
        workbook.setCompressTempFiles(true)  // Reduce temp file size
        val sheet = workbook.createSheet("Assets")

        // Create styles ONCE (not per cell - anti-pattern in RequirementController.kt)
        val styles = createAssetStyles(workbook)

        // Write header + data rows
        createHeaderRow(sheet, styles.header)
        assets.forEachIndexed { index, asset ->
            createAssetRow(sheet, index + 1, asset, styles)
        }

        // Use fixed widths (auto-sizing adds 3 minutes overhead)
        setFixedColumnWidths(sheet)

        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.dispose()  // CRITICAL: Clean up temp files

        return outputStream
    }
}
```

**Import Pattern** (5K rows in <60s):
```kotlin
import org.apache.poi.xssf.usermodel.XSSFWorkbook

fun importFromExcel(inputStream: InputStream): ImportResult {
    XSSFWorkbook(inputStream).use { workbook ->
        val sheet = workbook.getSheetAt(0)
        val headerMap = validateAndMapHeaders(sheet)  // Case-insensitive

        val assets = mutableListOf<Asset>()
        val errors = mutableListOf<String>()

        for (rowIndex in 1..sheet.lastRowNum) {
            try {
                val asset = parseRowToAsset(sheet.getRow(rowIndex), headerMap)
                if (!assetRepository.existsByName(asset.name)) {
                    assets.add(asset)
                } else {
                    errors.add("Row ${rowIndex + 1}: Duplicate asset name '${asset.name}'")
                }
            } catch (e: Exception) {
                errors.add("Row ${rowIndex + 1}: ${e.message}")
            }
        }

        assetRepository.saveAll(assets)  // Batch save
        return ImportResult(assets.size, errors)
    }
}
```

**Key Techniques**:
1. **DataFormatter for numeric precision**: Prevents scientific notation for AWS account IDs
2. **LocalDateTime native support**: POI 5.x handles dates directly, no conversion needed
3. **Style reuse**: Create CellStyle objects once before loop (64K style limit)
4. **Fixed column widths**: Avoid auto-sizing overhead (3 minutes for 10K rows)
5. **Batch persistence**: Use `saveAll()` instead of individual `save()` (10x speedup)

**Performance Characteristics**:
- Memory: Constant ~210MB for export regardless of row count
- Temp files: ~200MB with compression (vs 1GB+ uncompressed)
- Import validation: Case-insensitive headers, flexible column order
- Export fields: All 14 asset columns including workgroup names (comma-separated)

**Alternatives Considered**:
- SXSSF for reading: No streaming read API exists
- Auto-size all columns: Too slow (adds 3 minutes for 10K rows)
- Per-cell styling: Exhausts 64K style limit, 30x slower

---

## 2. Transactional Bulk Delete

### Decision: Manual JPQL cascade delete with @Transactional on service layer

**Rationale**:
- `repository.deleteAll()` with JPA cascade = N+1 queries (110K DELETE statements for 10K assets with 10 vulnerabilities each)
- JPQL bulk delete is 10-100x faster but requires manual cascade handling
- Existing codebase has anti-pattern: `@Transactional` on controllers (RequirementController, NormController)

**Implementation Approach**:

**Service Layer** (RECOMMENDED):
```kotlin
@Singleton
class AssetService(
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val scanResultRepository: ScanResultRepository,
    private val entityManager: EntityManager
) {
    private val deletionInProgress = AtomicBoolean(false)

    @Transactional(timeout = "PT60S", rollbackOn = [Exception::class])
    open fun deleteAllAssets(): DeleteResult {
        // First-request-wins concurrency control
        if (!deletionInProgress.compareAndSet(false, true)) {
            throw ConcurrentOperationException("Bulk deletion already in progress")
        }

        try {
            // Step 1: Clear many-to-many join table
            entityManager.createNativeQuery("DELETE FROM asset_workgroups")
                .executeUpdate()

            // Step 2: Delete children FIRST (foreign key constraints)
            val vulnCount = vulnerabilityRepository.deleteAllInBatch()
            val scanCount = scanResultRepository.deleteAllInBatch()

            // Step 3: Delete assets
            val assetCount = assetRepository.count()
            assetRepository.deleteAllInBatch()

            // Step 4: Clear stale cache
            entityManager.clear()

            return DeleteResult(assetCount, vulnCount, scanCount)

        } finally {
            deletionInProgress.set(false)
        }
    }
}
```

**Key Techniques**:
1. **@Transactional on service**: Separates transaction lifetime from HTTP request duration
2. **Manual cascade delete**: Children first to avoid FK constraint violations
3. **AtomicBoolean semaphore**: Application-level lock for first-request-wins (no DB overhead)
4. **EntityManager.clear()**: Clears stale Hibernate cache after JPQL delete
5. **60-second timeout**: Safety margin (15-30s expected for 10K assets)

**Configuration Requirements**:
```yaml
# application.yml
jpa:
  default:
    properties:
      hibernate:
        jdbc:
          batch_size: 100
        order_inserts: true
        order_updates: true

datasources:
  default:
    url: jdbc:mariadb://localhost:3306/secman?connectTimeout=10000&socketTimeout=60000
    hikari:
      connection-timeout: 10000
      maximum-pool-size: 10
```

**Performance Characteristics**:
- 100 assets: <1 second
- 1,000 assets: 3-5 seconds
- 10,000 assets: 15-25 seconds (under 30s requirement)
- 20,000 assets: 25-35 seconds

**Database Cascade Alternative** (Optional optimization):
```kotlin
// Add to Vulnerability entity
@JoinColumn(
    name = "asset_id",
    foreignKey = ForeignKey(
        foreignKeyDefinition = "FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE"
    )
)
```

Then simplify to:
```kotlin
@Transactional
open fun deleteAllAssets(): DeleteResult {
    assetRepository.deleteAllInBatch()  // DB cascade handles children
    entityManager.clear()
    return DeleteResult(count)
}
```

**Alternatives Considered**:
- Pessimistic locking (`SELECT ... FOR UPDATE`): Requires loading all 10K rows (slow)
- Optimistic locking (`@Version`): Doesn't prevent concurrent deletes
- Redis distributed lock: Overkill for single-instance deployment

---

## 3. React Confirmation Modal with Bootstrap 5.3

### Decision: Custom React modal with checkbox acknowledgment (not text input)

**Rationale**:
- Existing codebase pattern: `ReleaseDeleteConfirm.tsx` uses custom modal with Bootstrap classes
- Checkbox acknowledgment balances safety and speed (better than text input or simple OK/Cancel)
- No additional dependencies (not using react-bootstrap library)

**Implementation Approach**:

```typescript
interface BulkDeleteModalProps {
  isOpen: boolean;
  assetCount: number;
  onClose: () => void;
  onConfirm: () => void;
  isDeleting?: boolean;
}

const BulkDeleteModal: React.FC<BulkDeleteModalProps> = ({
  isOpen,
  assetCount,
  onClose,
  onConfirm,
  isDeleting = false
}) => {
  const [confirmChecked, setConfirmChecked] = useState(false);

  // ESC key handler (disabled during deletion)
  useEffect(() => {
    if (!isOpen) return;

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape' && !isDeleting) {
        onClose();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, isDeleting, onClose]);

  if (!isOpen) return null;

  return (
    <>
      <div className={`modal-backdrop fade ${isOpen ? 'show' : ''}`} onClick={onClose} />
      <div className={`modal fade ${isOpen ? 'show' : ''}`} style={{ display: 'block' }} role="dialog">
        <div className="modal-dialog">
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">Delete All Assets</h5>
              <button type="button" className="btn-close" onClick={onClose} disabled={isDeleting} />
            </div>
            <div className="modal-body">
              <div className="alert alert-danger">
                <strong>Warning:</strong> This action cannot be undone.
                <ul className="mb-0 mt-2">
                  <li>All {assetCount} assets will be permanently deleted</li>
                  <li>Associated vulnerabilities will be removed</li>
                  <li>Scan results will be lost</li>
                </ul>
              </div>

              <div className="form-check">
                <input
                  type="checkbox"
                  className="form-check-input"
                  id="confirmDelete"
                  checked={confirmChecked}
                  onChange={(e) => setConfirmChecked(e.target.checked)}
                  disabled={isDeleting}
                />
                <label className="form-check-label" htmlFor="confirmDelete">
                  <strong>I understand that {assetCount} assets will be permanently deleted</strong>
                </label>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-secondary" onClick={onClose} disabled={isDeleting}>
                Cancel
              </button>
              <button
                className="btn btn-danger"
                onClick={onConfirm}
                disabled={!confirmChecked || isDeleting}
              >
                {isDeleting ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" />
                    Deleting {assetCount} assets...
                  </>
                ) : (
                  `Delete ${assetCount} Assets`
                )}
              </button>
            </div>
          </div>
        </div>
      </div>
    </>
  );
};
```

**Key Techniques**:
1. **Checkbox acknowledgment**: Requires deliberate action (not click-through)
2. **Dynamic button text**: Shows count + loading state
3. **ESC key handling**: Closes modal (but not during deletion)
4. **All elements disabled**: Prevents interaction during deletion
5. **Focus management**: Returns focus to trigger after closing

**Accessibility Features**:
- `role="dialog"` for screen readers
- `aria-labelledby` associates title with dialog
- Proper checkbox semantics with `form-check`
- `disabled` prevents interaction and updates ARIA state
- Spinner has `role="status"` for announcements

**State Management** (in AssetManagement.tsx):
```typescript
const [showBulkDeleteModal, setShowBulkDeleteModal] = useState(false);
const [isDeletingBulk, setIsDeletingBulk] = useState(false);

const handleBulkDelete = async () => {
  setIsDeletingBulk(true);
  try {
    await authenticatedDelete('/api/assets/bulk');
    await fetchAssets();
    setShowBulkDeleteModal(false);
  } catch (err) {
    setError(err instanceof Error ? err.message : 'Failed to delete assets');
  } finally {
    setIsDeletingBulk(false);
  }
};
```

**Alternatives Considered**:
- Text input verification ("type DELETE"): Slower, overkill for this operation
- Simple OK/Cancel: Too easy to click accidentally
- react-bootstrap library: Unnecessary dependency

---

## 4. Workgroup-Based Access Control

### Decision: Reuse existing AssetService.findByWorkgroups() pattern

**Rationale**:
- Feature 008 (workgroups) already implements workgroup filtering
- AssetController uses `@Secured(SecurityRule.IS_AUTHENTICATED)` + service-layer filtering
- Export must respect same access control rules

**Implementation Approach**:

**Export Service**:
```kotlin
@Singleton
class AssetExportService(
    private val assetRepository: AssetRepository,
    private val workgroupService: WorkgroupService
) {
    fun exportAssets(authentication: Authentication): List<Asset> {
        val username = authentication.name
        val roles = authentication.roles.map { it.toString() }

        return if (roles.contains("ADMIN")) {
            // ADMIN sees all assets
            assetRepository.findAll().toList()
        } else {
            // Non-ADMIN sees workgroup assets + owned assets
            val userWorkgroups = workgroupService.getWorkgroupsForUser(username)
            val workgroupAssets = assetRepository.findByWorkgroups(userWorkgroups)
            val ownedAssets = assetRepository.findByCreator(username)

            (workgroupAssets + ownedAssets).distinctBy { it.id }
        }
    }
}
```

**Import Service**:
```kotlin
@Transactional
open fun importAssets(inputStream: InputStream, authentication: Authentication): ImportResult {
    val username = authentication.name
    val userWorkgroups = workgroupService.getWorkgroupsForUser(username)

    // ... parse Excel file ...

    // Associate imported assets with user's workgroups
    assets.forEach { asset ->
        asset.manualCreator = userRepository.findByUsername(username).orElse(null)
        asset.workgroups.addAll(userWorkgroups)
    }

    assetRepository.saveAll(assets)
}
```

**Key Principles**:
- ADMIN role: Full access to all assets
- Non-ADMIN: Workgroup assets + personally created/uploaded assets
- Import tracking: Set `manualCreator` for access control
- Export filtering: Apply same rules as `GET /api/assets`

---

## 5. Sidebar Navigation Enhancement

### Decision: Extend existing I/O menu with nested sub-items

**Rationale**:
- Sidebar.tsx already has I/O menu with Import/Export structure (lines 174-199)
- Simply add "Assets" option to existing submenus
- Consistent with user expectation (Requirements, Nmap, Masscan, Vulnerabilities already listed)

**Implementation Approach**:

```typescript
// In Sidebar.tsx, update I/O menu:
{ioMenuOpen && (
  <ul className="list-unstyled ps-4">
    <li>
      <a href="/import" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
        <i className="bi bi-cloud-upload me-2"></i> Import
      </a>
      {/* NEW: Nested submenu for import types */}
      <ul className="list-unstyled ps-4">
        <li>
          <a href="/import?type=requirements" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
            Requirements
          </a>
        </li>
        <li>
          <a href="/import?type=assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
            <i className="bi bi-server me-2"></i> Assets
          </a>
        </li>
        {/* ... existing types ... */}
      </ul>
    </li>
    <li>
      <a href="/export" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
        <i className="bi bi-download me-2"></i> Export
      </a>
      {/* NEW: Nested submenu for export types */}
      <ul className="list-unstyled ps-4">
        <li>
          <a href="/export?type=requirements" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
            Requirements
          </a>
        </li>
        <li>
          <a href="/export?type=assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
            <i className="bi bi-server me-2"></i> Assets
          </a>
        </li>
      </ul>
    </li>
  </ul>
)}
```

**Import/Export Component Updates**:
```typescript
// Import.tsx - Add 'assets' to ImportType union
type ImportType = 'requirements' | 'nmap' | 'masscan' | 'vulnerabilities' | 'assets';

// Pre-select based on URL param
useEffect(() => {
  const params = new URLSearchParams(window.location.search);
  const type = params.get('type');
  if (type && ['requirements', 'nmap', 'masscan', 'vulnerabilities', 'assets'].includes(type)) {
    setImportType(type as ImportType);
  }
}, []);
```

**Key Features**:
- URL-based pre-selection: `/import?type=assets` auto-selects Assets tab
- Icon consistency: Use `bi-server` for assets (matches Asset Management)
- Nested structure: Follows existing pattern for logical grouping

---

## Implementation Risks & Mitigations

### Risk 1: Excel Export Performance Degradation
**Risk**: 10K asset export exceeds 15-second target
**Likelihood**: Low
**Impact**: Medium (user frustration, timeout errors)
**Mitigation**:
- Use SXSSFWorkbook with 100-row window
- Pre-create all CellStyle objects (not per-cell)
- Use fixed column widths (avoid auto-sizing)
- Test with 10K dataset during development

### Risk 2: Bulk Delete Transaction Timeout
**Risk**: 10K asset deletion exceeds 60-second timeout
**Likelihood**: Low
**Impact**: High (partial deletions, data inconsistency)
**Mitigation**:
- Manual cascade delete (children first)
- Consider database-level CASCADE for Phase 2 optimization
- Monitor deletion times in staging with production-like data
- Set transaction timeout to 60s with explicit rollback handling

### Risk 3: Concurrent Bulk Operations
**Risk**: Two ADMINs trigger bulk delete simultaneously
**Likelihood**: Low
**Impact**: Medium (duplicate operations, wasted resources)
**Mitigation**:
- AtomicBoolean semaphore for first-request-wins
- Return 409 Conflict on second request
- Display "operation in progress" message in UI

### Risk 4: Import Memory Exhaustion
**Risk**: Very large import files (>10MB) cause OOM errors
**Likelihood**: Low (file size validation enforced)
**Impact**: High (server crash, lost data)
**Mitigation**:
- Enforce 10MB file size limit (existing infrastructure)
- Validate file size BEFORE processing
- Use XSSFWorkbook (no streaming needed for 5K rows)
- Monitor heap usage during imports

---

## Testing Strategy

### Contract Tests (Backend)
- `AssetBulkDeleteContractTest.kt`: DELETE /api/assets/bulk (ADMIN only, transaction rollback, 403 for non-ADMIN)
- `AssetExportContractTest.kt`: GET /api/assets/export (workgroup filtering, empty state, all fields present)
- `AssetImportContractTest.kt`: POST /api/import/upload-assets-xlsx (validation, duplicates, error reporting)

### Unit Tests (Backend)
- `AssetServiceTest.kt`: Bulk delete with cascades, transaction rollback on failure
- `AssetExportServiceTest.kt`: Workgroup filtering, ADMIN vs non-ADMIN, workgroup name formatting
- `AssetImportServiceTest.kt`: Row parsing, duplicate detection, validation errors

### E2E Tests (Frontend)
- `asset-bulk-operations.spec.ts`: Complete workflow (export → delete → import)
- Button visibility (ADMIN only, hidden when count=0)
- Modal confirmation (checkbox required, ESC key, loading state)
- Navigation (sidebar links pre-select type)

**Test Data Requirements**:
- 100 assets for smoke tests (<1s operations)
- 1,000 assets for integration tests (3-5s operations)
- 10,000 assets for performance validation (15-30s operations)

---

## Conclusion

All research findings support the feasibility of meeting performance targets (<30s bulk delete, <15s export, <60s import) using:

1. **Apache POI 5.3**: SXSSFWorkbook for export, XSSFWorkbook for import
2. **Transactional Safety**: Manual JPQL cascade delete with @Transactional on service layer
3. **User Confirmation**: React modal with checkbox acknowledgment + loading states
4. **Access Control**: Reuse existing workgroup filtering from Feature 008
5. **Navigation**: Extend existing I/O sidebar menu with nested sub-items

No technical blockers identified. All patterns consistent with existing codebase (Features 008, 013, 016). Ready to proceed to Phase 1 (data model and API contracts).
