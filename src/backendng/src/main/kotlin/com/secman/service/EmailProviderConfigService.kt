package com.secman.service

import com.secman.domain.EmailConfig
import com.secman.domain.EmailProvider
import com.secman.repository.EmailConfigRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Service for managing email provider configurations
 * Handles SMTP and Amazon SES provider settings
 */
@Singleton
class EmailProviderConfigService(
    private val emailConfigRepository: EmailConfigRepository,
    private val sesEmailService: SesEmailService
) {
    private val logger = LoggerFactory.getLogger(EmailProviderConfigService::class.java)

    /**
     * Get all email configurations
     */
    fun getAllConfigs(): List<EmailConfig> {
        return emailConfigRepository.findAll().map { it.toSafeResponse() }
    }

    /**
     * Get active email configuration
     */
    fun getActiveConfig(): EmailConfig? {
        return emailConfigRepository.findActiveConfig().orElse(null)?.toSafeResponse()
    }

    /**
     * Get email configuration by ID
     */
    fun getConfigById(id: Long): EmailConfig? {
        return emailConfigRepository.findById(id).orElse(null)?.toSafeResponse()
    }

    /**
     * Create new SMTP email configuration
     */
    @Transactional
    fun createSmtpConfig(
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
    ): Result<EmailConfig> {
        val config = EmailConfig.createSmtp(
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
            imapEnabled = imapEnabled
        )

        // Validate
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(errors.joinToString(", ")))
        }

        val saved = emailConfigRepository.save(config)
        logger.info("Created SMTP email configuration: ${saved.name} (ID: ${saved.id})")

        return Result.success(saved.toSafeResponse())
    }

    /**
     * Create new Amazon SES email configuration
     */
    @Transactional
    fun createSesConfig(
        name: String,
        sesAccessKey: String,
        sesSecretKey: String,
        sesRegion: String,
        fromEmail: String,
        fromName: String
    ): Result<EmailConfig> {
        val config = EmailConfig.createSes(
            name = name,
            sesAccessKey = sesAccessKey,
            sesSecretKey = sesSecretKey,
            sesRegion = sesRegion,
            fromEmail = fromEmail,
            fromName = fromName
        )

        // Validate
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(errors.joinToString(", ")))
        }

        val saved = emailConfigRepository.save(config)
        logger.info("Created Amazon SES email configuration: ${saved.name} (ID: ${saved.id})")

        return Result.success(saved.toSafeResponse())
    }

    /**
     * Update email configuration
     */
    @Transactional
    fun updateConfig(
        id: Long,
        name: String?,
        smtpHost: String? = null,
        smtpPort: Int? = null,
        smtpUsername: String? = null,
        smtpPassword: String? = null,
        smtpTls: Boolean? = null,
        smtpSsl: Boolean? = null,
        sesAccessKey: String? = null,
        sesSecretKey: String? = null,
        sesRegion: String? = null,
        fromEmail: String? = null,
        fromName: String? = null,
        imapHost: String? = null,
        imapPort: Int? = null,
        imapEnabled: Boolean? = null
    ): Result<EmailConfig> {
        val existing = emailConfigRepository.findById(id).orElse(null)
            ?: return Result.failure(IllegalArgumentException("Email configuration not found"))

        val updated = existing.copy(
            name = name ?: existing.name,
            smtpHost = smtpHost ?: existing.smtpHost,
            smtpPort = smtpPort ?: existing.smtpPort,
            smtpTls = smtpTls ?: existing.smtpTls,
            smtpSsl = smtpSsl ?: existing.smtpSsl,
            sesRegion = sesRegion ?: existing.sesRegion,
            fromEmail = fromEmail ?: existing.fromEmail,
            fromName = fromName ?: existing.fromName,
            imapHost = imapHost ?: existing.imapHost,
            imapPort = imapPort ?: existing.imapPort,
            imapEnabled = imapEnabled ?: existing.imapEnabled
        ).withUpdatedCredentials(
            newUsername = smtpUsername,
            newPassword = smtpPassword,
            newAccessKey = sesAccessKey,
            newSecretKey = sesSecretKey
        )

        // Validate
        val errors = updated.validate()
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(errors.joinToString(", ")))
        }

        val saved = emailConfigRepository.update(updated)
        logger.info("Updated email configuration: ${saved.name} (ID: ${saved.id})")

        return Result.success(saved.toSafeResponse())
    }

    /**
     * Delete email configuration
     */
    @Transactional
    fun deleteConfig(id: Long): Result<Unit> {
        val config = emailConfigRepository.findById(id).orElse(null)
            ?: return Result.failure(IllegalArgumentException("Email configuration not found"))

        if (config.isActive) {
            return Result.failure(IllegalStateException("Cannot delete active email configuration. Deactivate it first."))
        }

        emailConfigRepository.deleteById(id)
        logger.info("Deleted email configuration: ${config.name} (ID: $id)")

        return Result.success(Unit)
    }

    /**
     * Activate email configuration
     * Deactivates all other configurations
     */
    @Transactional
    fun activateConfig(id: Long): Result<EmailConfig> {
        val config = emailConfigRepository.findById(id).orElse(null)
            ?: return Result.failure(IllegalArgumentException("Email configuration not found"))

        // Deactivate all other configurations
        emailConfigRepository.deactivateAll()

        // Activate this one
        val activated = emailConfigRepository.update(config.activate())
        logger.info("Activated email configuration: ${activated.name} (ID: ${activated.id})")

        return Result.success(activated.toSafeResponse())
    }

    /**
     * Deactivate email configuration
     */
    @Transactional
    fun deactivateConfig(id: Long): Result<EmailConfig> {
        val config = emailConfigRepository.findById(id).orElse(null)
            ?: return Result.failure(IllegalArgumentException("Email configuration not found"))

        val deactivated = emailConfigRepository.update(config.deactivate())
        logger.info("Deactivated email configuration: ${deactivated.name} (ID: ${deactivated.id})")

        return Result.success(deactivated.toSafeResponse())
    }

    /**
     * Test email configuration by sending a test email
     */
    fun testConfig(id: Long, testEmailAddress: String): Result<String> {
        val config = emailConfigRepository.findById(id).orElse(null)
            ?: return Result.failure(IllegalArgumentException("Email configuration not found"))

        return when (config.provider) {
            EmailProvider.SMTP -> testSmtpConfig(config, testEmailAddress)
            EmailProvider.AMAZON_SES -> testSesConfig(config, testEmailAddress)
        }
    }

    /**
     * Test SMTP configuration
     */
    private fun testSmtpConfig(config: EmailConfig, testEmailAddress: String): Result<String> {
        // TODO: Implement SMTP test using EmailSender with the specific config
        // For now, return a placeholder
        return Result.success("SMTP test functionality will be implemented")
    }

    /**
     * Test SES configuration
     */
    private fun testSesConfig(config: EmailConfig, testEmailAddress: String): Result<String> {
        val result = sesEmailService.sendTestEmail(config, testEmailAddress)

        return if (result.success) {
            Result.success("Test email sent successfully via SES. MessageId: ${result.messageId}")
        } else {
            Result.failure(Exception(result.errorMessage ?: "Unknown error"))
        }
    }

    /**
     * Verify SES configuration without sending email
     */
    fun verifySesConfig(id: Long): Result<String> {
        val config = emailConfigRepository.findById(id).orElse(null)
            ?: return Result.failure(IllegalArgumentException("Email configuration not found"))

        if (config.provider != EmailProvider.AMAZON_SES) {
            return Result.failure(IllegalArgumentException("Configuration is not an Amazon SES provider"))
        }

        val (isValid, message) = sesEmailService.verifyConfiguration(config)

        return if (isValid) {
            Result.success(message)
        } else {
            Result.failure(Exception(message))
        }
    }
}
