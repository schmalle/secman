# Web security scanner design

## Purpose and scope

`secman_web_check` will become an independently released Python scanner for
HTTP security headers, TLS configuration and common web-server
misconfigurations. It will scan one explicitly supplied target or a bounded
list of explicitly supplied targets, produce terminal, JSON, SARIF and HTML
reports, optionally retain runs in MariaDB, and optionally publish normalized
findings to SecMan.

The scanner will not crawl sites, discover ports, exploit vulnerabilities,
brute-force credentials, fuzz arbitrary payloads or perform authentication
testing. Its default mode is non-invasive. A separate `--active` flag enables a
small documented set of safe probes. Loopback, link-local, private and reserved
addresses are denied by default and require `--allow-private-targets` for an
authorized internal scan.

The SecMan parent application will gain a `WEB_SECURITY` integration source so
these results are not mislabeled as `VISUAL`. This is an additive contract
change; existing `GITHUB_AI` and `VISUAL` clients remain compatible.

## Architecture

The extension will use Python 3.12 or newer and `uv` for environment,
dependency, command and lockfile management. A `src/secman_web_check` package
will separate the following responsibilities:

- CLI and configuration parsing.
- Target normalization, DNS resolution and network-address policy.
- HTTP collection and redirect handling.
- Native HTTP, header, cookie, CORS and misconfiguration checks.
- TLS collection and advanced TLS analysis through a dedicated Python TLS
  engine.
- A normalized run, target and finding model.
- Terminal, JSON, SARIF and self-contained HTML rendering.
- Optional MariaDB persistence.
- Optional SecMan REST integration.

HTTP checks will be native Python. TLS scanning will use SSLyze as the dedicated
engine because it provides a Python API for certificate, cipher, protocol and
known TLS-vulnerability analysis. The adapter boundary will translate SSLyze
results into the scanner's own model so report and integration code never
depends directly on SSLyze data structures.

Checks will be small functions grouped by security area and registered in an
explicit catalogue. Every check will receive bounded collected evidence and
return zero or more normalized findings. This keeps checks independently
testable without introducing a general-purpose plugin system in the first
release.

## Command-line interface

The primary commands will be:

```text
secman-web-check scan https://example.com
secman-web-check scan --targets-file examples/targets.txt
secman-web-check scan https://example.com --active --allow-private-targets
secman-web-check db install
secman-web-check db status
secman-web-check history list
```

The batch file will be UTF-8 text with one URL or hostname per line. Blank lines
and lines beginning with `#` are ignored. Targets are normalized and
deduplicated before any network connection. A hostname without a scheme uses a
documented HTTPS-first policy. Userinfo in target URLs is rejected.

Relevant scan options will include repeatable `--format` values for `terminal`,
`json`, `sarif` and `html`; output directory; concurrency; connect/read/overall
timeouts; redirect limit; `--active`; `--allow-private-targets`; `--store-db`;
`--push-to-secman`; and `--fail-on`. Defaults will be bounded and conservative.

Configuration precedence is CLI, environment, TOML configuration, then safe
defaults. Passwords and tokens will not be accepted as literal CLI arguments.
Database and SecMan secrets come from environment variables populated by
`pass-cli`. The repository will include placeholder-only `.env.example` and
TOML examples.

Exit codes are:

- `0`: scanning completed and no finding met the configured failure threshold.
- `1`: at least one finding met or exceeded `--fail-on`.
- `2`: invalid configuration, no usable target or an operational scan failure.

For batches, one target failure will not stop unrelated targets. The final exit
code will reflect the highest applicable condition, and every target will have
an explicit success, partial, failed or skipped outcome.

## Target and network safety

Each hostname will be resolved before connecting. All returned addresses must
pass the configured public/private policy. The policy will reject loopback,
link-local, private, multicast, unspecified, documentation and other reserved
ranges by default for both IPv4 and IPv6. Literal IP targets use the same
checks.

Every redirect destination will be normalized, resolved and checked before it
is followed. Redirect count, response bytes and decompressed bytes will be
bounded. Schemes other than HTTP and HTTPS are rejected. TLS verification is on
by default and cannot be silently disabled by a failed handshake. Network
errors will be sanitized so credentials and sensitive URL components do not
enter logs or reports.

`--allow-private-targets` is an explicit authorization signal for internal
targets, not a general relaxation of URL validation. It does not enable
loopback, link-local, multicast or unspecified addresses. If a hostname changes
resolution during a run, the new addresses must independently pass policy.

The scanner will limit concurrency globally and per host, set an identifying
user agent, and avoid retries for active probes unless explicitly safe. Active
checks will use only documented allowlisted requests.

## Security checks

### TLS

TLS checks will cover trusted chain and hostname verification, self-signed and
expired certificates, near expiry, certificate key and signature quality,
supported TLS protocol versions, weak or deprecated cipher suites, insecure
renegotiation, compression and fallback where available, OCSP stapling, and
SSLyze-supported checks for Heartbleed, ROBOT and OpenSSL CCS injection.

Failure to establish verified TLS is retained as evidence but will not cause an
unverified HTTP request to be sent. Individual TLS capabilities that cannot be
tested on a platform will mark the target partial rather than being reported as
clean.

### HTTP transport and response

Transport checks will cover HTTPS availability, HTTP-to-HTTPS redirection,
HTTPS downgrade redirects, redirect loops or excessive chains, unexpected
cross-host redirects and inconsistent status behavior. Collection will retain
only bounded response metadata and the bounded content needed by enabled
checks.

### Headers and browser policy

Checks will cover HSTS presence and policy quality, CSP presence and unsafe
directives, framing protection, MIME-sniffing protection, Referrer-Policy,
Permissions-Policy, COOP, COEP, CORP, sensitive-response cache controls,
duplicate or conflicting policy headers, and unnecessary server or framework
version disclosure.

### Cookies

Cookies will be checked for `Secure`, `HttpOnly` and `SameSite`, delivery over
plain HTTP, overly broad domain/path attributes and invalid `__Host-` or
`__Secure-` prefix usage. Evidence will redact cookie values.

### CORS

Passive response analysis will identify wildcard origins and unsafe credentials
combinations. Active mode may send allowlisted synthetic Origin and preflight
requests to identify arbitrary-origin reflection and inconsistent preflight
behavior. No credential is sent with these probes.

### Passive misconfiguration

Bounded response analysis will detect strong indicators of directory listing,
verbose error or debug disclosure, internal-address leakage, exposed technology
versions, insecure forms, mixed active content and a missing or invalid
`/.well-known/security.txt`. Heuristic findings will carry lower confidence and
document the evidence that triggered them.

### Active misconfiguration probes

`--active` will enable safe `OPTIONS` and `TRACE` checks and a small allowlist
of common exposure paths such as `.env`, `.git/HEAD`, server-status and selected
backup/configuration names. A successful HTTP status alone is never sufficient:
content type, bounded signatures and negative controls must support the
finding. The exact probe catalogue, requests and potential impact will be
documented.

## Finding and run model

Every finding will have a stable rule ID and stable external identity derived
from the rule plus normalized target and location, excluding mutable title and
severity. It will contain severity, confidence, title, description,
recommendation, bounded redacted evidence, target URL and first/last observation
timestamps. Rules will document rationale, false-positive considerations and
remediation.

One batch invocation creates a local run containing one result per target. A
target is complete only when every enabled check for that exact target finishes
without truncation or required-capability failure. Otherwise it is partial,
failed or skipped with explicit reasons. Finding deduplication happens before
rendering, persistence and upload.

All output adapters consume the same immutable normalized result model:

- Terminal is the primary human summary with per-target status and severity
  counts.
- JSON uses a versioned documented schema and is the primary machine format.
- SARIF maps stable rules and target URLs to standards-compatible results.
- HTML is self-contained, accessible and escapes every target-controlled value.

Output filenames include a collision-safe run ID. Renderers do not make network
requests and do not embed live remote content.

## MariaDB persistence

Persistence is disabled unless `--store-db` is supplied. MariaDB is the only
database backend. Versioned SQL migrations and installer/status commands will
manage these tables:

- `schema_version`: installed migration versions and checksums.
- `scan_run`: run ID, mode, version, configuration fingerprint, timestamps,
  status and aggregate counts.
- `scan_target`: normalized target, resolved-address summary, HTTP/TLS outcome,
  completeness, timings and sanitized error summary.
- `scan_finding`: stable identity, rule, severity, confidence, text, evidence,
  URL and observation timestamps.

Foreign keys will cascade only from an explicitly deleted run to its target and
finding rows. Indexed lookup paths will cover run time, normalized target,
status, severity and stable finding identity. Parameterized queries will be
used exclusively. Transactions will make each stored target result atomic.

The database installer creates or upgrades schema objects but will not create a
privileged server account automatically. A separate example SQL grant file will
show the minimum privileges for a scanner-specific database and user, using
placeholders that an operator must replace and review.

Database failure must not discard already generated reports. It marks the
persistence stage failed and contributes to the overall partial/operational
result. No database password is written to logs, reports or configuration
fingerprints.

## SecMan integration

REST integration uses the versioned `/api/integrations/v1` contract and is
disabled unless `--push-to-secman` is supplied. Configuration uses
`SECMAN_URL`, `SECMAN_USERNAME`, `SECMAN_PASSWORD` and
`SECMAN_SCANNER_ID`. TLS certificate verification and bounded timeouts are
mandatory defaults.

The client authenticates through SecMan's current login flow, discovers
subjects for the registered scanner and matches each target by normalized URI
first and canonical hostname second. Missing or ambiguous subjects fail safely
for that target. The client never creates inventory or silently uses legacy
`cli-add`.

Each target is submitted as one atomic snapshot with an idempotent run key.
Stable scanner finding IDs become SecMan external IDs; evidence is bounded to
the server contract. A successful complete target scan may resolve absent
findings for that scanner and subject. Partial, failed, skipped or truncated
scans set `completeCoverage=false` and never resolve older findings. Rejected
v1 submissions never fall back to another ingestion path.

Upload failure preserves local reports and stored results, is included in the
run summary and is safe to retry with the same run key. Authentication material
is redacted from errors.

## Parent SecMan changes

The backend source allowlist will add `WEB_SECURITY`. Existing scanner-source
validation, service-user assignment, subject binding, asset authorization and
run limits remain unchanged. Tests will prove that the new value is accepted
and unknown values remain rejected.

The frontend scanner configuration and finding source filter will add the label
`Web security`. Existing scanner types and GitHub-specific subject handling stay
unchanged. Frontend tests will cover creation/filter values and labels.

`docs/EXTENSIONS.md`, `docs/INTEGRATION_RESULTS.md` and the shared integration
contract documentation will describe registration, source semantics and rollout
for the new extension. The version-1 request shape remains unchanged.

## Scripts and examples

The extension will provide scripts for initial `uv` setup, a single-target run,
a batch run, MariaDB schema installation and verification. Scripts will invoke
the package through `uv run`, accept targets and options as arguments, and rely
on environment variables populated by `pass-cli` for secrets.

Examples will include a commented target list, safe TOML configuration,
placeholder environment configuration, representative JSON and SARIF shapes,
SecMan registration/upload commands and MariaDB setup. No real credential,
internal hostname or production identifier will be committed.

## Testing and completion criteria

Development will be test-driven. Unit tests will cover every rule and renderer,
target normalization, IP policy, redirect revalidation, deduplication,
configuration precedence, failure thresholds and redaction. Mocked integration
tests will cover HTTP/TLS edge cases, MariaDB transactions and migrations, and
SecMan login, paging, matching, upload, replay and failure handling.

Security regression tests will cover redirects to denied addresses, address
changes, oversized and decompression-bomb responses, malicious report content,
cookie-value redaction, credential leakage, SQL injection attempts, path
normalization and active-mode gating. Shared fixtures will validate the SecMan
wire contract.

The extension gates will include its full pytest suite, lint, formatting, type
checking, packaging and locked dependency verification through `uv`. Parent
changes require focused backend and frontend tests, full builds, the OWASP
review, a clean backend start and the required `e2ejs` and
`e2evulnexception` skills. Destructive E2E testing may run only after
`SECMAN_HOST` is confirmed to point to a disposable stack.

Completion also requires a full README, installation and quick-start guide,
CLI reference, rule catalogue and severity rationale, configuration reference,
safety/authorization policy, MariaDB guide, SecMan integration guide, report
schema documentation, examples, troubleshooting and contributor/testing guide.

## OWASP impact

- A01: reuse SecMan's assigned-service-user and asset-access boundaries.
- A02: verify TLS by default and source secrets only through `pass-cli`/env.
- A03: normalize inputs, escape reports and parameterize all SQL.
- A04: bound targets, concurrency, redirects, response/evidence size and active
  probes; fail closed on ambiguous subject matching.
- A05: keep active probing explicit and configuration defaults secure.
- A06: lock Python dependencies with `uv` and document update verification.
- A07: reuse SecMan authentication without persisting session credentials.
- A08: version schemas, use stable identities and atomic persistence/upload.
- A09: emit structured sanitized outcomes without secrets or cookie values.
- A10: enforce target-address policy before every initial or redirected request.

No HIGH-or-above security finding in the implementation may remain unresolved
at completion.
