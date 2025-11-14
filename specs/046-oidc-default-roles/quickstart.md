# Quickstart: OIDC Default Roles

**Feature**: 046-oidc-default-roles
**Estimated Effort**: 4-6 hours (2 hours implementation, 2-4 hours testing)
**Prerequisites**: Feature 041 (OIDC/SAML Identity Providers) functional

## Overview

This guide walks through implementing automatic USER and VULN role assignment for newly created OIDC users.

**What you'll build**:
1. Modify `OAuthService` to assign default roles during user creation
2. Add transaction atomicity to ensure rollback on failure
3. Implement audit logging for role assignment events
4. Add async email notifications to administrators
5. Create email template for admin notifications

**What you won't build**:
- New API endpoints (uses existing `/oauth/callback`)
- Frontend changes (feature is transparent to users)
- Database migrations (uses existing User.roles field)

---

## Implementation Steps

### Step 1: Locate OIDC User Creation Logic (15 minutes)

**File**: `src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt`

Find the method that creates new users during auto-provisioning. It likely looks like:

```kotlin
fun handleCallback(code: String, state: String): User {
    // ... token exchange, userInfo fetch ...

    val existingUser = userRepository.findByEmail(userInfo.email)
    if (existingUser != null) {
        return existingUser // Existing user, no changes
    }

    if (!identityProvider.autoProvision) {
        throw UnauthorizedException("Auto-provisioning disabled")
    }

    // THIS IS WHERE WE'LL ADD DEFAULT ROLES
    val newUser = User(
        email = userInfo.email,
        username = userInfo.email.substringBefore("@"),
        passwordHash = null,
        roles = mutableSetOf() // <-- CURRENTLY EMPTY
    )

    return userRepository.save(newUser)
}
```

---

### Step 2: Add Default Roles and Transaction (30 minutes)

**Modify**: `OAuthService.kt`

**Changes**:
1. Extract user creation to separate `@Transactional` method
2. Initialize roles with `mutableSetOf("USER", "VULN")`
3. Add imports for transaction support

```kotlin
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.slf4j.MDC

@Singleton
class OAuthService(
    private val userRepository: UserRepository,
    private val identityProviderRepository: IdentityProviderRepository,
    private val emailSender: EmailSender
) {
    private val logger = LoggerFactory.getLogger(OAuthService::class.java)
    private val securityLog = LoggerFactory.getLogger("security.audit")

    fun handleCallback(code: String, state: String): User {
        val identityProvider = validateStateAndGetProvider(state)
        val tokenResponse = exchangeCodeForTokens(code, identityProvider)
        val userInfo = fetchUserInfo(tokenResponse.accessToken, identityProvider)

        // Check for existing user (FR-006: don't modify existing users)
        val existingUser = userRepository.findByEmail(userInfo.email)
        if (existingUser != null) {
            logger.info("Existing user logged in via OIDC: ${userInfo.email}")
            return existingUser
        }

        // Auto-provision check (FR-007)
        if (!identityProvider.autoProvision) {
            throw UnauthorizedException("Auto-provisioning is disabled for ${identityProvider.name}")
        }

        // Create new user with default roles (FR-001, FR-002, FR-009)
        return createNewOidcUser(
            email = userInfo.email,
            username = userInfo.email.substringBefore("@"),
            identityProviderName = identityProvider.name
        )
    }

    @Transactional // FR-009: Atomic transaction
    open fun createNewOidcUser(email: String, username: String, identityProviderName: String): User {
        logger.info("Creating new OIDC user: $email from provider: $identityProviderName")

        val newUser = User(
            email = email,
            username = username,
            passwordHash = null, // OIDC users don't have passwords
            roles = mutableSetOf("USER", "VULN") // FR-001, FR-002: Default roles
        )

        val savedUser = userRepository.save(newUser)
        logger.info("User created successfully: ${savedUser.id}, username: ${savedUser.username}")

        // FR-010: Audit logging
        auditRoleAssignment(savedUser, "USER,VULN", identityProviderName)

        // FR-011: Notify admins (async, best-effort per FR-012)
        notifyAdminsNewUser(savedUser, identityProviderName)

        return savedUser
    }

    // ... existing methods (validateStateAndGetProvider, exchangeCodeForTokens, etc.) ...
}
```

**Why `open` modifier?**
- Micronaut uses proxy-based AOP for `@Transactional`
- Methods must be `open` (not final) for proxying to work

---

### Step 3: Implement Audit Logging (20 minutes)

**Add to**: `OAuthService.kt`

```kotlin
private fun auditRoleAssignment(user: User, roles: String, identityProviderName: String) {
    try {
        // Use MDC for structured logging
        MDC.put("event", "role_assignment")
        MDC.put("user_id", user.id.toString())
        MDC.put("username", user.username)
        MDC.put("email", user.email)
        MDC.put("roles", roles)
        MDC.put("identity_provider", identityProviderName)

        securityLog.info("OIDC user created with default roles") // NFR-001

        MDC.clear()
    } catch (e: Exception) {
        // Log errors but don't propagate (audit logging is best-effort)
        logger.error("Failed to write audit log for user ${user.username}", e)
    }
}
```

**Configure Logback** (add to `src/backendng/src/main/resources/logback.xml`):

```xml
<!-- Security audit log appender -->
<appender name="SECURITY_AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/security-audit.log</file>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder" />
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/security-audit.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
        <maxHistory>90</maxHistory> <!-- NFR-002: 90 days retention -->
    </rollingPolicy>
</appender>

<!-- Dedicated logger for security events -->
<logger name="security.audit" level="INFO" additivity="false">
    <appender-ref ref="SECURITY_AUDIT" />
</logger>
```

**Add dependency** (if not already present in `build.gradle.kts`):

```kotlin
dependencies {
    // ... existing dependencies ...
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
}
```

---

### Step 4: Implement Admin Notifications (45 minutes)

**Add to**: `OAuthService.kt`

```kotlin
import io.micronaut.scheduling.annotation.Async

@Async // NFR-003: Non-blocking email delivery
open fun notifyAdminsNewUser(user: User, identityProviderName: String) {
    try {
        logger.info("Sending admin notifications for new user: ${user.username}")

        val admins = userRepository.findByRolesContaining("ADMIN")
        if (admins.isEmpty()) {
            logger.warn("No administrators found to notify about new user: ${user.username}")
            return
        }

        admins.forEach { admin ->
            try {
                emailSender.send(
                    to = admin.email,
                    subject = "New OIDC User Created - ${user.username}",
                    template = "admin-new-user.html",
                    context = mapOf(
                        "newUserUsername" to user.username,
                        "newUserEmail" to user.email,
                        "newUserRoles" to user.roles.joinToString(", "),
                        "identityProvider" to identityProviderName,
                        "timestamp" to java.time.Instant.now().toString()
                    )
                )
                logger.debug("Admin notification sent to: ${admin.email}")
            } catch (e: Exception) {
                // FR-012: Log but don't propagate (best-effort delivery)
                logger.error("Failed to send notification to admin ${admin.email}", e)
            }
        }
    } catch (e: Exception) {
        // NFR-004: Log failures for troubleshooting
        logger.error("Failed to send admin notifications for user ${user.username}", e)
        // Don't rethrow - email is best-effort
    }
}
```

**Configure async executor** (`src/backendng/src/main/resources/application.yml`):

```yaml
micronaut:
  executors:
    email:
      type: scheduled
      core-pool-size: 2
      maximum-pool-size: 5
      queue-size: 100
```

---

### Step 5: Create Email Template (30 minutes)

**Create file**: `src/backendng/src/main/resources/email-templates/admin-new-user.html`

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>New OIDC User Created</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            line-height: 1.6;
            color: #333;
            max-width: 600px;
            margin: 0 auto;
            padding: 20px;
        }
        .header {
            background-color: #0066cc;
            color: white;
            padding: 20px;
            text-align: center;
            border-radius: 5px 5px 0 0;
        }
        .content {
            background-color: #f9f9f9;
            padding: 20px;
            border: 1px solid #ddd;
            border-radius: 0 0 5px 5px;
        }
        .info-table {
            width: 100%;
            border-collapse: collapse;
            margin: 15px 0;
        }
        .info-table th {
            text-align: left;
            padding: 10px;
            background-color: #e6f2ff;
            border: 1px solid #ccc;
        }
        .info-table td {
            padding: 10px;
            border: 1px solid #ccc;
        }
        .footer {
            text-align: center;
            margin-top: 20px;
            font-size: 12px;
            color: #666;
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>🔐 New OIDC User Created</h1>
    </div>
    <div class="content">
        <p>A new user has been automatically created via OIDC authentication.</p>

        <table class="info-table">
            <tr>
                <th>Username</th>
                <td>${newUserUsername}</td>
            </tr>
            <tr>
                <th>Email</th>
                <td>${newUserEmail}</td>
            </tr>
            <tr>
                <th>Assigned Roles</th>
                <td><strong>${newUserRoles}</strong></td>
            </tr>
            <tr>
                <th>Identity Provider</th>
                <td>${identityProvider}</td>
            </tr>
            <tr>
                <th>Created At</th>
                <td>${timestamp}</td>
            </tr>
        </table>

        <p><strong>Action Required:</strong></p>
        <ul>
            <li>Verify the new user has appropriate access to assets and vulnerabilities</li>
            <li>Consider adding the user to relevant workgroups if needed</li>
            <li>Review the user's role assignments and modify if necessary</li>
        </ul>

        <p><em>This user was automatically provisioned with default roles (USER, VULN) as per system policy.</em></p>
    </div>
    <div class="footer">
        <p>This is an automated notification from Secman.<br>
        For questions, contact your system administrator.</p>
    </div>
</body>
</html>
```

---

## Testing (Manual - Before TDD)

### Quick Manual Test

1. **Start backend**:
   ```bash
   cd src/backendng
   ./gradlew run
   ```

2. **Configure test OIDC provider** (if not already done):
   - Create Identity Provider via admin UI or API
   - Set `autoProvision = true`
   - Note the provider ID

3. **Trigger OAuth flow**:
   ```bash
   # Visit in browser (replace {provider-id} with actual ID)
   http://localhost:8080/oauth/authorize?provider={provider-id}
   ```

4. **Authenticate with identity provider** (Google, Microsoft, etc.)

5. **Verify results**:
   ```sql
   -- Check user created with roles
   SELECT u.id, u.username, u.email, ur.role
   FROM users u
   LEFT JOIN user_roles ur ON u.id = ur.user_id
   WHERE u.email = 'your-test-email@example.com';

   -- Expected: 2 rows with roles 'USER' and 'VULN'
   ```

6. **Check audit log**:
   ```bash
   tail -f logs/security-audit.log
   ```

7. **Check admin email** (if SMTP configured):
   - Verify admin users received notification email
   - Check email content matches template

---

## Troubleshooting

### Issue: User created but no roles assigned

**Cause**: Transaction not applied correctly

**Solution**:
- Verify `@Transactional` annotation present on `createNewOidcUser()`
- Check method is `open` (not final)
- Ensure Micronaut transaction management is configured

### Issue: Email not sent

**Cause**: SMTP not configured or async executor not running

**Solution**:
```yaml
# Check application.yml
micronaut:
  email:
    from: noreply@example.com
  smtp:
    host: smtp.gmail.com
    port: 587
    auth: true
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    starttls: true
```

### Issue: Existing users getting roles reset

**Cause**: Logic not checking for existing users before creating new one

**Solution**:
- Verify `userRepository.findByEmail()` check happens BEFORE `createNewOidcUser()`
- Add logging to confirm code path

---

## Next Steps

After implementation complete:

1. **Run `/speckit.tasks`** to generate implementation task list
2. **Write tests** (when user requests testing):
   - Unit test: `OAuthServiceTest.kt` (role assignment logic)
   - Integration test: `OAuthIntegrationTest.kt` (full OAuth flow)
   - E2E test: Playwright test for first-time OIDC login
3. **Manual QA**:
   - Test with multiple identity providers
   - Test existing user login (roles unchanged)
   - Test with auto-provision disabled (should reject)
4. **Code review**: Verify constitutional compliance
5. **Merge**: Create PR with conventional commit message

---

## Time Estimates

| Task | Estimated Time |
|------|---------------|
| Step 1: Locate code | 15 min |
| Step 2: Add roles + transaction | 30 min |
| Step 3: Audit logging | 20 min |
| Step 4: Admin notifications | 45 min |
| Step 5: Email template | 30 min |
| Manual testing | 30 min |
| **Total** | **2 hours 50 min** |

Add 2-4 hours for comprehensive testing (if requested).

---

## Reference

- **Spec**: [spec.md](./spec.md)
- **Plan**: [plan.md](./plan.md)
- **Research**: [research.md](./research.md)
- **Data Model**: [data-model.md](./data-model.md)
- **API Contract**: [contracts/oauth-callback.yaml](./contracts/oauth-callback.yaml)
