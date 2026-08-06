package com.secman.event

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * Event published when a new risk assessment is created
 */
@Serdeable
data class RiskAssessmentCreatedEvent(
    /**
     * Unique identifier of the created risk assessment
     */
    val riskAssessmentId: Long,

    /**
     * Title of the risk assessment
     */
    val title: String,

    /**
     * Risk level (LOW, MEDIUM, HIGH, CRITICAL)
     */
    val riskLevel: String,

    /**
     * User who created the assessment
     */
    val createdBy: String,

    /**
     * When the assessment was created
     */
    val createdAt: LocalDateTime,

    /**
     * Description of the risk assessment
     */
    val description: String? = null,

    /**
     * Category of the risk assessment
     */
    val category: String? = null,

    /**
     * Impact level (LOW, MEDIUM, HIGH, CRITICAL)
     */
    val impact: String? = null,

    /**
     * Probability level (LOW, MEDIUM, HIGH, CRITICAL)
     */
    val probability: String? = null,

    /**
     * Additional metadata for the risk assessment
     */
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        /**
         * Create event from risk assessment data
         */
        fun fromRiskAssessment(
            id: Long,
            title: String,
            riskLevel: String,
            createdBy: String,
            createdAt: LocalDateTime,
            description: String? = null,
            category: String? = null,
            impact: String? = null,
            probability: String? = null,
            additionalData: Map<String, Any> = emptyMap()
        ): RiskAssessmentCreatedEvent {
            return RiskAssessmentCreatedEvent(
                riskAssessmentId = id,
                title = title,
                riskLevel = riskLevel,
                createdBy = createdBy,
                createdAt = createdAt,
                description = description,
                category = category,
                impact = impact,
                probability = probability,
                metadata = additionalData
            )
        }

        /**
         * High-priority risk levels that typically trigger immediate notifications
         */
        val HIGH_PRIORITY_LEVELS = setOf("HIGH", "CRITICAL")

        /**
         * All valid risk levels
         */
        val VALID_RISK_LEVELS = setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
    }

    /**
     * Check if this risk assessment is high priority
     */
    fun isHighPriority(): Boolean {
        return riskLevel.uppercase() in HIGH_PRIORITY_LEVELS
    }

    /**
     * Check if this risk assessment should trigger immediate notifications
     */
    fun shouldTriggerImmediateNotification(): Boolean {
        return isHighPriority()
    }

    /**
     * Get all risk assessment data as a map for condition matching
     */
    fun getRiskAssessmentData(): Map<String, Any> {
        val data = mutableMapOf<String, Any>(
            "id" to riskAssessmentId,
            "title" to title,
            "riskLevel" to riskLevel,
            "createdBy" to createdBy,
            "createdAt" to createdAt
        )

        description?.let { data["description"] = it }
        category?.let { data["category"] = it }
        impact?.let { data["impact"] = it }
        probability?.let { data["probability"] = it }

        // Add metadata
        data.putAll(metadata)

        return data
    }

    /**
     * Get a human-readable summary of the event
     */
    fun getSummary(): String {
        return "Risk Assessment '$title' (${riskLevel.uppercase()}) created by $createdBy at $createdAt"
    }

    /**
     * Validate the event data
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (riskAssessmentId <= 0) {
            errors.add("Risk assessment ID must be positive")
        }

        if (title.isBlank()) {
            errors.add("Title cannot be empty")
        }

        if (riskLevel.isBlank()) {
            errors.add("Risk level cannot be empty")
        } else if (riskLevel.uppercase() !in VALID_RISK_LEVELS) {
            errors.add("Invalid risk level: $riskLevel. Must be one of: ${VALID_RISK_LEVELS.joinToString()}")
        }

        if (createdBy.isBlank()) {
            errors.add("Created by cannot be empty")
        }

        // Validate optional fields if present
        impact?.let { impactLevel ->
            if (impactLevel.uppercase() !in VALID_RISK_LEVELS) {
                errors.add("Invalid impact level: $impactLevel")
            }
        }

        probability?.let { probLevel ->
            if (probLevel.uppercase() !in VALID_RISK_LEVELS) {
                errors.add("Invalid probability level: $probLevel")
            }
        }

        return errors
    }

    /**
     * Check if this event matches specific criteria
     */
    fun matches(criteria: Map<String, Any>): Boolean {
        val eventData = getRiskAssessmentData()

        return criteria.all { (key, expectedValue) ->
            val actualValue = eventData[key]
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
     * Get event type for logging and metrics
     */
    fun getEventType(): String = "risk_assessment_created"

    /**
     * Get event severity based on risk level
     */
    fun getEventSeverity(): String {
        return when (riskLevel.uppercase()) {
            "CRITICAL" -> "critical"
            "HIGH" -> "high"
            "MEDIUM" -> "medium"
            "LOW" -> "low"
            else -> "unknown"
        }
    }
}