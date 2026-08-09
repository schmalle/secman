# Blind spots — what `owasp-check.sh` cannot see

The static gate covers the mechanical half of the OWASP rules: the shapes that
are wrong on sight. This file is the other half — the failures that are
*semantic*, where the code is syntactically indistinguishable from correct code
and only the meaning differs.

**A green scanner is not a clean change.** Walk this list against your diff
after `./scripts/owasp-check.sh` is green, and state what you concluded.

---

## A01 — the ones that look exactly right

**Is the id actually owner-scoped, or does the query merely happen to filter?**

```kotlin
// Passes every static check. Wrong.
val vulns = vulnerabilityRepository.findByAssetId(assetId)
```

`assetId` came from the client. Nothing here proves the caller may see that
asset. The scanner sees a derived query with a bound parameter and correctly
says nothing. Only you can see that the authorization step is missing.

**Does the filter cover every access path?** A hand-written "user's workgroup"
query is not `AssetFilterService` — it silently drops the manual-creator,
scan-uploader, AWS-mapping, AD-domain, sharing-rule, owner and
workgroup-account paths. It will look correct in every test whose fixture uses
a workgroup.

**Is the `@Secured` role the narrowest that works, or the first that
compiled?** `@Secured(SecurityRule.IS_AUTHENTICATED)` on an admin action passes
every grep ever written.

**Does a materialized view or native query pre-filter make you *feel* safe?**
That is the failure mode `CLAUDE.md` names twice. The SQL is a perf hint. If the
Kotlin does not re-check, the boundary is not there.

**Is the new MCP tool's guard the *right* guard?** `owasp-check.sh` proves a
guard call exists. It cannot tell `requireAnyRole(context, "USER")` from
`requireAnyRole(context, "ADMIN")` on a destructive tool.

---

## A02 — meaning, not shape

- Is the value in that "encrypted" column **actually** routed through
  `EncryptedStringConverter`, or is the field just named `encryptedToken`?
- Does the DTO that leaves the controller include the credential field? Jackson
  serializes what the class has. A `…Configured: Boolean` was the plan; check
  the response is what shipped.
- Does `***HIDDEN***` round-trip correctly — does a save with the mask preserve
  the stored value, or overwrite it with the literal string?

---

## A03 — where sanitization happens

- `DOMPurify.sanitize` on the value **at the assignment site**, not five
  components upstream. Stored rows predate the control; a value sanitized on
  write in 2025 says nothing about a row written in 2024.
- Is *every* user-controlled export cell sanitized, or only the ones in the
  block you edited? No E2E gate opens an exported file — this regression is
  invisible at runtime.
- Is the allowlist for a sort column genuinely **closed**? A map with a
  `?: userInput` fallback is not an allowlist.

---

## A04 — design, which has no syntax

- Can this endpoint be called in a loop to enumerate something?
- Is the page size bounded, or does `size=100000` work?
- Is the state transition legal from *every* state a client can reach, or only
  from the one the UI offers? `ARCHIVED` is terminal — is that enforced in the
  service, or only by the button being hidden?
- Does the new flow create a second way to do something that already had one
  rule attached to it?

---

## A05 — configuration in context

- Does the CSP still cover the new inline script/style you added to an Astro
  page?
- Does the error you return distinguish cases the client should not be able to
  distinguish?
- Is the new toggle disabled outside the `test` profile, or merely defaulted
  off?

---

## A06 — supply chain

- Is the new dependency actually needed, or is there something on the classpath?
- Did `package-lock.json` move with `package.json`?
- Did anything land in `src/clinotify` that is not stdlib?

---

## A07 — identity semantics

- Does the failure message let an attacker tell "no such user" from "wrong
  password"? Timing counts too, though it is rarely the practical risk here.
- Is `X-MCP-User-Email` behind a **verified** API key on this specific path, or
  only on the paths you happened to look at?
- Did a test fixture loosen a cookie flag or lengthen a lifetime to make itself
  pass?

---

## A08 — order of operations

- Does validation run **before** the parser sees the bytes, or after?
- Is `MAX_FILE_SIZE` still aligned with `application.yml`?
- For an image: does the pipeline **re-encode**, or does it merely check magic
  bytes? Sniffing alone does not kill a polyglot.
- Is the dimension probe before `read(0)`, so a decompression bomb is rejected
  before allocation?

---

## A09 — is the log useful *and* safe

- Does the log line carry **actor + target + outcome**, or just "failed"?
- Is the interpolated value the secret's *length* or the secret?
- Does the `catch` swallow a security-relevant failure while looking busy —
  logging at `debug`, or returning a default that hides the denial?

---

## A10 — the host you did not think of

- Does the allowlist cover redirects, or only the first request?
- Can the configured host resolve to loopback, link-local, RFC-1918, or
  `169.254.169.254`? A hostname allowlist that permits an attacker-controlled
  DNS name is not a network control.
- Is the value DB-stored config that an admin can set? That is still untrusted
  input for SSRF purposes — the threat model is "compromised or careless admin
  reaches internal services", and it is why `SlackClient` validates a URL that
  only an authenticated user could have set.

---

## The meta-check

Ask once, at the end: **did I work around a control to make something build,
start, or pass?** Every real incident in this repo's history that this checklist
exists to prevent started with a control that was in the way.
