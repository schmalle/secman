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
| accounts-from-dynamodb.csv | Backend CSV upload format (`account_id`, `owner_email`, optional `display_name`) | 2 valid rows, 1 duplicate |
| accounts-with-display-name.json | Cloud Custodian export with `display_name` (CLI `import`) | Links 3 accounts to `aws-Legacy-alpha` / `aws-DevOps-beta`; the 4th is reported as an error |
| mappings-with-display-name.csv | CLI `import` CSV format with the optional `display_name` column | Same linking as the JSON sample |

## Display names and workgroups

The two `*-display-name*` fixtures exercise
[docs/AWS_ACCOUNT_WORKGROUP_LINKING.md](../../docs/AWS_ACCOUNT_WORKGROUP_LINKING.md):
an account whose display name is `DevOps-beta` is linked to the workgroup
`aws-DevOps-beta`, which is created if it does not exist.

They deliberately cover four cases in one file:

- a name that matches an existing workgroup,
- two accounts sharing one display name (both land in the same workgroup),
- a name whose workgroup does not exist yet (it gets created), and
- `Data_Platform.01`, which **cannot** be a workgroup name — `Workgroup.name`
  allows letters, digits, spaces and hyphens only — so it is reported as an
  error and no workgroup is created for it.

Try them without changing anything:

```bash
./scripts/secman manage-user-mappings import \
    -f testdata/user-mappings/accounts-with-display-name.json --dry-run
```

## Usage

These `.xlsx`/`.txt` fixtures are hand-maintained — the generator script that
originally produced them (`scripts/generate_e2e_test_files.py`) no longer
exists in this repo. Edit the fixture files directly if the expected behavior
above needs to change.

They are consumed by `scripts/test/test-e2e-aws-account-workgroup-import.sh`
(skill `/aws-account-workgroup-import`), which uploads the `.xlsx` suite to
`POST /api/import/upload-user-mappings` and asserts the counts in the table
above, and runs the two `*-display-name*` fixtures through the CLI as a
**dry run**. The dry run is deliberate: these files carry fixed account ids, so
importing them for real would collide between runs and leave real-looking rows
behind. Every destructive assertion in that driver uses a generated file with
synthetic ids instead.
