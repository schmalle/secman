# Research: Email Functionality Implementation

**Phase 0 Output** | **Date**: 2025-09-21

## Research Findings

### Email Notification Triggers
**Decision**: Implement event-driven notifications using Micronaut's event publishing
**Rationale**:
- Micronaut provides built-in event system for decoupled architecture
- Events allow for easy addition of multiple notification types in future
- Maintains separation of concerns between risk assessment and email services

**Alternatives considered**:
- Direct service calls from RiskAssessmentController (rejected: tight coupling)
- Database triggers (rejected: business logic belongs in application layer)
- Message queues (rejected: overkill for current scale)

**Implementation approach**:
- Create `RiskAssessmentCreatedEvent`
- Publish event from risk assessment service
- Email service listens for events and sends notifications

### Admin UI Accessibility Issues
**Decision**: Fix route configuration and authentication flow for admin email config page
**Rationale**:
- Current admin UI exists but may have authentication or routing issues
- Astro SSR requires proper authentication state management
- React components need proper client-side hydration

**Research findings**:
- Existing EmailConfigManagement.tsx component already implemented
- Admin email-config.astro page exists
- Issue likely in authentication middleware or route protection

**Fixes needed**:
- Verify JWT token validation in admin routes
- Ensure proper authentication state in Layout component
- Check CORS configuration for admin API endpoints

### Test Email Account Management
**Decision**: Create TestEmailAccount entity and management endpoints
**Rationale**:
- Test accounts need separate lifecycle from production users
- Should support multiple email providers for testing
- Need validation tracking and test result storage

**Alternatives considered**:
- Use existing User entity with test flag (rejected: mixing concerns)
- External email testing service (rejected: adds dependency)
- Manual testing only (rejected: doesn't meet automation requirements)

**Implementation approach**:
- New TestEmailAccount entity with provider, credentials, status
- Admin endpoints for CRUD operations
- Integration with EmailService for validation

### Email Configuration Encryption
**Decision**: Use Spring Security Crypto's AES encryption for sensitive fields
**Rationale**:
- Already included in dependencies (spring-security-crypto:6.3.5)
- Industry-standard AES encryption
- Transparent encryption/decryption in JPA converters

**Alternatives considered**:
- Database-level encryption (rejected: less flexible, harder to migrate)
- Custom encryption (rejected: security risk, reinventing wheel)
- Environment variables only (rejected: not scalable for multiple configs)

**Implementation approach**:
- JPA AttributeConverter for automatic encrypt/decrypt
- Separate encryption key from JWT secret
- Store encrypted fields as BLOB or TEXT

### SMTP vs IMAP Requirements
**Decision**: Enhance SMTP support, add IMAP for inbox monitoring (optional)
**Rationale**:
- SMTP already implemented for sending emails
- IMAP useful for bounce/reply monitoring
- FR-009 requires both protocols support

**SMTP enhancements needed**:
- Better error handling and retry logic
- Connection pooling for high-volume sending
- Support for additional authentication methods

**IMAP implementation**:
- Optional feature for advanced users
- Monitor inbox for bounces and autoreplies
- Store message status in EmailNotification entity

### Performance and Reliability
**Decision**: Implement async email sending with failure tracking
**Rationale**:
- Email delivery can be slow (3-30 seconds)
- Risk assessment creation shouldn't block on email
- Need audit trail for delivery status

**Implementation approach**:
- CompletableFuture already used in EmailService
- Add EmailNotificationLog entity for tracking
- Implement retry mechanism for failed deliveries
- Add monitoring endpoints for delivery status

## Resolved Clarifications

All technical context items have been clarified:
- ✅ Language/Framework: Kotlin 2.0.21 + Micronaut 4.4.3
- ✅ Email infrastructure: JavaMail with existing EmailService
- ✅ Storage: MariaDB with JPA encryption converters
- ✅ Testing: JUnit 5 + Mockk + Playwright
- ✅ Authentication: Existing JWT system
- ✅ Encryption: Spring Security Crypto AES

## Next Phase Dependencies

Phase 1 design will focus on:
1. Event system design for risk assessment notifications
2. TestEmailAccount entity and API contracts
3. Email encryption JPA converters
4. Enhanced EmailService interface
5. Admin UI routing and authentication fixes