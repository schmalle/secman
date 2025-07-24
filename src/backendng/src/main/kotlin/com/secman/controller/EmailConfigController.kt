package com.secman.controller

import com.secman.domain.EmailConfig
import com.secman.repository.EmailConfigRepository
import com.secman.service.EmailService
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

@Controller("/api/email-config")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class EmailConfigController(
    private val emailConfigRepository: EmailConfigRepository,
    private val emailService: EmailService
) {
    
    private val log = LoggerFactory.getLogger(EmailConfigController::class.java)

    @Serdeable
    data class CreateEmailConfigRequest(
        @NotBlank val smtpHost: String,
        @Min(1) @Max(65535) val smtpPort: Int = 587,
        @Nullable val smtpUsername: String? = null,
        @Nullable val smtpPassword: String? = null,
        val smtpTls: Boolean = true,
        val smtpSsl: Boolean = false,
        @NotBlank @Email val fromEmail: String,
        @NotBlank val fromName: String,
        val isActive: Boolean = false
    )

    @Serdeable
    data class UpdateEmailConfigRequest(
        @Nullable val smtpHost: String? = null,
        @Nullable val smtpPort: Int? = null,
        @Nullable val smtpUsername: String? = null,
        @Nullable val smtpPassword: String? = null,
        @Nullable val smtpTls: Boolean? = null,
        @Nullable val smtpSsl: Boolean? = null,
        @Nullable val fromEmail: String? = null,
        @Nullable val fromName: String? = null,
        @Nullable val isActive: Boolean? = null
    )

    @Serdeable
    data class TestEmailRequest(
        @NotBlank @Email val testEmail: String,
        @Nullable val message: String? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String
    )

    @Get
    @Transactional(readOnly = true)
    open fun listConfigs(): HttpResponse<List<EmailConfig>> {
        return try {
            log.debug("Fetching all email configurations")
            
            val configs = emailConfigRepository.findAll()
                .map { it.toSafeResponse() } // Mask passwords
            
            log.debug("Found {} email configurations", configs.size)
            HttpResponse.ok(configs)
        } catch (e: Exception) {
            log.error("Error fetching email configurations", e)
            HttpResponse.serverError<List<EmailConfig>>()
        }
    }

    @Get("/active")
    @Transactional(readOnly = true)
    open fun getActiveConfig(): HttpResponse<*> {
        return try {
            log.debug("Fetching active email configuration")
            
            val activeConfig = emailConfigRepository.findActiveConfig().orElse(null)
            
            if (activeConfig != null) {
                log.debug("Found active configuration: {}", activeConfig.id)
                HttpResponse.ok(activeConfig.toSafeResponse())
            } else {
                log.debug("No active email configuration found")
                HttpResponse.notFound(ErrorResponse("No active email configuration found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching active email configuration", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getConfig(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching email configuration with id: {}", id)
            
            val config = emailConfigRepository.findById(id).orElse(null)
            
            if (config != null) {
                log.debug("Found email configuration: {}", config.smtpHost)
                HttpResponse.ok(config.toSafeResponse())
            } else {
                log.debug("Email configuration not found with id: {}", id)
                HttpResponse.notFound(ErrorResponse("Email configuration not found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching email configuration with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post
    @Transactional
    open fun createConfig(@Valid @Body request: CreateEmailConfigRequest): HttpResponse<*> {
        return try {
            log.debug("Creating email configuration for host: {}", request.smtpHost)
            
            // If setting as active, deactivate all others first
            if (request.isActive) {
                emailConfigRepository.deactivateAll()
                log.debug("Deactivated all existing configurations")
            }
            
            val config = EmailConfig(
                smtpHost = request.smtpHost.trim(),
                smtpPort = request.smtpPort,
                smtpUsername = request.smtpUsername?.trim()?.takeIf { it.isNotBlank() },
                smtpPassword = request.smtpPassword?.takeIf { it.isNotBlank() },
                smtpTls = request.smtpTls,
                smtpSsl = request.smtpSsl,
                fromEmail = request.fromEmail.trim(),
                fromName = request.fromName.trim(),
                isActive = request.isActive
            )
            
            val savedConfig = emailConfigRepository.save(config)
            
            log.info("Created email configuration: {} (active: {})", savedConfig.smtpHost, savedConfig.isActive)
            HttpResponse.status<EmailConfig>(HttpStatus.CREATED).body(savedConfig.toSafeResponse())
        } catch (e: Exception) {
            log.error("Error creating email configuration", e)
            HttpResponse.badRequest(ErrorResponse("Error creating email configuration: ${e.message}"))
        }
    }

    @Put("/{id}")
    @Transactional
    open fun updateConfig(id: Long, @Valid @Body request: UpdateEmailConfigRequest): HttpResponse<*> {
        return try {
            log.debug("Updating email configuration with id: {}", id)
            
            val config = emailConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Email configuration not found"))
            
            // Update fields if provided
            request.smtpHost?.let { config.smtpHost = it.trim() }
            request.smtpPort?.let { config.smtpPort = it }
            request.smtpUsername?.let { config.smtpUsername = it.trim().takeIf { it.isNotBlank() } }
            request.smtpTls?.let { config.smtpTls = it }
            request.smtpSsl?.let { config.smtpSsl = it }
            request.fromEmail?.let { config.fromEmail = it.trim() }
            request.fromName?.let { config.fromName = it.trim() }
            
            // Handle password update (only if not the masked value)
            if (config.shouldUpdatePassword(request.smtpPassword)) {
                config.smtpPassword = request.smtpPassword
            }
            
            // Handle active status change
            request.isActive?.let { newActiveStatus ->
                if (newActiveStatus && !config.isActive) {
                    // Setting as active - deactivate all others first
                    emailConfigRepository.deactivateAllExcept(id)
                    log.debug("Deactivated all other configurations")
                }
                config.isActive = newActiveStatus
            }
            
            val updatedConfig = emailConfigRepository.update(config)
            
            log.info("Updated email configuration: {} (active: {})", updatedConfig.smtpHost, updatedConfig.isActive)
            HttpResponse.ok(updatedConfig.toSafeResponse())
        } catch (e: Exception) {
            log.error("Error updating email configuration with id: {}", id, e)
            HttpResponse.badRequest(ErrorResponse("Error updating email configuration: ${e.message}"))
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun deleteConfig(id: Long): HttpResponse<*> {
        return try {
            log.debug("Deleting email configuration with id: {}", id)
            
            val config = emailConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Email configuration not found"))
            
            emailConfigRepository.delete(config)
            
            log.info("Deleted email configuration: {}", config.smtpHost)
            HttpResponse.ok(mapOf("message" to "Email configuration deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting email configuration with id: {}", id, e)
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("Error deleting email configuration: ${e.message}"))
        }
    }

    @Post("/{id}/test")
    @Transactional(readOnly = true)
    open fun testConfig(id: Long, @Valid @Body request: TestEmailRequest): HttpResponse<*> {
        return try {
            log.debug("Testing email configuration with id: {}", id)
            
            val config = emailConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Email configuration not found"))
            
            log.debug("Sending test email to: {}", request.testEmail)
            
            // Send test email asynchronously
            val future: CompletableFuture<Boolean> = emailService.testEmailConfiguration(config, request.testEmail)
            
            // Wait for result (with timeout)
            val success = try {
                future.get() // Wait for completion
            } catch (e: Exception) {
                log.error("Test email failed", e)
                false
            }
            
            if (success) {
                log.info("Test email sent successfully to: {}", request.testEmail)
                HttpResponse.ok(mapOf(
                    "message" to "Test email sent successfully",
                    "testEmail" to request.testEmail
                ))
            } else {
                log.warn("Test email failed for configuration: {}", config.smtpHost)
                HttpResponse.badRequest(ErrorResponse("Failed to send test email. Please check your configuration."))
            }
            
        } catch (e: Exception) {
            log.error("Error testing email configuration with id: {}", id, e)
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("Error testing email configuration: ${e.message}"))
        }
    }
}