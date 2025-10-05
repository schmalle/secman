# Quick Start Guide - Test Email Accounts

## Access the Feature
1. Log in as an admin user
2. Navigate to: http://localhost:4321/admin/test-email-accounts
3. Or click "Test Email Accounts" in the admin menu (if available)

## Create a Gmail Test Account (Recommended for Testing)

### Step 1: Generate Gmail App Password
1. Go to https://myaccount.google.com/apppasswords
2. Sign in with your Gmail account
3. Create a new app password:
   - App: Mail
   - Device: Other (Custom name) - Enter "SecMan Test"
4. Copy the 16-character password (e.g., "abcd efgh ijkl mnop")

### Step 2: Add Account in SecMan
1. Click "Add New Account" button
2. Fill in the form:
   ```
   Account Name: Gmail Test
   Email Address: your-email@gmail.com
   Email Provider: Gmail (selected from dropdown)
   Username: your-email@gmail.com
   Password: abcdefghijklmnop (paste the app password without spaces)
   SMTP Host: smtp.gmail.com (auto-filled)
   SMTP Port: 587 (auto-filled)
   IMAP Host: imap.gmail.com (auto-filled)
   IMAP Port: 993 (auto-filled)
   Description: Gmail test account for development
   ```
3. Click "Save"

### Step 3: Test Connection
1. Find your account in the list (Status: "VERIFICATION PENDING")
2. Click "Test Connection" button
3. Wait for the test to complete
4. Status should change to "ACTIVE" if successful

### Step 4: Send Test Email
1. Click "Send Test Email" button
2. Enter recipient email address (can be your own)
3. Check your inbox for the test email

## Create Other Provider Accounts

### Outlook Account
```
Username: your-email@outlook.com
Password: Your regular Outlook password
SMTP Host: smtp-mail.outlook.com
SMTP Port: 587
```

### Yahoo Account
```
Username: your-email@yahoo.com
Password: Yahoo app password (required, like Gmail)
SMTP Host: smtp.mail.yahoo.com
SMTP Port: 587
```

### Custom SMTP Server
```
Username: Your SMTP username
Password: Your SMTP password
SMTP Host: mail.yourdomain.com
SMTP Port: 587 or 465
```

## Edit Existing Account
1. Click "Edit" button on any account
2. Modify fields as needed
3. Leave username/password blank to keep existing credentials
4. Click "Update"

## Troubleshooting

### "Connection test failed"
- **Gmail/Yahoo**: Make sure you're using an app-specific password, not your regular password
- **All providers**: Verify username and password are correct
- **Network**: Check firewall allows outbound connections on port 587
- **SMTP Settings**: Verify host and port are correct for your provider

### "Cannot send test email from unverified account"
- Run "Test Connection" first to activate the account
- Status must be "ACTIVE" to send emails

### "Invalid credentials"
- For Gmail: Regenerate app password and try again
- For Outlook: Ensure you're using correct account password
- For Yahoo: Create new app password at Yahoo Account Security

## Status Meanings
- **VERIFICATION PENDING**: New account, not tested yet
- **TESTING**: Currently testing connection
- **ACTIVE**: Verified and ready to use
- **FAILED**: Connection test failed
- **INACTIVE**: Manually disabled

## Best Practices
1. Use app-specific passwords for Gmail and Yahoo (never use your main password)
2. Test connection immediately after creating an account
3. Keep account descriptions clear and meaningful
4. Regularly test accounts to ensure they remain active
5. Delete unused or expired accounts

## Security Notes
- Passwords are encrypted in the database
- Passwords are never displayed after saving
- Only admins can view and manage test email accounts
- API credentials are masked in API responses
