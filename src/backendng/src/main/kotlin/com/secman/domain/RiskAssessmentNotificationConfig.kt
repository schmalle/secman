package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * Entity representing notification configuration for risk assessments
 */
@Entity
@Table(name = "risk_assessment_notification_configs")
@Serdeable
data class RiskAssessmentNotificationConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 100, unique = true)
    val name: String,

    @Column(nullable = false, columnDefinition = "TEXT", name = "recipient_emails")
    val recipientEmails: String,

    @Column(nullable = true, columnDefinition = "TEXT")
    val conditions: String? = null,

    @Column(nullable = false, name = "is_active")
    val isActive: Boolean = true,

    @Column(nullable = false, name = "notification_timing")
    val notificationTiming: String = "immediate", // immediate, daily, weekly

    @Column(nullable = false, name = "notification_frequency")
    val notificationFrequency: String = "all", // all, high_only, critical_only

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "created_at")
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(nullable = false, name = "updated_at")
    val updatedAt: LocalDateTime? = null
) {
    companion object {
        /**
         * Create a new notification configuration
         */
        fun create(
            name: String,
            recipientEmails: List<String>,
            conditions: Map<String, Any>? = null,
            notificationTiming: String = "immediate",
            notificationFrequency: String = "all"
        ): RiskAssessmentNotificationConfig {
            val recipientEmailsJson = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<List<String>>(),
                recipientEmails
            )

            val conditionsJson = conditions?.let { cond ->
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, Any>>(),
                    cond
                )
            }

            return RiskAssessmentNotificationConfig(
                name = name,
                recipientEmails = recipientEmailsJson,
                conditions = conditionsJson,
                notificationTiming = notificationTiming,
                notificationFrequency = notificationFrequency,
                isActive = true
            )
        }

        /**
         * Valid notification timing options
         */
        val VALID_TIMING_OPTIONS = setOf("immediate", "daily", "weekly", "monthly")

        /**
         * Valid notification frequency options
         */
        val VALID_FREQUENCY_OPTIONS = setOf("all", "high_only", "critical_only", "medium_and_above")
    }

    /**
     * Get recipient emails as a list
     */
    fun getRecipientEmailsList(): List<String> {
        return try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(recipientEmails)
        } catch (e: Exception) {
            throw RuntimeException("Failed to parse recipient emails", e)
        }
    }

    /**
     * Get conditions as a map
     */
    fun getConditionsMap(): Map<String, Any>? {
        return if (conditions.isNullOrBlank()) {
            null
        } else {
            try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(conditions)
            } catch (e: Exception) {
                null // Return null if parsing fails
            }
        }
    }

    /**
     * Update recipient emails
     */
    fun withUpdatedRecipients(newRecipients: List<String>): RiskAssessmentNotificationConfig {
        val recipientEmailsJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<List<String>>(),
            newRecipients
        )

        return copy(
            recipientEmails = recipientEmailsJson,
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * Update conditions
     */
    fun withUpdatedConditions(newConditions: Map<String, Any>?): RiskAssessmentNotificationConfig {
        val conditionsJson = newConditions?.let { cond ->
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<Map<String, Any>>(),
                cond
            )
        }

        return copy(
            conditions = conditionsJson,
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * Toggle active status
     */
    fun toggleActive(): RiskAssessmentNotificationConfig {
        return copy(
            isActive = !isActive,
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * Check if this configuration matches a risk assessment
     */
    fun matchesRiskAssessment(riskAssessmentData: Map<String, Any>): Boolean {
        if (!isActive) return false

        val conditionsMap = getConditionsMap() ?: return true // No conditions means match all

        return conditionsMap.all { (key, expectedValue) ->
            val actualValue = riskAssessmentData[key]
            when {
                expectedValue is String && actualValue is String -> {
                    expectedValue.equals(actualValue, ignoreCase = true)
                }
                expectedValue is List<*> && actualValue != null -> {
                    expectedValue.contains(actualValue)
                }
                expectedValue is Map<*, *> -> {
                    // Handle complex condition matching
                    when (val operator = expectedValue["operator"]) {
                        "equals" -> expectedValue["value"] == actualValue
                        "contains" -> actualValue.toString().contains(expectedValue["value"].toString(), ignoreCase = true)
                        "in" -> (expectedValue["values"] as? List<*>)?.contains(actualValue) == true
                        "greater_than" -> {
                            val numValue = actualValue.toString().toDoubleOrNull()
                            val expectedNum = expectedValue["value"].toString().toDoubleOrNull()
                            numValue != null && expectedNum != null && numValue > expectedNum
                        }
                        else -> expectedValue["value"] == actualValue
                    }
                }
                else -> expectedValue == actualValue
            }
        }
    }

    /**
     * Check if notification should be sent based on frequency setting
     */
    fun shouldNotifyForRiskLevel(riskLevel: String): Boolean {
        if (!isActive) return false

        return when (notificationFrequency.lowercase()) {
            "all" -> true
            "high_only" -> riskLevel.uppercase() in setOf("HIGH", "CRITICAL")
            "critical_only" -> riskLevel.uppercase() == "CRITICAL"
            "medium_and_above" -> riskLevel.uppercase() in setOf("MEDIUM", "HIGH", "CRITICAL")
            else -> true
        }
    }

    /**
     * Check if it's time to send notification based on timing setting
     */
    fun isTimeToNotify(lastNotificationTime: LocalDateTime?): Boolean {
        if (!isActive) return false
        if (notificationTiming == "immediate") return true
        if (lastNotificationTime == null) return true

        val now = LocalDateTime.now()
        return when (notificationTiming) {
            "daily" -> lastNotificationTime.plusDays(1).isBefore(now)
            "weekly" -> lastNotificationTime.plusWeeks(1).isBefore(now)
            "monthly" -> lastNotificationTime.plusMonths(1).isBefore(now)
            else -> true
        }
    }

    /**
     * Validate notification configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (name.isBlank()) {
            errors.add("Configuration name cannot be empty")
        }

        try {
            val recipients = getRecipientEmailsList()
            if (recipients.isEmpty()) {
                errors.add("At least one recipient email is required")
            } else {
                recipients.forEach { email ->
                    if (!email.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))) {
                        errors.add("Invalid email format: $email")
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Invalid recipient emails format")
        }

        if (notificationTiming !in VALID_TIMING_OPTIONS) {
            errors.add("Invalid notification timing: $notificationTiming")
        }

        if (notificationFrequency !in VALID_FREQUENCY_OPTIONS) {
            errors.add("Invalid notification frequency: $notificationFrequency")
        }

        // Validate conditions if present
        getConditionsMap()?.let { conditionsMap ->
            if (conditionsMap.isEmpty()) {
                errors.add("Conditions cannot be empty when specified")
            }
        }

        return errors
    }

    /**
     * Get configuration summary for display
     */
    fun getSummary(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        result["id"] = id ?: 0
        result["name"] = name
        result["recipientCount"] = getRecipientEmailsList().size
        result["recipients"] = getRecipientEmailsList()
        result["isActive"] = isActive
        result["timing"] = notificationTiming
        result["frequency"] = notificationFrequency
        result["hasConditions"] = !conditions.isNullOrBlank()
        getConditionsMap()?.let { result["conditions"] = it }
        createdAt?.let { result["createdAt"] = it }
        updatedAt?.let { result["updatedAt"] = it }
        return result
    }
}