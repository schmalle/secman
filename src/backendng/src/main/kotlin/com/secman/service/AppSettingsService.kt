package com.secman.service

import com.secman.domain.AppSettings
import com.secman.repository.AppSettingsRepository
import io.micronaut.context.annotation.Value
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Service for managing application-wide settings.
 * Feature: 068-requirements-alignment-process
 */
@Singleton
open class AppSettingsService(
    private val appSettingsRepository: AppSettingsRepository
) {
    private val logger = LoggerFactory.getLogger(AppSettingsService::class.java)

    @Value("\${app.frontend.base-url:http://localhost:4321}")
    private var defaultBaseUrl: String = "http://localhost:4321"

    @Serdeable
    data class AppSettingsDto(
        val baseUrl: String,
        val globalCveApprovalAdminOnly: Boolean,
        val updatedBy: String?,
        val updatedAt: String?
    )

    /**
     * Get current application settings.
     * Creates default settings if none exist.
     */
    @Transactional
    open fun getSettings(): AppSettingsDto {
        val settings = getOrCreateSettings()
        return AppSettingsDto(
            baseUrl = settings.getNormalizedBaseUrl(),
            globalCveApprovalAdminOnly = settings.globalCveApprovalAdminOnly,
            updatedBy = settings.updatedBy,
            updatedAt = settings.updatedAt?.toString()
        )
    }

    /**
     * Get the base URL for use in email generation.
     * Returns the normalized URL without trailing slash.
     */
    @Transactional
    open fun getBaseUrl(): String {
        val settings = getOrCreateSettings()
        return settings.getNormalizedBaseUrl()
    }

    /**
     * Check if global CVE approval is restricted to ADMIN only.
     */
    @Transactional
    open fun isGlobalCveApprovalAdminOnly(): Boolean {
        return getOrCreateSettings().globalCveApprovalAdminOnly
    }

    /**
     * Update application settings.
     *
     * @param baseUrl The new base URL
     * @param updatedBy Username of the admin making the change
     * @param globalCveApprovalAdminOnly Whether CVE_PATTERN approvals require ADMIN role
     * @return Updated settings DTO
     * @throws IllegalArgumentException if validation fails
     */
    @Transactional
    open fun updateSettings(baseUrl: String, updatedBy: String, globalCveApprovalAdminOnly: Boolean = false): AppSettingsDto {
        val settings = getOrCreateSettings()

        // Update the base URL
        settings.baseUrl = baseUrl.trimEnd('/')
        settings.globalCveApprovalAdminOnly = globalCveApprovalAdminOnly
        settings.updatedBy = updatedBy

        // Validate
        val errors = settings.validateBaseUrl()
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }

        val updated = appSettingsRepository.update(settings)
        logger.info("App settings updated by {}: baseUrl={}, globalCveApprovalAdminOnly={}", updatedBy, updated.baseUrl, updated.globalCveApprovalAdminOnly)

        return AppSettingsDto(
            baseUrl = updated.getNormalizedBaseUrl(),
            globalCveApprovalAdminOnly = updated.globalCveApprovalAdminOnly,
            updatedBy = updated.updatedBy,
            updatedAt = updated.updatedAt?.toString()
        )
    }

    /**
     * Get existing settings or create default ones.
     */
    private fun getOrCreateSettings(): AppSettings {
        val existing = appSettingsRepository.findAll()
        return if (existing.isEmpty()) {
            logger.info("Creating default app settings with baseUrl={}", defaultBaseUrl)
            val default = AppSettings(
                baseUrl = defaultBaseUrl,
                updatedBy = "system"
            )
            appSettingsRepository.save(default)
        } else {
            existing.first()
        }
    }
}
