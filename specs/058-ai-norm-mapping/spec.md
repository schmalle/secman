# Feature Specification: AI-Powered Norm Mapping for Requirements

**Feature Branch**: `058-ai-norm-mapping`
**Created**: 2026-01-02
**Status**: Draft
**Input**: User description: "Implement AI-powered norm mapping for requirements using OpenRouter with Opus 4.5 to suggest ISO 27001 and IEC 62443 mappings"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Auto-Map Requirements to Security Standards (Priority: P1)

A security manager has imported 168 requirements into the system. Many of these requirements are not yet mapped to security standards (ISO 27001 and IEC 62443). The manager clicks the "Missing mapping" button to automatically analyze requirements without existing norm mappings and receive AI-suggested mappings.

**Why this priority**: This is the core feature - enabling automated norm mapping saves significant manual effort and ensures consistent compliance mapping across all requirements.

**Independent Test**: Can be fully tested by clicking the "Missing mapping" button on a requirements list containing items without norm mappings, and verifying AI suggestions are returned and can be applied.

**Acceptance Scenarios**:

1. **Given** requirements exist without norm mappings, **When** user clicks "Missing mapping" button, **Then** system identifies requirements missing norm mappings and queries AI for suggestions
2. **Given** AI returns mapping suggestions, **When** user reviews suggestions, **Then** each suggestion shows the requirement text, suggested standard (ISO 27001 or IEC 62443), specific control/section reference, and confidence level
3. **Given** user approves a mapping suggestion, **When** user clicks apply, **Then** the norm mapping is saved to the requirement and persists in the database

---

### User Story 2 - Review and Select AI Suggestions (Priority: P1)

After the AI generates mapping suggestions, the security manager reviews them in a modal dialog. They can select which suggestions to apply and which to skip. Only selected suggestions are saved to the requirements.

**Why this priority**: User oversight is critical - AI suggestions must be validated by humans before being applied to ensure accuracy and compliance.

**Independent Test**: Can be tested by presenting AI suggestions, selecting a subset, clicking apply, and verifying only selected mappings are saved.

**Acceptance Scenarios**:

1. **Given** AI suggestions are displayed in a modal, **When** user selects specific suggestions using checkboxes, **Then** system tracks which suggestions are selected
2. **Given** user has selected some suggestions, **When** user clicks "Apply Selected Mappings", **Then** only the selected mappings are saved to their respective requirements
3. **Given** suggestions are displayed, **When** user clicks "Close" without applying, **Then** no mappings are saved and original data remains unchanged

---

### User Story 3 - Progress Feedback During AI Processing (Priority: P2)

While the AI is analyzing requirements, the user sees a loading indicator and status message. This provides feedback that the system is working on their request.

**Why this priority**: Good user experience requires feedback during long-running operations to prevent confusion and repeated clicks.

**Independent Test**: Can be tested by clicking "Missing mapping" and observing that a spinner and "Analyzing..." message appear during processing.

**Acceptance Scenarios**:

1. **Given** user clicks "Missing mapping" button, **When** system starts processing, **Then** button shows a spinner and "Analyzing..." text, and button is disabled
2. **Given** AI processing completes successfully, **When** results are ready, **Then** spinner disappears and results modal appears
3. **Given** AI processing fails, **When** error occurs, **Then** user sees an error message explaining the issue

---

### User Story 4 - Skip Already-Mapped Requirements (Priority: P2)

The AI analysis only processes requirements that don't already have norm mappings. Requirements with existing mappings are automatically excluded from analysis to save processing time and avoid redundant suggestions.

**Why this priority**: Efficiency - prevents unnecessary AI calls for requirements that already have complete mappings.

**Independent Test**: Can be tested by having a mix of mapped and unmapped requirements, running the AI analysis, and verifying only unmapped requirements appear in suggestions.

**Acceptance Scenarios**:

1. **Given** some requirements have existing norm mappings, **When** user clicks "Missing mapping", **Then** only requirements without norm mappings are sent for AI analysis
2. **Given** all requirements already have norm mappings, **When** user clicks "Missing mapping", **Then** user sees a message indicating all requirements are already mapped

---

### Edge Cases

- What happens when the AI service (OpenRouter) is unavailable or returns an error?
  - System displays a user-friendly error message and allows retry
- What happens when the AI returns no suggestions for a requirement?
  - Requirement is noted as analyzed but skipped (no applicable standards found)
- How does system handle very long requirement texts?
  - Requirement text is truncated to a reasonable limit for the AI prompt
- What happens when API key is not configured?
  - User sees an error message indicating AI configuration is required

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST identify requirements without existing norm mappings when user initiates the mapping process
- **FR-002**: System MUST send requirement short text to OpenRouter AI service for analysis using the Opus 4.5 model (`anthropic/claude-opus-4-5-20251101`)
- **FR-003**: System MUST request mappings to ISO 27001 and IEC 62443 security standards from the AI
- **FR-004**: System MUST display AI suggestions in a modal dialog with checkboxes for each suggestion
- **FR-005**: Each suggestion MUST include: requirement identifier, suggested standard name, specific control/section reference, and confidence level (1-5)
- **FR-006**: System MUST allow users to select which suggestions to apply via checkboxes
- **FR-007**: System MUST save approved norm mappings to the respective requirements in the database
- **FR-012**: System MUST automatically create new norm entries in the database if AI-suggested norms do not already exist
- **FR-013**: System MUST send all unmapped requirements to the AI in a single batch request for efficiency
- **FR-014**: System MUST restrict AI mapping feature to users with ADMIN, REQ, or SECCHAMPION roles
- **FR-008**: System MUST show a loading indicator while AI analysis is in progress
- **FR-009**: System MUST display appropriate error messages when AI service fails
- **FR-010**: System MUST use the existing OpenRouter configuration (API key, base URL) from the translation config or environment
- **FR-011**: System MUST only process requirements that don't already have norm mappings

### Key Entities

- **Requirement**: Security requirement with shortreq text field that will be analyzed; may have associated norms
- **Norm**: Security standard reference (ISO 27001, IEC 62443) with name, version, and optional section reference
- **AI Mapping Suggestion**: Temporary data structure containing requirement ID, suggested norm, control reference, and confidence level
- **Translation Config**: Existing entity storing OpenRouter API configuration (API key, base URL)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can request AI mapping suggestions for unmapped requirements within 3 clicks
- **SC-002**: System returns AI suggestions within 60 seconds for batches of up to 50 requirements
- **SC-003**: 90% of users can successfully apply AI-suggested mappings on first attempt
- **SC-004**: System correctly identifies and skips requirements that already have norm mappings
- **SC-005**: AI suggestions include at least one relevant ISO 27001 or IEC 62443 reference for 80% of security-related requirements

## Clarifications

### Session 2026-01-02

- Q: When the AI suggests a norm like "ISO 27001: A.8.1.1", how should the system match it to existing norms in the database? → A: Create new norms automatically if AI-suggested norm doesn't exist in DB
- Q: When processing multiple unmapped requirements, how should the system send them to the AI? → A: Send all unmapped requirements in a single AI request (batch processing)
- Q: Which user roles should be allowed to use the "Missing mapping" AI feature? → A: Same roles that can edit requirements (ADMIN, REQ, SECCHAMPION)

## Assumptions

1. **OpenRouter API Key**: A valid OpenRouter API key is available in the system configuration
2. **Opus 4.5 Availability**: The Claude Opus 4.5 model is available through OpenRouter
3. **Norm Auto-Creation**: The system will automatically create new norm entries when AI suggests norms not yet in the database
4. **User Permissions**: Users with ADMIN, REQ, or SECCHAMPION roles have permission to use AI mapping and add norm mappings
5. **Network Access**: The backend server has network access to reach OpenRouter API endpoints
