# Quickstart: S3 User Mapping Import

**Feature**: 065-s3-user-mapping-import
**Date**: 2026-01-20

## Prerequisites

1. **AWS Credentials** configured via one of:
   - Environment variables: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
   - AWS credentials file (`~/.aws/credentials`)
   - IAM role (for EC2/ECS deployments)

2. **IAM Permissions** for the S3 bucket:
   ```json
   {
     "Effect": "Allow",
     "Action": [
       "s3:GetObject",
       "s3:HeadObject"
     ],
     "Resource": "arn:aws:s3:::your-bucket/path/to/mappings/*"
   }
   ```

3. **CLI built** (if not already):
   ```bash
   ./gradlew :cli:shadowJar
   ```

## Basic Usage

### Import from S3 (Default Credentials)

```bash
./bin/secman manage-user-mappings import-s3 \
  --bucket my-mapping-bucket \
  --key user-mappings/current.csv \
  --admin-user admin@example.com
```

### Dry-Run (Validate Without Importing)

```bash
./bin/secman manage-user-mappings import-s3 \
  --bucket my-mapping-bucket \
  --key user-mappings/current.csv \
  --admin-user admin@example.com \
  --dry-run
```

### Specify Region and Profile

```bash
./bin/secman manage-user-mappings import-s3 \
  --bucket my-mapping-bucket \
  --key user-mappings/current.csv \
  --aws-region eu-west-1 \
  --aws-profile production \
  --admin-user admin@example.com
```

### Specify File Format

```bash
./bin/secman manage-user-mappings import-s3 \
  --bucket my-mapping-bucket \
  --key data/users.txt \
  --format CSV \
  --admin-user admin@example.com
```

## File Formats

### CSV Format

Upload a file like `user-mappings.csv`:

```csv
email,type,value
alice@example.com,DOMAIN,corp.local
alice@example.com,AWS_ACCOUNT,123456789012
bob@example.com,DOMAIN,dev.local
```

### JSON Format

Upload a file like `user-mappings.json`:

```json
[
  {
    "email": "alice@example.com",
    "domains": ["corp.local", "dev.local"],
    "awsAccounts": ["123456789012"]
  },
  {
    "email": "bob@example.com",
    "domains": ["staging.local"]
  }
]
```

## Cron Setup (Daily Import)

### Using Environment Variables

```bash
# /etc/cron.d/secman-user-mappings
0 2 * * * secman-user AWS_ACCESS_KEY_ID=xxx AWS_SECRET_ACCESS_KEY=xxx \
  /opt/secman/bin/secman manage-user-mappings import-s3 \
  --bucket company-mappings \
  --key daily/user-mappings.csv \
  --admin-user admin@company.com \
  >> /var/log/secman/user-mapping-import.log 2>&1
```

### Using IAM Role (EC2)

```bash
# /etc/cron.d/secman-user-mappings
0 2 * * * root /opt/secman/bin/secman manage-user-mappings import-s3 \
  --bucket company-mappings \
  --key daily/user-mappings.csv \
  --admin-user admin@company.com \
  >> /var/log/secman/user-mapping-import.log 2>&1
```

### Using AWS Profile

```bash
# /etc/cron.d/secman-user-mappings
0 2 * * * secman-user /opt/secman/bin/secman manage-user-mappings import-s3 \
  --bucket company-mappings \
  --key daily/user-mappings.csv \
  --aws-profile secman-import \
  --admin-user admin@company.com \
  >> /var/log/secman/user-mapping-import.log 2>&1
```

## Exit Codes

| Code | Meaning | Action |
|------|---------|--------|
| 0 | Success | All mappings imported |
| 1 | Partial success | Some mappings failed validation (check logs) |
| 2+ | Fatal error | S3 access failed, auth error, or file parse error |

## Troubleshooting

### "AWS credentials not found"

Ensure credentials are configured:
```bash
# Check current identity
aws sts get-caller-identity

# Or set environment variables
export AWS_ACCESS_KEY_ID=your-key
export AWS_SECRET_ACCESS_KEY=your-secret
```

### "Access denied"

Check IAM permissions include `s3:GetObject` and `s3:HeadObject` for the specific bucket/key.

### "Bucket not found"

Verify:
1. Bucket name is correct (no typos)
2. Bucket exists in the specified region
3. Region is correctly specified with `--aws-region`

### "File exceeds 10MB limit"

Split the mapping file into smaller chunks or remove duplicate entries.

## Verify Import Results

After import, verify mappings were created:

```bash
./bin/secman manage-user-mappings list \
  --admin-user admin@example.com \
  --format TABLE
```
