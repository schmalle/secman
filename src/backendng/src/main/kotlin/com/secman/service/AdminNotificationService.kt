package com.secman.service

import com.secman.domain.AdminNotificationSettings
import com.secman.domain.User
import com.secman.dto.AdminNotificationConfigDto
import com.secman.repository.AdminNotificationSettingsRepository
import com.secman.repository.UserRepository
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Service for managing admin notification settings
 * Feature: 027-admin-user-notifications
 *
 * Handles configuration for email notifications sent to ADMIN users when new users are created.
 * Implements in-memory caching for performance with lazy initialization.
 */
@Singleton
open class AdminNotificationService(
    private val settingsRepository: AdminNotificationSettingsRepository,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val emailGenerator: AdminNotificationEmailGenerator
) {
    private val logger = LoggerFactory.getLogger(AdminNotificationService::class.java)

    // Cached enabled state for fast lookup with lazy initialization
    private val cachedEnabled = AtomicBoolean(true) // Default to enabled (opt-out model)
    private val cachedSenderEmail = AtomicReference<String>("noreply@secman.local")
    private val cacheInitialized = AtomicBoolean(false)

    /**
     * Initialize cache lazily on first access
     * Thread-safe singleton initialization pattern
     */
    private fun ensureCacheInitialized() {
        if (!cacheInitialized.get()) {
            synchronized(this) {
                if (!cacheInitialized.get()) {
                    try {
                        val settings = getOrCreateSettings()
                        cachedEnabled.set(settings.notificationsEnabled)
                        cachedSenderEmail.set(settings.senderEmail)
                        cacheInitialized.set(true)
                        logger.info("Initialized admin notification settings cache: enabled=${settings.notificationsEnabled}, sender=${settings.senderEmail}")
                    } catch (e: Exception) {
                        logger.error("Failed to initialize admin notification settings cache, using defaults", e)
                        cachedEnabled.set(true)
                        cachedSenderEmail.set("noreply@secman.local")
                        cacheInitialized.set(true)
                    }
                }
            }
        }
    }

    /**
     * Check if admin notifications are enabled
     * Uses cached value for performance (no database query)
     *
     * @return true if notifications are enabled, false otherwise
     */
    fun isNotificationEnabled(): Boolean {
        ensureCacheInitialized()
        return cachedEnabled.get()
    }

    /**
     * Get current sender email from cache
     *
     * @return current sender email address
     */
    fun getSenderEmail(): String {
        ensureCacheInitialized()
        return cachedSenderEmail.get()
    }

    /**
     * Get current notification settings
     * Returns existing settings or creates default if none exist
     *
     * @return current settings as DTO
     */
    fun getSettings(): AdminNotificationConfigDto {
        val settings = getOrCreateSettings()
        return AdminNotificationConfigDto(
            enabled = settings.notificationsEnabled,
            senderEmail = settings.senderEmail
        )
    }

    /**
     * Update notification settings
     *
     * @param dto new settings
     * @param updatedByUsername username of the admin making the change
     * @return updated settings as DTO
     */
    fun updateSettings(dto: AdminNotificationConfigDto, updatedByUsername: String): AdminNotificationConfigDto {
        logger.info("Updating admin notification settings: enabled=${dto.enabled}, sender=${dto.senderEmail}, updatedBy=$updatedByUsername")

        // Validate email format
        if (!isValidEmail(dto.senderEmail)) {
            throw IllegalArgumentException("Invalid sender email format: ${dto.senderEmail}")
        }

        val settings = getOrCreateSettings()

        // Update entity
        val updatedSettings = settings.update(
            enabled = dto.enabled,
            senderEmail = dto.senderEmail,
            updatedByUsername = updatedByUsername
        )

        // Save to database
        val savedSettings = settingsRepository.update(updatedSettings)

        // Invalidate cache and reload
        refreshCache(savedSettings)

        logger.info("Admin notification settings updated successfully: id=${savedSettings.id}")

        return AdminNotificationConfigDto(
            enabled = savedSettings.notificationsEnabled,
            senderEmail = savedSettings.senderEmail
        )
    }

    /**
     * Get existing settings or create default if none exist
     * Thread-safe singleton pattern
     */
    private fun getOrCreateSettings(): AdminNotificationSettings {
        return settingsRepository.findFirstSettings().orElseGet {
            logger.info("No admin notification settings found, creating default settings")
            val defaultSettings = AdminNotificationSettings.createDefault()
            settingsRepository.save(defaultSettings)
        }
    }

    /**
     * Refresh the in-memory cache with new settings
     */
    private fun refreshCache(settings: AdminNotificationSettings) {
        cachedEnabled.set(settings.notificationsEnabled)
        cachedSenderEmail.set(settings.senderEmail)
        logger.debug("Refreshed cache: enabled=${settings.notificationsEnabled}, sender=${settings.senderEmail}")
    }

    /**
     * Validate email format
     */
    private fun isValidEmail(email: String): Boolean {
        if (email.isBlank()) return false
        return email.matches(Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\$"))
    }

    /**
     * Send email notifications to all ADMIN users for a new user registration (manual creation)
     *
     * This method runs asynchronously and never throws exceptions to ensure
     * user creation is never blocked by email failures.
     *
     * @param newUser The newly created user
     * @param createdByUsername Username of the admin who created the user
     */
    @Async
    open fun sendNewUserNotificationForManualCreation(newUser: User, createdByUsername: String): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                // Check if notifications are enabled
                if (!isNotificationEnabled()) {
                    logger.debug("Admin notifications are disabled, skipping notification for user: ${newUser.username}")
                    return@runAsync
                }

                logger.info("Sending new user notification for manual creation: user=${newUser.username}, createdBy=$createdByUsername")

                // Get all ADMIN users
                val adminUsers = userRepository.findAll().filter { it.hasRole(User.Role.ADMIN) }

                if (adminUsers.isEmpty()) {
                    logger.warn("No ADMIN users found to notify about new user: ${newUser.username}")
                    return@runAsync
                }

                logger.debug("Found ${adminUsers.size} ADMIN users to notify")

                // Generate email content
                val subject = emailGenerator.generateSubject(newUser.username)
                val htmlContent = emailGenerator.generateManualCreationEmail(
                    newUser = newUser,
                    createdByUsername = createdByUsername,
                    registrationTimestamp = newUser.createdAt ?: Instant.now()
                )

                // Send to each ADMIN user (filter out those with invalid emails)
                adminUsers.forEach { adminUser ->
                    try {
                        if (adminUser.email.isNullOrBlank()) {
                            logger.warn("ADMIN user ${adminUser.username} has no email address, skipping notification")
                            return@forEach
                        }

                        if (!isValidEmail(adminUser.email)) {
                            logger.warn("ADMIN user ${adminUser.username} has invalid email: ${adminUser.email}, skipping")
                            return@forEach
                        }

                        // Send email asynchronously (fire and forget)
                        emailService.sendHtmlEmail(
                            to = adminUser.email,
                            subject = subject,
                            htmlContent = htmlContent
                        ).whenComplete { success, error ->
                            if (error != null) {
                                logger.error("Failed to send new user notification to ${adminUser.email}: ${error.message}", error)
                            } else if (success == true) {
                                logger.debug("Successfully sent new user notification to ${adminUser.email}")
                            } else {
                                logger.warn("Email send returned false for ${adminUser.email}")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error sending notification to ADMIN user ${adminUser.username}: ${e.message}", e)
                        // Continue with other admins even if one fails
                    }
                }

                logger.info("New user notification process completed for user: ${newUser.username}")
            } catch (e: Exception) {
                logger.error("Unexpected error in sendNewUserNotificationForManualCreation: ${e.message}", e)
                // Swallow exception to prevent user creation failure
            }
        }
    }

    /**
     * Send email notifications to all ADMIN users for a new user registration (OAuth)
     *
     * This method runs asynchronously and never throws exceptions to ensure
     * user registration is never blocked by email failures.
     *
     * @param newUser The newly registered user
     * @param oauthProvider OAuth provider name (e.g., "GitHub", "Google")
     */
    @Async
    open fun sendNewUserNotificationForOAuth(newUser: User, oauthProvider: String): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                // Check if notifications are enabled
                if (!isNotificationEnabled()) {
                    logger.debug("Admin notifications are disabled, skipping notification for OAuth user: ${newUser.username}")
                    return@runAsync
                }

                logger.info("Sending new user notification for OAuth registration: user=${newUser.username}, provider=$oauthProvider")

                // Get all ADMIN users
                val adminUsers = userRepository.findAll().filter { it.hasRole(User.Role.ADMIN) }

                if (adminUsers.isEmpty()) {
                    logger.warn("No ADMIN users found to notify about OAuth user: ${newUser.username}")
                    return@runAsync
                }

                logger.debug("Found ${adminUsers.size} ADMIN users to notify")

                // Generate email content
                val subject = emailGenerator.generateSubject(newUser.username)
                val htmlContent = emailGenerator.generateOAuthRegistrationEmail(
                    newUser = newUser,
                    oauthProvider = oauthProvider,
                    registrationTimestamp = newUser.createdAt ?: Instant.now()
                )

                // Send to each ADMIN user (filter out those with invalid emails)
                adminUsers.forEach { adminUser ->
                    try {
                        if (adminUser.email.isNullOrBlank()) {
                            logger.warn("ADMIN user ${adminUser.username} has no email address, skipping notification")
                            return@forEach
                        }

                        if (!isValidEmail(adminUser.email)) {
                            logger.warn("ADMIN user ${adminUser.username} has invalid email: ${adminUser.email}, skipping")
                            return@forEach
                        }

                        // Send email asynchronously (fire and forget)
                        emailService.sendHtmlEmail(
                            to = adminUser.email,
                            subject = subject,
                            htmlContent = htmlContent
                        ).whenComplete { success, error ->
                            if (error != null) {
                                logger.error("Failed to send OAuth notification to ${adminUser.email}: ${error.message}", error)
                            } else if (success == true) {
                                logger.debug("Successfully sent OAuth notification to ${adminUser.email}")
                            } else {
                                logger.warn("Email send returned false for ${adminUser.email}")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error sending OAuth notification to ADMIN user ${adminUser.username}: ${e.message}", e)
                        // Continue with other admins even if one fails
                    }
                }

                logger.info("OAuth user notification process completed for user: ${newUser.username}")
            } catch (e: Exception) {
                logger.error("Unexpected error in sendNewUserNotificationForOAuth: ${e.message}", e)
                // Swallow exception to prevent user registration failure
            }
        }
    }
}
