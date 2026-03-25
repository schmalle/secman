# Security Review Report - secman

**Date:** 2026-03-25
**Scope:** Full codebase review (backend, frontend, CLI, configuration, deployment)
**Severity Scale:** CRITICAL > HIGH > MEDIUM > LOW > INFO

---

## Executive Summary

The secman codebase demonstrates **mature security practices** across most areas. Authentication uses HttpOnly cookies with JWT, passwords are hashed with BCrypt, RBAC is consistently enforced via `@Secured` annotations, and input validation covers OWASP Top 10 vectors. However, several findings require attention before or during production deployment.

**Critical:** 1 | **High:** 5 | **Medium:** 12 | **Low:** 4

---

## CRITICAL Findings

### C-1: Default JWT Secret Runs in Production Without Enforcement

**File:** `src/backendng/src/main/resources/application.yml:67`

```yaml
secret: ${JWT_SECRET:pleasechangethissecrectokeyproductionforsecurityreasonsmustbe256bits}
```

If the `JWT_SECRET` environment variable is not set, the application runs with a known default. Any attacker who reads this config can forge arbitrary JWT tokens, impersonate any user (including ADMIN), and gain full system access.

**Recommendation:** Add a startup validator that refuses to start if the JWT secret matches the default value in non-dev environments.

---

## HIGH Findings

### H-1: Hardcoded Default Admin Password

**File:** `src/backendng/src/main/kotlin/com/secman/config/DefaultAdminBootstrapper.kt:36`

```kotlin
const val DEFAULT_ADMIN_PASSWORD = "password"
```

On first startup with an empty database, an admin user is created with username `admin` and password `password`. If an attacker reaches the application before the operator changes this, they gain full ADMIN access.

**Recommendation:** Generate a random password at bootstrap time and log it once, or require password setup via an initial configuration step.

### H-2: Public Anonymous Endpoints Expose Business Data

**Files:**
- `AlignmentController.kt:500-738` - 6 anonymous endpoints for alignment results/reviews via token
- `DemandClassificationController.kt:75` - Public classification endpoint (no auth)
- `DemandClassificationController.kt:344` - Public result lookup by hash
- `PublicRequirementDownloadController.kt:23` - Public requirement file download

The alignment endpoints use opaque tokens as access control (bearer-token-in-URL pattern). If tokens are guessable, short, or logged in proxy/access logs, unauthorized users can access alignment session data including reviewer names, decisions, and requirement details.

The demand classification public endpoint allows unauthenticated users to invoke the classification service and store results in the database, which could be abused for DoS.

**Recommendation:**
- Ensure alignment tokens are cryptographically random and sufficiently long (>=32 bytes)
- Add rate limiting to public classification endpoint
- Review whether public endpoints truly need to be anonymous

### H-3: JWT Tokens Passed in URL Query Parameters for SSE

**File:** `src/backendng/src/main/kotlin/com/secman/controller/ExceptionBadgeUpdateHandler.kt`

SSE endpoints require JWT tokens passed as `?token=<jwt>` query parameters because the EventSource API does not support custom headers. Tokens in URLs are logged by proxies, web servers, CDNs, and appear in browser history.

**Recommendation:** Use a short-lived, single-use SSE ticket exchanged via an authenticated endpoint, rather than passing the full JWT in the URL.

### H-4: MCP CORS Allows All Origins

**File:** `src/backendng/src/main/resources/application.yml:41-42`

```yaml
allowed-origins-regex:
  - ".*"  # MCP clients can come from anywhere (non-browser)
```

While MCP clients authenticate via API key headers and `allow-credentials: false` prevents cookie-bearing requests, the wildcard origin means any web page could send requests to MCP endpoints. If an API key is leaked, there is no origin restriction to limit exploitation.

**Recommendation:** Restrict to known MCP client origins in production, or implement request signing.

### H-5: Default Encryption Secrets

**File:** `src/backendng/src/main/resources/application.yml:160-162`

```yaml
password: ${SECMAN_ENCRYPTION_PASSWORD:SecManDefaultEncryptionPassword2024}
salt: ${SECMAN_ENCRYPTION_SALT:5c0744940b5c369b}
```

Used to encrypt sensitive data at rest (CrowdStrike client secrets, translation API keys). The default values are known and static. If not overridden in production, all encrypted data can be trivially decrypted.

**Recommendation:** Same as C-1 - refuse to start with default encryption secrets in production.

---

## MEDIUM Findings

### M-1: Rate Limiting is In-Memory Only

**File:** `src/backendng/src/main/kotlin/com/secman/controller/AuthController.kt:40-77`

Login rate limiting uses `ConcurrentHashMap`, which resets on restart and is not shared across instances. In a clustered deployment, an attacker can distribute brute-force attempts across instances.

**Recommendation:** Move rate limiting state to a shared store (Redis, database).

### M-2: Missing Rate Limiting on Expensive Endpoints

Endpoints like `GET /api/vulnerabilities/current` (complex queries) and file import endpoints have no rate limiting. Authenticated users could trigger resource exhaustion.

**Recommendation:** Add per-user rate limiting on expensive operations.

### M-3: Exception Messages Leaked in API Responses

**Files:**
- `VulnerabilityManagementController.kt` - Returns `e.message` in error responses
- `VulnerabilityExceptionRequestController.kt` - Multiple instances of `e.message`
- `DemandClassificationController.kt:98-99` - Returns exception details

Internal exception messages may reveal implementation details, class names, or SQL errors.

**Recommendation:** Return generic error messages; log details server-side only.

### M-4: CSP Uses 'unsafe-inline' for Scripts

**File:** `src/backendng/src/main/kotlin/com/secman/filter/SecurityHeadersFilter.kt`

```
script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net
```

Required for Astro framework hydration, but weakens XSS protection. An attacker who finds an HTML injection point can execute inline scripts.

**Recommendation:** Migrate to nonce-based CSP when Astro supports it.

### M-5: WebAuthn Challenge Storage is In-Memory

**File:** `src/backendng/src/main/kotlin/com/secman/service/WebAuthnService.kt`

WebAuthn registration/authentication challenges are stored in a `ConcurrentHashMap`. In multi-instance deployments, a user who initiates registration on instance A and completes on instance B will fail.

**Recommendation:** Store challenges in Redis or database with TTL.

### M-6: Password Policy Only Enforces Length

**File:** `src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt:258`

Passwords require 8-200 characters but no complexity requirements (mixed case, digits, symbols). Users can set weak passwords like `password` or `12345678`.

**Recommendation:** Add complexity requirements or use a password strength estimator (e.g., zxcvbn).

### M-7: CLI Stores Credentials in Plaintext YAML

**File:** `src/cli/src/main/kotlin/com/secman/cli/config/ConfigLoader.kt`

CrowdStrike credentials stored in `~/.secman/crowdstrike.yaml` as plaintext. Any process with read access to the user's home directory can steal these credentials.

**Recommendation:** Use OS keychain, encrypted storage, or require environment variables only.

### M-8: OAuth COOP/CORP Headers Excluded

**File:** `src/backendng/src/main/kotlin/com/secman/filter/SecurityHeadersFilter.kt`

Cross-Origin-Opener-Policy and Cross-Origin-Resource-Policy headers are excluded for `/oauth/` paths. This reduces isolation during the OAuth flow.

**Recommendation:** Re-evaluate if COOP can be set to `same-origin-allow-popups` for OAuth paths.

### M-9: Email Template HTML Injection

**Files:** `src/backendng/src/main/resources/email-templates/`

Email templates use string `.replace()` for variable substitution. If asset names, hostnames, or user-supplied text contain HTML, it will be rendered in email clients.

**Recommendation:** HTML-escape all user-supplied variables before template substitution.

### M-10: Debug Logging Enabled by Default

**File:** `src/backendng/src/main/resources/application.yml`

```yaml
com.secman: DEBUG
```

DEBUG level may log request parameters, internal state, and other sensitive information. Should be INFO or WARN in production.

**Recommendation:** Set production log level to INFO. Use environment variable override for debugging.

### M-11: Database Password Default in docker-compose.yml

**File:** `docker-compose.yml`

Docker compose file uses placeholder `CHANGEME` passwords. If deployed without overriding, the database is accessible with known credentials.

**Recommendation:** Remove default values from compose file; require explicit env file.

### M-12: ConfigBundle Creates User with Known Password

**File:** `src/backendng/src/main/kotlin/com/secman/service/ConfigBundleService.kt`

Creates users with password `ChangeMeNow123!` during config bundle import. If not changed, these accounts are accessible with a known password.

**Recommendation:** Generate random passwords or force password change on first login.

---

## LOW Findings

### L-1: Alignment Tokens in Email Links

Alignment result tokens appear in email notification links. Email is not a secure transport; tokens may be logged, cached, or forwarded.

### L-2: Self-Signed Certificates in Docker (RSA 2048)

**File:** `docker/nginx/entrypoint.sh` - Development uses RSA 2048 self-signed certs. Consider RSA 4096 or ECDSA P-256 for future-proofing.

### L-3: Frontend Console Logging

Login component includes `console.log` statements that may leak OAuth flow details in browser developer tools.

### L-4: No Scheduled Cleanup of Expired OAuth States

OAuth states have a 10-minute expiry but no scheduled cleanup job to purge expired entries from the database.

---

## Positive Security Findings

The following areas demonstrate strong security practices:

| Area | Implementation |
|------|---------------|
| **JWT Cookies** | HttpOnly, Secure, SameSite=Lax - tokens not accessible to JavaScript |
| **Password Hashing** | BCrypt via Spring Security (industry standard) |
| **RBAC** | @Secured annotations on all 62 controllers with 7 granular roles |
| **Input Validation** | SQL injection, XSS, command injection, path traversal detection (InputValidationService) |
| **XXE Prevention** | Full OWASP-compliant protection in MasscanParserService (DOCTYPE, external entities disabled) |
| **SSRF Prevention** | IdentityProviderController blocks private IPs and cloud metadata endpoints |
| **File Upload** | Multi-layer validation: size, extension, content-type, blocked dangerous extensions |
| **Security Headers** | X-Frame-Options DENY, HSTS preload, Permissions-Policy, Cache-Control |
| **CSRF Protection** | Frontend CSRF token interceptor + SameSite cookies |
| **OAuth Robustness** | Exponential backoff retry for fast Azure callbacks, state CSRF tokens |
| **MCP Authentication** | API key hashing (BCrypt), IP rate limiting, delegation validation |
| **Audit Logging** | Authentication events, MCP tool calls, password changes tracked |
| **IDOR Prevention** | Asset access returns 404 for both "not found" and "no access" |
| **Secret Masking** | FalconConfig, email passwords, S3 keys masked in API responses and logs |
| **No Command Injection** | No Runtime.exec() or ProcessBuilder with user input in backend |
| **Frontend Token Security** | No tokens in localStorage; only non-sensitive user metadata stored client-side |
| **External Link Safety** | All `target="_blank"` links include `rel="noopener noreferrer"` |

---

## Remediation Priority

### Immediate (Pre-Production)
1. **C-1**: Enforce non-default JWT secret at startup
2. **H-1**: Replace hardcoded default admin password with random generation
3. **H-5**: Enforce non-default encryption secrets at startup
4. **M-10**: Set production log level to INFO

### Short-Term (Next Sprint)
5. **H-2**: Add rate limiting to public endpoints; review necessity
6. **H-3**: Replace SSE JWT-in-URL with ticket-based auth
7. **M-3**: Replace exception messages with generic errors
8. **M-6**: Add password complexity requirements
9. **M-9**: HTML-escape email template variables
10. **M-12**: Force password change for ConfigBundle-created users

### Medium-Term
11. **H-4**: Restrict MCP CORS to known origins
12. **M-1**: Distributed rate limiting (Redis)
13. **M-4**: Nonce-based CSP migration
14. **M-5**: Distributed WebAuthn challenge storage
15. **M-7**: Encrypted CLI credential storage

---

*This review covers static analysis of the codebase. Dynamic testing (penetration testing) is recommended as a complement.*
