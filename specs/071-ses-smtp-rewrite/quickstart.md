# Quickstart: SES SMTP Rewrite

**Feature**: 071-ses-smtp-rewrite
**Date**: 2026-01-29

## Prerequisites

- Kotlin/Java backend builds successfully: `./gradlew build`
- Access to AWS SES console to generate SMTP credentials

## Files to Modify

1. **`src/backendng/build.gradle.kts`**
   - Remove `software.amazon.awssdk:ses:2.41.8` dependency
   - Remove `software.amazon.awssdk:auth:2.41.8` dependency

2. **`src/backendng/src/main/kotlin/com/secman/service/SesEmailService.kt`**
   - Remove all AWS SDK imports
   - Rewrite `sendEmail()` to use Jakarta Mail SMTP with properties derived from `EmailConfig`
   - Rewrite `sendTestEmail()` to use SMTP path
   - Rewrite `verifyConfiguration()` to use `Transport.connect()` instead of SES API
   - Keep `sanitizeEmailHeader()` unchanged

3. **`src/backendng/src/main/kotlin/com/secman/domain/EmailConfig.kt`**
   - Add `getSesSmtpHost(): String` method (returns `email-smtp.{sesRegion}.amazonaws.com`)
   - Add `getSesSmtpProperties(): Map<String, String>` method (returns Jakarta Mail properties for port 587, STARTTLS)

4. **`src/backendng/src/main/kotlin/com/secman/service/EmailSender.kt`**
   - No changes needed — `sendEmailViaSes()` already delegates to `SesEmailService.sendEmail()` which will now use SMTP internally

5. **`src/backendng/src/main/kotlin/com/secman/service/EmailProviderConfigService.kt`**
   - Update `verifySesConfig()` to call the rewritten `verifyConfiguration()` method (should work unchanged since the interface is the same)

## Verification

```bash
# Build the project (confirms AWS SDK removal compiles cleanly)
./gradlew build

# Verify no AWS SES SDK imports remain
grep -r "software.amazon.awssdk" src/backendng/src/main/kotlin/ || echo "No AWS SDK imports found (expected)"

# Test email send via admin UI or CLI
# Configure SES with SMTP credentials in the admin email config page
# Send a test email to verify SMTP-based delivery
```

## Implementation Order

1. Add `getSesSmtpHost()` and `getSesSmtpProperties()` to `EmailConfig.kt`
2. Rewrite `SesEmailService.kt` to use Jakarta Mail SMTP
3. Remove AWS SES SDK dependencies from `build.gradle.kts`
4. Build and verify with `./gradlew build`
5. Test email sending via the admin UI test email feature

## Generating SES SMTP Credentials

To use this feature, admins must generate SES SMTP credentials:

1. Go to AWS Console → SES → SMTP Settings
2. Click "Create SMTP credentials"
3. This creates an IAM user and generates SMTP username + password
4. Enter these as the SES access key and secret key in the secman email configuration
