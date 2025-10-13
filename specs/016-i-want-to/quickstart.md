# Quickstart Guide: CSV User Mapping Upload

**Feature**: CSV-Based User Mapping Upload (Feature 016)
**Audience**: System Administrators
**Prerequisites**: ADMIN role required

## Overview

The CSV User Mapping Upload feature allows administrators to bulk import email-to-AWS-account mappings from CSV files exported directly from AWS Organizations. This guide covers how to prepare, upload, and troubleshoot CSV user mapping files.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [CSV Format Requirements](#csv-format-requirements)
3. [Step-by-Step Upload](#step-by-step-upload)
4. [Troubleshooting](#troubleshooting)
5. [Common Errors](#common-errors)
6. [FAQ](#faq)

---

## Quick Start

**5-Minute Setup**:

1. Export your AWS Organizations accounts to CSV (or download the template)
2. Ensure CSV has `account_id` and `owner_email` columns
3. Navigate to **Admin → User Mappings**
4. Click **Upload CSV** button
5. Select your CSV file and submit
6. Review import results

---

## CSV Format Requirements

### Required Columns (Case-Insensitive)

Your CSV file **must** include these columns (order doesn't matter):

| Column Name | Description | Example |
|-------------|-------------|---------|
| `account_id` | AWS account ID (12 digits) | `123456789012` |
| `owner_email` | User email address | `user@example.com` |

### Optional Columns

| Column Name | Description | Default if Omitted |
|-------------|-------------|-------------------|
| `domain` | Organization domain | `-NONE-` |

### Additional Columns

Your CSV may contain additional columns (e.g., `display_name`, `status`, `tags`) - they will be ignored.

### Example Valid CSV

**Option 1: With domain column**
```csv
account_id,owner_email,domain
000487141098,markus.schmall@covestro.com,covestro.com
998986922434,test1.test1@covestro.com,covestro.com
123456789012,admin@example.com,example.com
```

**Option 2: Without domain column** (domain defaults to "-NONE-")
```csv
account_id,owner_email
000487141098,markus.schmall@covestro.com
998986922434,test1.test1@covestro.com
123456789012,admin@example.com
```

**Option 3: Scientific notation** (Excel export format)
```csv
account_id,owner_email
9.98987E+11,test1.test1@covestro.com
1.23457E+11,admin@example.com
```

### File Specifications

- **Format**: CSV (RFC 4180 compliant)
- **Encoding**: UTF-8 (recommended) or ISO-8859-1
- **Delimiter**: Comma, semicolon, or tab (auto-detected)
- **File Extension**: `.csv`
- **Max Size**: 10MB
- **Max Rows**: Approximately 200,000 rows (depending on column count)

---

## Step-by-Step Upload

### Step 1: Prepare Your CSV File

**From AWS Organizations**:

1. Go to AWS Organizations console
2. Select **Accounts**
3. Click **Export** → **Download CSV**
4. Open the downloaded file in Excel or text editor
5. Verify `account_id` and `owner_email` columns are present
6. Save as CSV (UTF-8 encoding recommended)

**From Scratch**:

1. Download the CSV template:
   - Navigate to **Admin → User Mappings**
   - Click **Download CSV Template**
2. Open template in Excel, Google Sheets, or text editor
3. Fill in your account IDs and email addresses
4. Save as CSV

### Step 2: Access the Upload Page

1. Log in to secman with an ADMIN account
2. Navigate to **Admin** → **User Mappings**
3. You will see two upload buttons:
   - **Upload Excel** (existing feature)
   - **Upload CSV** (this feature)

### Step 3: Upload Your CSV File

1. Click the **Upload CSV** button
2. File picker opens (filters for `.csv` files)
3. Select your prepared CSV file
4. Click **Open** to start upload
5. Wait for processing (typically 2-10 seconds for 1000 rows)

### Step 4: Review Import Results

After upload completes, you'll see a summary:

**Success Example**:
```
✓ Successfully imported 10 user mappings

Imported: 10
Skipped: 0
```

**Partial Success Example**:
```
✓ Successfully imported 8 user mappings

Imported: 8
Skipped: 2

Errors:
- Line 3: owner_email - Invalid email format: "not-an-email"
- Line 5: account_id - Must be exactly 12 digits: "12345"
```

**Duplicate Detection Example**:
```
✓ Successfully imported 7 user mappings

Imported: 7
Skipped: 3

Errors:
- Line 4: Duplicate mapping already exists: user@example.com / 123456789012 / domain.com
- Line 8: Duplicate mapping already exists: admin@example.com / 999888777666 / -NONE-
- Line 9: Duplicate within uploaded file: user@example.com / 123456789012 / domain.com
```

### Step 5: Verify Imported Mappings

1. Scroll down to the **User Mappings** table on the same page
2. Search for newly imported email addresses or account IDs
3. Verify mappings appear correctly

---

## Troubleshooting

### Upload Fails with "Missing Required Headers"

**Problem**: CSV is missing `account_id` or `owner_email` columns

**Solution**:
1. Open your CSV file in a text editor
2. Check the first line (header row)
3. Ensure it contains `account_id` and `owner_email` (case-insensitive)
4. Correct example: `account_id,owner_email` or `Account_ID,Owner_Email`

### Upload Fails with "Invalid File Type"

**Problem**: File is not a `.csv` file

**Solution**:
1. Check file extension (should be `.csv`, not `.xlsx` or `.txt`)
2. If file is Excel (.xlsx), use the **Upload Excel** button instead
3. If file is text (.txt), rename to `.csv` and ensure comma-separated values

### Upload Fails with "File Too Large"

**Problem**: CSV file exceeds 10MB size limit

**Solution**:
1. Split your CSV into multiple smaller files
2. Remove unnecessary columns (keep only `account_id`, `owner_email`, `domain`)
3. Upload each file separately

### Some Rows Skipped: "Invalid Email Format"

**Problem**: Email addresses don't match required format

**Solution**:
1. Review error message for line number
2. Open CSV and check email at that line
3. Ensure email contains `@` character
4. Ensure email format: `user@domain.com`
5. Remove spaces, special characters

**Common Invalid Emails**:
- `user` (missing @domain)
- `user@` (missing domain)
- `@domain.com` (missing user part)
- `user @domain.com` (space in email)

### Some Rows Skipped: "Must Be Exactly 12 Digits"

**Problem**: AWS account ID is not 12 digits

**Solution**:
1. Review error message for line number
2. Open CSV and check account ID at that line
3. Ensure account ID is exactly 12 numeric digits
4. Remove spaces, letters, or extra digits

**Common Invalid Account IDs**:
- `12345` (too short)
- `1234567890123` (too long - 13 digits)
- `abc123456789` (contains letters)
- ` 123456789012 ` (leading/trailing spaces)

### Scientific Notation Not Parsing Correctly

**Problem**: AWS account IDs in scientific notation (e.g., `9.98987E+11`) result in wrong values

**Reason**: Excel may truncate precision when exporting large numbers

**Solution**:
1. In Excel, format account ID column as **Text** before exporting
2. Or manually convert scientific notation to 12-digit number:
   - `9.98987E+11` → `998987000000`
3. Re-save as CSV and upload again

### Duplicate Mappings Detected

**Problem**: Some rows skipped with "Duplicate mapping already exists"

**Reason**: A mapping with the same email + account ID + domain already exists in the database

**Solution**:
1. This is **expected behavior** - duplicates are automatically skipped
2. If you want to update existing mappings, delete old ones first
3. If duplicate is within the same CSV file, remove duplicate rows

### All Rows Skipped: "Unable to Parse CSV"

**Problem**: CSV file has invalid encoding or format

**Solution**:
1. Re-save CSV with UTF-8 encoding:
   - Excel: **Save As** → **CSV UTF-8 (Comma delimited) (.csv)**
   - Google Sheets: **File** → **Download** → **Comma Separated Values (.csv)**
2. Ensure delimiter is comma, semicolon, or tab (not pipe `|` or other)
3. Remove any special characters or binary data

---

## Common Errors

### Error Code 400: Bad Request

| Error Message | Cause | Solution |
|---------------|-------|----------|
| Missing required headers | CSV lacks `account_id` or `owner_email` columns | Add missing column headers |
| Empty file uploaded | CSV file is 0 bytes | Ensure file has data rows |
| Invalid CSV format | Malformed CSV (unclosed quotes, invalid encoding) | Re-export CSV with UTF-8 encoding |
| Expected .csv file, received .xlsx | Wrong file type selected | Use **Upload Excel** button for .xlsx files |

### Error Code 401: Unauthorized

| Error Message | Cause | Solution |
|---------------|-------|----------|
| Unauthorized | Not logged in or JWT token expired | Log in again |

### Error Code 403: Forbidden

| Error Message | Cause | Solution |
|---------------|-------|----------|
| Access denied. ADMIN role required. | Your account lacks ADMIN role | Contact system administrator to grant ADMIN role |

### Error Code 413: Payload Too Large

| Error Message | Cause | Solution |
|---------------|-------|----------|
| File size exceeds maximum limit of 10MB | CSV file is larger than 10MB | Split file into smaller chunks |

---

## FAQ

### Q: Can I upload both Excel and CSV files?

**A**: Yes! Both **Upload Excel** and **Upload CSV** buttons are available. Choose the format that matches your file.

### Q: What happens to duplicate mappings?

**A**: Duplicate mappings (same email + account ID + domain) are automatically skipped. You'll see them listed in the "Skipped" section with reason "Duplicate mapping already exists".

### Q: Can I upload CSV files with semicolon or tab delimiters?

**A**: Yes! The system automatically detects comma, semicolon, or tab delimiters.

### Q: What if my CSV has extra columns (e.g., display_name, status)?

**A**: Extra columns are ignored. Only `account_id`, `owner_email`, and optionally `domain` are extracted.

### Q: What does the "-NONE-" domain value mean?

**A**: `-NONE-` is a sentinel value assigned when your CSV doesn't include a `domain` column. It indicates "no specific domain".

### Q: Can I upload CSV files with international characters (ü, é, ñ)?

**A**: Yes! Ensure your CSV is saved with UTF-8 encoding. Most modern tools (Excel, Google Sheets) support this.

### Q: How many rows can I upload at once?

**A**: The file size limit is 10MB, which typically allows ~200,000 rows (depending on column count). For larger datasets, split into multiple files.

### Q: What if my CSV has scientific notation account IDs (e.g., 9.98987E+11)?

**A**: The system automatically parses scientific notation and converts to 12-digit strings. Example: `9.98987E+11` → `998987000000`.

**Note**: Excel may truncate precision when exporting. For best results, format account IDs as **Text** in Excel before exporting.

### Q: Can I download a template CSV file?

**A**: Yes! Click **Download CSV Template** on the User Mappings page. The template shows the required format with one example row.

### Q: How long does upload take?

**A**: Typical processing time:
- 100 rows: ~1 second
- 1,000 rows: ~5 seconds
- 10,000 rows: ~30 seconds

### Q: What if upload fails halfway through?

**A**: Uploads are transactional - either all valid rows are imported, or none. If a server error occurs, no partial data is saved. You can safely retry.

### Q: Can I see which mappings were imported?

**A**: After upload, scroll down to the **User Mappings** table. Use the search box to filter by email or account ID to find newly imported mappings.

### Q: What's the difference between CSV and Excel upload?

**A**: Functionally identical - both create the same UserMapping records. Choose the format that matches your source data:
- **CSV**: AWS Organizations exports, simple text files
- **Excel**: Manually created mapping files, complex formatting

---

## Best Practices

1. **Validate Before Upload**:
   - Open CSV in text editor to check format
   - Verify headers: `account_id,owner_email`
   - Ensure no empty rows (except trailing blank lines)

2. **Use UTF-8 Encoding**:
   - Prevents encoding issues with international characters
   - Most modern tools default to UTF-8

3. **Format Account IDs as Text in Excel**:
   - Prevents scientific notation conversion
   - Select account ID column → Format Cells → Text → Re-enter values

4. **Test with Small File First**:
   - Upload 10-20 rows to verify format
   - Fix any errors before uploading full dataset

5. **Keep Backup of Original CSV**:
   - In case of errors, you can re-upload after corrections

6. **Review Import Results**:
   - Check "Imported" and "Skipped" counts
   - Read error messages for skipped rows
   - Verify mappings appear in table

---

## Related Features

- **Excel User Mapping Upload** (Feature 013): Upload mappings from .xlsx files
- **Workgroup Management** (Feature 008): Assign users to workgroups for access control
- **Admin Role Management** (Feature 001): Grant ADMIN role to users for upload permissions

---

## Support

If you encounter issues not covered in this guide:

1. Check the [Troubleshooting](#troubleshooting) section
2. Review [Common Errors](#common-errors)
3. Contact your system administrator
4. Report bugs at: [secman GitHub Issues](https://github.com/your-org/secman/issues)

---

**Document Version**: 1.0.0
**Last Updated**: 2025-10-13
**Feature**: 016-i-want-to (CSV-Based User Mapping Upload)
