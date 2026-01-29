# Security Policy

## Supported Versions

| Version | Status |
| ------- | ------ |
| Current | Alpha  |

All versions are currently in alpha. Security patches are applied to the latest version only.

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Email**: Contact the maintainer at markus@schmall.io
2. **GitHub**: Create a private security advisory at [github.com/schmalle/secman/security/advisories](https://github.com/schmalle/secman/security/advisories)

Please include:

- Description of the vulnerability
- Steps to reproduce
- Potential impact assessment
- Suggested fix (if available)

**Do not** open public GitHub issues for security vulnerabilities.

## Response Timeline

- **Acknowledgment**: Within 48 hours
- **Initial assessment**: Within 7 days
- **Fix timeline**: Depends on severity (critical: ASAP, high: 14 days, medium: 30 days)

## Security Measures

SecMan implements the following security measures:

- **Authentication**: BCrypt password hashing, JWT tokens, OAuth2/OIDC, Passkeys/WebAuthn
- **Authorization**: Role-based access control (RBAC) with row-level security
- **Data Protection**: Parameterized queries (100% SQL injection prevention), encrypted sensitive configuration storage
- **Web Security**: HttpOnly cookies, Content Security Policy headers, SameSite CSRF protection
- **Input Validation**: File size limits, content-type validation, header validation on imports
- **Audit**: MCP operation audit logging, notification logging

## Security Review

For a detailed security assessment, see [SECURITY_REVIEW.md](SECURITY_REVIEW.md).

## Contact

**Maintainer:** Markus Schmall - markus@schmall.io
