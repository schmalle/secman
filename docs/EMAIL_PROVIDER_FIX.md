# Email Provider Test Fix - Complete Solution

## Problem Statement
When trying to send a test email with an email provider, both frontend and backend errors occurred. Users were unable to:
- Test SMTP connection to email providers
- Send test emails from configured accounts
- Properly authenticate with email servers

The root cause was a mismatch between what the frontend was sending and what the backend expected for credentials.

## Root Cause Analysis

### Frontend Issue
The frontend was sending credentials as a simple password string:
```typescript
credentials: 'mypassword123'  // Just a string
```

### Backend Expectation
The backend expected credentials as a JSON string containing username and password:
```kotlin
{
  "username": "user@example.com",
  "password": "mypassword123"
}
```

### Connection Failure
When the backend tried to establish an SMTP connection, it looked for:
- `config["username"]` - which didn't exist (empty string)
- `config["password"]` - which didn't exist (empty string)

This resulted in authentication failures when testing email accounts.

## Solution Applied

### 1. Updated Frontend Component (`TestEmailAccountManagement.tsx`)

#### Added Separate Username Field
- Changed from single "credentials" field to separate "username" and "password" fields
- Added proper form validation and user guidance
- Added app-specific password warnings for Gmail and Yahoo

#### Updated Form Data Structure
```typescript
interface TestEmailFormData {
  name: string;
  email: string;
  provider: 'GMAIL' | 'OUTLOOK' | 'YAHOO' | 'SMTP_CUSTOM' | 'IMAP_CUSTOM';
  username: string;    // NEW: Separate username field
  password: string;    // NEW: Separate password field
  smtpHost?: string;
  smtpPort?: number;
  imapHost?: string;
  imapPort?: number;
  description?: string;
}
```

#### Updated Form Submission
```typescript
// Build credentials JSON object
const credentialsObj: any = {
  username: formData.username,
  password: formData.password
};

// Send as JSON string
requestData.credentials = JSON.stringify(credentialsObj);
```

#### Fixed Provider Enum Values
- Changed from `'CUSTOM'` to `'SMTP_CUSTOM'` and `'IMAP_CUSTOM'` to match backend enums
- Updated status values from `'VERIFIED'` to `'ACTIVE'` to match backend enum

### 2. Form UI Improvements

#### Added Username Input
```html
<input
  type="text"
  id="username"
  name="username"
  required={!editingAccount}
  placeholder="Email address or username"
/>
<small>Usually your email address. Leave blank when editing to keep existing.</small>
```

#### Improved Password Input
```html
<input
  type="password"
  id="password"
  name="password"
  required={!editingAccount}
  placeholder="Leave blank to keep existing password"
/>
<small className="text-warning">
  Gmail and Yahoo require app-specific passwords.
</small>
```

### 3. Edit Functionality Enhancement
- When editing, credentials fields are now optional
- Users can update other fields without changing credentials
- Clear messaging that blank fields will keep existing credentials

## Testing Instructions

### 1. Create New Test Email Account

1. **Navigate to Test Email Accounts**
   - Go to http://localhost:4321/admin/test-email-accounts
   - Click "Add New Account"

2. **Fill in the Form**
   - **Account Name**: "Gmail Test Account"
   - **Email Address**: "your-email@gmail.com"
   - **Email Provider**: Select "Gmail"
   - **Username**: "your-email@gmail.com"
   - **Password**: Enter your Gmail app-specific password
   - **SMTP Host**: "smtp.gmail.com" (auto-filled)
   - **SMTP Port**: 587 (auto-filled)

3. **Save and Test**
   - Click "Save" to create the account
   - Status should be "VERIFICATION_PENDING"
   - Click "Test Connection" to verify SMTP connectivity
   - If successful, status changes to "ACTIVE"
   - Click "Send Test Email" to send an actual test email

### 2. Gmail App-Specific Password Setup

If using Gmail:
1. Enable 2-factor authentication on your Google account
2. Go to https://myaccount.google.com/apppasswords
3. Generate an app-specific password for "Mail"
4. Use this 16-character password (not your regular Gmail password)

### 3. Test Other Providers

**Outlook**:
- Username: your-email@outlook.com
- Password: Your Outlook password (not app-specific)
- SMTP Host: smtp-mail.outlook.com
- SMTP Port: 587

**Yahoo**:
- Username: your-email@yahoo.com
- Password: Yahoo app-specific password
- SMTP Host: smtp.mail.yahoo.com
- SMTP Port: 587

**Custom SMTP**:
- Username: Your SMTP username
- Password: Your SMTP password
- SMTP Host: your.smtp.server.com
- SMTP Port: 587 or 465

## Files Modified

1. **`/src/frontend/src/components/TestEmailAccountManagement.tsx`** (Modified)
   - Added separate username and password fields to replace single credentials field
   - Updated interface definitions for proper type safety
   - Fixed provider enum values (SMTP_CUSTOM, IMAP_CUSTOM instead of CUSTOM)
   - Fixed status enum values (ACTIVE instead of VERIFIED)
   - Improved form submission logic to build JSON credentials object
   - Enhanced edit functionality to allow optional credential updates
   - Added helpful UI hints for app-specific passwords

2. **`/src/frontend/src/pages/admin/test-email-accounts.astro`** (Created)
   - New admin page for test email account management
   - Imports and renders TestEmailAccountManagement component
   - Accessible at `/admin/test-email-accounts`

3. **`/EMAIL_PROVIDER_FIX.md`** (Created)
   - Complete documentation of the issue and fix
   - Testing instructions
   - Troubleshooting guide

## Backend Compatibility

The backend already had the correct implementation:
- It expects credentials as a JSON string with username/password
- It properly parses the JSON and extracts username/password for SMTP auth
- It handles optional credentials when editing (keeps existing if not provided)

## Expected Behavior After Fix

1. **Form Display**: Shows separate username and password fields with clear labels
2. **Provider Selection**: Correctly maps to backend enums (GMAIL, OUTLOOK, YAHOO, SMTP_CUSTOM, IMAP_CUSTOM)
3. **Status Display**: Shows correct status values (ACTIVE, VERIFICATION_PENDING, FAILED, INACTIVE)
4. **Test Connection**: Successfully authenticates with SMTP server
5. **Send Test Email**: Sends email using properly authenticated SMTP session
6. **Edit Mode**: Allows updating account details without re-entering credentials

## Verification

To verify the fix is working:

1. **Check Browser Console**: No JavaScript errors should appear
2. **Check Backend Logs**: Look for "Test email sent successfully" or connection success messages
3. **Check Email**: Test email should arrive in the specified inbox
4. **Check Status**: Account status should change from VERIFICATION_PENDING to ACTIVE after successful test

## Troubleshooting

### Issue: "Connection test failed"
- **Check**: Username and password are correct
- **Check**: For Gmail/Yahoo, using app-specific password
- **Check**: SMTP host and port are correct
- **Check**: Firewall allows outbound SMTP connections

### Issue: "Failed to send test email"
- **Check**: Account status is ACTIVE
- **Check**: SMTP credentials are still valid
- **Check**: Network connectivity to SMTP server

### Issue: "Cannot send test email from unverified account"
- **Solution**: Run "Test Connection" first to change status to ACTIVE
