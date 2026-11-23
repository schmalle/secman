# SecMan supplier portal

The supplier portal is a separate Go process. It serves the external
questionnaire, stores drafts, and issues receipts for durable submissions.
Main SecMan sends invitation emails and starts every integration request.
The portal has no main SecMan credentials or SMTP access.

## Build and start

From this directory, build with Go 1.26 or later.

```sh
go build -o secman-supplier-portal .
go test -race ./...
go vet ./...
```

Run the binary with these environment variables. Certificate and key values
are paths to PEM files mounted on the portal host.

| Variable | Value |
|---|---|
| `SUPPLIER_PUBLIC_LISTEN` | Public HTTPS listener address and port |
| `SUPPLIER_PUBLIC_ORIGIN` | Exact external HTTPS origin, without a trailing slash |
| `SUPPLIER_PUBLIC_PAUSED` | `true` starts only the mutual-TLS listener for reconciliation |
| `SUPPLIER_PUBLIC_CERT`, `SUPPLIER_PUBLIC_KEY` | Browser-facing certificate chain and private key |
| `SUPPLIER_INTEGRATION_LISTEN` | Separate HTTPS integration address and port |
| `SUPPLIER_INTEGRATION_CERT`, `SUPPLIER_INTEGRATION_KEY` | Integration certificate chain and private key |
| `SUPPLIER_INTEGRATION_CLIENT_CA` | Dedicated CA for the main SecMan client certificate |
| `SUPPLIER_STATE_DIR` | Private, persistent directory on a local filesystem |

Use one replica per state directory. The process locks the directory and
atomically replaces individual records. A restart clears browser sessions.
Suppliers can reopen a valid link to recover their draft or receipt.

Bind the integration listener to a separate network interface when available.
Allow inbound traffic on that port from main SecMan's egress addresses and
require mutual TLS. Restrict portal egress from reaching internal services
and shared control planes. Keep the public origin off main SecMan's cookie
domain. Store state and backups under the approved supplier data handling
policy. Access to the state directory includes access to drafts and answers.

## Configure main SecMan

| Variable | Value |
|---|---|
| `SECMAN_SUPPLIER_ENABLED` | `true` to turn on delivery and polling |
| `SECMAN_SUPPLIER_INTAKE_PAUSED` | `true` pauses polling and submission imports while delivery and revocation remain enabled |
| `SECMAN_SUPPLIER_PORTAL_ORIGIN` | Same origin as `SUPPLIER_PUBLIC_ORIGIN` |
| `SECMAN_SUPPLIER_INTEGRATION_ORIGIN` | HTTPS origin with a fixed public IPv4 address and the integration port |
| `SECMAN_SUPPLIER_CLIENT_KEYSTORE` | PKCS#12 file with main SecMan's dedicated client certificate and key |
| `SECMAN_SUPPLIER_CLIENT_KEYSTORE_PASSWORD` | Keystore password |
| `SECMAN_SUPPLIER_SERVER_TRUSTSTORE` | PKCS#12 truststore for the portal integration certificate |
| `SECMAN_SUPPLIER_SERVER_TRUSTSTORE_PASSWORD` | Truststore password |

The integration certificate must cover the configured IPv4 address in an IP
subject alternative name. Supply passwords and the existing
`SECMAN_ENCRYPTION_PASSWORD` and `SECMAN_ENCRYPTION_SALT` through SecMan's
secret provider. Supplier email delivery requires a configured SMTP connection
with STARTTLS or TLS and a certificate trusted by main SecMan.

The Bcrypt verifier implementation uses the pinned
[`golang.org/x/crypto/bcrypt`](https://pkg.go.dev/golang.org/x/crypto@v0.59.0/bcrypt)
module for compatibility with SecMan's existing BCrypt encoder.

See the [supplier assessment workflow](../../docs/SUPPLIER_RISK_ASSESSMENT.md#deliver-an-invitation)
for preparation, dispatch, revocation, and replacement.

Use the [deployment and recovery runbook](../../docs/SUPPLIER_PORTAL_OPERATIONS.md)
for Linux installation, pilot verification, and incident containment.
