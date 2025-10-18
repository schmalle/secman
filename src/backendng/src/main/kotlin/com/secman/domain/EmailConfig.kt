package com.secman.domain

import com.secman.util.EncryptedStringConverter
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "email_configs")
@Serdeable
data class EmailConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 100)
    @NotBlank
    val name: String,

    @Column(name = "smtp_host", nullable = false, length = 255)
    @NotBlank
    val smtpHost: String,

    @Column(name = "smtp_port", nullable = false)
    @Min(1) @Max(65535)
    val smtpPort: Int = 587,

    @Column(name = "smtp_username", length = 255)
    @Convert(converter = EncryptedStringConverter::class)
    val smtpUsername: String? = null,

    @Column(name = "smtp_password", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    val smtpPassword: String? = null,

    @Column(name = "smtp_tls", nullable = false)
    val smtpTls: Boolean = true,

    @Column(name = "smtp_ssl", nullable = false)
    val smtpSsl: Boolean = false,

    @Column(name = "from_email", nullable = false, length = 255)
    @NotBlank
    @Email
    val fromEmail: String,

    @Column(name = "from_name", nullable = false, length = 255)
    @NotBlank
    val fromName: String,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = false,

    // NEW: IMAP support
    @Column(name = "imap_host", length = 255)
    val imapHost: String? = null,

    @Column(name = "imap_port")
    val imapPort: Int? = null,

    @Column(name = "imap_enabled", nullable = false)
    val imapEnabled: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null
) {

    companion object {
        const val PASSWORD_MASK = "***HIDDEN***"
        const val USERNAME_MASK = "***HIDDEN***"

        /**
         * Create new email configuration with encrypted credentials
         */
        fun create(
            name: String,
            smtpHost: String,
            smtpPort: Int,
            smtpUsername: String?,
            smtpPassword: String?,
            smtpTls: Boolean,
            smtpSsl: Boolean,
            fromEmail: String,
            fromName: String,
            imapHost: String? = null,
            imapPort: Int? = null,
            imapEnabled: Boolean = false
        ): EmailConfig {
            return EmailConfig(
                name = name,
                smtpHost = smtpHost,
                smtpPort = smtpPort,
                smtpUsername = smtpUsername,
                smtpPassword = smtpPassword,
                smtpTls = smtpTls,
                smtpSsl = smtpSsl,
                fromEmail = fromEmail,
                fromName = fromName,
                imapHost = imapHost,
                imapPort = imapPort,
                imapEnabled = imapEnabled,
                isActive = false
            )
        }
    }

    /**
     * Check if this configuration has authentication credentials
     */
    fun hasAuthentication(): Boolean {
        return !smtpUsername.isNullOrBlank() && !smtpPassword.isNullOrBlank()
    }

    /**
     * Create a copy with sensitive data masked for API responses
     */
    fun toSafeResponse(): EmailConfig {
        return copy(
            smtpUsername = if (!smtpUsername.isNullOrBlank()) USERNAME_MASK else null,
            smtpPassword = if (!smtpPassword.isNullOrBlank()) PASSWORD_MASK else null
        )
    }

    /**
     * Check if credential update is needed (not the masked values)
     */
    fun shouldUpdateCredentials(newUsername: String?, newPassword: String?): Boolean {
        val shouldUpdateUsername = newUsername != null && newUsername != USERNAME_MASK
        val shouldUpdatePassword = newPassword != null && newPassword != PASSWORD_MASK
        return shouldUpdateUsername || shouldUpdatePassword
    }

    /**
     * Activate this configuration and deactivate others
     */
    fun activate(): EmailConfig {
        return copy(isActive = true)
    }

    /**
     * Deactivate this configuration
     */
    fun deactivate(): EmailConfig {
        return copy(isActive = false)
    }

    /**
     * Update with new credentials
     */
    fun withUpdatedCredentials(newUsername: String?, newPassword: String?): EmailConfig {
        return copy(
            smtpUsername = if (shouldUpdateCredentials(newUsername, null)) newUsername else smtpUsername,
            smtpPassword = if (shouldUpdateCredentials(null, newPassword)) newPassword else smtpPassword
        )
    }

    /**
     * Check if IMAP is configured and enabled
     */
    fun isImapConfigured(): Boolean {
        return imapEnabled && !imapHost.isNullOrBlank() && imapPort != null
    }

    /**
     * Get SMTP configuration properties for JavaMail
     */
    fun getSmtpProperties(): Map<String, String> {
        val props = mutableMapOf<String, String>()

        props["mail.smtp.host"] = smtpHost
        props["mail.smtp.port"] = smtpPort.toString()
        props["mail.smtp.auth"] = hasAuthentication().toString()

        if (smtpTls) {
            props["mail.smtp.starttls.enable"] = "true"
            props["mail.smtp.starttls.required"] = "true"
        }

        if (smtpSsl) {
            props["mail.smtp.ssl.enable"] = "true"
            props["mail.smtp.socketFactory.class"] = "javax.net.ssl.SSLSocketFactory"
            props["mail.smtp.socketFactory.port"] = smtpPort.toString()
            props["mail.smtp.socketFactory.fallback"] = "false"
        }

        // Security properties
        props["mail.smtp.ssl.trust"] = smtpHost
        props["mail.smtp.ssl.protocols"] = "TLSv1.2 TLSv1.3"

        // Timeout properties
        props["mail.smtp.connectiontimeout"] = "10000"
        props["mail.smtp.timeout"] = "10000"
        props["mail.smtp.writetimeout"] = "10000"

        return props
    }

    /**
     * Get IMAP configuration properties for JavaMail
     */
    fun getImapProperties(): Map<String, String>? {
        if (!isImapConfigured()) return null

        val props = mutableMapOf<String, String>()

        props["mail.imap.host"] = imapHost!!
        props["mail.imap.port"] = imapPort.toString()
        props["mail.imap.ssl.enable"] = "true"
        props["mail.imap.auth"] = hasAuthentication().toString()

        return props
    }

    /**
     * Validate configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (name.isBlank()) {
            errors.add("Configuration name cannot be empty")
        }

        if (smtpHost.isBlank()) {
            errors.add("SMTP host cannot be empty")
        }

        if (smtpPort !in 1..65535) {
            errors.add("SMTP port must be between 1 and 65535")
        }

        if (fromEmail.isBlank()) {
            errors.add("From email cannot be empty")
        }

        if (fromName.isBlank()) {
            errors.add("From name cannot be empty")
        }

        if (imapEnabled) {
            if (imapHost.isNullOrBlank()) {
                errors.add("IMAP host is required when IMAP is enabled")
            }
            if (imapPort == null || imapPort !in 1..65535) {
                errors.add("Valid IMAP port is required when IMAP is enabled")
            }
        }

        return errors
    }

    override fun toString(): String {
        return "EmailConfig(id=$id, name='$name', smtpHost='$smtpHost', smtpPort=$smtpPort, fromEmail='$fromEmail', isActive=$isActive)"
    }
}