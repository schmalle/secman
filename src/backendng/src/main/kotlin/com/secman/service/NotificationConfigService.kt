package com.secman.service

import com.secman.domain.RiskAssessmentNotificationConfig
import com.secman.repository.RiskAssessmentNotificationConfigRepository
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.*

/**
 * Service for managing notification configurations
 */
@Singleton
class NotificationConfigService(
    private val notificationConfigRepository: RiskAssessmentNotificationConfigRepository
) {

    private val logger = LoggerFactory.getLogger(NotificationConfigService::class.java)

    /**
     * Create a new notification configuration
     */
    suspend fun createNotificationConfig(
        name: String,
        description: String? = null,
        recipientEmails: String,
        notificationTiming: String = "immediate",
        notificationFrequency: String = "all",
        conditions: String? = null,
        isActive: Boolean = true
    ): RiskAssessmentNotificationConfig = withContext(Dispatchers.IO) {
        logger.info("Creating notification configuration: $name")

        // Validate name uniqueness
        if (notificationConfigRepository.existsByName(name)) {
            throw IllegalArgumentException("Notification configuration with name '$name' already exists")
        }

        // Parse recipient emails from JSON string
        val emailsList = try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(recipientEmails)
        } catch (e: Exception) {
            // If not JSON, treat as single email
            listOf(recipientEmails)
        }

        // Parse conditions from JSON string
        val conditionsMap = conditions?.let { conditionsStr ->
            try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(conditionsStr)
            } catch (e: Exception) {
                logger.warn("Failed to parse conditions JSON: ${e.message}")
                null
            }
        }

        val config = RiskAssessmentNotificationConfig.create(
            name = name,
            recipientEmails = emailsList,
            notificationTiming = notificationTiming,
            notificationFrequency = notificationFrequency,
            conditions = conditionsMap
        )

        // Validate configuration
        val validationErrors = validateNotificationConfig(config)
        if (validationErrors.isNotEmpty()) {
            throw IllegalArgumentException("Invalid configuration: ${validationErrors.joinToString(", ")}")
        }

        val savedConfig = notificationConfigRepository.save(config)
        logger.info("Created notification configuration with ID: ${savedConfig.id}")

        savedConfig
    }

    /**
     * Get all notification configurations
     */
    suspend fun getAllConfigurations(): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findAll()
    }

    /**
     * Get notification configuration by ID
     */
    suspend fun getConfigurationById(id: Long): RiskAssessmentNotificationConfig? = withContext(Dispatchers.IO) {
        notificationConfigRepository.findById(id).orElse(null)
    }

    /**
     * Get notification configuration by name
     */
    suspend fun getConfigurationByName(name: String): RiskAssessmentNotificationConfig? = withContext(Dispatchers.IO) {
        notificationConfigRepository.findByName(name).orElse(null)
    }

    /**
     * Get active notification configurations
     */
    suspend fun getActiveConfigurations(): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findActiveConfigurations()
    }

    /**
     * Get configurations by timing
     */
    suspend fun getConfigurationsByTiming(timing: String): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findByNotificationTiming(timing)
    }

    /**
     * Get configurations by frequency
     */
    suspend fun getConfigurationsByFrequency(frequency: String): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findByNotificationFrequency(frequency)
    }

    /**
     * Get immediate notification configurations
     */
    suspend fun getImmediateNotificationConfigs(): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findImmediateNotificationConfigs()
    }

    /**
     * Get scheduled notification configurations
     */
    suspend fun getScheduledNotificationConfigs(): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findScheduledNotificationConfigs()
    }

    /**
     * Update notification configuration
     */
    suspend fun updateConfiguration(
        id: Long,
        name: String? = null,
        description: String? = null,
        recipientEmails: String? = null,
        notificationTiming: String? = null,
        notificationFrequency: String? = null,
        conditions: String? = null,
        isActive: Boolean? = null
    ): RiskAssessmentNotificationConfig? = withContext(Dispatchers.IO) {
        val existingConfig = notificationConfigRepository.findById(id).orElse(null)
            ?: return@withContext null

        logger.info("Updating notification configuration: $id")

        // Check name uniqueness if name is being changed
        if (name != null && name != existingConfig.name) {
            if (notificationConfigRepository.existsByNameAndIdNot(name, id)) {
                throw IllegalArgumentException("Notification configuration with name '$name' already exists")
            }
        }

        val updatedConfig = existingConfig.copy(
            name = name ?: existingConfig.name,
            recipientEmails = recipientEmails ?: existingConfig.recipientEmails,
            notificationTiming = notificationTiming ?: existingConfig.notificationTiming,
            notificationFrequency = notificationFrequency ?: existingConfig.notificationFrequency,
            conditions = conditions ?: existingConfig.conditions,
            isActive = isActive ?: existingConfig.isActive,
            updatedAt = LocalDateTime.now()
        )

        // Validate updated configuration
        val validationErrors = validateNotificationConfig(updatedConfig)
        if (validationErrors.isNotEmpty()) {
            throw IllegalArgumentException("Invalid configuration: ${validationErrors.joinToString(", ")}")
        }

        val savedConfig = notificationConfigRepository.update(updatedConfig)
        logger.info("Updated notification configuration: $id")

        savedConfig
    }

    /**
     * Delete notification configuration
     */
    suspend fun deleteConfiguration(id: Long): Boolean = withContext(Dispatchers.IO) {
        val config = notificationConfigRepository.findById(id).orElse(null)
            ?: return@withContext false

        logger.info("Deleting notification configuration: $id (${config.name})")
        notificationConfigRepository.deleteById(id)

        logger.info("Deleted notification configuration: $id")
        true
    }

    /**
     * Activate configuration
     */
    suspend fun activateConfiguration(id: Long): Boolean = withContext(Dispatchers.IO) {
        val config = notificationConfigRepository.findById(id).orElse(null)
            ?: return@withContext false

        if (config.isActive) {
            logger.debug("Configuration $id is already active")
            return@withContext true
        }

        val activatedConfig = config.copy(isActive = true, updatedAt = LocalDateTime.now())
        notificationConfigRepository.update(activatedConfig)

        logger.info("Activated notification configuration: ${config.name}")
        true
    }

    /**
     * Deactivate configuration
     */
    suspend fun deactivateConfiguration(id: Long): Boolean = withContext(Dispatchers.IO) {
        val config = notificationConfigRepository.findById(id).orElse(null)
            ?: return@withContext false

        if (!config.isActive) {
            logger.debug("Configuration $id is already inactive")
            return@withContext true
        }

        val deactivatedConfig = config.copy(isActive = false, updatedAt = LocalDateTime.now())
        notificationConfigRepository.update(deactivatedConfig)

        logger.info("Deactivated notification configuration: ${config.name}")
        true
    }

    /**
     * Bulk activate configurations
     */
    suspend fun bulkActivateConfigurations(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        val updatedAt = LocalDateTime.now()
        val result = notificationConfigRepository.bulkUpdateActiveStatus(ids, true, updatedAt)

        logger.info("Bulk activated $result notification configurations")
        result
    }

    /**
     * Bulk deactivate configurations
     */
    suspend fun bulkDeactivateConfigurations(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        val updatedAt = LocalDateTime.now()
        val result = notificationConfigRepository.bulkUpdateActiveStatus(ids, false, updatedAt)

        logger.info("Bulk deactivated $result notification configurations")
        result
    }

    /**
     * Test configuration against risk assessment data
     */
    suspend fun testConfiguration(
        id: Long,
        riskAssessmentData: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        val config = notificationConfigRepository.findById(id).orElse(null)
            ?: return@withContext false

        val matches = config.matchesRiskAssessment(riskAssessmentData)
        val shouldNotify = config.shouldNotifyForRiskLevel(riskAssessmentData["riskLevel"]?.toString() ?: "")

        logger.debug("Testing configuration '${config.name}': matches=$matches, shouldNotify=$shouldNotify")

        matches && shouldNotify
    }

    /**
     * Get configurations that match risk assessment
     */
    suspend fun getMatchingConfigurations(
        riskAssessmentData: Map<String, Any>
    ): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        val activeConfigs = getActiveConfigurations()

        activeConfigs.filter { config ->
            val matches = config.matchesRiskAssessment(riskAssessmentData)
            val shouldNotify = config.shouldNotifyForRiskLevel(riskAssessmentData["riskLevel"]?.toString() ?: "")

            matches && shouldNotify
        }
    }

    /**
     * Search configurations by name
     */
    suspend fun searchConfigurationsByName(name: String): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.searchByName(name)
    }

    /**
     * Get configurations containing email
     */
    suspend fun getConfigurationsContainingEmail(email: String): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findConfigsContainingEmail(email)
    }

    /**
     * Get configurations with conditions
     */
    suspend fun getConfigurationsWithConditions(): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findConfigsWithConditions()
    }

    /**
     * Get configurations without conditions
     */
    suspend fun getConfigurationsWithoutConditions(): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findConfigsWithoutConditions()
    }

    /**
     * Get configuration statistics
     */
    suspend fun getConfigurationStatistics(): Map<String, Any> = withContext(Dispatchers.IO) {
        val stats = notificationConfigRepository.getConfigurationStatistics()
        val timingDistribution = notificationConfigRepository.getTimingDistribution()
        val frequencyDistribution = notificationConfigRepository.getFrequencyDistribution()

        // Convert list of maps to single map
        val basicStats = stats.associate { it["metric"].toString() to it["count"] as Long }

        mapOf(
            "statistics" to basicStats,
            "timingDistribution" to timingDistribution.associate {
                it["timing"].toString() to it["count"] as Long
            },
            "frequencyDistribution" to frequencyDistribution.associate {
                it["frequency"].toString() to it["count"] as Long
            }
        )
    }

    /**
     * Get configurations by priority
     */
    suspend fun getConfigurationsByPriority(): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findConfigsByPriority()
    }

    /**
     * Get configurations due for scheduled notification
     */
    suspend fun getConfigurationsDueForScheduledNotification(): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findConfigsDueForScheduledNotification()
    }

    /**
     * Update notification timing
     */
    suspend fun updateNotificationTiming(id: Long, timing: String): Boolean = withContext(Dispatchers.IO) {
        val config = notificationConfigRepository.findById(id).orElse(null)
            ?: return@withContext false

        // Validate timing
        if (!isValidNotificationTiming(timing)) {
            throw IllegalArgumentException("Invalid notification timing: $timing")
        }

        val updatedAt = LocalDateTime.now()
        val result = notificationConfigRepository.updateNotificationTiming(id, timing, updatedAt)

        if (result > 0) {
            logger.info("Updated notification timing for configuration $id to $timing")
        }

        result > 0
    }

    /**
     * Update notification frequency
     */
    suspend fun updateNotificationFrequency(id: Long, frequency: String): Boolean = withContext(Dispatchers.IO) {
        val config = notificationConfigRepository.findById(id).orElse(null)
            ?: return@withContext false

        // Validate frequency
        if (!isValidNotificationFrequency(frequency)) {
            throw IllegalArgumentException("Invalid notification frequency: $frequency")
        }

        val updatedAt = LocalDateTime.now()
        val result = notificationConfigRepository.updateNotificationFrequency(id, frequency, updatedAt)

        if (result > 0) {
            logger.info("Updated notification frequency for configuration $id to $frequency")
        }

        result > 0
    }

    /**
     * Get configurations created within time period
     */
    suspend fun getConfigurationsCreatedBetween(
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findCreatedBetween(startDate, endDate)
    }

    /**
     * Get recently updated configurations
     */
    suspend fun getRecentlyUpdatedConfigurations(since: LocalDateTime): List<RiskAssessmentNotificationConfig> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findRecentlyUpdated(since)
    }

    /**
     * Validate notification configuration
     */
    private fun validateNotificationConfig(config: RiskAssessmentNotificationConfig): List<String> {
        val errors = mutableListOf<String>()

        // Use the entity's validation method
        errors.addAll(config.validate())

        // Additional service-level validation
        if (!isValidNotificationTiming(config.notificationTiming)) {
            errors.add("Invalid notification timing: ${config.notificationTiming}")
        }

        if (!isValidNotificationFrequency(config.notificationFrequency)) {
            errors.add("Invalid notification frequency: ${config.notificationFrequency}")
        }

        // Validate email list format
        val emailValidationErrors = validateEmailList(config.recipientEmails)
        errors.addAll(emailValidationErrors)

        // Validate conditions JSON if present
        if (!config.conditions.isNullOrBlank()) {
            val conditionValidationErrors = validateConditionsJson(config.conditions!!)
            errors.addAll(conditionValidationErrors)
        }

        return errors
    }

    /**
     * Validate notification timing
     */
    private fun isValidNotificationTiming(timing: String): Boolean {
        return timing in setOf("immediate", "daily", "weekly", "monthly")
    }

    /**
     * Validate notification frequency
     */
    private fun isValidNotificationFrequency(frequency: String): Boolean {
        return frequency in setOf("all", "critical_only", "high_only", "medium_and_above")
    }

    /**
     * Validate email list format
     */
    private fun validateEmailList(emails: String): List<String> {
        val errors = mutableListOf<String>()

        if (emails.isBlank()) {
            errors.add("Recipient emails cannot be empty")
            return errors
        }

        val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val emailList = emails.split(",").map { it.trim() }

        emailList.forEach { email ->
            if (email.isNotBlank() && !emailPattern.matches(email)) {
                errors.add("Invalid email format: $email")
            }
        }

        return errors
    }

    /**
     * Validate conditions JSON format
     */
    private fun validateConditionsJson(conditions: String): List<String> {
        val errors = mutableListOf<String>()

        try {
            // Basic JSON validation - in a real implementation you'd use a JSON library
            if (!conditions.trim().startsWith("{") || !conditions.trim().endsWith("}")) {
                errors.add("Conditions must be valid JSON object")
            }
        } catch (e: Exception) {
            errors.add("Invalid JSON format in conditions: ${e.message}")
        }

        return errors
    }

    /**
     * Clean up old inactive configurations
     */
    suspend fun cleanupOldInactiveConfigurations(beforeDate: LocalDateTime): Int = withContext(Dispatchers.IO) {
        val deletedCount = notificationConfigRepository.deleteInactiveConfigsOlderThan(beforeDate)

        if (deletedCount > 0) {
            logger.info("Cleaned up $deletedCount old inactive notification configurations")
        }

        deletedCount
    }

    /**
     * Find duplicate configuration names
     */
    suspend fun findDuplicateNames(): List<String> = withContext(Dispatchers.IO) {
        notificationConfigRepository.findDuplicateNames()
    }

    /**
     * Count configurations by status
     */
    suspend fun countConfigurationsByStatus(): Map<String, Long> = withContext(Dispatchers.IO) {
        mapOf(
            "active" to notificationConfigRepository.countByIsActive(true),
            "inactive" to notificationConfigRepository.countByIsActive(false)
        )
    }

    /**
     * Count configurations by timing
     */
    suspend fun countConfigurationsByTiming(): Map<String, Long> = withContext(Dispatchers.IO) {
        val timings = listOf("immediate", "daily", "weekly", "monthly")
        timings.associateWith { timing ->
            notificationConfigRepository.countByNotificationTiming(timing)
        }
    }

    /**
     * Count configurations by frequency
     */
    suspend fun countConfigurationsByFrequency(): Map<String, Long> = withContext(Dispatchers.IO) {
        val frequencies = listOf("all", "critical_only", "high_only", "medium_and_above")
        frequencies.associateWith { frequency ->
            notificationConfigRepository.countByNotificationFrequency(frequency)
        }
    }
}