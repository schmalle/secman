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

/**
 * Email provider type
 */
enum class EmailProvider {
    SMTP,
    AMAZON_SES
}

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

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    val provider: EmailProvider = EmailProvider.SMTP,

    // SMTP Configuration
    @Column(name = "smtp_host", length = 255)
    val smtpHost: String? = null,

    @Column(name = "smtp_port")
    @Min(1) @Max(65535)
    val smtpPort: Int? = 587,

    @Column(name = "smtp_username", length = 255)
    @Convert(converter = EncryptedStringConverter::class)
    val smtpUsername: String? = null,

    @Column(name = "smtp_password", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    val smtpPassword: String? = null,

    @Column(name = "smtp_tls")
    val smtpTls: Boolean? = true,

    @Column(name = "smtp_ssl")
    val smtpSsl: Boolean? = false,

    // Amazon SES Configuration
    @Column(name = "ses_access_key", length = 255)
    @Convert(converter = EncryptedStringConverter::class)
    val sesAccessKey: String? = null,

    @Column(name = "ses_secret_key", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    val sesSecretKey: String? = null,

    @Column(name = "ses_region", length = 50)
    val sesRegion: String? = null,

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
        const val ACCESS_KEY_MASK = "***HIDDEN***"
        const val SECRET_KEY_MASK = "***HIDDEN***"

        /**
         * Create new SMTP email configuration
         */
        fun createSmtp(
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
                provider = EmailProvider.SMTP,
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

        /**
         * Create new Amazon SES email configuration
         */
        fun createSes(
            name: String,
            sesAccessKey: String,
            sesSecretKey: String,
            sesRegion: String,
            fromEmail: String,
            fromName: String
        ): EmailConfig {
            return EmailConfig(
                name = name,
                provider = EmailProvider.AMAZON_SES,
                sesAccessKey = sesAccessKey,
                sesSecretKey = sesSecretKey,
                sesRegion = sesRegion,
                fromEmail = fromEmail,
                fromName = fromName,
                isActive = false
            )
        }
    }

    /**
     * Check if this configuration has authentication credentials
     */
    fun hasAuthentication(): Boolean {
        return when (provider) {
            EmailProvider.SMTP -> !smtpUsername.isNullOrBlank() && !smtpPassword.isNullOrBlank()
            EmailProvider.AMAZON_SES -> !sesAccessKey.isNullOrBlank() && !sesSecretKey.isNullOrBlank()
        }
    }

    /**
     * Create a copy with sensitive data masked for API responses
     */
    fun toSafeResponse(): EmailConfig {
        return copy(
            smtpUsername = if (!smtpUsername.isNullOrBlank()) USERNAME_MASK else null,
            smtpPassword = if (!smtpPassword.isNullOrBlank()) PASSWORD_MASK else null,
            sesAccessKey = if (!sesAccessKey.isNullOrBlank()) ACCESS_KEY_MASK else null,
            sesSecretKey = if (!sesSecretKey.isNullOrBlank()) SECRET_KEY_MASK else null
        )
    }

    /**
     * Check if credential update is needed (not the masked values)
     */
    fun shouldUpdateCredentials(
        newUsername: String? = null,
        newPassword: String? = null,
        newAccessKey: String? = null,
        newSecretKey: String? = null
    ): Boolean {
        val shouldUpdateUsername = newUsername != null && newUsername != USERNAME_MASK
        val shouldUpdatePassword = newPassword != null && newPassword != PASSWORD_MASK
        val shouldUpdateAccessKey = newAccessKey != null && newAccessKey != ACCESS_KEY_MASK
        val shouldUpdateSecretKey = newSecretKey != null && newSecretKey != SECRET_KEY_MASK
        return shouldUpdateUsername || shouldUpdatePassword || shouldUpdateAccessKey || shouldUpdateSecretKey
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
    fun withUpdatedCredentials(
        newUsername: String? = null,
        newPassword: String? = null,
        newAccessKey: String? = null,
        newSecretKey: String? = null
    ): EmailConfig {
        return copy(
            smtpUsername = if (newUsername != null && newUsername != USERNAME_MASK) newUsername else smtpUsername,
            smtpPassword = if (newPassword != null && newPassword != PASSWORD_MASK) newPassword else smtpPassword,
            sesAccessKey = if (newAccessKey != null && newAccessKey != ACCESS_KEY_MASK) newAccessKey else sesAccessKey,
            sesSecretKey = if (newSecretKey != null && newSecretKey != SECRET_KEY_MASK) newSecretKey else sesSecretKey
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

        if (fromEmail.isBlank()) {
            errors.add("From email cannot be empty")
        }

        if (fromName.isBlank()) {
            errors.add("From name cannot be empty")
        }

        // Provider-specific validation
        when (provider) {
            EmailProvider.SMTP -> {
                if (smtpHost.isNullOrBlank()) {
                    errors.add("SMTP host cannot be empty for SMTP provider")
                }

                if (smtpPort == null || smtpPort !in 1..65535) {
                    errors.add("SMTP port must be between 1 and 65535")
                }

                if (imapEnabled) {
                    if (imapHost.isNullOrBlank()) {
                        errors.add("IMAP host is required when IMAP is enabled")
                    }
                    if (imapPort == null || imapPort !in 1..65535) {
                        errors.add("Valid IMAP port is required when IMAP is enabled")
                    }
                }
            }
            EmailProvider.AMAZON_SES -> {
                if (sesAccessKey.isNullOrBlank()) {
                    errors.add("SES access key cannot be empty for Amazon SES provider")
                }

                if (sesSecretKey.isNullOrBlank()) {
                    errors.add("SES secret key cannot be empty for Amazon SES provider")
                }

                if (sesRegion.isNullOrBlank()) {
                    errors.add("SES region cannot be empty for Amazon SES provider")
                }
            }
        }

        return errors
    }

    override fun toString(): String {
        return "EmailConfig(id=$id, name='$name', provider=$provider, fromEmail='$fromEmail', isActive=$isActive)"
    }
}