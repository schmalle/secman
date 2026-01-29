# Feature Specification: Enhanced Admin Summary Email

**Feature Branch**: `069-enhanced-admin-summary`
**Created**: 2026-01-28
**Status**: Draft
**Input**: User description: "Extend the admin summary email by a link to the domain of secman (stored in backend) + /vulnerability-statistics, so that a user can directly click on it. Also add in the email the top 10 most affected products and servers."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Clickable Link to Vulnerability Statistics (Priority: P1)

As an admin user receiving the summary email, I want a clickable link that takes me directly to the vulnerability statistics page in secman, so that I can quickly review the current security posture without manually navigating to the application.

**Why this priority**: This is the simplest change and provides immediate navigational value. The link uses the existing backend-configured domain concatenated with `/vulnerability-statistics`, requiring minimal data gathering.

**Independent Test**: Can be fully tested by sending a summary email (or dry-run preview) and verifying the link URL is correct and clickable, pointing to the vulnerability statistics page.

**Acceptance Scenarios**:

1. **Given** the admin summary email is sent, **When** the admin opens the email, **Then** the email contains a prominently displayed clickable link to `{configured-base-url}/vulnerability-statistics`.
2. **Given** the backend base URL is configured as `https://secman.covestro.net`, **When** the email is rendered, **Then** the link points to `https://secman.covestro.net/vulnerability-statistics`.
3. **Given** the admin clicks the link in the email, **When** the browser opens, **Then** the user is taken to the vulnerability statistics page (login may be required if not already authenticated).
4. **Given** the email is viewed in a plain-text email client, **When** the admin reads the email, **Then** the full URL is displayed as readable text.

---

### User Story 2 - Top 10 Most Affected Products in Email (Priority: P2)

As an admin user, I want to see the top 10 most affected products listed in the summary email, so that I can immediately identify which software products have the most vulnerabilities without logging into the application.

**Why this priority**: Products are a key organizational concern for security management. Showing the top 10 most vulnerable products gives admins an at-a-glance understanding of where the greatest product-level risk lies.

**Independent Test**: Can be tested by sending a summary email with known vulnerability data and verifying the top 10 products are listed with their vulnerability counts.

**Acceptance Scenarios**:

1. **Given** vulnerabilities exist across multiple products, **When** the admin summary email is sent, **Then** the email lists the top 10 products sorted by total vulnerability count (descending).
2. **Given** each product in the top 10 list, **When** the admin reads the email, **Then** each entry shows the product name and total vulnerability count.
3. **Given** fewer than 10 products have vulnerabilities, **When** the email is sent, **Then** only the available products are listed (no empty rows or placeholders).
4. **Given** no vulnerability data exists, **When** the email is sent, **Then** the products section displays a message indicating no vulnerability data is available.

---

### User Story 3 - Top 10 Most Affected Servers in Email (Priority: P3)

As an admin user, I want to see the top 10 most affected servers (assets) listed in the summary email, so that I can immediately identify which servers require the most urgent attention.

**Why this priority**: Server-level visibility complements the product view and helps admins prioritize remediation efforts on specific infrastructure. This builds on the same data-gathering pattern as the products story.

**Independent Test**: Can be tested by sending a summary email with known vulnerability data and verifying the top 10 servers are listed with their vulnerability counts.

**Acceptance Scenarios**:

1. **Given** vulnerabilities exist across multiple assets, **When** the admin summary email is sent, **Then** the email lists the top 10 servers/assets sorted by total vulnerability count (descending).
2. **Given** each server in the top 10 list, **When** the admin reads the email, **Then** each entry shows the server name and total vulnerability count.
3. **Given** fewer than 10 servers have vulnerabilities, **When** the email is sent, **Then** only the available servers are listed.
4. **Given** no vulnerability data exists, **When** the email is sent, **Then** the servers section displays a message indicating no vulnerability data is available.

---

### Edge Cases

- What happens when the backend base URL is not configured or empty? The link section should still render but use a sensible fallback (e.g., omit the link or show a placeholder message).
- What happens when there are zero vulnerabilities in the system? The top 10 sections should show a "No vulnerability data available" message instead of empty tables.
- What happens when the email is viewed in a plain-text email client? The link should appear as a full URL, and the top 10 lists should be formatted as readable ASCII-aligned text.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The summary email MUST include a clickable link to the vulnerability statistics page, constructed from the configured backend base URL and the path `/vulnerability-statistics`.
- **FR-002**: The summary email MUST include a "Top 10 Most Affected Products" section listing products sorted by total vulnerability count in descending order.
- **FR-003**: The summary email MUST include a "Top 10 Most Affected Servers" section listing assets/servers sorted by total vulnerability count in descending order.
- **FR-004**: Each product entry MUST display the product name and total vulnerability count.
- **FR-005**: Each server entry MUST display the server name and total vulnerability count.
- **FR-006**: Both top 10 lists MUST gracefully handle fewer than 10 entries by showing only available data.
- **FR-007**: Both top 10 lists MUST show a "No vulnerability data available" message when no data exists.
- **FR-008**: The link and top 10 data MUST appear in both HTML and plain-text email formats.
- **FR-009**: The top 10 data MUST reflect system-wide statistics (admin-level access, unfiltered by workgroup or domain).
- **FR-010**: The vulnerability statistics link MUST be visually prominent in the email (e.g., styled as a button or highlighted link in the HTML version).

### Key Entities

- **SystemStatistics** (extended): The existing statistics data structure, extended to include top products, top servers, and the vulnerability statistics URL.
- **Product Entry**: A product name paired with its total vulnerability count.
- **Server Entry**: A server/asset name paired with its total vulnerability count.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Admin recipients can navigate from the email to the vulnerability statistics page in a single click.
- **SC-002**: The top 10 most affected products are accurately displayed, matching the data shown on the vulnerability statistics page.
- **SC-003**: The top 10 most affected servers are accurately displayed, matching the data shown on the vulnerability statistics page.
- **SC-004**: Both HTML and plain-text email versions render all new content correctly.

## Assumptions

- The backend base URL configured via `app.backend.base-url` (environment variable `BACKEND_BASE_URL`) is the correct URL for linking to the frontend vulnerability statistics page. This is consistent with current production deployment where backend and frontend share the same domain.
- The admin summary email uses admin-level (unfiltered) access when gathering top 10 data, since all recipients are ADMIN users.
- The existing vulnerability statistics service methods provide the required data for the top 10 lists.
- The top 10 lists show only the product/server name and total vulnerability count to keep the email concise. Severity breakdowns are available on the full statistics page via the link.
