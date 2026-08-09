# Control inventory — reuse these, never write a second one

One control per risk. Every entry names the file that already implements it
correctly, so new code copies something already reviewed. A second sanitizer,
second URL validator or second permission check is not defence in depth — it is
a divergence that gets patched on one side only.

Paths are relative to the repo root. Backend package root is
`src/backendng/src/main/kotlin/com/secman/`.

---

## A01 — Broken Access Control

**Authoritative filter**: `service/AssetFilterService.kt`

```kotlin
// Resolving a client-supplied id
if (!assetFilterService.canAccessAsset(assetId, authentication)) {
    return HttpResponse.notFound()          // not 403 — do not confirm existence
}

// Listing
val assets = assetFilterService.getAccessibleAssets(authentication)
val ids    = assetFilterService.getAccessibleAssetIds(authentication)
```

Never `repository.findById(userSuppliedId)` and return it. The ten access paths
are listed in `CLAUDE.md` §Unified Asset Access; do not restate or re-derive
them in a new query.

Note the deliberate asymmetry, which is easy to get backwards:
`getAccessibleAssets()` / `getAccessibleAssetIds()` short-circuit for **ADMIN or
SECCHAMPION**; `getScopedAccessibleAssetIds()` short-circuits for **ADMIN only**.

**SQL pre-filters in materialized views and native queries are perf hints, never
the auth boundary.** This is the single most repeated bug class in the repo,
which is why `CLAUDE.md` states it twice and `owasp-check.sh` raises `A01-sql-authz`
on every touched query.

**Controllers**: `@Secured` on the class or every endpoint.

```kotlin
@Controller("/api/thing")
@Secured(SecurityRule.IS_AUTHENTICATED)
class ThingController {
    @Get
    fun list(authentication: Authentication): HttpResponse<*> {
        if (!authentication.roles.contains("VULN")) return HttpResponse.status(HttpStatus.FORBIDDEN)
        ...
    }
}
```

A public endpoint is an explicit, justified exception — today only
`GET /api/crowdstrike/last-checkin` and `GET /api/maintenance-banners/active`.
Never a default, never "for now".

**MCP tools** need three things, and the third fails open:

1. an entry in `mcp/McpToolPermissions.kt` **`LISTING`** — gates `tools/list`
2. an entry in the same file's **`CALLING`** — gates `tools/call`
3. a guard call in `execute()` from `mcp/tools/McpToolGuards.kt`

```kotlin
override suspend fun execute(args: Map<String, Any>, context: McpExecutionContext): McpToolResult {
    requireDelegation(context)?.let { return it }
    requireAnyRole(context, "ADMIN", "VULN")?.let { return it }
    ...
}
```

A missing `CALLING` entry fails **closed** and looks like a bug someone will
chase. A missing guard fails **open** and looks like nothing at all.
`owasp-check.sh` checks all three mechanically for that reason.

---

## A02 — Cryptographic Failures

| Need | Use | Never |
|---|---|---|
| hash a password or API-key secret | `BCryptPasswordEncoder` | SHA-256, MD5, hand-rolled |
| store a credential at rest | `util/EncryptedStringConverter.kt` | plain column |
| hold the session JWT | HttpOnly `secman_auth` cookie (`service/AuthCookieService.kt`) | `localStorage`, `sessionStorage`, non-HttpOnly cookie, any logged value |
| supply a secret to code | `pass-cli` / env | a literal in source, tests, scripts or fixtures |

The SHA-256 API-key path in `service/McpAuthenticationService.kt` exists solely
to migrate legacy keys. Do not extend or imitate it.

Credentials are **never returned** by an API — expose a `…Configured: Boolean`
instead, and accept `***HIDDEN***` back to mean "keep the stored value".

The SSE `?token=` query parameter is the one documented exception to
cookie-only transport (`EventSource` has no headers).

`config/JwtSigningValidator.kt`, `config/DatabaseCredentialValidator.kt` and
`config/DatasourceUrlValidator.kt` fail the boot on weak config **by design**.
Never relax one to make the backend start.

---

## A03 — Injection

### SQL

```kotlin
// Correct — bound parameter
@Query("SELECT v FROM Vulnerability v WHERE v.asset.id IN :ids")
fun findByAssetIdIn(ids: Collection<Long>, pageable: Pageable): Page<Vulnerability>

// Wrong — interpolation
@Query("SELECT v FROM Vulnerability v WHERE v.severity = '$severity'")
```

The same rule binds the ~58 `nativeQuery = true` methods in
`repository/VulnerabilityRepository.kt`, `WorkgroupRepository.kt` and
`AwsAccountSharingRepository.kt`. Things that cannot be bound — column name,
sort direction, table — map through a **closed allowlist or enum**:

```kotlin
private val SORTABLE = mapOf("name" to "a.name", "lastSeen" to "a.last_seen")
val column = SORTABLE[request.sortBy] ?: "a.name"      // request value never reaches SQL unbound
val dir    = if (request.desc) "DESC" else "ASC"       // enum, not a string from the client
```

### HTML

`components/RichContent.tsx` and `components/admin/HtmlEditor.tsx` are the
reference. Sanitize **at the assignment site**:

```tsx
import DOMPurify from 'dompurify';
<div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(html) }} />
```

Sanitizing on write is not sufficient — stored rows predate the control.

### Excel / CSV export

Every user-controlled cell goes through `util/ExcelSanitizer.kt`:

```kotlin
row.createCell(0).setCellValue(ExcelSanitizer.sanitize(asset.name))
```

This one is invisible at runtime: no E2E gate ever opens an exported file, so a
regression is caught only by `ExcelSanitizerTest` and by `owasp-check.sh`.

### OS commands

No user-controlled string interpolated into a shell command, from Kotlin or from
`./scripts/`. Pass argv arrays; quote every variable in bash.

### Logs

Strip or encode CR/LF from user input before it reaches a log line (log
forging) — see `service/InputValidationService.kt`.

---

## A04 — Insecure Design

**Unbounded is a design bug.** Page at the query:

```kotlin
// Correct
vulnerabilityRepository.findByAssetIdIn(accessibleIds, pageable)

// Wrong — this exact pattern OOMed get_vulnerabilities on 1.1M rows
vulnerabilityRepository.findAll().filter { it.assetId in accessibleIds }.take(50)
```

**Business invariants are server-side.** Release status transitions
(`PREPARATION → ALIGNMENT → ACTIVE → ARCHIVED`), exception `kind`/subject/scope
validity, ownership, workgroup membership. A rule that exists only in the UI is
not a rule — the UI check is UX, the controller check is the boundary
(Hard Principle 2).

---

## A05 — Security Misconfiguration

- `filter/SecurityHeadersFilter.kt` — CSP, HSTS, `X-Frame-Options: DENY`,
  COOP/COEP/CORP, permissions policy. If a feature "requires" `unsafe-eval` or a
  wildcard `connect-src`, change the feature.
- CORS: explicit origin allowlist. Never `*` combined with credentials.
- Errors: generic message to the client, detail to the server log.
  `exception/ValidationExceptionHandler.kt` is the pattern. Never return a stack
  trace, SQL string, internal path or driver message.
- No debug endpoint, verbose-logging toggle or seeded default credential enabled
  outside the `test` profile.

---

## A06 — Vulnerable and Outdated Components

- Prefer the stdlib and what is already on the classpath. A new third-party
  dependency needs a stated reason and a call-out in the PR body.
- Pin exact versions. No ranges, no `latest`. `package-lock.json` stays in step
  with `package.json` — `npm ci` is the gate.
- `src/clinotify` is **stdlib-only by contract**. A dependency there breaks its
  deployment.

---

## A07 — Identification and Authentication Failures

Authenticate through `service/AuthenticationProviderUserPassword.kt`,
`service/OAuthService.kt` or `service/McpAuthenticationService.kt`. Never a
bespoke path.

`X-MCP-User-Email` **identifies** a delegated user. It is not a credential and
must always sit behind a verified API key.

Login, password-reset and lookup errors must not disclose whether an account
exists. Password change stays LOCAL-account-only. MFA state stays
server-enforced.

Never lengthen a token/session lifetime, or loosen a cookie's
`HttpOnly`/`Secure`/`SameSite`, to fix a UX or test problem.

---

## A08 — Software and Data Integrity Failures

### Uploads — validate before parsing

`controller/ImportController.kt` `validateFile` is the reference: size,
extension, content type, non-empty — **all before the parser is touched**. Keep
`MAX_FILE_SIZE` aligned with `application.yml`.

### XML — the XXE block, copied verbatim

```kotlin
val factory = DocumentBuilderFactory.newInstance()
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
factory.setXIncludeAware(false)
factory.setExpandEntityReferences(false)
```

From `service/NmapParserService.kt`, mirrored in `service/MasscanParserService.kt`.
Never construct a bare `DocumentBuilderFactory`.

### Other

- Never deserialize untrusted input into a polymorphic or arbitrary type —
  parse into an explicit DTO.
- Never fetch code, config or a template at runtime and execute or eval it.
- Archives: reject entry paths containing `..`, bound the decompressed size.

### Image uploads

The profile-picture path is the reference for a different reason: decode →
centre-crop → scale → **re-encode**. That round trip, not the magic-byte sniff,
is what kills polyglots and strips EXIF. The dimension probe runs *before*
`read(0)` as a decompression-bomb guard.

---

## A09 — Security Logging and Monitoring Failures

Log authentication failures, RBAC denials, admin actions, imports and exports
with **actor + target + outcome**.

```kotlin
// Correct — names the secret, logs no value
log.info("API key auth failed: keyId={} outcome=denied reason=expired", keyId)
log.debug("Slack destination resolved: userId={} webhookConfigured={}", userId, url != null)

// Wrong
log.debug("auth: user=$username password=$password")
log.info("Using token $token")
```

`logger.debug` counts — it runs in dev, where real `pass-cli` secrets are loaded.

No silent `catch (e: Exception) { }`. A swallowed security-relevant failure is
itself a monitoring failure.

---

## A10 — Server-Side Request Forgery

`service/SlackClient.kt` `validateWebhookUrl` is the reference implementation
and worth reading in full before writing any new outbound call. It checks, in
order: non-empty, expected prefix, parses as a URI, absolute, `https` scheme,
**host equality against the parsed authority** (a prefix match alone is not
enough — `https://hooks.slack.com/@evil.example` starts with the prefix and
resolves elsewhere), no `userInfo`, default port only.

Every outbound URL derived from user input or DB-stored config gets the same
treatment: identity provider endpoints and JWKS, GitHub App, CrowdStrike, S3
endpoints, notification webhooks. Reject loopback, link-local, RFC-1918 and
cloud metadata (`169.254.169.254`). Re-apply the check to redirect targets, and
never follow a redirect into a range the original request would have been
denied — `SlackClient` does not follow redirects at all.

`service/TelegramClient.kt` `validateBotToken` is a **security control, not
input hygiene**: the token goes in the request URL *path*, so its shape decides
what path is requested.

`filter/McpOriginValidationFilter.kt` is the inbound analogue. Do not disable it.
