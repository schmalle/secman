# Data Model: SES SMTP Rewrite

**Feature**: 071-ses-smtp-rewrite
**Date**: 2026-01-29

## Modified Entities

### EmailConfig (modified behavior, no schema change)

The database schema remains unchanged. The semantic meaning of the SES fields changes:

| Column | Before (SES API) | After (SES SMTP) |
|--------|-------------------|-------------------|
| `ses_access_key` | AWS IAM access key ID | SES SMTP username |
| `ses_secret_key` | AWS IAM secret access key | SES SMTP password |
| `ses_region` | AWS region for SES API endpoint | AWS region (used to derive SMTP host: `email-smtp.{region}.amazonaws.com`) |

All three columns remain encrypted via `EncryptedStringConverter`. No migration script needed.

### EmailConfig Method Changes

| Method | Before | After |
|--------|--------|-------|
| `hasAuthentication()` (SES case) | Checks `sesAccessKey` + `sesSecretKey` not blank | Same check — fields now hold SMTP credentials |
| `validate()` (SES case) | Validates `sesAccessKey`, `sesSecretKey`, `sesRegion` | Same validation — field names unchanged |
| New: `getSesSmtpHost()` | N/A | Returns `email-smtp.{sesRegion}.amazonaws.com` |
| New: `getSesSmtpProperties()` | N/A | Returns Jakarta Mail properties map for SES SMTP (host, port 587, STARTTLS, auth) |

## No Schema Changes

This feature modifies zero database columns, tables, or indexes. The existing `email_configs` table structure is fully compatible. The only change is the interpretation of `ses_access_key` and `ses_secret_key` from IAM credentials to SMTP credentials.

## Removed Dependencies

| Dependency | Version | Purpose | Replacement |
|-----------|---------|---------|-------------|
| `software.amazon.awssdk:ses` | 2.41.8 | AWS SES API client | Jakarta Mail SMTP (already present) |
| `software.amazon.awssdk:auth` | 2.41.8 | AWS credential handling | Standard SMTP authentication |
