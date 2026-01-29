# Research: Enhanced Admin Summary Email

**Feature**: 069-enhanced-admin-summary
**Date**: 2026-01-28

## R1: Base URL Configuration for Email Link

**Decision**: Use `AppConfig.backend.baseUrl` injected via `@ConfigurationProperties("app")`

**Rationale**: The `AppConfig` already provides `backend.baseUrl` from the `BACKEND_BASE_URL` environment variable (default: `https://localhost:8080`, production: `https://secman.covestro.net`). The backend and frontend share the same domain in production, so this URL is correct for constructing the link to `/vulnerability-statistics`.

**Alternatives considered**:
- Use `AppConfig.frontend.baseUrl` — rejected because the frontend URL (`FRONTEND_URL`) defaults to `http://localhost:4321` and is intended for dev-only CORS configuration, not for user-facing links.
- Hardcode the URL — rejected for obvious inflexibility.

## R2: Gathering Top-10 Data Without Authentication Context

**Decision**: Create new overloaded methods in `AdminSummaryService` that query `VulnerabilityStatisticsService` data without the `Authentication` parameter, using direct repository queries for admin-level (unfiltered) access.

**Rationale**: The existing `getMostVulnerableProducts(authentication, domain)` and `getTopAssetsByVulnerabilities(authentication, domain)` methods require an `Authentication` object for RBAC filtering. The admin summary email runs from a CLI context (no HTTP authentication). Since all recipients are ADMIN users and the email should reflect system-wide statistics, the service should query the vulnerability data directly without RBAC filtering.

**Alternatives considered**:
- Create a synthetic `Authentication` object with ADMIN role — adds complexity and violates clean separation between CLI and HTTP concerns.
- Add `authentication: Authentication? = null` optional parameter to existing methods — changes existing API surface and risk of accidental unfiltered access from controllers.

## R3: Template Variable Strategy for Lists

**Decision**: Use simple string replacement with pre-rendered HTML/text blocks for the top-10 lists. Generate the list HTML/text in Kotlin code before passing to the template.

**Rationale**: The existing template rendering uses `String.replace("${var}", value)` for simple scalar values. For the top-10 lists, generate the complete HTML table rows / ASCII text lines in Kotlin and inject them as a single template variable (e.g., `${topProductsHtml}`, `${topServersHtml}`, `${topProductsText}`, `${topServersText}`).

**Alternatives considered**:
- Use a template engine (Thymeleaf, Freemarker) — over-engineered for this use case; would require adding a new dependency and refactoring all existing templates.
- Embed loop logic in templates — not supported by the current simple replacement approach.

## R4: Email Link Placement and Styling

**Decision**: Place the vulnerability statistics link as a prominent call-to-action button between the existing statistics summary and the new top-10 sections.

**Rationale**: Placing the link after the summary statistics and before the detailed top-10 lists creates a natural reading flow: overview → action → details. The HTML version uses a styled button; the plain-text version shows the full URL.

**Alternatives considered**:
- Place at the top of the email — interrupts the header/date context.
- Place at the bottom — may be missed if the email is long.
