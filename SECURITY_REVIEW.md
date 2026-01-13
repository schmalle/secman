# Security Review Report - Secman

**Date:** 2026-01-13
**Reviewed by:** Claude Security Analysis
**Scope:** Full codebase security assessment

---

## Executive Summary

This security review identified **4 critical**, **6 high**, **8 medium**, and **5 low** severity issues across the secman codebase. The most critical finding is the exposure of OAuth client secrets through a public API endpoint.

### Risk Summary

| Severity | Count | Status |
|----------|-------|--------|
| Critical | 4 | Requires immediate action |
| High | 6 | Fix within 1 week |
| Medium | 8 | Fix within 1 month |
| Low | 5 | Fix as resources allow |

---

## Critical Findings

### CRIT-001: OAuth Client Secrets Exposed in Public API

**Location:** `src/backendng/src/main/kotlin/com/secman/controller/IdentityProviderController.kt:141-152`

**Issue:** The `/api/identity-providers/enabled` endpoint is marked `@Secured(SecurityRule.IS_ANONYMOUS)` and returns full `IdentityProvider` entities including the `clientSecret` field.

**Code:**
```kotlin
@Get("/enabled")
@Secured(SecurityRule.IS_ANONYMOUS)  // PUBLIC - No authentication required
fun getEnabledProviders(): HttpResponse<*> {
    val providers = identityProviderRepository.findByEnabled(true)
    HttpResponse.ok(providers)  // Exposes clientSecret!
}
```

**Impact:** Any unauthenticated user can obtain OAuth client secrets by calling `GET /api/identity-providers/enabled`.

**Remediation:**
1. Add `@JsonIgnore` to `IdentityProvider.clientSecret` field
2. Create a `IdentityProviderPublicDto` without sensitive fields
3. Add `@Convert(converter = EncryptedStringConverter::class)` for at-rest encryption

---

### CRIT-002: IdentityProvider clientSecret Not Encrypted at Rest

**Location:** `src/backendng/src/main/kotlin/com/secman/domain/IdentityProvider.kt:25-26`

**Issue:** Unlike `FalconConfig` and `EmailConfig` which use `@Convert(converter = EncryptedStringConverter::class)`, the `IdentityProvider.clientSecret` is stored in plaintext.

**Comparison:**
```kotlin
// FalconConfig.kt - SECURE
@Convert(converter = EncryptedStringConverter::class)
var clientSecret: String = ""

// IdentityProvider.kt - VULNERABLE
@Column(name = "client_secret")
var clientSecret: String? = null  // NO ENCRYPTION!
```

**Impact:** Database compromise or backup exposure reveals OAuth secrets in plaintext.

---

### CRIT-003: Default Encryption Credentials in Production Code

**Location:** `src/backendng/src/main/kotlin/com/secman/util/EncryptedStringConverter.kt:18-19`

**Issue:** Hardcoded default encryption password and salt used when environment variables are not set.

```kotlin
private const val DEFAULT_PASSWORD = "SecManDefaultEncryptionPassword2024"
private const val DEFAULT_SALT = "5c0744940b5c369b"
```

**Also in:** `src/backendng/src/main/resources/application.yml:143-145`
```yaml
password: ${SECMAN_ENCRYPTION_PASSWORD:SecManDefaultEncryptionPassword2024}
salt: ${SECMAN_ENCRYPTION_SALT:5c0744940b5c369b}
```

**Impact:** If environment variables aren't set, all encrypted fields use weak, known defaults.

**Remediation:** Make these required (fail startup if not set) rather than defaulted.

---

### CRIT-004: Default JWT Secret in Configuration

**Location:** `src/backendng/src/main/resources/application.yml:66`

```yaml
secret: ${JWT_SECRET:pleasechangethissecrectokeyproductionforsecurityreasonsmustbe256bits}
```

**Impact:** Unsigned JWT tokens if environment variable not set. Token forgery possible.

---

## High Severity Findings

### HIGH-001: MCP Test User ID Fallback

**Location:** `src/backendng/src/main/kotlin/com/secman/controller/McpAdminController.kt:512-514`

**Issue:**
```kotlin
if (authentication == null) {
    logger.debug("No authentication provided, using test user ID")
    return 1L  // Dangerous fallback!
}
```

**Impact:** Security bypass if Micronaut security is misconfigured.

---

### HIGH-002: File Size Limit Mismatch

**Location:**
- `ImportController.kt:52` - `MAX_FILE_SIZE = 10MB`
- `application.yml:11-13` - `max-file-size: 100MB`

**Issue:** Controller validates 10MB but server accepts 100MB. Files 10-100MB bypass controller validation.

---

### HIGH-003: XXE Protection Incomplete in XML Parsers

**Location:** `src/backendng/src/main/kotlin/com/secman/service/MasscanParserService.kt:62-74`

**Issue:**
```kotlin
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)  // Should be TRUE
```

Setting `disallow-doctype-decl` to `false` allows DOCTYPE declarations which can be exploited.

---

### HIGH-004: Temporary File Race Condition

**Location:** `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt:590-612`

**Issue:** CSV upload creates temp file in system directory without secure permissions.

```kotlin
val tempFile = java.io.File.createTempFile("csv_upload_", ".csv")
```

**Impact:** TOCTOU (Time-of-check to time-of-use) race condition possible.

---

### HIGH-005: MCP Rate Limiting Not Enforced

**Location:** `src/backendng/src/main/kotlin/com/secman/controller/McpController.kt`

**Issue:** Tool execution endpoint doesn't check rate limits despite having rate limit service.

---

### HIGH-006: Delegation Failure Tracking In-Memory Only

**Location:** `src/backendng/src/main/kotlin/com/secman/service/McpDelegationService.kt:56`

**Issue:** Failure records stored in `ConcurrentHashMap`, lost on restart, not shared across instances.

---

## Medium Severity Findings

### MED-001: Content-Type Validation Bypass

**Location:** `ImportController.kt:455-457, 575-580`

**Issue:** Accepting `octet-stream` content type is too permissive. CSV validation warns but allows anyway.

---

### MED-002: Email Domain Validation Regex Weakness

**Location:** `McpDelegationService.kt:310`

```kotlin
val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
```

**Issue:** Allows consecutive dots/hyphens like `user..name@domain..com`.

---

### MED-003: println() Instead of Logger

**Location:** `VulnerabilityStatisticsController.kt:63,95,124,157,186,216,257`

**Issue:** Error messages printed to stdout instead of structured logging.

---

### MED-004: MCP Session Timeout Not Enforced

**Location:** `McpSessionService.kt:152-231`

**Issue:** Sessions created but timeout not validated on HTTP requests.

---

### MED-005: No Batch Size Limits on Import

**Location:** `AssetImportService.kt:62-156`

**Issue:** Large imports could cause memory exhaustion.

---

### MED-006: Formula Injection in Excel Parsing

**Location:** `VulnerabilityImportService.kt:302-315`

**Issue:** Excel formulas evaluated which could cause DoS or data exposure.

---

### MED-007: Missing Antivirus/Malware Scanning

**Issue:** No file content scanning beyond format validation.

---

### MED-008: CORS Allows All Origins for MCP

**Location:** `application.yml:42`

```yaml
allowed-origins-regex: ".*"
```

**Issue:** While documented for non-browser MCP clients, this could be exploited.

---

## Low Severity Findings

### LOW-001: OAuth Controller Logs Full URLs

**Location:** `OAuthController.kt:67-79`

**Issue:** Full authorization URLs with state tokens logged.

---

### LOW-002: Sensitive Data in Filenames Logged

**Location:** `ImportController.kt:555-556`

**Issue:** User-provided filenames logged without sanitization.

---

### LOW-003: Audit Logging Asynchronous

**Location:** `McpAuditService.kt`

**Issue:** Async logging could lose recent entries on crash.

---

### LOW-004: Missing Rate Limit Response Headers

**Issue:** Clients don't know their remaining quota.

---

### LOW-005: No API Key Rotation Endpoint

**Issue:** Users must revoke and recreate keys instead of rotating.

---

## Positive Security Findings

The review also identified many well-implemented security patterns:

| Area | Implementation | Status |
|------|---------------|--------|
| Password Hashing | BCrypt with automatic salting | Secure |
| SQL Injection | 100% parameterized queries | Secure |
| XSS Prevention | HttpOnly cookies, CSP headers | Secure |
| CSRF Protection | SameSite cookies, state tokens | Secure |
| Input Validation | Comprehensive validation service | Good |
| Security Headers | X-Frame-Options, HSTS, CSP | Complete |
| User Enumeration | Same error for all auth failures | Good |
| OAuth State | Cryptographic random, 10min expiry | Secure |
| MFA Support | WebAuthn/FIDO2 passkeys | Good |
| API Key Hashing | SHA-256 with salt | Secure |
| Brute Force | IP-based rate limiting | Good |

---

## Optimization Plan

### Phase 1: Critical (Immediate - This Week)

1. **Fix CRIT-001: IdentityProvider Secret Exposure**
   - Add `@JsonIgnore` to `clientSecret` field
   - Create `IdentityProviderPublicDto` for public endpoints
   - Update `/enabled` endpoint to use DTO
   - Files: `IdentityProvider.kt`, `IdentityProviderController.kt`

2. **Fix CRIT-002: Encrypt clientSecret at Rest**
   - Add `@Convert(converter = EncryptedStringConverter::class)`
   - Run database migration to encrypt existing values
   - File: `IdentityProvider.kt`

3. **Fix CRIT-003 & CRIT-004: Remove Default Secrets**
   - Add startup validation requiring `SECMAN_ENCRYPTION_PASSWORD`, `SECMAN_ENCRYPTION_SALT`, `JWT_SECRET`
   - Remove defaults from `application.yml`
   - Files: `EncryptedStringConverter.kt`, `application.yml`

### Phase 2: High (Next Sprint)

4. **Fix HIGH-001: Remove Test User Fallback**
   - Throw `UnauthorizedException` instead of defaulting
   - File: `McpAdminController.kt`

5. **Fix HIGH-002: Align File Size Limits**
   - Either reduce `application.yml` to 10MB or update controller to 100MB
   - Files: `ImportController.kt`, `application.yml`

6. **Fix HIGH-003: Complete XXE Protection**
   - Set `disallow-doctype-decl` to `true`
   - Files: `MasscanParserService.kt`, `NmapParserService.kt`

7. **Fix HIGH-004: Secure Temp File Handling**
   - Use `Files.createTempFile()` with secure attributes
   - Or process in-memory without temp files
   - File: `ImportController.kt`

8. **Fix HIGH-005 & HIGH-006: Implement Rate Limiting**
   - Enable Redis-backed rate limiting
   - Move delegation tracking to database
   - Files: `McpController.kt`, `McpDelegationService.kt`

### Phase 3: Medium (Next Month)

9. Tighten content-type validation
10. Improve email regex validation
11. Replace println with logger
12. Enforce MCP session timeout
13. Add batch size limits
14. Disable formula evaluation in Excel
15. Consider antivirus integration
16. Review CORS configuration

### Phase 4: Low (As Resources Allow)

17. Reduce OAuth logging verbosity
18. Sanitize filenames in logs
19. Make audit logging synchronous for critical events
20. Add rate limit headers
21. Implement API key rotation

---

## Testing Recommendations

1. **Penetration Testing**: Focus on OAuth flow and MCP endpoints
2. **Fuzzing**: File upload endpoints with malformed files
3. **Load Testing**: Rate limiting effectiveness
4. **Security Scanning**: OWASP ZAP or similar for endpoint testing

---

## Compliance Considerations

- **GDPR**: User data deletion capabilities exist (UserDeletionValidator)
- **SOC2**: Audit logging implemented but needs synchronous option
- **PCI-DSS**: Not applicable (no payment data)

---

## Conclusion

The secman codebase demonstrates generally good security practices with comprehensive input validation, secure password handling, and proper SQL injection prevention. However, the critical exposure of OAuth client secrets requires immediate remediation. The optimization plan provides a prioritized roadmap for addressing all identified issues.
