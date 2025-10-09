# User Mapping Upload - E2E Test Data Files

Generated for Feature 013-user-mapping-upload

## Test Files

| File | Purpose | Expected Behavior |
|------|---------|-------------------|
| valid-mappings.xlsx | 3 valid mappings | Import all 3 successfully |
| invalid-emails.xlsx | 4 invalid email formats | Skip all with error messages |
| invalid-aws-accounts.xlsx | 5 invalid AWS account IDs | Skip all with error messages |
| invalid-domains.xlsx | 4 invalid domain formats | Skip 3, normalize 1 uppercase |
| missing-columns.xlsx | Missing Domain column | Fail with header error |
| empty-file.xlsx | Headers only, no data | Import 0, skip 0 |
| mixed-valid-invalid.xlsx | 3 valid, 2 invalid | Import 3, skip 2 with errors |
| duplicates.xlsx | 2 unique, 2 duplicates | Import 2, skip 2 duplicates |
| large-file.xlsx | 150 valid rows | Import all 150 successfully |
| special-characters.xlsx | Valid edge cases | Import all 4 successfully |
| wrong-format.txt | Text file, not Excel | Fail with format error |
| empty-cells.xlsx | Some empty cells | Skip 4 rows with missing data |

## Usage

```bash
# Generate files
python3 scripts/generate_e2e_test_files.py

# Run E2E tests
npm test -- user-mapping-upload.spec.ts
```
