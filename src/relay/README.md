# secman-relay

A zero-trust relay that lets the secman mobile apps see status without anything
being able to reach secman.

secman pushes an authorization-aware status snapshot out to this process; this
process serves it read-only to enrolled devices. It never connects to secman,
holds no secman credential, and has no write path into anything.

Full design, threat model and operations guide: [`docs/RELAY.md`](../../docs/RELAY.md).
Client API reference: `extensions/secman_app_ios/docs/API.md`.

## Quick start

```bash
go build -o secman-relay ./cmd/secman-relay

export RELAY_LISTEN_ADDR=:8443
export RELAY_TLS_MODE=off RELAY_PLAINTEXT_ACK=true     # dev only
export RELAY_STATE_DIR=./state
export RELAY_INGEST_TOKEN=$(openssl rand -base64 32)
export RELAY_INGEST_HMAC_KEY=$(openssl rand -base64 32)
export RELAY_TOKEN_SIGNING_KEY=$(openssl rand -base64 32)
export RELAY_APPLE_ENABLED=true RELAY_APPLE_AUDIENCES=io.secman.status

./secman-relay -check-config     # validate without listening
./secman-relay
```

Then point secman at it:

```bash
SECMAN_RELAY_ENABLED=true
SECMAN_RELAY_URL=http://localhost:8443
SECMAN_RELAY_ALLOW_PLAINTEXT_URL=true   # dev only
SECMAN_RELAY_TOKEN=<the same ingest token>
SECMAN_RELAY_HMAC_KEY=<the same hmac key>
```

## No third-party dependencies

`go.mod` has no `require` block, and that is deliberate. This is the one
component of secman on the public internet, and its value as a target is "the
box the phone app already trusts". Keeping the dependency graph at exactly zero
means:

- the supply chain is the Go release and this repository, with nothing else to
  audit, pin or patch out of band;
- `go build` works with no module proxy, which matters in a locked-down build
  environment;
- there is no transitive dependency that can be yanked, typosquatted or
  compromised.

The one place this costs something is ACME: instead of
`golang.org/x/crypto/acme/autocert` there is a ~450-line RFC 8555 client in
`internal/acme`. It supports exactly what is needed — one account, one order,
HTTP-01, ECDSA P-256 — and is tested against a mock CA that verifies the
client's JWS on every request.

## Layout

```
cmd/secman-relay/     wiring, listeners, graceful shutdown
internal/
  config/             environment parsing; fails closed on weak config
  model/              the secman↔relay contract, and the authorization rules
  store/              the snapshot, in memory only, monotonic
  devices/            principals, roles, device registry (persisted 0600)
  auth/               ingest HMAC, access tokens, device challenge/response
  idp/                Apple/Google OIDC verification, GitHub OAuth (BFF)
  ingest/             the secman→relay plane
  api/                the device→relay plane
  httpx/              security headers, rate limiting, body caps, access log
  acme/               RFC 8555 client (HTTP-01)
  tlsx/               certificate lifecycle: acme | file | off
  logging/            structured JSON, with log-forging and secret guards
deploy/               systemd unit, Dockerfile, example environment
```

## Configuration

Every variable is documented in [`docs/ENVIRONMENT.md`](../../docs/ENVIRONMENT.md)
§Mobile relay. The ones that decide the security posture:

| Variable | Why it matters |
|---|---|
| `RELAY_LISTEN_ADDR` | the selectable public port |
| `RELAY_INGEST_LISTEN_ADDR` | bind ingest to a private interface — the recommended shape |
| `RELAY_TLS_MODE` | `acme`, `file` or `off` (ALB termination) |
| `RELAY_PLAINTEXT_ACK` | required for `off`; makes plaintext deliberate |
| `RELAY_INGEST_TOKEN` / `_HMAC_KEY` | both required; a token alone cannot forge a push |
| `RELAY_TOKEN_SIGNING_KEY` | signs device access tokens; must differ from the above |
| `RELAY_PRIVILEGED_ROLES` / `RELAY_STRONG_PROVIDERS` | admins may only sign in with Apple or Google |
| `RELAY_TRUSTED_PROXY_CIDRS` | the only thing that makes `X-Forwarded-For` believed |

The process refuses to start on a missing, short or placeholder secret, on
plaintext without the acknowledgement, or on a privileged role with no reachable
strong provider — the same fail-closed posture as secman's
`JwtSigningValidator`.

## Testing

```bash
go test ./...        # 189 cases; no network, no Docker, no fixtures
go vet ./... && gofmt -l .
```

Worth knowing about the suite: `internal/acme` runs a complete issuance against
a mock CA that signs a real CSR and verifies every JWS; `internal/idp` runs a
mock OIDC issuer covering algorithm confusion, audience, issuer, expiry, nonce
replay and key rotation; `internal/api` drives the whole journey end to end and
asserts the RBAC mirror, immediate demotion, revocation and stale-labelling.

## Operational notes

- **The snapshot is memory-only.** A restart serves 503 until secman's next
  push — self-healing, and nothing of secman's data is ever on this disk.
- **`/readyz` does not require a snapshot.** If it did, an ALB would remove the
  relay from its target group before the first push and it could then never
  receive one.
- **`devices.json` is the only state worth backing up.** Losing it means every
  device re-enrols.
