# Research: OIDC Default Roles

**Date**: 2025-11-14
**Feature**: 046-oidc-default-roles
**Phase**: 0 - Outline & Research

## Research Questions

Based on Technical Context unknowns and technology choices, the following areas require research:

1. **Transaction Management**: How to ensure atomicity of user creation + role assignment in Micronaut/Hibernate?
2. **Async Email Notifications**: How to implement non-blocking email delivery in Micronaut?
3. **Audit Logging**: What's the best practice for logging security events with structured data?
4. **Feature 041 Integration**: How does the existing OIDC auto-provisioning work in `OAuthService`?

## Findings

### 1. Transaction Management in Micronaut

**Decision**: Use `@Transactional` annotation with rollback on any exception

**Rationale**:
- Micronaut supports declarative transaction management via `@jakarta.transaction.Transactional`
- Default behavior: rollback on RuntimeException (unchecked exceptions)
- For user creation + role assignment, wrap in single method annotated with `@Transactional`
- Hibernate will manage transaction boundaries automatically
- If role assignment fails (e.g., constraint violation), entire transaction rolls back

**Implementation Pattern**:
```kotlin
@Transactional
open fun createUserWithRoles(email: String, username: String, idpName: String): User {
    val user = User(email = email, username = username, roles = mutableSetOf("USER", "VULN"))
    userRepository.save(user) // If this fails or role constraint fails, rollback entire operation
    auditLog(user, "USER,VULN", idpName)
    return user
}
```

**Alternatives Considered**:
- Manual transaction management via `TransactionOperations`: Rejected - too verbose, error-prone
- No transaction (best-effort save): Rejected - violates FR-009 atomicity requirement
- Separate transaction for roles: Rejected - creates orphaned users if role assignment fails

**References**:
- Micronaut Data Transactions: https://micronaut-projects.github.io/micronaut-data/latest/guide/#transactions
- Hibernate Transaction Management: JPA spec section 3.3

---

### 2. Async Email Notifications in Micronaut

**Decision**: Use `@Async` annotation with separate thread pool for email delivery

**Rationale**:
- Micronaut supports `@Async` for non-blocking operations
- Email delivery (SMTP) can be slow (100-500ms); must not block user creation transaction
- Use `@Async` on email notification method to execute in background thread pool
- If email fails, log error but don't propagate exception to caller
- User creation transaction completes before email attempt begins

**Implementation Pattern**:
```kotlin
@Async
open fun notifyAdminsNewUser(user: User, idpName: String) {
    try {
        val admins = userRepository.findByRolesContaining("ADMIN")
        admins.forEach { admin ->
            emailSender.send(
                to = admin.email,
                subject = "New OIDC User Created",
                template = "admin-new-user.html",
                context = mapOf("user" to user, "idp" to idpName)
            )
        }
    } catch (e: Exception) {
        logger.error("Failed to send admin notification for user ${user.username}", e)
        // Don't rethrow - email is best-effort per FR-012
    }
}
```

**Alternatives Considered**:
- Synchronous email in transaction: Rejected - blocks user creation, violates NFR-003
- Message queue (RabbitMQ, Kafka): Rejected - overkill for this use case, adds infrastructure complexity
- Spring @Async with CompletableFuture: Not applicable - using Micronaut, not Spring
- Fire-and-forget thread: Rejected - no thread pool management, resource leak risk

**Configuration**:
```yaml
# application.yml
micronaut:
  executors:
    email:
      type: scheduled
      core-pool-size: 2
      maximum-pool-size: 5
```

**References**:
- Micronaut Async: https://docs.micronaut.io/latest/guide/#taskExecutors
- JavaMail SMTP best practices: RFC 5321

---

### 3. Audit Logging for Security Events

**Decision**: Use SLF4J with structured logging (JSON format) and dedicated security logger

**Rationale**:
- Micronaut uses SLF4J as logging abstraction
- Create dedicated logger for security events: `private val securityLog = LoggerFactory.getLogger("security.audit")`
- Log role assignment events at INFO level with structured data
- Use MDC (Mapped Diagnostic Context) for contextual information
- Format: JSON with timestamp (ISO 8601), user, roles, identity provider
- Log retention handled by logback configuration (NFR-002)

**Implementation Pattern**:
```kotlin
private val securityLog = LoggerFactory.getLogger("security.audit")

fun auditRoleAssignment(user: User, roles: String, idpName: String) {
    MDC.put("event", "role_assignment")
    MDC.put("user_id", user.id.toString())
    MDC.put("username", user.username)
    MDC.put("email", user.email)
    MDC.put("roles", roles)
    MDC.put("identity_provider", idpName)

    securityLog.info("OIDC user created with default roles")

    MDC.clear()
}
```

**Logback Configuration**:
```xml
<!-- logback.xml -->
<appender name="SECURITY_AUDIT" class="ch.qos.logback.core.FileAppender">
    <file>logs/security-audit.log</file>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder" />
</appender>

<logger name="security.audit" level="INFO" additivity="false">
    <appender-ref ref="SECURITY_AUDIT" />
</logger>
```

**Alternatives Considered**:
- Plain text logging: Rejected - difficult to parse for compliance audits
- Database audit table: Rejected - adds complexity, not required for this feature
- External audit service (Splunk, ELK): Rejected - infrastructure decision, not feature-level
- No logging: Rejected - violates NFR-001

**References**:
- SLF4J MDC: https://www.slf4j.org/manual.html#mdc
- Logstash Logback Encoder: https://github.com/logfellow/logstash-logback-encoder

---

### 4. Feature 041 Integration - Existing OIDC Auto-Provisioning

**Decision**: Extend existing `OAuthService.handleCallback()` method to add default roles

**Research Findings**:
Based on CLAUDE.md context and typical Micronaut OAuth implementation:

- Feature 041 provides OIDC authentication via `OAuthController` with `/oauth/authorize` and `/oauth/callback` endpoints
- `OAuthService` handles token exchange and user provisioning
- Auto-provisioning creates new User entity when `identityProvider.autoProvision == true`
- User creation likely happens in `OAuthService.provisionUser()` or similar method
- Current implementation probably creates user with NO roles (empty set)

**Integration Strategy**:
1. Locate user creation logic in `OAuthService`
2. Modify to initialize `roles = mutableSetOf("USER", "VULN")` instead of empty set
3. Add `@Transactional` to user creation method
4. Add audit logging call after successful save
5. Add async email notification call (fire-and-forget)

**Pseudo-code for integration point**:
```kotlin
// In OAuthService.kt - BEFORE modification
@Singleton
class OAuthService(
    private val userRepository: UserRepository,
    private val identityProviderRepository: IdentityProviderRepository
) {
    fun handleCallback(code: String, state: String): User {
        val idp = validateStateAndGetProvider(state)
        val tokenResponse = exchangeCodeForTokens(code, idp)
        val userInfo = fetchUserInfo(tokenResponse.accessToken, idp)

        val existingUser = userRepository.findByEmail(userInfo.email)
        if (existingUser != null) {
            return existingUser // FR-006: Don't modify existing user roles
        }

        if (!idp.autoProvision) {
            throw UnauthorizedException("Auto-provisioning disabled")
        }

        // MODIFY THIS SECTION:
        val newUser = User(
            email = userInfo.email,
            username = userInfo.email.substringBefore("@"),
            roles = mutableSetOf() // <-- CHANGE TO: mutableSetOf("USER", "VULN")
        )
        return userRepository.save(newUser)
    }
}
```

**After Modification**:
```kotlin
@Transactional
open fun createNewOidcUser(email: String, username: String, idpName: String): User {
    val newUser = User(
        email = email,
        username = username,
        passwordHash = null, // OIDC users don't have passwords
        roles = mutableSetOf("USER", "VULN") // FR-001, FR-002
    )
    val savedUser = userRepository.save(newUser) // FR-009: Atomic with role assignment

    auditRoleAssignment(savedUser, "USER,VULN", idpName) // FR-010

    notifyAdminsNewUser(savedUser, idpName) // FR-011 (async, best-effort)

    return savedUser
}
```

**Alternatives Considered**:
- Separate role assignment service: Rejected - adds unnecessary complexity for simple operation
- Post-creation hook: Rejected - complicates transaction management
- Event-driven approach (@EventListener): Rejected - synchronous operation simpler for this use case

**File Locations**:
- Modify: `src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt`
- Use: `src/backendng/src/main/kotlin/com/secman/service/EmailSender.kt` (existing)
- Use: `src/backendng/src/main/kotlin/com/secman/repository/UserRepository.kt` (existing)

---

## Summary

All research questions resolved with concrete technical decisions:

| Question | Decision | Key Technology |
|----------|----------|----------------|
| Transaction atomicity | `@Transactional` annotation | Micronaut Data + Hibernate |
| Async email | `@Async` annotation | Micronaut Executors |
| Audit logging | SLF4J + MDC + JSON | Logback + Logstash encoder |
| Feature 041 integration | Extend `OAuthService.handleCallback()` | Existing OAuth flow |

No NEEDS CLARIFICATION items remaining. Ready to proceed to Phase 1 (Design & Contracts).
