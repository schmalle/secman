package com.secman.service

import com.secman.domain.EmailConfig
import com.secman.repository.EmailConfigRepository
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import jakarta.inject.Singleton
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CompletableFuture

@Singleton
@ExecuteOn(TaskExecutors.IO)
open class EmailService(
    private val emailConfigRepository: EmailConfigRepository
) {
    
    private val log = LoggerFactory.getLogger(EmailService::class.java)
    
    /**
     * Send email with both text and HTML content
     */
    fun sendEmail(
        to: String,
        subject: String,
        textContent: String,
        htmlContent: String
    ): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            runBlocking {
                try {
                    val activeConfig = getActiveEmailConfig()
                        ?: throw IllegalStateException("No active email configuration found")
                    
                    sendEmailWithConfig(activeConfig, to, subject, textContent, htmlContent)
                } catch (e: Exception) {
                    log.error("Failed to send email to {}: {}", to, e.message, e)
                    false
                }
            }
        }
    }
    
    /**
     * Send HTML email with auto-generated text content
     */
    fun sendHtmlEmail(
        to: String,
        subject: String,
        htmlContent: String
    ): CompletableFuture<Boolean> {
        val textContent = convertHtmlToText(htmlContent)
        return sendEmail(to, subject, textContent, htmlContent)
    }
    
    /**
     * Test email configuration by sending a test email
     */
    fun testEmailConfiguration(
        config: EmailConfig,
        testToEmail: String
    ): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            try {
                val subject = "SecMan Email Configuration Test"
                val textContent = """
                    This is a test email from SecMan to verify your email configuration.
                    
                    Configuration Details:
                    - SMTP Host: ${config.smtpHost}
                    - SMTP Port: ${config.smtpPort}
                    - TLS Enabled: ${config.smtpTls}
                    - SSL Enabled: ${config.smtpSsl}
                    - Authentication: ${if (config.hasAuthentication()) "Yes" else "No"}
                    
                    If you received this email, your configuration is working correctly!
                """.trimIndent()
                
                val htmlContent = """
                    <html>
                    <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                        <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                            <h2 style="color: #2c3e50;">SecMan Email Configuration Test</h2>
                            <p>This is a test email from SecMan to verify your email configuration.</p>
                            
                            <div style="background-color: #f8f9fa; padding: 15px; border-radius: 5px; margin: 20px 0;">
                                <h3 style="margin-top: 0; color: #495057;">Configuration Details:</h3>
                                <ul style="margin: 0; padding-left: 20px;">
                                    <li><strong>SMTP Host:</strong> ${config.smtpHost}</li>
                                    <li><strong>SMTP Port:</strong> ${config.smtpPort}</li>
                                    <li><strong>TLS Enabled:</strong> ${config.smtpTls}</li>
                                    <li><strong>SSL Enabled:</strong> ${config.smtpSsl}</li>
                                    <li><strong>Authentication:</strong> ${if (config.hasAuthentication()) "Yes" else "No"}</li>
                                </ul>
                            </div>
                            
                            <p style="color: #28a745; font-weight: bold;">
                                ✅ If you received this email, your configuration is working correctly!
                            </p>
                            
                            <hr style="margin: 30px 0; border: none; border-top: 1px solid #dee2e6;">
                            <p style="font-size: 0.9em; color: #6c757d;">
                                This email was sent by SecMan Email Configuration Test
                            </p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                
                sendEmailWithConfig(config, testToEmail, subject, textContent, htmlContent)
            } catch (e: Exception) {
                log.error("Failed to test email configuration: {}", e.message, e)
                false
            }
        }
    }
    
    /**
     * Get the currently active email configuration
     */
    private fun getActiveEmailConfig(): EmailConfig? {
        return emailConfigRepository.findActiveConfig().orElse(null)
    }
    
    /**
     * Send email using specific configuration
     */
    private fun sendEmailWithConfig(
        config: EmailConfig,
        to: String,
        subject: String,
        textContent: String,
        htmlContent: String
    ): Boolean {
        return try {
            log.debug("Sending email to {} using SMTP {}:{}", to, config.smtpHost, config.smtpPort)
            
            val properties = createMailProperties(config)
            val session = createMailSession(properties, config)
            
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.fromEmail, config.fromName))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)
                
                // Create multipart message with both text and HTML
                val multipart = MimeMultipart("alternative")
                
                // Add text part
                val textPart = MimeBodyPart().apply {
                    setText(textContent, "UTF-8")
                }
                multipart.addBodyPart(textPart)
                
                // Add HTML part
                val htmlPart = MimeBodyPart().apply {
                    setContent(htmlContent, "text/html; charset=UTF-8")
                }
                multipart.addBodyPart(htmlPart)
                
                setContent(multipart)
                sentDate = Date()
            }
            
            Transport.send(message)
            log.info("Successfully sent email to {} with subject: {}", to, subject)
            true
            
        } catch (e: Exception) {
            log.error("Failed to send email to {}: {}", to, e.message, e)
            false
        }
    }
    
    /**
     * Create mail properties from email configuration
     */
    private fun createMailProperties(config: EmailConfig): Properties {
        return Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", config.hasAuthentication().toString())
            
            if (config.smtpTls) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            
            if (config.smtpSsl) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.socketFactory.port", config.smtpPort.toString())
            }
            
            // Additional security properties
            put("mail.smtp.ssl.trust", config.smtpHost)
            put("mail.smtp.ssl.protocols", "TLSv1.2")
        }
    }
    
    /**
     * Create mail session with or without authentication
     */
    private fun createMailSession(properties: Properties, config: EmailConfig): Session {
        return if (config.hasAuthentication()) {
            Session.getInstance(properties, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.smtpUsername, config.smtpPassword)
                }
            })
        } else {
            Session.getInstance(properties)
        }
    }
    
    /**
     * Convert HTML content to plain text
     */
    private fun convertHtmlToText(htmlContent: String): String {
        return try {
            Jsoup.parse(htmlContent).text()
        } catch (e: Exception) {
            log.warn("Failed to convert HTML to text: {}", e.message)
            // Fallback: strip HTML tags with regex
            htmlContent.replace(Regex("<[^>]*>"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}