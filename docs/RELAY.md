# Mobile relay

A zero-trust relay that lets the secman mobile apps see status without anything
being able to reach secman.

- **Relay server**: `src/relay/` (Go, no third-party dependencies)
- **secman side**: `src/backendng/…/com/secman/relay/`
- **iOS/iPadOS client**: `extensions/secman_app_ios/`

---

## 1. The idea in one picture

```
      trusted network                      DMZ                        internet
 ┌───────────────────────────┐   ┌────────────────────────┐   ┌──────────────────┐
 │ secman                    │   │ secman-relay           │   │ iOS / iPadOS app │
 │                           │   │                        │   │                  │
 │ RelayPublisher ───────────┼──▶│ /ingest/v1  (private)  │   │                  │
 │  outbound only, every 60s │   │  bearer + HMAC + nonce │   │                  │
 │                           │   │                        │   │                  │
 │ RelayPrincipalService ────┼──▶│ snapshot (memory only) │◀──┤ /api/v1 (public) │
 │  users, roles, identities │   │ principals + roles     │   │  enclave key +   │
 │                           │   │ device registry (0600) │   │  15-min token    │
 └───────────────────────────┘   └────────────────────────┘   └──────────────────┘
      MariaDB, CrowdStrike            no database                Secure Enclave
      the actual data                 no secman credential
                                      no route back
```

**secman dials the relay. The relay never dials secman.** It holds no secman
credential, is never told where secman is, and contains no code path that could
reach inward. That is the property everything else rests on: a fully compromised
DMZ box yields dashboard-level aggregates and nothing more — no database, no
write path, no lateral movement.

### Why a push and not a reverse proxy

A proxy in the DMZ needs an inbound path into the trusted network *and* a
credential to authenticate with, putting both inside the blast radius. Its
compromise is worth "an authenticated session against secman". This design's is
worth "a copy of the last dashboard numbers".

The cost is freshness: the app sees data as of the last push. That is made
visible rather than hidden — every response carries the snapshot's age and a
`stale` flag past a threshold.

### What "zero trust" means here, concretely

| Principle | How it shows up |
|---|---|
| No network location is trusted | Being on the ingest port, inside the VPC or behind the ALB grants nothing. Every request is authenticated on its own. |
| Authenticate every request | Ingest: bearer token **and** an HMAC over the body, with a timestamp and single-use nonce. Mobile: a token minted from a challenge signed by a Secure Enclave key. |
| Verify explicitly, per request | Roles are resolved from the principal record on every read — never cached on the device, never baked into a token. |
| Least privilege | The relay holds only sections secman chose to publish, and a device sees only what its user's roles allow. |
| Assume breach | Snapshot in memory only. No secman credential. No inbound path. Revocation bites on the next request. |
| Explicit trust decisions | Plaintext mode needs `RELAY_PLAINTEXT_ACK=true`; a weak or placeholder secret aborts the boot. |

---

## 2. Enabling it

Off by default. One switch turns it on:

```bash
SECMAN_RELAY_ENABLED=true
SECMAN_RELAY_URL=https://relay.example.com
SECMAN_RELAY_TOKEN=...      # pass-cli
SECMAN_RELAY_HMAC_KEY=...   # pass-cli, must differ from the token
SECMAN_RELAY_INSTANCE_ID=secman-prod
```

With it unset, no outbound connection is ever attempted and `/api/relay/*`
reports that the relay is disabled.

Full variable reference: [`ENVIRONMENT.md`](ENVIRONMENT.md) §Mobile relay.

---

## 3. The two planes

### Ingest — secman to relay

| Route | Purpose |
|---|---|
| `POST /ingest/v1/snapshot` | the status snapshot |
| `POST /ingest/v1/control` | principals, roles, identities, enrollment grants, revocations |
| `GET /ingest/v1/devices` | the device inventory, read back by secman's admin UI |
| `GET /ingest/v1/status` | the relay's own health and snapshot freshness |

Every request carries:

```
Authorization: Bearer <RELAY_INGEST_TOKEN>
X-Secman-Timestamp: 1786382589
X-Secman-Nonce: <≥16 hex chars, unique>
X-Secman-Signature: v1=<hex HMAC-SHA256>
```

signed over `v1:<timestamp>:<nonce>:<hex sha256(body)>`. The signature is what
makes a stolen bearer token insufficient on its own; the timestamp (±5 min) and
the single-use nonce make a captured request un-replayable. Signing a digest
rather than the body keeps the construction identical for a small control
document and a multi-megabyte snapshot.

Bind ingest to a private interface with `RELAY_INGEST_LISTEN_ADDR` — the
recommended production shape — and optionally restrict it further with
`RELAY_INGEST_ALLOWED_CIDRS`, which is checked against the TCP peer and never
against `X-Forwarded-For`. A network allowlist a header can satisfy is not an
allowlist.

### Mobile — device to relay

Read-only. Documented for client authors in
`extensions/secman_app_ios/docs/API.md`.

```
GET  /api/v1/providers              what this relay supports, and its policy
POST /api/v1/auth/nonce             a nonce bound to a device public key
POST /api/v1/auth/oidc              bind a device with an Apple/Google ID token
POST /api/v1/auth/github/{start,callback,complete}
POST /api/v1/enroll                 bind a device with an admin-issued code
POST /api/v1/auth/challenge         a per-session challenge
POST /api/v1/auth/token             exchange a signature for a 15-minute token
GET  /api/v1/session                who am I, what may I read
GET  /api/v1/status[/{section}]     the snapshot
GET  /healthz, /readyz              unauthenticated, and deliberately empty
```

`/readyz` does **not** require a snapshot. If it did, a relay behind an ALB
would be pulled from the target group before secman's first push and could then
never receive one — a deadlock that only appears in production.

---

## 4. Authorization mirrors secman

The requirement is that the app carry the same access rights as secman itself.
It is met by having secman state the rule and the relay enforce it — neither
side invents one.

**Per section**, the snapshot carries the roles the originating controller
demands:

| Section | Roles | Mirrors |
|---|---|---|
| `totals` | ADMIN | admin summary |
| `kpis` | ADMIN, SECCHAMPION | `DashboardController` |
| `exceptions` | ADMIN, SECCHAMPION | `VulnerabilityExceptionRequestController.getPendingCount` |
| `imports` | ADMIN, VULN | `CrowdStrikeController` |
| `top-products` | ADMIN | admin summary |
| `top-servers` | ADMIN | admin summary |

The table lives in `RelaySnapshotBuilder.SECTION_POLICIES`. **Changing a
controller's `@Secured` means changing the matching row.** Nothing in the build
catches that drift: the relay enforces whatever it is told, so a stale row means
a phone showing data the web UI would refuse.

**Per user**, secman pushes a principal record: the secman username, the roles
they hold *right now*, and their linked external identities.

A read passes only if the principal holds a required role **and** the device's
scope names the section. A scope narrows; it can never widen. Both gates are
applied where the bytes are selected, not in a handler, so no route can forget
one.

Consequences worth knowing:

- **A demotion takes effect on the next request.** Roles are read from the
  principal every time — nothing is cached on the device, nothing is in the
  token, so there is nothing to invalidate.
- **Principals are replace-on-push** (`principalsAuthoritative`), because roles
  must be able to shrink and a user secman no longer knows must stop working.
  Revocations stay additive, so one can never be undone by a later push that
  omits it.
- **403 is uniform.** Out of role, out of scope and nonexistent all answer
  identically, so the boundary is not a map of what the relay holds.

---

## 5. Sign-in

Three questions, answered by three different parties:

| Question | Answered by |
|---|---|
| Which device is this? | a P-256 key in the phone's Secure Enclave, on every request |
| Who is holding it? | Apple, Google or GitHub, once, at binding time |
| May they see anything? | **only** the principal list secman pushed |

Verifying an identity grants nothing. An Apple account no administrator has
linked to a secman user is refused.

### Providers

- **Apple / Google** — OIDC ID tokens verified against the provider's JWKS.
  RS256 is pinned (no `alg: none`, no algorithm confusion), and issuer, audience
  and expiry are all checked. The audience is a security control, not
  boilerplate: an ID token minted for a *different* app is a valid token, and
  `aud` is what stops it being accepted here.
- **GitHub** — no ID token exists, so the relay runs the authorization-code flow
  as a confidential client. The secret stays on the relay; the app never holds a
  GitHub credential. The subject is the **numeric account id**, never the login,
  which can be renamed and re-registered by somebody else.
- **Enrollment code** — a single-use code an admin issues in secman. secman
  never stores the plaintext and the relay only holds its SHA-256, so neither a
  database dump nor a relay compromise yields a usable code.

### Administrators need a strong provider

An account holding a privileged role (`RELAY_PRIVILEGED_ROLES`, default `ADMIN`)
may only be bound through `RELAY_STRONG_PROVIDERS` (default `apple,google`).

Enforced three times, and the third is the one that matters:

1. secman refuses to link a GitHub account to an ADMIN, at link time.
2. The relay refuses the binding.
3. **The relay re-checks on every token issue** — so promoting a user to ADMIN
   today immediately invalidates a device bound via GitHub yesterday.

The relay refuses to start if a privileged role is configured but no strong
provider is enabled; otherwise an admin could never sign in and the failure
would look like an app bug.

### Binding is proof-of-possession throughout

The login nonce is issued against one device public key, and the binding request
must carry a signature from that key. A captured ID token therefore cannot
register an attacker's key — or, worse, somebody else's public key, quietly
attaching an attacker's session to another person's device record.

---

## 6. TLS on a selectable port

`RELAY_LISTEN_ADDR` sets the port (default `:8443`). Three termination modes:

### `acme` — built-in Let's Encrypt

```bash
RELAY_TLS_MODE=acme
RELAY_ACME_DOMAINS=relay.example.com
RELAY_ACME_EMAIL=ops@example.com
RELAY_ACME_ACCEPT_TOS=true
RELAY_ACME_HTTP01_ADDR=:80        # must be reachable from the internet
```

An RFC 8555 client (`src/relay/internal/acme/`) written against the standard
library. HTTP-01 only, so no wildcards; ECDSA P-256 throughout; renewal at 30
days remaining, checked twice a day. The account key and certificate are cached
under `RELAY_ACME_CACHE_DIR` (0700, key 0600).

Point `RELAY_ACME_DIRECTORY_URL` at the staging directory while testing a
deployment — production rate limits are low enough to lock you out for a week.

### `file` — an existing certificate

```bash
RELAY_TLS_MODE=file
RELAY_TLS_CERT_FILE=/etc/ssl/relay.crt
RELAY_TLS_KEY_FILE=/etc/ssl/relay.key
```

Reloaded within a minute of the file changing. A half-written pair during an
external renewal keeps the previous certificate rather than taking the relay
down. Use this with certbot/lego, a corporate PKI, or when you pin the app to a
key you rotate on your own schedule.

### `off` — plaintext behind a terminator

```bash
RELAY_TLS_MODE=off
RELAY_PLAINTEXT_ACK=true
RELAY_TRUSTED_PROXY_CIDRS=10.0.0.0/16
```

For an AWS ALB, an ingress controller or a local reverse proxy. This is a
first-class production mode, not a debug escape hatch — and it is safe because
no security property of the relay depends on TLS terminating *here*:
authentication is per request and end-to-end at the application layer, so the
ALB is a transport, not a trust boundary.

The acknowledgement flag is what keeps it deliberate. HSTS is not emitted in
this mode; the terminator owns it.

`RELAY_TRUSTED_PROXY_CIDRS` is the only thing that makes `X-Forwarded-For`
believed, and only from those peers. Left empty, the TCP peer is always used.
Trusting the header unconditionally would let any client choose its own
rate-limit bucket and its own audit-log identity.

---

## 7. Operating it

### AWS ALB

- Target group → the relay's `RELAY_LISTEN_ADDR`, protocol HTTP, health check
  `/healthz`.
- Listener 443 with an ACM certificate; `RELAY_TLS_MODE=off` on the relay.
- Set `RELAY_TRUSTED_PROXY_CIDRS` to the VPC/subnet range the ALB uses.
- Keep the ingest plane off the ALB entirely: give it
  `RELAY_INGEST_LISTEN_ADDR=10.0.1.7:9443` on a private interface, and let
  secman reach it over VPC peering or a VPN.
- ALB idle timeout should exceed `RELAY_IDLE_TIMEOUT` (default 90s).

### systemd

`src/relay/deploy/secman-relay.service` runs it as a dynamic user with
`NoNewPrivileges`, a read-only filesystem apart from the state directory, and
capabilities dropped except `CAP_NET_BIND_SERVICE` (only needed for ACME's
port 80).

### Container

`src/relay/deploy/Dockerfile` builds a static binary into `scratch`. No shell,
no package manager, no libc — the image is the binary and a CA bundle.

### Backup and restore

- `devices.json` in `RELAY_STATE_DIR` — the device registry and the last
  principal set. Losing it means every device must re-enrol.
- The ACME cache — losing the account key costs the CA's rate-limit history.
- **The snapshot is not backed up.** It is memory-only and refills on the next
  push, which is the point.

### Monitoring

`GET /ingest/v1/status` (authenticated) reports `pushesAccepted`,
`pushesRejected`, `snapshotAgeSeconds`, `devices`, `principals`. secman surfaces
it at `GET /api/relay/status`.

Alert on: `snapshotAgeSeconds` above ~3× the publish interval; a rising
`pushesRejected`; and `secman.relay` warnings in the secman log.

---

## 8. Administering it from secman

All ADMIN-only, all under `/api/relay`:

| Endpoint | Purpose |
|---|---|
| `GET /status` | publisher state plus the relay's own report |
| `POST /publish` | push a snapshot now |
| `GET /sections` | available sections and their role gates |
| `GET/POST /identities`, `DELETE /identities/{id}` | link an Apple/Google/GitHub account to a secman user |
| `POST /principals/publish` | force the authorization state to the relay |
| `POST /enrollments` | issue a single-use enrollment code |
| `POST /revocations` | revoke one device, or all of them |
| `GET /devices` | the relay's device inventory |

Linking an identity requires the provider's **stable subject** — Apple's or
Google's `sub`, GitHub's numeric id. Never an email or a login name.

---

## 9. Data published

Aggregates only. Counts, percentages, and the two "top 10" name lists the admin
summary email already sends. No CVE rows, no per-asset findings, no exception
contents, no user directory beyond the principals needed to authorize the app.

`SECMAN_RELAY_SECTIONS` is the data-minimisation control: a section not listed
is never assembled, so it cannot leak.

---

## 10. Threats, and what each buys an attacker

| Compromise | What they get | What stops the rest |
|---|---|---|
| The relay host | the current snapshot; the ability to serve false data to phones | no secman credential, no route to secman, no plaintext enrollment code, no device private key |
| The ingest bearer token | nothing on its own | every request also needs a valid HMAC over the body |
| A captured ingest request | nothing | timestamp window, single-use nonce, and a monotonic snapshot check |
| A phone | its owner's access, biometry-gated | revocation bites on the next request; the enclave key is non-exportable |
| An access token | reads at that user's role level, for ≤15 minutes | expiry; revocation checked live |
| A user's Apple account | nothing | the identity must be linked to a principal by an admin |
| A leaked enrollment code | one binding for a non-privileged account | single use, ≤24h, never usable for an ADMIN |
| The relay's device registry file | device ids and public keys | no private keys, no tokens, no secman data |

Not addressed, stated plainly: a malicious secman administrator, a jailbroken
phone with code execution in the app's process, or traffic analysis showing that
a device polls a relay.

---

## 11. Building and testing

```bash
cd src/relay
go build ./...
go vet ./...
go test ./...              # 189 cases, no network, no Docker
./secman-relay -check-config   # validate the environment without listening
```

The test suite includes a mock ACME CA that runs a complete issuance and
verifies the client's JWS on every request, a mock OIDC issuer covering
algorithm confusion, audience, issuer, expiry, nonce replay and key rotation,
and end-to-end handler tests for the RBAC mirror, revocation and staleness.

The relay is deliberately dependency-free, so `go build` works with no module
proxy and its supply chain is the Go release plus this repository.
