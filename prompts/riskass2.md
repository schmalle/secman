

# Risk Assessment Response System - Complete Implementation Guide

## Overview
Implement a comprehensive risk assessment response system where respondents receive email notifications and can complete assessments through a secure web interface.

## Core Requirements

### 1. Risk Assessment Creation
- End date must be pre-filled with 14 days from current date
- When assessment is started, automatically send email notifications to respondents
- Generate unique secure tokens for each respondent

### 2. Email Notification System
- **HTML Email Design**: Professional, responsive email template
- **Email Content**: Assessment details, deadline, secure access link
- **Email Configuration**: Admin settings for SMTP server configuration
- **Delivery**: Reliable email delivery with error handling

### 3. Respondent Interface
- **Secure Access**: Token-based authentication (no login required)
- **Assessment Display**: Show all requirements from selected use cases
- **Response Options**: Yes/No/N/A radio buttons + comment field per requirement
- **Progress Tracking**: Visual progress indicator
- **Auto-save**: Periodic saving of responses
- **Submit Functionality**: Final submission with validation

### 4. Response Management
- **Database Storage**: Store all responses with timestamps
- **Status Updates**: Update assessment status to "COMPLETED"
- **Completion Notification**: Email summary to assessment creator
- **Data Integrity**: Ensure response completeness and validation

## Missing Components Analysis

### 1. Database Schema Additions
- **Response Entity**: Store individual requirement responses
- **Email Configuration**: Admin settings for SMTP
- **Token Management**: Secure tokens with expiration
- **Audit Trail**: Track response history and changes

### 2. Security & Token Management
- **Token Generation**: Cryptographically secure random tokens
- **Token Validation**: Expiration checking and security validation
- **Access Control**: Prevent unauthorized access
- **CSRF Protection**: Secure form submissions

### 3. Email Infrastructure
- **SMTP Configuration**: Database-stored email settings
- **Template Engine**: HTML email template system
- **Queue Management**: Asynchronous email sending
- **Delivery Tracking**: Monitor email delivery status
- **Error Handling**: Retry mechanisms and failure notifications

### 4. Advanced Features
- **Response Analytics**: Completion rates and statistics
- **Reminder System**: Automated reminder emails before deadline
- **Partial Responses**: Save and resume functionality
- **Mobile Responsiveness**: Optimized for mobile devices
- **Export Functionality**: Generate reports from responses

## Implementation Plan

### Phase 1: Database Schema & Models
1. Create Response entity with requirement mapping
2. Add email configuration settings
3. Implement token management system
4. Update risk assessment model for response tracking

### Phase 2: Backend Infrastructure
1. Email service with SMTP configuration
2. Token generation and validation service
3. Response management API endpoints
4. Email template rendering system

### Phase 3: Frontend Development
1. Update risk assessment creation UI
2. Build secure respondent interface
3. Implement progress tracking and auto-save
4. Create responsive email templates

### Phase 4: Integration & Testing
1. End-to-end workflow testing
2. Email delivery testing
3. Security testing (token validation, CSRF)
4. Performance testing with multiple respondents

## Technical Architecture

### Email System
- **SMTP Provider**: Support for Gmail, Office365, custom SMTP
- **Template Engine**: Thymeleaf or similar for HTML email generation
- **Queue System**: Background job processing for email sending
- **Configuration**: Database-stored SMTP settings with admin interface

### Security Framework
- **Token Format**: JWT or UUID with database validation
- **Expiration**: Configurable token lifetime (default: assessment end date)
- **Rate Limiting**: Prevent abuse of response endpoints
- **Input Validation**: Comprehensive validation for all user inputs

### Database Design
```sql
-- Response tracking table
response (
  id, risk_assessment_id, requirement_id, respondent_email,
  answer (YES/NO/N_A), comment, created_at, updated_at
)

-- Token management
assessment_token (
  id, risk_assessment_id, token, respondent_email, 
  expires_at, used_at, created_at
)

-- Email configuration
email_config (
  id, smtp_host, smtp_port, smtp_username, smtp_password,
  smtp_tls, from_email, from_name, created_at, updated_at
)
```

## Files to be Created/Modified

### Backend Files

#### New Files:
- `app/models/Response.java` - Individual requirement responses
- `app/models/AssessmentToken.java` - Secure access tokens
- `app/models/EmailConfig.java` - SMTP configuration
- `app/controllers/ResponseController.java` - Response management
- `app/controllers/EmailConfigController.java` - Email settings
- `app/services/EmailService.java` - Email sending functionality
- `app/services/TokenService.java` - Token generation/validation
- `app/services/NotificationService.java` - Assessment notifications
- `conf/evolutions/default/10.sql` - Response tables
- `conf/evolutions/default/11.sql` - Token management
- `conf/evolutions/default/12.sql` - Email configuration
- `app/views/emails/assessment_notification.scala.html` - Email template
- `app/views/emails/completion_summary.scala.html` - Completion email

#### Modified Files:
- `app/models/RiskAssessment.java` - Add response tracking
- `app/controllers/RiskAssessmentController.java` - Integration with notifications
- `conf/routes` - New API endpoints
- `conf/application.conf` - Email configuration

### Frontend Files

#### New Files:
- `src/components/ResponseInterface.tsx` - Respondent UI
- `src/components/EmailConfigManagement.tsx` - Admin email settings
- `src/pages/respond/[token].astro` - Token-based response page
- `src/pages/admin/email-config.astro` - Email configuration page
- `src/styles/response-interface.css` - Response UI styling

#### Modified Files:
- `src/components/RiskAssessmentManagement.tsx` - Pre-fill end date
- `src/components/Sidebar.tsx` - Add email config link
- `src/layouts/Layout.astro` - Response page layout

### Email Templates:
- `resources/email-templates/assessment-notification.html`
- `resources/email-templates/completion-summary.html`
- `resources/email-templates/reminder-notification.html`

## Configuration Requirements

### Email Server Settings (Admin Interface):
- SMTP Host/Port
- Authentication credentials
- TLS/SSL configuration
- From address and display name
- Email template customization

### Security Settings:
- Token expiration policies
- Rate limiting configuration
- CSRF protection settings
- Input validation rules

## Testing Strategy

### Unit Tests:
- Email service functionality
- Token generation and validation
- Response validation and storage
- Template rendering

### Integration Tests:
- Complete assessment workflow
- Email delivery pipeline
- Security token validation
- Database integrity

### End-to-End Tests:
- Full user journey simulation
- Cross-browser compatibility
- Mobile responsiveness
- Performance under load

## Deployment Considerations

### Environment Configuration:
- Separate SMTP settings for dev/staging/production
- Token encryption keys
- Database migration scripts
- Monitoring and logging setup

### Monitoring:
- Email delivery success rates
- Response completion tracking
- Error logging and alerting
- Performance metrics

This comprehensive implementation will provide a robust, secure, and user-friendly risk assessment response system with professional email notifications and a streamlined respondent experience.