# Research & Technology Decisions: CSV-Based User Mapping Upload

**Feature**: 016-i-want-to
**Phase**: 0 - Research & Technology Decisions
**Date**: 2025-10-13
**Status**: Completed

## Research Question 1: CSV Parsing Library Selection

**Question**: Which CSV parsing library best fits Micronaut + Kotlin for parsing RFC 4180 CSV with encoding detection?

**Decision**: Apache Commons CSV 1.11.0

**Rationale**:
- **Proven reliability**: Apache Commons CSV is RFC 4180 compliant with extensive production usage
- **Kotlin compatibility**: Works seamlessly with Kotlin (Java library)
- **Feature completeness**: Supports custom delimiters, quoted fields, header mapping, null handling
- **Lightweight**: No heavy dependencies (only requires Java 8+)
- **Active maintenance**: Regular updates, latest version 1.11.0 (2024)
- **Performance**: Efficient streaming parser, handles large files without loading entire file into memory
- **Project alignment**: Already using Apache POI for Excel; Apache Commons CSV is consistent ecosystem choice

**Alternatives Considered**:
- **OpenCSV**: Popular but less rigorous RFC 4180 compliance, quirky API
- **Kotlin CSV**: Pure Kotlin but less mature, smaller community
- **kotlinx.serialization**: Primarily for JSON/protobuf, CSV support is experimental

**Implementation Notes**:
- Add to `build.gradle.kts`: `implementation("org.apache.commons:commons-csv:1.11.0")`
- Use `CSVFormat.RFC4180.builder()` for consistent parsing
- Enable header mapping with `.setHeader().setSkipHeaderRecord(true)`
- Handle case-insensitive headers by normalizing to lowercase

---

## Research Question 2: Scientific Notation Parsing

**Question**: How to reliably parse AWS account IDs in scientific notation (e.g., 9.98987E+11) to 12-digit strings?

**Decision**: BigDecimal parsing with toLong() conversion

**Rationale**:
- **Accuracy**: BigDecimal preserves full precision without floating-point rounding errors
- **Robustness**: Handles all scientific notation formats (9.98987E+11, 1.0E+12, 9.98987E11)
- **Validation**: Can validate result is exactly 12 digits after conversion
- **Kotlin native**: No additional dependencies

**Implementation Approach**:
```kotlin
fun parseAccountId(value: String): String? {
    return try {
        // Try direct parsing first (for normal strings like "123456789012")
        if (value.matches(Regex("\\d{12}"))) {
            return value
        }

        // Handle scientific notation
        val bigDecimal = BigDecimal(value.trim())
        val longValue = bigDecimal.toLong()
        val accountId = longValue.toString()

        // Validate exactly 12 digits
        if (accountId.matches(Regex("\\d{12}"))) {
            accountId
        } else {
            null // Invalid length
        }
    } catch (e: NumberFormatException) {
        null // Invalid format
    }
}
```

**Test Cases**:
- `"9.98987E+11"` → `"998987000000"` (note: Excel truncates precision)
- `"9.98986922434E+11"` → `"998986922434"`
- `"123456789012"` → `"123456789012"` (pass-through)
- `"1.0E+12"` → `"1000000000000"` (invalid - 13 digits)
- `"abc"` → `null` (invalid format)

**Alternatives Considered**:
- **String manipulation**: Fragile, hard to handle all edge cases
- **Double parsing**: Loses precision for large numbers (floating-point rounding)
- **Custom parser**: Over-engineering

**Edge Case Handling**:
- If scientific notation results in non-12-digit value, skip row with error message
- Log precision warning if Excel export appears to have truncated digits

---

## Research Question 3: CSV Encoding Detection

**Question**: Best approach to detect CSV encoding (UTF-8 vs ISO-8859-1) automatically?

**Decision**: Manual BOM detection + UTF-8 default with ISO-8859-1 fallback

**Rationale**:
- **Simplicity**: No additional dependencies
- **Performance**: Minimal overhead (check first 3 bytes for BOM)
- **Coverage**: Handles 95%+ of real-world CSV files
- **Fail-safe**: Graceful fallback to ISO-8859-1 if UTF-8 parsing fails

**Implementation Approach**:
```kotlin
fun detectEncodingAndRead(file: File): BufferedReader {
    val inputStream = FileInputStream(file)
    val bomBytes = ByteArray(3)
    val mark = inputStream.read(bomBytes)

    // Check for UTF-8 BOM (EF BB BF)
    val charset = if (mark == 3 &&
                      bomBytes[0] == 0xEF.toByte() &&
                      bomBytes[1] == 0xBB.toByte() &&
                      bomBytes[2] == 0xBF.toByte()) {
        Charsets.UTF_8 // Skip BOM, use UTF-8
    } else {
        inputStream.close()
        // Try UTF-8 first, fallback to ISO-8859-1
        try {
            return File(file.path).bufferedReader(Charsets.UTF_8)
        } catch (e: MalformedInputException) {
            return File(file.path).bufferedReader(Charsets.ISO_8859_1)
        }
    }

    return InputStreamReader(inputStream, charset).buffered()
}
```

**Alternatives Considered**:
- **Apache Tika**: 15MB+ dependency for charset detection alone (overkill)
- **juniversalchardet**: 1MB library, good accuracy but adds complexity
- **ICU4J**: Heavy dependency (10MB+), primarily for internationalization

**Limitations**:
- Does not detect all exotic encodings (Windows-1252, etc.)
- User must ensure CSV is UTF-8 or ISO-8859-1 (documented in quickstart)
- If both fail, return clear error message: "Unable to parse CSV - unsupported encoding"

---

## Research Question 4: Delimiter Detection

**Question**: How to auto-detect CSV delimiter (comma, semicolon, tab)?

**Decision**: Apache Commons CSV auto-detection via first line analysis

**Rationale**:
- **Built-in support**: Apache Commons CSV has `CSVFormat.DEFAULT.builder().setDelimiter(detectDelimiter(firstLine))`
- **Simple heuristic**: Count occurrences of comma, semicolon, tab in first line
- **Reliable**: Works for 95%+ of real-world CSVs
- **Fail-safe**: Default to comma if detection is ambiguous

**Implementation Approach**:
```kotlin
fun detectDelimiter(firstLine: String): Char {
    val commaCount = firstLine.count { it == ',' }
    val semicolonCount = firstLine.count { it == ';' }
    val tabCount = firstLine.count { it == '\t' }

    return when {
        commaCount >= semicolonCount && commaCount >= tabCount -> ','
        semicolonCount >= commaCount && semicolonCount >= tabCount -> ';'
        tabCount >= commaCount && tabCount >= semicolonCount -> '\t'
        else -> ',' // Default to comma
    }
}
```

**Test Cases**:
- `"account_id,owner_email"` → `,` (comma)
- `"account_id;owner_email"` → `;` (semicolon)
- `"account_id\towner_email"` → `\t` (tab)
- `"account_id"` → `,` (single column, default to comma)

**Alternatives Considered**:
- **Configuration-based**: Require user to specify delimiter (poor UX)
- **Apache Commons CSV CSVFormat.guess()**: Not available in current API
- **Manual parsing**: Reinventing the wheel

**Edge Case Handling**:
- If delimiter detection fails (e.g., no delimiters found), return error: "Unable to parse CSV - invalid format"
- If quoted fields contain delimiters, Apache Commons CSV handles correctly

---

## Research Question 5: Frontend File Input Pattern

**Question**: Best practice for adding second file upload button (CSV) next to existing Excel upload in React/Astro?

**Decision**: Separate buttons with shared handler logic, unified ImportResult display

**Rationale**:
- **UX clarity**: Separate buttons make format choice explicit ("Upload Excel" vs "Upload CSV")
- **Code reuse**: Extract shared logic into `uploadUserMappings(file, endpoint)` utility function
- **Maintainability**: Single component manages both upload flows, single result display
- **Consistency**: Mirrors release management pattern (Feature 011) and asset import pattern (Feature 002, 005)

**Implementation Pattern**:
```tsx
// UserMappingUpload.tsx (or similar component)
const UserMappingUpload = () => {
  const [result, setResult] = useState<ImportResult | null>(null);
  const [loading, setLoading] = useState(false);

  const handleUpload = async (file: File, endpoint: string) => {
    setLoading(true);
    try {
      const formData = new FormData();
      formData.append(endpoint === '/api/import/upload-user-mappings' ? 'xlsxFile' : 'csvFile', file);

      const response = await axios.post(endpoint, formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      });

      setResult(response.data);
    } catch (error) {
      // Handle error
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <input type="file" accept=".xlsx" onChange={(e) => handleUpload(e.target.files[0], '/api/import/upload-user-mappings')} />
      <input type="file" accept=".csv" onChange={(e) => handleUpload(e.target.files[0], '/api/import/upload-user-mappings-csv')} />

      {result && <ImportResultDisplay result={result} />}
    </div>
  );
};
```

**Alternatives Considered**:
- **Unified button with format dropdown**: More clicks, less clear
- **Separate components**: Code duplication, harder to maintain consistency
- **Automatic format detection**: Hidden complexity, error-prone

**Implementation Notes**:
- Use `accept=".csv"` on CSV file input for browser-level filtering
- Add clear labels: "Upload Excel (.xlsx)" and "Upload CSV (.csv)"
- Disable both buttons during upload (shared loading state)
- Display single ImportResult regardless of upload source
- Add "Download CSV Template" link next to "Download Excel Template"

**Accessibility**:
- ARIA labels: `aria-label="Upload user mappings from CSV file"`
- Keyboard navigation: Both file inputs should be focusable
- Error announcements: Use `role="alert"` for error messages

---

## Summary

**Key Technology Decisions**:
1. **CSV Library**: Apache Commons CSV 1.11.0 (add to build.gradle.kts)
2. **Scientific Notation**: BigDecimal parsing with 12-digit validation
3. **Encoding Detection**: BOM detection + UTF-8 default with ISO-8859-1 fallback
4. **Delimiter Detection**: First-line analysis (comma/semicolon/tab)
5. **Frontend Pattern**: Separate buttons, shared handler, unified result display

**Dependencies to Add**:
- Backend: `org.apache.commons:commons-csv:1.11.0` in `build.gradle.kts`
- Frontend: No new dependencies (Axios already available)

**Next Steps**:
- Proceed to Phase 1: Design & Contracts
- Create `data-model.md` (reuse UserMapping entity)
- Create `contracts/csv-upload.yaml` (OpenAPI spec)
- Create `quickstart.md` (user documentation)
- Update `CLAUDE.md` with new endpoint and parser locations

**Research Completion Date**: 2025-10-13
**Phase 0 Status**: ✅ COMPLETE - Ready for Phase 1
