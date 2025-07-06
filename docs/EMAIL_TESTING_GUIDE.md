# Secman Email Testing Guide

## Overview

This guide provides comprehensive instructions for testing the Secman email functionality, including the EmailService and email configuration features. The email system supports both plain text and HTML email sending with configurable SMTP settings.

## Email Service Architecture

The email system consists of:
- **EmailService**: Core service for sending emails
- **EmailConfig**: Configuration entity for SMTP settings
- **EmailConfigController**: REST API for managing email configurations

## Test Categories

### 1. Unit Tests

#### EmailService Tests
Located in `src/backend/test/services/EmailServiceTest.java`

**Test Coverage:**
- Basic email sending functionality
- HTML email processing
- Configuration validation
- Error handling scenarios
- Command line parameter integration
- Concurrent request handling

#### Running Unit Tests
```bash
# Run all email service tests
cd src/backend
sbt "testOnly services.EmailServiceTest"

# Run specific test method
sbt "testOnly services.EmailServiceTest -- --tests testSendEmailWithValidConfiguration"

# Run with verbose output
sbt "testOnly services.EmailServiceTest" -v
```

### 2. Integration Tests

#### Email Configuration API Tests
Test the REST endpoints for email configuration management:

```bash
# Test email configuration listing
curl -X GET http://localhost:9000/api/email-configs \
  -H "Cookie: PLAY_SESSION=your-session-cookie"

# Test email configuration creation
curl -X POST http://localhost:9000/api/email-configs \
  -H "Content-Type: application/json" \
  -H "Cookie: PLAY_SESSION=your-session-cookie" \
  -d '{
    "smtpHost": "smtp.gmail.com",
    "smtpPort": 587,
    "fromEmail": "test@example.com",
    "fromName": "Test Sender",
    "smtpUsername": "testuser",
    "smtpPassword": "testpass",
    "smtpTls": true,
    "smtpSsl": false,
    "isActive": true
  }'

# Test email configuration testing
curl -X POST http://localhost:9000/api/email-configs/1/test \
  -H "Content-Type: application/json" \
  -H "Cookie: PLAY_SESSION=your-session-cookie" \
  -d '{"testEmail": "recipient@example.com"}'
```

### 3. Command Line Testing

#### Environment Variables
Set up email testing environment variables:

```bash
# Core email test configuration
export SECMAN_EMAIL_TEST_RECIPIENT="test@example.com"
export SECMAN_EMAIL_TEST_SUBJECT="Secman Email Test"
export SECMAN_EMAIL_TEST_CONTENT="This is a test email from Secman"

# SMTP configuration for testing
export SECMAN_SMTP_HOST="smtp.example.com"
export SECMAN_SMTP_PORT="587"
export SECMAN_SMTP_USERNAME="testuser"
export SECMAN_SMTP_PASSWORD="testpass"
export SECMAN_SMTP_TLS="true"
export SECMAN_SMTP_SSL="false"

# Test mode configuration
export SECMAN_EMAIL_TEST_MODE="true"
export SECMAN_EMAIL_LOG_LEVEL="DEBUG"
```

#### Command Line Options
Run email tests with command line parameters:

```bash
# Basic email test
sbt "testOnly services.EmailServiceTest" \
  -Dtest.email.recipient=recipient@example.com \
  -Dtest.email.subject="Command Line Test" \
  -Dtest.email.content="Testing email from command line"

# Test with custom SMTP settings
sbt "testOnly services.EmailServiceTest" \
  -Dtest.smtp.host=smtp.gmail.com \
  -Dtest.smtp.port=587 \
  -Dtest.smtp.tls=true

# Test with debugging enabled
sbt "testOnly services.EmailServiceTest" \
  -Dtest.email.debug=true \
  -Dtest.email.verbose=true
```

## Test Configuration

### Test Database Setup
The email tests use H2 in-memory database configuration:

```conf
# application-test.conf
db.default.driver = "org.h2.Driver"
db.default.url = "jdbc:h2:mem:testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
db.default.username = "sa"
db.default.password = ""

# JPA configuration for tests
jpa.default = "testPersistenceUnit"
```

### Mock Email Configuration
Tests use mocked EmailConfig objects:

```java
EmailConfig testConfig = new EmailConfig();
testConfig.setSmtpHost("test.smtp.com");
testConfig.setSmtpPort(587);
testConfig.setFromEmail("test@example.com");
testConfig.setFromName("Test Sender");
testConfig.setSmtpTls(true);
testConfig.setIsActive(true);
```

## Test Scenarios

### 1. Basic Email Sending
```java
@Test
public void testSendEmailWithValidConfiguration() {
    CompletionStage<Boolean> result = emailService.sendEmail(
        "recipient@example.com",
        "Test Subject",
        "Test plain text content",
        "<p>Test HTML content</p>"
    );
    
    assertNotNull("Email service should return a CompletionStage", result);
}
```

### 2. HTML Email Processing
```java
@Test
public void testSendHtmlEmailWithValidInput() {
    Html htmlContent = Html.apply("<h1>Test HTML Email</h1><p>Content</p>");
    
    CompletionStage<Boolean> result = emailService.sendHtmlEmail(
        "recipient@example.com",
        "Test HTML Subject",
        htmlContent
    );
    
    assertNotNull("HTML email service should return a CompletionStage", result);
}
```

### 3. Configuration Validation
```java
@Test
public void testEmailConfigurationValidation() {
    assertTrue("Configuration with credentials should have authentication", 
               testEmailConfig.hasAuthentication());
    
    EmailConfig noAuthConfig = new EmailConfig();
    assertFalse("Configuration without credentials should not have authentication", 
                noAuthConfig.hasAuthentication());
}
```

### 4. Error Handling
```java
@Test
public void testEmailServiceErrorHandling() {
    when(mockJpaApi.withTransaction(any()))
        .thenThrow(new RuntimeException("Database connection failed"));
    
    CompletionStage<Boolean> result = emailService.sendEmail(
        "recipient@example.com", "Error Test", "Content", "<p>Content</p>"
    );
    
    assertNotNull("Should handle database errors gracefully", result);
}
```

### 5. Command Line Parameter Testing
```java
@Test
public void testEmailServiceWithCommandLineParameters() {
    String testRecipient = System.getProperty("test.email.recipient", "test@example.com");
    String testSubject = System.getProperty("test.email.subject", "Command Line Test Email");
    String testContent = System.getProperty("test.email.content", "Test content from command line");
    
    CompletionStage<Boolean> result = emailService.sendEmail(
        testRecipient, testSubject, testContent, "<p>" + testContent + "</p>"
    );
    
    assertNotNull("Should handle command line parameters", result);
}
```

## Advanced Testing

### Performance Testing
Test concurrent email sending:

```java
@Test
public void testEmailServicePerformanceConsiderations() {
    CompletionStage<Boolean> result1 = emailService.sendEmail(
        "recipient1@example.com", "Concurrent Test 1", "Content 1", "<p>Content 1</p>"
    );
    
    CompletionStage<Boolean> result2 = emailService.sendEmail(
        "recipient2@example.com", "Concurrent Test 2", "Content 2", "<p>Content 2</p>"
    );
    
    // Verify concurrent handling
    verify(mockJpaApi, times(2)).withTransaction(any());
}
```

### Integration with Test Environment
Use the test environment setup scripts:

```bash
# Start test environment
cd tests/scripts
node setup-test-env.js

# Run email tests with full environment
sbt "testOnly services.EmailServiceTest" \
  -Dtest.environment=integration \
  -Dtest.email.live=false
```

## Test Data Management

### Test Email Configurations
Create test configurations for different scenarios:

```java
// TLS configuration
EmailConfig tlsConfig = new EmailConfig();
tlsConfig.setSmtpTls(true);
tlsConfig.setSmtpSsl(false);

// SSL configuration  
EmailConfig sslConfig = new EmailConfig();
sslConfig.setSmtpTls(false);
sslConfig.setSmtpSsl(true);

// Plain configuration
EmailConfig plainConfig = new EmailConfig();
plainConfig.setSmtpTls(false);
plainConfig.setSmtpSsl(false);
```

### Test Content Templates
Use consistent test content:

```java
String testPlainContent = "This is a test email to verify email functionality.";
String testHtmlContent = "<h1>Test Email</h1><p>This is a test email to verify email functionality.</p>";
Html testHtml = Html.apply(testHtmlContent);
```

## Debugging and Troubleshooting

### Enable Debug Logging
Add to `application-test.conf`:

```conf
# Email debug logging
logger.services.EmailService = DEBUG
logger.javax.mail = DEBUG
logger.com.sun.mail = DEBUG
```

### Test Debugging
Run tests with debugging:

```bash
# Run with debug output
sbt "testOnly services.EmailServiceTest" \
  -Dtest.email.debug=true \
  -Dlogger.services.EmailService=DEBUG

# Run with verbose mocking
sbt "testOnly services.EmailServiceTest" \
  -Dmockito.verbose=true
```

### Common Issues and Solutions

1. **Mock Configuration Issues**
   - Ensure all mock objects are properly initialized
   - Verify mock behavior setup matches actual service calls
   - Check that CompletionStage handling is correct

2. **Database Transaction Issues**
   - Verify JPA transaction mocking
   - Check EntityManager mock setup
   - Ensure query mocking matches actual queries

3. **Email Content Issues**
   - Test with various HTML content types
   - Verify character encoding handling
   - Check multipart message construction

## Best Practices

1. **Test Independence**: Each test should be independent and not rely on other tests
2. **Mock Usage**: Use mocks for external dependencies (database, SMTP servers)
3. **Error Coverage**: Test both success and failure scenarios
4. **Parameter Validation**: Test with various parameter combinations
5. **Performance**: Consider concurrent access patterns in tests
6. **Documentation**: Keep tests well-documented and maintainable

## Continuous Integration

### CI/CD Pipeline Integration
Add email tests to your CI pipeline:

```yaml
# .github/workflows/test.yml
- name: Run Email Service Tests
  run: |
    cd src/backend
    sbt "testOnly services.EmailServiceTest"
```

### Test Reports
Generate test reports:

```bash
# Generate test reports
sbt "testOnly services.EmailServiceTest" \
  -Dtest.report.format=junit \
  -Dtest.report.output=target/test-reports/email-tests.xml
```

This comprehensive testing approach ensures that the email functionality is robust, reliable, and properly integrated with the rest of the Secman application.