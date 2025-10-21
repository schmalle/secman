# Feature Specification: Vulnerability Exception Request & Approval Workflow

**Feature Branch**: `031-vuln-exception-approval`
**Created**: 2025-10-20
**Status**: Draft
**Input**: User description: "for every overdue vulnerability i can ask for an exception, if i am not a SECCHAMPION or an ADMIN. If SECCHAMPION or ADMIN ask for an exception this is auto approved. If a user asks for an exception (add a button to raise an Exception) in the relevant UI elements, then secman notes this down and shows this visible in the UI. The exception needs to be approved by an ADMIN or SECCHAMPION. Create a dedicated Approval UI for exceptions, which is only accessible for ADMIN or SECCHAMPION role users. Also an overview over all exceptions needs to be shown in a professional way."

## Clarifications

### Session 2025-10-20

- Q: When two ADMIN users simultaneously approve/reject the same pending request, what happens? → A: First-approver-wins with graceful error (second user sees "This request was just reviewed by [username]")
- Q: How should pagination work when exception request lists exceed display limits? → A: Server-side pagination with page size controls (user selects 20/50/100 per page, API returns paginated results)
- Q: What lifecycle events must be logged for exception requests? → A: All state transitions logged
- Q: How should the pending count badge update in navigation? → A: Backend push with fallback polling (SSE/WebSocket for real-time updates, 30-second polling if connection fails)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Regular User Requests Single Vulnerability Exception (Priority: P1)

As a regular user viewing overdue vulnerabilities, I need to request an exception for a specific vulnerability on a specific asset so that I can document why remediation is delayed or not applicable, and get formal approval to exclude it from overdue metrics.

**Why this priority**: This is the core functionality - enabling users to request exceptions for individual vulnerabilities that cannot be immediately remediated. This addresses the most common use case and provides immediate value.

**Independent Test**: Can be fully tested by logging in as a regular user, navigating to an overdue vulnerability, clicking "Request Exception", filling out the form with a business justification and expiration date, and submitting. The request should be visible in the user's request history with PENDING status.

**Acceptance Scenarios**:

1. **Given** a regular user is viewing the Current Vulnerabilities table with at least one overdue vulnerability, **When** they click the "Request Exception" button for that vulnerability, **Then** a modal form opens with fields for reason (required, 50-2048 characters) and expiration date (required, must be future date)

2. **Given** a regular user has filled out the exception request form with valid data, **When** they submit the form, **Then** the system creates a new exception request with PENDING status, displays a success message, and shows the request in their "My Exception Requests" page

3. **Given** a regular user has submitted an exception request, **When** they view the vulnerability in the table again, **Then** the vulnerability shows a "Pending Exception" badge and the "Request Exception" button is disabled

4. **Given** a regular user is viewing their "My Exception Requests" page, **When** they look at a pending request, **Then** they see the vulnerability details, their submitted reason, expiration date, submission date, and current status (PENDING)

---

### User Story 2 - ADMIN/SECCHAMPION Auto-Approved Exception Requests (Priority: P1)

As an ADMIN or SECCHAMPION user, I need my exception requests to be automatically approved so that I can immediately exclude vulnerabilities without waiting for a separate approval process, while maintaining an audit trail.

**Why this priority**: This is equally critical as US1 because it enables privileged users to work efficiently while still maintaining governance and audit trails. It's part of the MVP because the role-based workflow is a core requirement.

**Independent Test**: Can be fully tested by logging in as an ADMIN or SECCHAMPION user, requesting an exception for a vulnerability, and verifying the request is immediately created with APPROVED status and auto-approved flag set to true. The exception should immediately affect the vulnerability's overdue status.

**Acceptance Scenarios**:

1. **Given** an ADMIN user requests an exception for a vulnerability, **When** they submit the form, **Then** the system creates the request with APPROVED status, sets auto-approved flag to true, sets reviewed_by to the requester, and immediately creates the corresponding exception

2. **Given** a SECCHAMPION user has submitted an auto-approved exception request, **When** they view the vulnerability again, **Then** the vulnerability shows "Excepted" badge (not "Pending"), and the overdue status reflects the exception

3. **Given** an ADMIN views their "My Exception Requests" page, **When** they look at their requests, **Then** auto-approved requests are clearly marked with an "Auto-Approved" badge

---

### User Story 3 - ADMIN/SECCHAMPION Approves Pending Exception Requests (Priority: P1)

As an ADMIN or SECCHAMPION user, I need to review and approve or reject pending exception requests from other users so that I can maintain security governance and ensure exceptions are justified.

**Why this priority**: This completes the approval workflow and is essential for the governance model. Without this, regular user requests would remain pending indefinitely.

**Independent Test**: Can be fully tested by having a regular user create a pending request, then logging in as an ADMIN, navigating to the "Exception Approvals" dashboard, viewing the pending request details, and approving it with an optional comment. The request should transition to APPROVED status and create the actual exception.

**Acceptance Scenarios**:

1. **Given** an ADMIN is viewing the "Exception Approvals" dashboard, **When** the page loads, **Then** they see a summary showing count of pending requests, recently approved requests, recently rejected requests, and a table of all pending requests sorted by oldest first

2. **Given** an ADMIN is viewing a pending request in the approval dashboard, **When** they click "View Details", **Then** a modal opens showing complete vulnerability details (CVE, asset name, IP, severity, days open), the requester's name, full justification text, requested expiration date, and submission date

3. **Given** an ADMIN has reviewed a pending request details, **When** they click "Approve" and optionally add a review comment, **Then** the system creates the request as APPROVED, records the reviewer's username and timestamp, creates the corresponding exception (IP, PRODUCT, or ASSET type based on request scope), and updates the vulnerability's overdue status

4. **Given** a SECCHAMPION has reviewed a pending request, **When** they click "Reject" and provide a required review comment explaining why, **Then** the system updates the request status to REJECTED, records the reviewer and timestamp, sends a notification to the requester, and does NOT create an exception

5. **Given** an ADMIN has approved or rejected a request, **When** the original requester views their "My Exception Requests" page, **Then** they see the updated status (APPROVED or REJECTED), the reviewer's name, review date, and any review comments

---

### User Story 4 - Flexible Exception Scope (Per-Vulnerability or Per-CVE Pattern) (Priority: P2)

As any user, I need to choose whether my exception request applies to a single vulnerability instance or to all instances of a CVE across multiple assets, so that I can efficiently handle vulnerabilities that are legitimately not fixable across my entire infrastructure.

**Why this priority**: This is a valuable efficiency feature but not critical for MVP. Users can still request exceptions one-by-one in P1 stories. This reduces administrative burden for common scenarios (e.g., legacy systems, accepted risks).

**Independent Test**: Can be fully tested by creating an exception request and selecting "Pattern-based" scope, specifying a CVE pattern (e.g., "CVE-2022-0001"), and verifying that approval creates a PRODUCT-type exception that matches all instances of that CVE across assets.

**Acceptance Scenarios**:

1. **Given** a user is filling out the exception request form, **When** they select the scope option, **Then** they see two radio buttons: "Single Vulnerability (this asset only)" and "CVE Pattern (all assets with this CVE)"

2. **Given** a user selects "CVE Pattern" scope and submits the request, **When** the request is approved, **Then** the system creates a PRODUCT-type exception with the CVE as the target value, which applies to all matching vulnerabilities across all assets

3. **Given** a user selects "Single Vulnerability" scope and submits the request, **When** the request is approved, **Then** the system creates an ASSET-type exception linked to the specific asset ID, which applies only to vulnerabilities on that asset

---

### User Story 5 - User Cancels Own Pending Request (Priority: P2)

As a user, I need to cancel my own pending exception requests so that I can withdraw requests that are no longer needed (e.g., vulnerability was remediated, better solution found).

**Why this priority**: This is a convenience feature that improves user control but isn't critical for MVP. Users can wait for approval/rejection or contact admins to cancel.

**Independent Test**: Can be fully tested by creating a pending request, navigating to "My Exception Requests", clicking "Cancel" on a pending request, confirming the action, and verifying the request status changes to CANCELLED.

**Acceptance Scenarios**:

1. **Given** a user is viewing their "My Exception Requests" page with pending requests, **When** they click the "Cancel" button on a pending request, **Then** a confirmation dialog appears asking "Are you sure you want to cancel this exception request?"

2. **Given** a user has confirmed cancellation of a pending request, **When** the action completes, **Then** the request status changes to CANCELLED, the request remains visible in history but is clearly marked as cancelled, and the "Cancel" button is no longer available

3. **Given** a user attempts to cancel a request that is already APPROVED, REJECTED, or EXPIRED, **When** they view the request, **Then** no "Cancel" button is shown (only PENDING requests can be cancelled)

---

### User Story 6 - Exception Request Notification System (Priority: P3)

As an ADMIN or SECCHAMPION, I need to receive email notifications when new exception requests are submitted, so that I can review them promptly without having to constantly check the dashboard. As a requester, I need to be notified when my request is approved or rejected.

**Why this priority**: This improves responsiveness and user experience but the system is fully functional without it. Users can check dashboards manually in the MVP.

**Independent Test**: Can be fully tested by creating an exception request as a regular user, verifying that ADMIN/SECCHAMPION users receive an email notification with request details, then approving the request and verifying the requester receives an approval notification email.

**Acceptance Scenarios**:

1. **Given** a regular user submits an exception request, **When** the request is created, **Then** all ADMIN and SECCHAMPION users receive an email notification containing the vulnerability details, requester name, reason summary (first 200 characters), and a link to the approval dashboard

2. **Given** an ADMIN approves or rejects a request, **When** the status changes, **Then** the requester receives an email notification with the decision (approved/rejected), reviewer name, review comment if provided, and next steps

3. **Given** an exception is expiring within 7 days, **When** the daily scheduled job runs, **Then** the original requester receives an email reminder about the expiring exception with the vulnerability details and expiration date

---

### User Story 7 - Exception Overview Dashboard for All Users (Priority: P2)

As any user, I need to view a comprehensive overview of all my exception requests with filtering and statistics, so that I can track the status of my requests and understand my exception history.

**Why this priority**: This provides visibility and self-service capability but users can still function with basic request listing from P1 stories. Enhanced UX feature.

**Independent Test**: Can be fully tested by creating multiple exception requests with different statuses, navigating to "My Exception Requests" page, and verifying that summary statistics (total requests, approved count, pending count, rejected count) are accurate and that filtering by status works correctly.

**Acceptance Scenarios**:

1. **Given** a user navigates to "My Exception Requests" page, **When** the page loads, **Then** they see summary cards showing: total requests count, approved count (with green badge), pending count (with yellow badge), and rejected count (with red badge)

2. **Given** a user is viewing their exception requests list, **When** they select a status filter (All, Pending, Approved, Rejected, Expired, Cancelled), **Then** the table updates to show only requests matching that status

3. **Given** a user is viewing their exception requests, **When** they click on a request row, **Then** a detail modal opens showing complete information: vulnerability details, request reason, expiration date, submission date, current status, reviewer information (if approved/rejected), and review comments

4. **Given** a user has requests with various statuses, **When** they view the table, **Then** each request shows a color-coded status badge (green for approved, yellow for pending, red for rejected, gray for expired/cancelled) and relevant dates (submission date, review date, expiration date)

---

### User Story 8 - Exception Approval Statistics and Reporting (Priority: P3)

As an ADMIN, I need to view statistics about exception requests (approval rates, average approval time, requests by user, requests by CVE) so that I can monitor governance effectiveness and identify patterns.

**Why this priority**: This is valuable for governance and audit but not required for the system to function. Analytics can be added after core workflow is proven.

**Independent Test**: Can be fully tested by having multiple users create and approve/reject various requests over time, then navigating to the "Exception Approvals" dashboard and verifying that statistics section shows accurate metrics for approval rate, average approval time, top requesters, and most common CVEs.

**Acceptance Scenarios**:

1. **Given** an ADMIN is viewing the "Exception Approvals" dashboard, **When** they scroll to the statistics section, **Then** they see metrics for: total requests (all time), approval rate percentage, average approval time in hours, and requests by status (breakdown chart)

2. **Given** an ADMIN views the statistics over a selected time period (last 7 days, 30 days, 90 days, all time), **When** they change the time period filter, **Then** all statistics update to reflect only requests created within that period

3. **Given** an ADMIN needs to audit exception requests, **When** they click "Export to Excel" on the approval dashboard, **Then** the system generates an Excel file containing all requests (or filtered subset) with columns: request ID, vulnerability CVE, asset name, requester, submission date, status, reviewer, review date, reason, and review comments

---

### Edge Cases

- **What happens when a user requests an exception for a vulnerability that already has an active exception?** System should prevent duplicate requests and show a message: "This vulnerability already has an active exception until [date]. You cannot request another exception until the current one expires."

- **What happens when a vulnerability is remediated (removed from system) while an exception request is pending?** The request should remain in the system with a status indicator showing "Vulnerability No Longer Exists" but still be approvable/rejectable for audit trail completeness.

- **What happens when an exception expires?** A scheduled job runs daily to check for expired exceptions. When found, the request status is updated to EXPIRED, the corresponding exception is deactivated (if expiration date is set), and the requester receives an email notification. The vulnerability will return to OVERDUE status if still present.

- **What happens when a user with pending requests is deleted or deactivated?** Pending requests should remain in the system but be marked with "Requester Account Inactive". ADMIN/SECCHAMPION can still approve/reject these requests for audit purposes.

- **What happens when an ADMIN/SECCHAMPION who approved requests is deleted?** Historical approvals remain intact with the reviewer's username preserved for audit trail. The user record is soft-deleted or marked inactive, but approval history is immutable.

- **What happens when multiple users request exceptions for the same vulnerability?** System should detect this and show a warning: "Another user has already requested an exception for this vulnerability (Status: [status], Requested by: [username]). Proceed with your request?" This allows ADMIN/SECCHAMPION to see multiple perspectives.

- **What happens when the expiration date requested is more than 1 year in the future?** System should show a warning message but allow submission: "Warning: Exception expiration is more than 365 days in the future. Security best practices recommend shorter exception periods. Are you sure?" ADMIN/SECCHAMPION can still approve with longer periods if justified.

- **What happens when a user tries to request an exception for a vulnerability that is not overdue?** The "Request Exception" button should only appear for vulnerabilities with overdue status (OVERDUE or beyond threshold). For non-overdue vulnerabilities, the button is not shown or is disabled with tooltip: "Exceptions can only be requested for overdue vulnerabilities."

- **What happens when an ADMIN/SECCHAMPION accidentally auto-approves their own request and wants to revoke it?** Auto-approved requests can be "cancelled" by the requester (even if ADMIN/SECCHAMPION) which sets status to CANCELLED and removes the associated exception. This provides a self-correction mechanism.

- **What happens during bulk approval if some requests fail validation?** System processes each request independently. Successful approvals complete, failed approvals show error messages, and the user sees a summary: "5 of 10 requests approved. 5 failed: [list of errors with request IDs]". This prevents all-or-nothing failures.

- **What happens when two ADMIN/SECCHAMPION users simultaneously approve or reject the same pending request?** The first transaction to complete successfully wins, updating the request status to APPROVED or REJECTED. The second user's attempt fails with error message: "This request was just reviewed by [username] at [timestamp]. Current status: [APPROVED/REJECTED]". This prevents duplicate approvals and maintains data integrity through atomic status transitions.

## Requirements *(mandatory)*

### Functional Requirements

#### Exception Request Creation

- **FR-001**: System MUST provide a "Request Exception" button on each overdue vulnerability row in the Current Vulnerabilities table, Asset Vulnerability tables, Account Vulns view, and Workgroup Vulns view

- **FR-002**: System MUST disable or hide the "Request Exception" button if the vulnerability already has an active approved exception request

- **FR-003**: System MUST display an exception request modal form when user clicks "Request Exception" with the following fields:
  - Exception scope (radio buttons): "Single Vulnerability" or "CVE Pattern"
  - Reason/justification (required, 50-2048 characters, multiline text area)
  - Expiration date (required, date picker, must be future date)
  - Business justification guidelines text (read-only helper text)

- **FR-004**: System MUST validate exception request form inputs:
  - Reason field: minimum 50 characters, maximum 2048 characters, cannot be only whitespace
  - Expiration date: must be future date (after current date)
  - Display validation errors inline with red text and prevent submission until valid

- **FR-005**: System MUST create exception requests with status based on user role:
  - Regular user → PENDING status (requires approval)
  - ADMIN user → APPROVED status with auto-approved flag = true
  - SECCHAMPION user → APPROVED status with auto-approved flag = true

- **FR-006**: System MUST capture the following data for each exception request:
  - Requesting user (username and user ID)
  - Vulnerability details (vulnerability ID, CVE, asset ID, asset name, asset IP)
  - Exception scope (SINGLE_VULNERABILITY or CVE_PATTERN)
  - Reason/justification text
  - Requested expiration date
  - Request submission timestamp
  - Current status (PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED)
  - Auto-approved flag (true/false)

- **FR-007**: System MUST automatically set reviewer information for auto-approved requests:
  - Reviewed by: same as requesting user
  - Review date: same as submission timestamp
  - Review comment: null (optional)

#### Exception Request Display and Status

- **FR-008**: System MUST display exception status badges on vulnerability rows in all vulnerability tables:
  - PENDING: Yellow badge with "Pending Exception" text and hourglass icon
  - APPROVED: Green badge with "Excepted" text and shield-check icon
  - No active request: No badge shown

- **FR-009**: System MUST update vulnerability overdue status calculation to account for approved exception requests:
  - If vulnerability has APPROVED exception with future expiration date → status = EXCEPTED
  - If vulnerability has PENDING exception → overdue status unchanged (still shows OVERDUE if applicable)
  - If exception is EXPIRED → overdue status recalculated normally

#### My Exception Requests Page

- **FR-010**: System MUST provide a "My Exception Requests" page accessible to all authenticated users showing only their own requests

- **FR-011**: System MUST display summary statistics on "My Exception Requests" page:
  - Total requests count (all time)
  - Approved requests count (green badge)
  - Pending requests count (yellow badge)
  - Rejected requests count (red badge)

- **FR-012**: System MUST display a filterable, paginated table of user's exception requests with columns:
  - Status badge (color-coded)
  - CVE ID
  - Asset name
  - Scope (Single or Pattern)
  - Submission date
  - Status (PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED)
  - Actions (View Details, Cancel if PENDING)
  - Pagination: Server-side with configurable page size (20, 50, or 100 items per page)

- **FR-013**: System MUST provide status filter dropdown on "My Exception Requests" page with options: All, Pending, Approved, Rejected, Expired, Cancelled

- **FR-014**: System MUST provide a "Cancel" button for PENDING requests allowing user to cancel their own request

- **FR-015**: System MUST show confirmation dialog when user clicks "Cancel" with message: "Are you sure you want to cancel this exception request?"

- **FR-016**: System MUST update cancelled requests to CANCELLED status and prevent further actions (cannot be approved, rejected, or cancelled again)

- **FR-017**: System MUST provide a detail modal when user clicks on a request row showing:
  - Complete vulnerability information (CVE, severity, asset name, asset IP, days open, product versions)
  - Request information (scope, reason, expiration date, submission date)
  - Status information (current status, reviewer name if reviewed, review date if reviewed, review comment if provided)
  - Auto-approved indicator (if applicable)

#### Exception Approval Dashboard

- **FR-018**: System MUST provide an "Exception Approvals" dashboard page accessible only to ADMIN and SECCHAMPION roles (401/403 error for others)

- **FR-019**: System MUST display summary statistics on "Exception Approvals" dashboard:
  - Pending requests count (requiring action)
  - Approved requests count (last 30 days)
  - Rejected requests count (last 30 days)
  - Average approval time in hours (for approved requests in last 30 days)

- **FR-020**: System MUST display a paginated table of all pending requests sorted by submission date (oldest first) with columns:
  - CVE ID
  - Asset name
  - Requested by (username)
  - Reason (truncated to first 100 characters with ellipsis)
  - Submission date
  - Days pending (calculated from submission date)
  - Actions (View Details, Approve, Reject)
  - Pagination: Server-side with configurable page size (20, 50, or 100 items per page)

- **FR-021**: System MUST provide a detail modal when ADMIN/SECCHAMPION clicks "View Details" showing:
  - Complete vulnerability information (CVE, severity, asset name, asset IP, days open, product versions, scan date)
  - Requester information (username, submission date)
  - Request information (scope, full reason text, requested expiration date)
  - Action buttons (Approve, Reject, Close)

- **FR-022**: System MUST provide "Approve" button in detail modal that:
  - Opens a review comment text area (optional, 0-1024 characters)
  - Shows confirmation message: "Approve this exception request?"
  - On confirm, updates request status to APPROVED, records reviewer username and timestamp, creates corresponding exception

- **FR-023**: System MUST provide "Reject" button in detail modal that:
  - Opens a review comment text area (required, 10-1024 characters)
  - Shows confirmation message: "Reject this exception request? You must provide a reason."
  - On confirm, updates request status to REJECTED, records reviewer username and timestamp, does NOT create exception

- **FR-024**: System MUST create exception entries when requests are approved:
  - Single Vulnerability scope → Create ASSET-type exception with asset_id = request.assetId
  - CVE Pattern scope → Create PRODUCT-type exception with target_value = request.cve
  - Set exception expiration_date = request.expirationDate
  - Set exception reason = request.reason
  - Set exception created_by = reviewer.username

- **FR-024b**: System MUST handle concurrent approval/rejection attempts using first-approver-wins pattern:
  - When multiple reviewers simultaneously attempt to approve or reject the same pending request, the first transaction to complete successfully commits the status change
  - Subsequent attempts receive error message: "This request was just reviewed by [username] at [timestamp]. Current status: [APPROVED/REJECTED]"
  - System ensures atomic status transitions to prevent race conditions
  - Failed concurrent attempts do not modify request state or create partial records

#### Exception History and Audit

- **FR-025**: System MUST provide an "All Exception Requests" tab on "Exception Approvals" dashboard showing all requests (not just pending) with filtering by:
  - Status (All, Pending, Approved, Rejected, Expired, Cancelled)
  - Date range (submission date)
  - Requester (username search)
  - Reviewer (username search)

- **FR-026**: System MUST provide "Export to Excel" button on "Exception Approvals" dashboard that generates an Excel file containing:
  - Request ID
  - Vulnerability CVE
  - Asset name
  - Asset IP
  - Requester username
  - Submission date
  - Status
  - Reviewer username (if reviewed)
  - Review date (if reviewed)
  - Reason text
  - Review comment (if provided)
  - Expiration date
  - Auto-approved flag

- **FR-026b**: System MUST log all exception request state transitions to audit trail including:
  - Request creation (user, timestamp, vulnerability ID, scope, reason summary)
  - Status changes: PENDING → APPROVED/REJECTED/CANCELLED, APPROVED → EXPIRED/CANCELLED
  - Approvals (reviewer, timestamp, review comment if provided)
  - Rejections (reviewer, timestamp, review comment)
  - Cancellations (user, timestamp, whether auto-approved or pending)
  - Expirations (timestamp, original requester, expiration date)
  - Each log entry captures: event type, timestamp, actor (user performing action), old state, new state, and relevant context
  - Logs retained permanently for compliance and debugging

#### Exception Expiration and Cleanup

- **FR-027**: System MUST run a scheduled job daily (at midnight) that:
  - Identifies all APPROVED exception requests with expiration_date in the past
  - Updates those requests to EXPIRED status
  - Deactivates corresponding exception entries (by setting expiration_date if not already set, or marking inactive)
  - Recalculates overdue status for affected vulnerabilities

- **FR-028**: System MUST send notification when exception expires:
  - Email to original requester
  - Subject: "Security Exception Expired: [CVE] on [Asset]"
  - Body: vulnerability details, original reason, expiration date, notice that vulnerability will return to overdue status

#### Navigation and Access Control

- **FR-029**: System MUST add "My Exception Requests" menu item to sidebar navigation under "Vuln Management" section, visible to all authenticated users

- **FR-030**: System MUST add "Approve Exceptions" menu item to sidebar navigation under "Vuln Management" section, visible only to ADMIN and SECCHAMPION roles

- **FR-031**: System MUST display a real-time notification badge on "Approve Exceptions" menu item showing count of pending requests:
  - Badge displays count as red badge with number (e.g., "5" for 5 pending requests)
  - Update mechanism: Backend push via Server-Sent Events (SSE) or WebSocket for real-time updates
  - Fallback: 30-second polling if push connection fails or is unavailable
  - Badge updates when: new request created, request approved/rejected, request cancelled
  - Update latency target: within 5 seconds of state change (as per SC-008)

- **FR-032**: System MUST enforce role-based access control:
  - All authenticated users: Can create requests, view own requests, cancel own pending requests
  - ADMIN and SECCHAMPION: All user capabilities PLUS view all requests, approve requests, reject requests, view approval dashboard, auto-approved requests
  - Regular users attempting to access approval dashboard: 403 Forbidden error

#### Duplicate Prevention and Warnings

- **FR-033**: System MUST prevent duplicate active exception requests for the same vulnerability:
  - Before showing request form, check if vulnerability already has PENDING or APPROVED request
  - If found, show message: "This vulnerability already has an active exception (Status: [status], Expires: [date]). You cannot request another exception."
  - Disable "Request Exception" button

- **FR-034**: System MUST warn users when requesting long expiration periods:
  - If expiration date is more than 365 days in the future, show warning modal
  - Warning message: "Exception expiration is more than 365 days in the future. Security best practices recommend shorter exception periods with periodic review. Are you sure?"
  - Allow user to proceed or modify expiration date

### Key Entities *(include if feature involves data)*

- **VulnerabilityExceptionRequest**: Represents a request to create an exception for a vulnerability
  - Attributes: id, vulnerability (reference to Vulnerability), requesting user (reference to User), requesting username (string), exception scope (SINGLE_VULNERABILITY or CVE_PATTERN), reason (string, 50-2048 chars), requested expiration date, status (PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED), auto-approved flag (boolean), reviewer (reference to User, nullable), reviewer username (string, nullable), review date (timestamp, nullable), review comment (string, nullable, 0-1024 chars), submission timestamp, last updated timestamp
  - Relationships: Belongs to Vulnerability (many-to-one), Belongs to requesting User (many-to-one), Optionally belongs to reviewing User (many-to-one), May create one exception upon approval (one-to-one optional)

- **Exception** (existing entity, extended): Technical exception that affects overdue calculations
  - New relationship: May be linked to one VulnerabilityExceptionRequest (one-to-one optional) for audit trail
  - Existing attributes remain: id, exception_type (IP, PRODUCT, ASSET), target_value, asset_id (for ASSET type), expiration_date, reason, created_by, created_at, updated_at

- **ExceptionRequestNotification**: Tracks notification events for exception request workflow
  - Attributes: id, request (reference to VulnerabilityExceptionRequest), notification type (REQUEST_SUBMITTED, REQUEST_APPROVED, REQUEST_REJECTED, EXCEPTION_EXPIRING, EXCEPTION_EXPIRED), recipient (reference to User), recipient email, sent timestamp, delivery status (SENT, FAILED)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can submit exception requests in under 2 minutes (from clicking "Request Exception" to successful submission confirmation)

- **SC-002**: ADMIN and SECCHAMPION users can review and approve/reject exception requests in under 1 minute per request (from opening detail modal to completion)

- **SC-003**: Exception request approval/rejection decisions are visible to requesters within 5 seconds of ADMIN/SECCHAMPION action completion

- **SC-004**: Auto-approved exception requests (ADMIN/SECCHAMPION) immediately affect vulnerability overdue status (within 2 seconds of submission)

- **SC-005**: "My Exception Requests" page loads and displays up to 100 requests within 3 seconds

- **SC-006**: "Exception Approvals" dashboard loads and displays up to 50 pending requests within 3 seconds

- **SC-007**: System maintains complete audit trail showing who requested, when, who approved/rejected, when, and all justifications for 100% of exception requests

- **SC-008**: Pending request count badge in navigation updates within 5 seconds when new requests are created or existing requests are approved/rejected

- **SC-009**: Users successfully complete the exception request workflow (submit request → receive decision → understand outcome) without contacting support in 95% of cases

- **SC-010**: ADMIN/SECCHAMPION users can identify requests requiring urgent attention (oldest pending, critical vulnerabilities) within 30 seconds of viewing the approval dashboard

- **SC-011**: Exception expiration process runs automatically every day, correctly identifying and expiring all requests past their expiration date with 100% accuracy

- **SC-012**: System prevents duplicate exception requests for the same vulnerability in 100% of cases where active request already exists

## Assumptions

- **Assumption 1**: The existing notification infrastructure from Feature 027 (AdminNotificationSettings) can be extended to support exception request notifications without significant refactoring

- **Assumption 2**: Exception requests are permanently retained in the database for audit purposes and never hard-deleted, even after expiration or cancellation

- **Assumption 3**: The workgroup access control model (from Feature 008) does NOT apply to exception requests - users see only their own requests regardless of workgroup membership (as clarified)

- **Assumption 4**: Exception requests can be submitted for vulnerabilities in any status (OK, OVERDUE, EXCEPTED), but the UI will prioritize showing the button for OVERDUE vulnerabilities

- **Assumption 5**: When a CVE Pattern exception is approved, it applies to all future vulnerabilities with that CVE discovered in future scans, not just existing vulnerabilities at approval time

- **Assumption 6**: ADMIN users have authority to approve exceptions for any vulnerability across all workgroups, regardless of the requester's workgroup membership

- **Assumption 7**: The exception request reason field accepts plain text only (no HTML or markdown formatting) and is sanitized to prevent XSS attacks

- **Assumption 8**: Average approval time metric (FR-019) is calculated as median time rather than mean to avoid skewing by outliers (e.g., requests approved after 30+ days)

- **Assumption 9**: When an ADMIN/SECCHAMPION submits an auto-approved request, they can still cancel it afterwards if they realize it was submitted in error

- **Assumption 10**: The system does not send notifications to the requester when they submit their own request (only when status changes via approval/rejection by another user)

## Dependencies

- **Dependency 1**: Feature 004 (VULN Role & Vulnerability Management UI) - Existing vulnerability management infrastructure and overdue status calculation logic

- **Dependency 2**: Feature 021 (Vulnerability Overdue Exception Logic) - Existing VulnerabilityException entity and exception matching logic that determines which vulnerabilities are excepted

- **Dependency 3**: Feature 027 (Admin User Notification System) - Existing email notification infrastructure for sending approval/rejection notifications

- **Dependency 4**: User authentication and authorization system - JWT tokens, role-based access control (USER, ADMIN, SECCHAMPION, VULN roles)

- **Dependency 5**: Feature 008 (Workgroup-Based Access Control) - Asset and vulnerability access patterns, though exception request visibility is user-scoped not workgroup-scoped

## Out of Scope

- **Out of Scope 1**: Bulk exception requests (requesting exceptions for multiple selected vulnerabilities in one action) - users must request exceptions one vulnerability at a time or using CVE pattern scope

- **Out of Scope 2**: Exception request templates or pre-defined justification reasons - users must write custom justification text for each request

- **Out of Scope 3**: Delegation of approval authority (ADMIN assigning specific users as exception approvers) - only ADMIN and SECCHAMPION roles can approve

- **Out of Scope 4**: SLA enforcement (automatic escalation or expiration of pending requests after X days) - requests remain pending indefinitely until manually approved/rejected

- **Out of Scope 5**: Integration with external ticketing systems (Jira, ServiceNow) for exception approval workflows

- **Out of Scope 6**: Exception request discussion threads or comment history - only single approval/rejection comment is supported

- **Out of Scope 7**: Risk scoring adjustments based on exception status - exceptions affect overdue calculations but not vulnerability risk scores

- **Out of Scope 8**: Exception request approval workflows with multiple approval stages (e.g., manager approval + security approval) - single approval step only

- **Out of Scope 9**: Automatic exception renewal reminders or simplified renewal process - users must submit new requests after expiration

- **Out of Scope 10**: Mobile-optimized UI for exception request creation and approval - desktop/tablet browser experience only
