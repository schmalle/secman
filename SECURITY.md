# Security Policy

## Supported versions

All releases are alpha. Patches land on `main` only.

## Reporting

- **Email**: markus@schmall.io
- **GitHub**: [private security advisory](https://github.com/schmalle/secman/security/advisories)

Include: description, repro steps, impact assessment, suggested fix (optional). Do **not** open public issues for vulnerabilities.

## Response SLO

| | Target |
|---|---|
| Acknowledgement | 48 h |
| Initial assessment | 7 d |
| Critical fix | 72 h |
| High fix | 14 d |
| Medium fix | 30 d |

## In-tree controls

- AuthN: BCrypt, JWT, OAuth2/OIDC, Passkeys/WebAuthn, optional MFA.
- AuthZ: 9-role RBAC + row-level filtering on assets (workgroup, ownership, AWS account, AD domain, sharing).
- Storage: parameterized queries (no string-built SQL); sensitive config encrypted at rest (`SECMAN_ENCRYPTION_PASSWORD`/`SALT`).
- Web: HttpOnly + Secure cookies, CSP, SameSite for CSRF.
- Imports: file-size cap, MIME and header validation.
- Audit: MCP operations and notifications logged; OAuth state retried with exponential backoff to mitigate fast-SSO races.

Maintainer: Markus Schmall — markus@schmall.io.
