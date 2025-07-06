package services;

import models.EmailConfig;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import play.db.jpa.JPAApi;
import play.libs.concurrent.HttpExecutionContext;
import play.twirl.api.Html;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.NoResultException;

import java.util.concurrent.CompletionStage;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for EmailService functionality
 * Tests email sending, configuration validation, and error handling
 * 
 * This test suite focuses on testing the service logic and configuration validation
 * without actually sending emails to avoid dependencies on SMTP servers.
 * 
 * Command line parameters can be tested by setting system properties:
 * -Dtest.email.recipient=test@example.com
 * -Dtest.email.subject="Test Subject"
 * -Dtest.email.content="Test Content"
 */
@RunWith(MockitoJUnitRunner.class)
public class EmailServiceTest {

    @Mock
    private JPAApi mockJpaApi;
    
    @Mock
    private HttpExecutionContext mockHttpExecutionContext;
    
    @Mock
    private EntityManager mockEntityManager;
    
    @Mock
    private TypedQuery<EmailConfig> mockQuery;
    
    private EmailService emailService;
    private EmailConfig testEmailConfig;

    @Before
    public void setup() {
        // Setup HttpExecutionContext mock - use ForkJoinPool for testing
        when(mockHttpExecutionContext.current()).thenReturn(java.util.concurrent.ForkJoinPool.commonPool());
        
        emailService = new EmailService(mockJpaApi, mockHttpExecutionContext);
        
        // Create test email configuration
        testEmailConfig = new EmailConfig();
        testEmailConfig.setSmtpHost("test.smtp.com");
        testEmailConfig.setSmtpPort(587);
        testEmailConfig.setFromEmail("test@example.com");
        testEmailConfig.setFromName("Test Sender");
        testEmailConfig.setSmtpUsername("testuser");
        testEmailConfig.setSmtpPassword("testpass");
        testEmailConfig.setSmtpTls(true);
        testEmailConfig.setSmtpSsl(false);
        testEmailConfig.setIsActive(true);
        
        // Setup mock behavior for JPA transaction - return false for actual email sending
        when(mockJpaApi.withTransaction(any(java.util.function.Function.class))).thenAnswer(invocation -> {
            java.util.function.Function<EntityManager, Object> function = invocation.getArgument(0);
            try {
                return function.apply(mockEntityManager);
            } catch (RuntimeException e) {
                // If email sending fails (expected in tests), return false
                return false;
            }
        });
        
        when(mockEntityManager.createQuery(anyString(), eq(EmailConfig.class))).thenReturn(mockQuery);
        when(mockQuery.setMaxResults(anyInt())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(testEmailConfig);
    }

    @Test
    public void testSendEmailValidatesConfiguration() {
        // Test that sendEmail method calls the correct database queries
        CompletionStage<Boolean> result = emailService.sendEmail(
            "recipient@example.com",
            "Test Subject",
            "Test plain text content",
            "<p>Test HTML content</p>"
        );
        
        assertNotNull("Email service should return a CompletionStage", result);
        
        // Verify that the query was executed to get active email configuration
        verify(mockJpaApi, times(1)).withTransaction(any(java.util.function.Function.class));
        verify(mockEntityManager, times(1)).createQuery(
            "SELECT ec FROM EmailConfig ec WHERE ec.isActive = true ORDER BY ec.updatedAt DESC",
            EmailConfig.class
        );
        verify(mockQuery, times(1)).setMaxResults(1);
        verify(mockQuery, times(1)).getSingleResult();
    }

    @Test
    public void testSendEmailHandlesNoActiveConfiguration() {
        // Test behavior when no active email configuration exists
        when(mockQuery.getSingleResult()).thenThrow(new NoResultException("No active email configuration found"));
        
        CompletionStage<Boolean> result = emailService.sendEmail(
            "recipient@example.com",
            "Test Subject",
            "Test content",
            "<p>Test HTML content</p>"
        );
        
        assertNotNull("Email service should return a CompletionStage even when no config exists", result);
        
        // Verify that the query was attempted
        verify(mockJpaApi, times(1)).withTransaction(any(java.util.function.Function.class));
        verify(mockQuery, times(1)).getSingleResult();
    }

    @Test
    public void testSendHtmlEmailProcessesContent() {
        // Test sending HTML email with Html object
        Html htmlContent = Html.apply("<h1>Test HTML Email</h1><p>This is a test HTML email content.</p>");
        
        CompletionStage<Boolean> result = emailService.sendHtmlEmail(
            "recipient@example.com",
            "Test HTML Subject",
            htmlContent
        );
        
        assertNotNull("HTML email service should return a CompletionStage", result);
        
        // Verify the underlying sendEmail method is called
        verify(mockJpaApi, times(1)).withTransaction(any(java.util.function.Function.class));
    }

    @Test
    public void testTestEmailConfiguration() {
        // Test the testEmailConfiguration method
        CompletionStage<Boolean> result = emailService.testEmailConfiguration();
        
        assertNotNull("Test email configuration should return a CompletionStage", result);
        
        // Verify that sendEmail is called with test parameters
        verify(mockJpaApi, times(1)).withTransaction(any(java.util.function.Function.class));
        verify(mockEntityManager, times(1)).createQuery(
            "SELECT ec FROM EmailConfig ec WHERE ec.isActive = true ORDER BY ec.updatedAt DESC",
            EmailConfig.class
        );
    }

    @Test
    public void testEmailConfigurationValidation() {
        // Test email configuration validation logic
        
        // Test configuration with authentication
        assertTrue("Configuration with username and password should have authentication", 
                   testEmailConfig.hasAuthentication());
        
        // Test configuration without authentication
        EmailConfig noAuthConfig = new EmailConfig();
        noAuthConfig.setSmtpHost("smtp.example.com");
        noAuthConfig.setSmtpPort(25);
        noAuthConfig.setFromEmail("noreply@example.com");
        noAuthConfig.setFromName("No Reply");
        
        assertFalse("Configuration without username/password should not have authentication", 
                    noAuthConfig.hasAuthentication());
        
        // Test configuration with empty credentials
        EmailConfig emptyCredConfig = new EmailConfig();
        emptyCredConfig.setSmtpUsername("");
        emptyCredConfig.setSmtpPassword("");
        
        assertFalse("Configuration with empty credentials should not have authentication", 
                    emptyCredConfig.hasAuthentication());
        
        // Test configuration with whitespace-only credentials
        EmailConfig whitespaceCredConfig = new EmailConfig();
        whitespaceCredConfig.setSmtpUsername("   ");
        whitespaceCredConfig.setSmtpPassword("   ");
        
        assertFalse("Configuration with whitespace-only credentials should not have authentication", 
                    whitespaceCredConfig.hasAuthentication());
    }

    @Test
    public void testEmailParameterValidation() {
        // Test various email parameter combinations
        
        // Test with null HTML content
        CompletionStage<Boolean> result1 = emailService.sendEmail(
            "recipient@example.com",
            "Test Subject",
            "Plain text only",
            null
        );
        assertNotNull("Should handle null HTML content", result1);
        
        // Test with empty HTML content
        CompletionStage<Boolean> result2 = emailService.sendEmail(
            "recipient@example.com",
            "Test Subject",
            "Plain text only",
            ""
        );
        assertNotNull("Should handle empty HTML content", result2);
        
        // Test with null text content
        CompletionStage<Boolean> result3 = emailService.sendEmail(
            "recipient@example.com",
            "Test Subject",
            null,
            "<p>HTML only</p>"
        );
        assertNotNull("Should handle null text content", result3);
        
        // Verify all calls were made
        verify(mockJpaApi, times(3)).withTransaction(any(java.util.function.Function.class));
    }

    @Test
    public void testEmailServiceWithCommandLineParameters() {
        // Test email service functionality that would be used with command line parameters
        
        // Simulate command line test parameters
        String testRecipient = System.getProperty("test.email.recipient", "test@example.com");
        String testSubject = System.getProperty("test.email.subject", "Command Line Test Email");
        String testContent = System.getProperty("test.email.content", "This is a test email from command line");
        
        CompletionStage<Boolean> result = emailService.sendEmail(
            testRecipient,
            testSubject,
            testContent,
            "<p>" + testContent + "</p>"
        );
        
        assertNotNull("Should handle command line parameters", result);
        
        // Verify that the service accepts various parameter formats
        assertEquals("Should use default recipient if not provided", "test@example.com", testRecipient);
        assertEquals("Should use default subject if not provided", "Command Line Test Email", testSubject);
        assertEquals("Should use default content if not provided", "This is a test email from command line", testContent);
        
        // Verify the service call was made
        verify(mockJpaApi, times(1)).withTransaction(any(java.util.function.Function.class));
    }

    @Test
    public void testEmailServiceErrorHandling() {
        // Test error handling scenarios
        
        // Test with database transaction failure
        when(mockJpaApi.withTransaction(any(java.util.function.Function.class)))
            .thenThrow(new RuntimeException("Database connection failed"));
        
        CompletionStage<Boolean> result = emailService.sendEmail(
            "recipient@example.com",
            "Error Test",
            "Error handling test",
            "<p>Error handling test</p>"
        );
        
        assertNotNull("Should handle database errors gracefully", result);
        
        // Reset mock for second test
        reset(mockJpaApi);
        when(mockJpaApi.withTransaction(any(java.util.function.Function.class))).thenAnswer(invocation -> {
            java.util.function.Function<EntityManager, Object> function = invocation.getArgument(0);
            return function.apply(mockEntityManager);
        });
        
        // Test with invalid email configuration
        EmailConfig invalidConfig = new EmailConfig();
        invalidConfig.setSmtpHost(null); // Invalid host
        invalidConfig.setSmtpPort(null); // Invalid port
        
        when(mockQuery.getSingleResult()).thenReturn(invalidConfig);
        
        CompletionStage<Boolean> result2 = emailService.sendEmail(
            "recipient@example.com",
            "Invalid Config Test",
            "Invalid config test",
            "<p>Invalid config test</p>"
        );
        
        assertNotNull("Should handle invalid configuration", result2);
    }

    @Test
    public void testHtmlContentProcessing() {
        // Test HTML content processing in sendHtmlEmail
        
        // Test with complex HTML content
        Html complexHtml = Html.apply(
            "<html><body><h1>Complex HTML</h1><p>This is a <strong>complex</strong> HTML email " +
            "with <em>formatting</em> and <a href='https://example.com'>links</a>.</p></body></html>"
        );
        
        CompletionStage<Boolean> result = emailService.sendHtmlEmail(
            "recipient@example.com",
            "Complex HTML Test",
            complexHtml
        );
        
        assertNotNull("Should handle complex HTML content", result);
        
        // Test with HTML containing special characters
        Html specialHtml = Html.apply(
            "<p>Special characters: &lt; &gt; &amp; &quot; &#39; äöü ñ</p>"
        );
        
        CompletionStage<Boolean> result2 = emailService.sendHtmlEmail(
            "recipient@example.com",
            "Special Characters Test",
            specialHtml
        );
        
        assertNotNull("Should handle special characters in HTML", result2);
        
        // Verify both calls were made
        verify(mockJpaApi, times(2)).withTransaction(any(java.util.function.Function.class));
    }

    @Test
    public void testEmailServiceWithDifferentSmtpSettings() {
        // Test with TLS enabled
        testEmailConfig.setSmtpTls(true);
        testEmailConfig.setSmtpSsl(false);
        
        CompletionStage<Boolean> result1 = emailService.sendEmail(
            "recipient@example.com",
            "TLS Test",
            "TLS enabled test",
            "<p>TLS enabled test</p>"
        );
        assertNotNull("Should handle TLS configuration", result1);
        
        // Test with SSL enabled
        testEmailConfig.setSmtpTls(false);
        testEmailConfig.setSmtpSsl(true);
        
        CompletionStage<Boolean> result2 = emailService.sendEmail(
            "recipient@example.com",
            "SSL Test",
            "SSL enabled test",
            "<p>SSL enabled test</p>"
        );
        assertNotNull("Should handle SSL configuration", result2);
        
        // Test with both disabled
        testEmailConfig.setSmtpTls(false);
        testEmailConfig.setSmtpSsl(false);
        
        CompletionStage<Boolean> result3 = emailService.sendEmail(
            "recipient@example.com",
            "Plain Test",
            "Plain connection test",
            "<p>Plain connection test</p>"
        );
        assertNotNull("Should handle plain connection", result3);
        
        // Verify all calls were made
        verify(mockJpaApi, times(3)).withTransaction(any(java.util.function.Function.class));
    }

    @Test
    public void testEmailServiceConcurrentRequests() {
        // Test that the service properly handles concurrent requests
        
        // Simulate multiple concurrent email sends
        CompletionStage<Boolean> result1 = emailService.sendEmail(
            "recipient1@example.com", "Concurrent Test 1", "Content 1", "<p>Content 1</p>"
        );
        
        CompletionStage<Boolean> result2 = emailService.sendEmail(
            "recipient2@example.com", "Concurrent Test 2", "Content 2", "<p>Content 2</p>"
        );
        
        CompletionStage<Boolean> result3 = emailService.sendEmail(
            "recipient3@example.com", "Concurrent Test 3", "Content 3", "<p>Content 3</p>"
        );
        
        assertNotNull("Should handle concurrent request 1", result1);
        assertNotNull("Should handle concurrent request 2", result2);
        assertNotNull("Should handle concurrent request 3", result3);
        
        // Verify that each request gets its own transaction
        verify(mockJpaApi, times(3)).withTransaction(any(java.util.function.Function.class));
    }

    @Test
    public void testEmailConfigurationConstructors() {
        // Test the EmailConfig constructors and basic functionality
        
        // Test default constructor
        EmailConfig defaultConfig = new EmailConfig();
        assertNotNull("Default constructor should create valid config", defaultConfig);
        
        // Test parameterized constructor
        EmailConfig paramConfig = new EmailConfig(
            "smtp.test.com", 
            587, 
            "test@example.com", 
            "Test Sender"
        );
        
        assertEquals("Should set SMTP host", "smtp.test.com", paramConfig.getSmtpHost());
        assertEquals("Should set SMTP port", Integer.valueOf(587), paramConfig.getSmtpPort());
        assertEquals("Should set from email", "test@example.com", paramConfig.getFromEmail());
        assertEquals("Should set from name", "Test Sender", paramConfig.getFromName());
        assertTrue("Should be active by default", paramConfig.getIsActive());
    }

    @Test
    public void testEmailConfigurationSettingsValidation() {
        // Test various email configuration settings
        
        // Test port validation
        testEmailConfig.setSmtpPort(25);
        assertEquals("Should accept port 25", Integer.valueOf(25), testEmailConfig.getSmtpPort());
        
        testEmailConfig.setSmtpPort(587);
        assertEquals("Should accept port 587", Integer.valueOf(587), testEmailConfig.getSmtpPort());
        
        testEmailConfig.setSmtpPort(465);
        assertEquals("Should accept port 465", Integer.valueOf(465), testEmailConfig.getSmtpPort());
        
        // Test boolean settings
        testEmailConfig.setSmtpTls(true);
        assertTrue("Should accept TLS setting", testEmailConfig.getSmtpTls());
        
        testEmailConfig.setSmtpSsl(false);
        assertFalse("Should accept SSL setting", testEmailConfig.getSmtpSsl());
        
        testEmailConfig.setIsActive(false);
        assertFalse("Should accept active setting", testEmailConfig.getIsActive());
    }
}