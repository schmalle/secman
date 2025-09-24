# Quickstart: Email Functionality Testing

**Phase 1 Output** | **Date**: 2025-09-21

## Prerequisites

1. **Backend running** on port 8080
2. **Frontend running** on port 4321
3. **Valid admin user** credentials (adminuser/password)
4. **Email server** for testing (Gmail, SMTP server, etc.)

## Test Scenario 1: Admin UI Access

### Goal
Verify admin can access email configuration interface

### Steps
1. **Login as admin**
   ```bash
   # Navigate to frontend
   curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username": "adminuser", "password": "password"}'
   ```

2. **Access admin email config page**
   - Open browser: `http://localhost:4321/admin/email-config`
   - Should load without authentication errors
   - Should display email configuration form

3. **Verify existing email configs**
   ```bash
   curl -H "Authorization: Bearer $JWT_TOKEN" \
     http://localhost:8080/api/email-configs
   ```

### Expected Results
- ✅ Admin UI loads successfully
- ✅ Email configuration form is accessible
- ✅ Existing configurations display properly
- ✅ No authentication or CORS errors

## Test Scenario 2: Email Configuration Setup

### Goal
Configure and test email server settings

### Steps
1. **Create email configuration**
   ```bash
   curl -X POST http://localhost:8080/api/email-configs \
     -H "Authorization: Bearer $JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "name": "Test SMTP",
       "smtpHost": "smtp.gmail.com",
       "smtpPort": 587,
       "smtpTls": true,
       "smtpSsl": false,
       "smtpUsername": "your-email@gmail.com",
       "smtpPassword": "your-app-password",
       "fromEmail": "your-email@gmail.com",
       "fromName": "SecMan Test"
     }'
   ```

2. **Activate configuration**
   ```bash
   curl -X PUT http://localhost:8080/api/email-configs/{id}/activate \
     -H "Authorization: Bearer $JWT_TOKEN"
   ```

3. **Test email sending**
   ```bash
   curl -X POST http://localhost:8080/api/email-configs/{id}/test \
     -H "Authorization: Bearer $JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"testEmail": "recipient@example.com"}'
   ```

### Expected Results
- ✅ Configuration saved with encrypted credentials
- ✅ Test email sent successfully
- ✅ Recipient receives test email
- ✅ No credentials logged in plaintext

## Test Scenario 3: Test Email Account Management

### Goal
Create and validate test email accounts

### Steps
1. **Create test account**
   ```bash
   curl -X POST http://localhost:8080/api/test-email-accounts \
     -H "Authorization: Bearer $JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "name": "Gmail Test Account",
       "emailAddress": "test@gmail.com",
       "provider": "GMAIL",
       "credentials": {
         "username": "test@gmail.com",
         "password": "app-password"
       }
     }'
   ```

2. **Verify test account**
   ```bash
   curl -X POST http://localhost:8080/api/test-email-accounts/{id}/verify \
     -H "Authorization: Bearer $JWT_TOKEN"
   ```

3. **Send test email**
   ```bash
   curl -X POST http://localhost:8080/api/test-email-accounts/{id}/test \
     -H "Authorization: Bearer $JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "subject": "Test Email",
       "content": "This is a test email from SecMan",
       "useHtml": false
     }'
   ```

### Expected Results
- ✅ Test account created successfully
- ✅ Verification passes
- ✅ Test email sent to account
- ✅ Account status updated correctly

## Test Scenario 4: Automatic Risk Assessment Notifications

### Goal
Verify emails sent automatically when risk assessments created

### Steps
1. **Configure notification recipients**
   ```bash
   curl -X POST http://localhost:8080/api/notifications/configs \
     -H "Authorization: Bearer $JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "name": "Security Team",
       "recipientEmails": ["security@company.com", "admin@company.com"],
       "conditions": null
     }'
   ```

2. **Create new risk assessment**
   ```bash
   curl -X POST http://localhost:8080/api/risk-assessments \
     -H "Authorization: Bearer $JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "title": "Test Risk Assessment",
       "description": "Testing email notifications",
       "riskLevel": "HIGH"
     }'
   ```

3. **Check notification logs**
   ```bash
   curl -H "Authorization: Bearer $JWT_TOKEN" \
     "http://localhost:8080/api/notifications/logs?riskAssessmentId={id}"
   ```

### Expected Results
- ✅ Risk assessment created successfully
- ✅ Email notification event triggered
- ✅ Notification sent to configured recipients
- ✅ Delivery logged with status SENT
- ✅ Recipients receive notification emails

## Test Scenario 5: Email Encryption Verification

### Goal
Verify sensitive email data is encrypted in database

### Steps
1. **Check database directly**
   ```sql
   -- Connect to MariaDB
   mysql -u secman -pCHANGEME secman

   -- Check encrypted fields
   SELECT id, name, smtp_username, smtp_password
   FROM email_configs
   WHERE smtp_username IS NOT NULL;
   ```

2. **Verify credentials not in plaintext**
   - SMTP username should be encrypted blob/text
   - SMTP password should be encrypted blob/text
   - No plaintext credentials visible

3. **Test credential retrieval through API**
   ```bash
   curl -H "Authorization: Bearer $JWT_TOKEN" \
     http://localhost:8080/api/email-configs/{id}
   ```

### Expected Results
- ✅ Database stores encrypted credential data
- ✅ API returns masked/hidden credentials
- ✅ No plaintext passwords in database dumps
- ✅ Encryption/decryption works transparently

## Error Scenarios

### Invalid Email Configuration
```bash
# Should fail with validation error
curl -X POST http://localhost:8080/api/email-configs \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "", "smtpHost": "invalid", "smtpPort": 999999}'
```

### Failed Email Delivery
```bash
# Configure invalid SMTP server
curl -X POST http://localhost:8080/api/email-configs \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Invalid SMTP",
    "smtpHost": "nonexistent.smtp.server",
    "smtpPort": 587,
    "fromEmail": "test@example.com",
    "fromName": "Test"
  }'
```

### Expected Error Handling
- ✅ Validation errors returned with 400 status
- ✅ Authentication errors returned with 401 status
- ✅ Email delivery failures logged properly
- ✅ Retry mechanism activated for transient failures
- ✅ User-friendly error messages displayed

## Performance Verification

### Email Sending Performance
```bash
# Time email sending
time curl -X POST http://localhost:8080/api/email-configs/{id}/test \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"testEmail": "test@example.com"}'
```

### Expected Performance
- ✅ API response < 200ms (email queued asynchronously)
- ✅ Email delivery < 30 seconds
- ✅ No blocking of risk assessment creation
- ✅ Proper connection pooling for SMTP

## Cleanup

After testing:
1. Delete test email configurations
2. Remove test email accounts
3. Clear notification logs
4. Deactivate test notification configs

```bash
# Clean up test data
curl -X DELETE http://localhost:8080/api/email-configs/{id} \
  -H "Authorization: Bearer $JWT_TOKEN"

curl -X DELETE http://localhost:8080/api/test-email-accounts/{id} \
  -H "Authorization: Bearer $JWT_TOKEN"
```