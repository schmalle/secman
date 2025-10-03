# Feature Specification: VULN Role & Vulnerability Management UI

**Feature Branch**: `004-i-want-to`
**Created**: 2025-10-03
**Status**: Draft
**Input**: User description: "i want to add an additional role VULN, only users with ADMIN role or VULN role can access vulnerabilities. In the same action i want in the sidebar an entry Vuln Management, when clicking on it, i want to have sub items Vulns and Exceptions. When clicking on vulns, i want to see a UI showing all vulnerablitites, which are current per system, i dont want to see any historic vulnerabilities from past scans. For the Exceptions UI i want you to plan a new domain class, an exception can be for an IP adress or for an entire product. It can be also time limited. Later on i want to implement some kind of notification for vulnerabilties and ignore excpetions in the notification."

## Execution Flow (main)
```
1. Parse user description from Input
   → If empty: ERROR "No feature description provided"
2. Extract key concepts from description
   → Identify: actors, actions, data, constraints
3. For each unclear aspect:
   → Mark with [NEEDS CLARIFICATION: specific question]
4. Fill User Scenarios & Testing section
   → If no clear user flow: ERROR "Cannot determine user scenarios"
5. Generate Functional Requirements
   → Each requirement must be testable
   → Mark ambiguous requirements
6. Identify Key Entities (if data involved)
7. Run Review Checklist
   → If any [NEEDS CLARIFICATION]: WARN "Spec has uncertainties"
   → If implementation details found: ERROR "Remove tech details"
8. Return: SUCCESS (spec ready for planning)
```

---

## ⚡ Quick Guidelines
- ✅ Focus on WHAT users need and WHY
- ❌ Avoid HOW to implement (no tech stack, APIs, code structure)
- 👥 Written for business stakeholders, not developers

### Section Requirements
- **Mandatory sections**: Must be completed for every feature
- **Optional sections**: Include only when relevant to the feature
- When a section doesn't apply, remove it entirely (don't leave as "N/A")

### For AI Generation
When creating this spec from a user prompt:
1. **Mark all ambiguities**: Use [NEEDS CLARIFICATION: specific question] for any assumption you'd need to make
2. **Don't guess**: If the prompt doesn't specify something (e.g., "login system" without auth method), mark it
3. **Think like a tester**: Every vague requirement should fail the "testable and unambiguous" checklist item
4. **Common underspecified areas**:
   - User types and permissions
   - Data retention/deletion policies
   - Performance targets and scale
   - Error handling behaviors
   - Integration requirements
   - Security/compliance needs

---

## Clarifications

### Session 2025-10-03
- Q: Should vulnerability exceptions support permanent (no expiration) status, or must all exceptions have time limits? → A: Permanent exceptions allowed (expiration date optional)
- Q: Can users edit existing exceptions, or must they delete and recreate them? → A: Users can edit all exception fields after creation
- Q: Should the Vulns page show which vulnerabilities are covered by exceptions, or is this only shown on the Exceptions page? → A: Show exception indicator on both Vulns and Exceptions pages
- Q: How should the system determine which vulnerability is "current" for each system when multiple scans exist? → A: Most recent scan timestamp per system (latest scan wins)
- Q: What filter and sort capabilities should the Vulns page provide? → A: Filter by severity, system, and exception status; Sort by all fields

---

## User Scenarios & Testing

### Primary User Story
As a security team member with VULN role, I need to view and manage current vulnerabilities for each system, create exceptions for specific assets or products, and ensure that only authorized personnel (ADMIN or VULN roles) can access vulnerability data. This enables effective vulnerability management while maintaining security through role-based access control.

### Acceptance Scenarios
1. **Given** I am logged in as a user with VULN role, **When** I click on "Vuln Management" in the sidebar, **Then** I see a submenu with "Vulns" and "Exceptions" options
2. **Given** I am logged in as a user with ADMIN role, **When** I access vulnerability-related pages, **Then** I have full access to all vulnerability data and exception management
3. **Given** I am logged in as a normal user without VULN or ADMIN role, **When** I try to access vulnerability pages, **Then** I am denied access and see an authorization error
4. **Given** I navigate to the Vulns page, **When** the page loads, **Then** I see only the most recent vulnerability for each system (not historical scan data)
5. **Given** I am viewing the Exceptions page, **When** I create an exception for a specific IP address, **Then** that exception applies only to vulnerabilities for that IP
6. **Given** I am viewing the Exceptions page, **When** I create an exception for a product, **Then** that exception applies to all systems running that product
7. **Given** I create a time-limited exception with an expiration date, **When** that date is reached, **Then** the exception is no longer active
8. **Given** an existing exception, **When** I edit any of its fields (type, target, expiration date, reason), **Then** the changes are saved and applied immediately
9. **Given** an active exception covers specific vulnerabilities, **When** I view the Vulns page, **Then** I see a visual indicator on each vulnerability that is covered by an exception
10. **Given** an active exception exists, **When** I view the Exceptions page, **Then** I see which vulnerabilities are affected by each exception
11. **Given** an exception exists for a vulnerability, **When** notifications are sent about vulnerabilities, **Then** vulnerabilities covered by active exceptions are excluded from notifications

### Edge Cases
- What happens when a user has both VULN and ADMIN roles? (Should have access via either role)
- What happens when a vulnerability exists for multiple systems and an exception is created for only one IP? (Exception applies only to that IP)
- What happens when a product exception exists and a new system with that product is discovered? (Exception automatically applies)
- What happens when an exception expires while vulnerabilities still exist? (Vulnerabilities become visible/notifiable again)
- What happens when viewing vulnerabilities for a system that has no current vulnerabilities but has historical ones? (Display shows no vulnerabilities for that system)
- What happens when a new scan has fewer vulnerabilities than a previous scan? (Only vulnerabilities from the most recent scan are displayed)
- What happens when creating an exception with no expiration date? (Exception remains active permanently until manually deleted)

## Requirements

### Functional Requirements

#### Access Control
- **FR-001**: System MUST create a new VULN role that can be assigned to users
- **FR-002**: System MUST restrict access to vulnerability-related pages and data to users with ADMIN or VULN roles only
- **FR-003**: System MUST deny access and display an authorization error when users without ADMIN or VULN roles attempt to access vulnerability features
- **FR-004**: System MUST apply role-based access control to all vulnerability-related functionality including viewing, creating, and managing exceptions

#### Navigation & UI
- **FR-005**: System MUST display a "Vuln Management" entry in the sidebar navigation for users with ADMIN or VULN roles
- **FR-006**: System MUST show "Vulns" and "Exceptions" as submenu items when "Vuln Management" is clicked
- **FR-007**: System MUST hide the "Vuln Management" sidebar entry for users without ADMIN or VULN roles

#### Vulnerability Display
- **FR-008**: System MUST display only the most current vulnerability for each system on the Vulns page, determined by the most recent scan timestamp per system
- **FR-009**: System MUST exclude vulnerabilities from earlier scans when a more recent scan exists for the same system
- **FR-010**: System MUST show vulnerability details including system identifier, vulnerability ID, severity, and scan timestamp
- **FR-011**: System MUST allow users to filter the vulnerability list by severity level, system identifier, and exception status (excepted/not excepted), and sort by all displayed fields (system, vulnerability ID, severity, scan timestamp, exception status)

#### Exception Management
- **FR-012**: System MUST allow users with ADMIN or VULN roles to create vulnerability exceptions
- **FR-013**: System MUST support creating exceptions for specific IP addresses
- **FR-014**: System MUST support creating exceptions for entire products (affecting all systems running that product)
- **FR-015**: System MUST support both time-limited exceptions (with expiration dates) and permanent exceptions (without expiration dates)
- **FR-016**: System MUST automatically deactivate time-limited exceptions when their expiration date is reached (permanent exceptions remain active until manually deleted)
- **FR-017**: System MUST display active exceptions on the Exceptions page
- **FR-018**: System MUST allow users to view, edit (all fields including type, target, expiration date, and reason), and delete exceptions
- **FR-019**: System MUST clearly indicate which vulnerabilities are covered by active exceptions on both the Vulns page (with visual indicator per vulnerability) and the Exceptions page (showing all affected vulnerabilities per exception)

#### Notification Integration (Future Phase)
- **FR-020**: System MUST support excluding vulnerabilities covered by active exceptions from notifications (implementation deferred to future phase)
- **FR-021**: System MUST maintain exception data structure to enable future notification filtering

### Key Entities

- **VulnerabilityException**: Represents an exception rule that excludes specific vulnerabilities from visibility or notifications. Key attributes include:
  - Unique identifier
  - Exception type (IP-based or product-based)
  - Target value (IP address or product name/version)
  - Creation date and creator
  - Expiration date (optional for permanent exceptions)
  - Reason/justification for the exception
  - Active/inactive status
  - Relationship to vulnerabilities affected by this exception

- **VULN Role**: A new user role that grants access to vulnerability management features alongside ADMIN role

---

## Review & Acceptance Checklist

### Content Quality
- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness
- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified (depends on existing vulnerability and authentication systems)

---

## Execution Status

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities resolved through clarification session
- [x] User scenarios defined
- [x] Requirements generated
- [x] Entities identified
- [x] Review checklist passed

---

