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

## Mandatory Security Review Process

**Every code change MUST undergo a security review before merge.** This is a constitutional requirement (Principle VII).

### What Must Be Reviewed

Every pull request must include a "Security Review" section in its description covering:

| Check | Description |
|-------|-------------|
| OWASP Top 10 | Review against all OWASP Top 10 vulnerability categories |
| Input Validation | No unsanitized user input reaches database, shell, or HTML |
| Authentication | @Secured annotations present on all endpoints, correct auth requirements |
| Authorization | Role checks enforced at service layer, not just controller |
| Data Exposure | No sensitive data in logs, error messages, or API responses |
| Injection | No SQL injection, command injection, XSS, or path traversal vectors |
| File Uploads | Size limits, content-type validation, path traversal prevention |
| Secrets | No hardcoded credentials, tokens, or encryption keys |

### When Enhanced Review Is Required

The following changes require explicit security sign-off from a reviewer with security expertise:

- Changes to authentication or authorization logic
- Changes to OAuth/OIDC flows
- Changes to encryption or secret handling
- New file upload or import endpoints
- Changes to MCP delegation or permission logic
- Changes to CORS, CSP, or other security headers
- New external API integrations

### Security Review in SECURITY_REVIEW.md

For a detailed security assessment of the full codebase, see [SECURITY_REVIEW.md](SECURITY_REVIEW.md).

## Contact

**Maintainer:** Markus Schmall - markus@schmall.io
