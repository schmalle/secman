package com.secman.controller

import com.secman.domain.EmailConfig
import com.secman.domain.EmailProvider
import com.secman.service.EmailProviderConfigService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Controller for managing email provider configurations (SMTP and Amazon SES)
 * ADMIN only access
 */
@Singleton
@Controller("/api/email-provider-configs")
@Secured("ADMIN")
class EmailProviderConfigController(
    private val emailProviderConfigService: EmailProviderConfigService
) {
    private val logger = LoggerFactory.getLogger(EmailProviderConfigController::class.java)

    /**
     * Get all email configurations
     */
    @Get
    fun getAllConfigs(): List<EmailConfigResponse> {
        return emailProviderConfigService.getAllConfigs().map { EmailConfigResponse.from(it) }
    }

    /**
     * Get active email configuration
     */
    @Get("/active")
    fun getActiveConfig(): HttpResponse<EmailConfigResponse> {
        val config = emailProviderConfigService.getActiveConfig()
        return if (config != null) {
            HttpResponse.ok(EmailConfigResponse.from(config))
        } else {
            HttpResponse.notFound()
        }
    }

    /**
     * Get email configuration by ID
     */
    @Get("/{id}")
    fun getConfigById(id: Long): HttpResponse<EmailConfigResponse> {
        val config = emailProviderConfigService.getConfigById(id)
        return if (config != null) {
            HttpResponse.ok(EmailConfigResponse.from(config))
        } else {
            HttpResponse.notFound()
        }
    }

    /**
     * Create new SMTP email configuration
     */
    @Post("/smtp")
    fun createSmtpConfig(@Body request: CreateSmtpConfigRequest): HttpResponse<*> {
        val result = emailProviderConfigService.createSmtpConfig(
            name = request.name,
            smtpHost = request.smtpHost,
            smtpPort = request.smtpPort,
            smtpUsername = request.smtpUsername,
            smtpPassword = request.smtpPassword,
            smtpTls = request.smtpTls,
            smtpSsl = request.smtpSsl,
            fromEmail = request.fromEmail,
            fromName = request.fromName,
            imapHost = request.imapHost,
            imapPort = request.imapPort,
            imapEnabled = request.imapEnabled
        )

        return result.fold(
            onSuccess = { config ->
                logger.info("Created SMTP configuration: ${config.name}")
                HttpResponse.created(EmailConfigResponse.from(config))
            },
            onFailure = { error ->
                logger.error("Failed to create SMTP configuration: ${error.message}")
                HttpResponse.badRequest(ErrorResponse(error.message ?: "Failed to create configuration"))
            }
        )
    }

    /**
     * Create new Amazon SES email configuration
     */
    @Post("/ses")
    fun createSesConfig(@Body request: CreateSesConfigRequest): HttpResponse<*> {
        val result = emailProviderConfigService.createSesConfig(
            name = request.name,
            sesAccessKey = request.sesAccessKey,
            sesSecretKey = request.sesSecretKey,
            sesRegion = request.sesRegion,
            fromEmail = request.fromEmail,
            fromName = request.fromName
        )

        return result.fold(
            onSuccess = { config ->
                logger.info("Created Amazon SES configuration: ${config.name}")
                HttpResponse.created(EmailConfigResponse.from(config))
            },
            onFailure = { error ->
                logger.error("Failed to create SES configuration: ${error.message}")
                HttpResponse.badRequest(ErrorResponse(error.message ?: "Failed to create configuration"))
            }
        )
    }

    /**
     * Update email configuration
     */
    @Put("/{id}")
    fun updateConfig(id: Long, @Body request: UpdateConfigRequest): HttpResponse<*> {
        val result = emailProviderConfigService.updateConfig(
            id = id,
            name = request.name,
            smtpHost = request.smtpHost,
            smtpPort = request.smtpPort,
            smtpUsername = request.smtpUsername,
            smtpPassword = request.smtpPassword,
            smtpTls = request.smtpTls,
            smtpSsl = request.smtpSsl,
            sesAccessKey = request.sesAccessKey,
            sesSecretKey = request.sesSecretKey,
            sesRegion = request.sesRegion,
            fromEmail = request.fromEmail,
            fromName = request.fromName,
            imapHost = request.imapHost,
            imapPort = request.imapPort,
            imapEnabled = request.imapEnabled
        )

        return result.fold(
            onSuccess = { config ->
                logger.info("Updated email configuration: ${config.name}")
                HttpResponse.ok(EmailConfigResponse.from(config))
            },
            onFailure = { error ->
                logger.error("Failed to update email configuration: ${error.message}")
                HttpResponse.badRequest(ErrorResponse(error.message ?: "Failed to update configuration"))
            }
        )
    }

    /**
     * Delete email configuration
     */
    @Delete("/{id}")
    fun deleteConfig(id: Long): HttpResponse<*> {
        val result = emailProviderConfigService.deleteConfig(id)

        return result.fold(
            onSuccess = {
                logger.info("Deleted email configuration ID: $id")
                HttpResponse.noContent<Unit>()
            },
            onFailure = { error ->
                logger.error("Failed to delete email configuration: ${error.message}")
                HttpResponse.badRequest(ErrorResponse(error.message ?: "Failed to delete configuration"))
            }
        )
    }

    /**
     * Activate email configuration
     */
    @Post("/{id}/activate")
    fun activateConfig(id: Long): HttpResponse<*> {
        val result = emailProviderConfigService.activateConfig(id)

        return result.fold(
            onSuccess = { config ->
                logger.info("Activated email configuration: ${config.name}")
                HttpResponse.ok(EmailConfigResponse.from(config))
            },
            onFailure = { error ->
                logger.error("Failed to activate email configuration: ${error.message}")
                HttpResponse.badRequest(ErrorResponse(error.message ?: "Failed to activate configuration"))
            }
        )
    }

    /**
     * Deactivate email configuration
     */
    @Post("/{id}/deactivate")
    fun deactivateConfig(id: Long): HttpResponse<*> {
        val result = emailProviderConfigService.deactivateConfig(id)

        return result.fold(
            onSuccess = { config ->
                logger.info("Deactivated email configuration: ${config.name}")
                HttpResponse.ok(EmailConfigResponse.from(config))
            },
            onFailure = { error ->
                logger.error("Failed to deactivate email configuration: ${error.message}")
                HttpResponse.badRequest(ErrorResponse(error.message ?: "Failed to deactivate configuration"))
            }
        )
    }

    /**
     * Test email configuration
     */
    @Post("/{id}/test")
    fun testConfig(id: Long, @Body request: TestEmailRequest): HttpResponse<*> {
        val result = emailProviderConfigService.testConfig(id, request.testEmailAddress)

        return result.fold(
            onSuccess = { message ->
                logger.info("Test email sent successfully for configuration ID: $id")
                HttpResponse.ok(SuccessResponse(message))
            },
            onFailure = { error ->
                logger.error("Failed to send test email: ${error.message}")
                HttpResponse.badRequest(ErrorResponse(error.message ?: "Failed to send test email"))
            }
        )
    }

    /**
     * Verify SES configuration
     */
    @Post("/{id}/verify-ses")
    fun verifySesConfig(id: Long): HttpResponse<*> {
        val result = emailProviderConfigService.verifySesConfig(id)

        return result.fold(
            onSuccess = { message ->
                logger.info("SES configuration verified for ID: $id")
                HttpResponse.ok(SuccessResponse(message))
            },
            onFailure = { error ->
                logger.error("SES verification failed: ${error.message}")
                HttpResponse.badRequest(ErrorResponse(error.message ?: "SES verification failed"))
            }
        )
    }

    // Request/Response DTOs
    data class CreateSmtpConfigRequest(
        val name: String,
        val smtpHost: String,
        val smtpPort: Int,
        val smtpUsername: String?,
        val smtpPassword: String?,
        val smtpTls: Boolean = true,
        val smtpSsl: Boolean = false,
        val fromEmail: String,
        val fromName: String,
        val imapHost: String? = null,
        val imapPort: Int? = null,
        val imapEnabled: Boolean = false
    )

    data class CreateSesConfigRequest(
        val name: String,
        val sesAccessKey: String,
        val sesSecretKey: String,
        val sesRegion: String,
        val fromEmail: String,
        val fromName: String
    )

    data class UpdateConfigRequest(
        val name: String? = null,
        val smtpHost: String? = null,
        val smtpPort: Int? = null,
        val smtpUsername: String? = null,
        val smtpPassword: String? = null,
        val smtpTls: Boolean? = null,
        val smtpSsl: Boolean? = null,
        val sesAccessKey: String? = null,
        val sesSecretKey: String? = null,
        val sesRegion: String? = null,
        val fromEmail: String? = null,
        val fromName: String? = null,
        val imapHost: String? = null,
        val imapPort: Int? = null,
        val imapEnabled: Boolean? = null
    )

    data class TestEmailRequest(
        val testEmailAddress: String
    )

    data class EmailConfigResponse(
        val id: Long?,
        val name: String,
        val provider: EmailProvider,
        val smtpHost: String?,
        val smtpPort: Int?,
        val smtpUsername: String?,
        val smtpTls: Boolean?,
        val smtpSsl: Boolean?,
        val sesAccessKey: String?,
        val sesRegion: String?,
        val fromEmail: String,
        val fromName: String,
        val isActive: Boolean,
        val imapHost: String?,
        val imapPort: Int?,
        val imapEnabled: Boolean
    ) {
        companion object {
            fun from(config: EmailConfig) = EmailConfigResponse(
                id = config.id,
                name = config.name,
                provider = config.provider,
                smtpHost = config.smtpHost,
                smtpPort = config.smtpPort,
                smtpUsername = config.smtpUsername,
                smtpTls = config.smtpTls,
                smtpSsl = config.smtpSsl,
                sesAccessKey = config.sesAccessKey,
                sesRegion = config.sesRegion,
                fromEmail = config.fromEmail,
                fromName = config.fromName,
                isActive = config.isActive,
                imapHost = config.imapHost,
                imapPort = config.imapPort,
                imapEnabled = config.imapEnabled
            )
        }
    }

    data class ErrorResponse(
        val message: String
    )

    data class SuccessResponse(
        val message: String
    )
}
