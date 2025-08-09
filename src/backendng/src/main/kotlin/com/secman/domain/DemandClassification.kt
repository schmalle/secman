package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime
import java.security.MessageDigest

@Entity
@Table(name = "demand_classification_rule")
@Serdeable
data class DemandClassificationRule(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    @NotBlank
    var name: String,

    @Column(length = 1024)
    var description: String? = null,

    @Column(name = "rule_json", columnDefinition = "TEXT", nullable = false)
    @NotBlank
    var ruleJson: String,

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "priority_order", nullable = false)
    var priorityOrder: Int = 0,

    @ManyToOne
    @JoinColumn(name = "created_by")
    var createdBy: User? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }
}

@Entity
@Table(name = "demand_classification_result")
@Serdeable
data class DemandClassificationResult(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @ManyToOne
    @JoinColumn(name = "demand_id")
    var demand: Demand? = null,

    @Column(name = "classification", nullable = false)
    @Enumerated(EnumType.STRING)
    @NotNull
    var classification: Classification,

    @Column(name = "confidence_score")
    var confidenceScore: Double? = null,

    @ManyToOne
    @JoinColumn(name = "applied_rule_id")
    var appliedRule: DemandClassificationRule? = null,

    @Column(name = "rule_evaluation_log", columnDefinition = "TEXT")
    var ruleEvaluationLog: String? = null,

    @Column(name = "classification_hash", nullable = false, unique = true)
    @NotBlank
    var classificationHash: String,

    @Column(name = "input_data", columnDefinition = "TEXT")
    var inputData: String? = null,

    @Column(name = "classified_at", nullable = false)
    var classifiedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "is_manual_override")
    var isManualOverride: Boolean = false,

    @ManyToOne
    @JoinColumn(name = "overridden_by")
    var overriddenBy: User? = null
) {
    companion object {
        fun generateHash(demandId: Long?, classification: Classification, timestamp: LocalDateTime): String {
            val input = "${demandId ?: "public"}-${classification.name}-${timestamp}"
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

@Serdeable
enum class Classification {
    A,  // High risk/critical
    B,  // Medium risk/moderate
    C   // Low risk/minimal
}

@Serdeable
data class RuleCondition(
    val type: ConditionType,
    val field: String? = null,
    val operator: ComparisonOperator? = null,
    val value: Any? = null,
    val conditions: List<RuleCondition>? = null
)

@Serdeable
enum class ConditionType {
    IF,
    AND,
    OR,
    NOT,
    COMPARISON
}

@Serdeable
enum class ComparisonOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    LESS_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN_OR_EQUAL,
    CONTAINS,
    NOT_CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    IN,
    NOT_IN,
    IS_NULL,
    IS_NOT_NULL
}

@Serdeable
data class ClassificationRule(
    val name: String,
    val description: String? = null,
    val condition: RuleCondition,
    val classification: Classification,
    val confidenceScore: Double = 1.0
)

@Serdeable
data class ClassificationInput(
    val title: String,
    val description: String? = null,
    val demandType: String,
    val priority: String,
    val businessJustification: String? = null,
    val assetType: String? = null,
    val assetOwner: String? = null,
    val customFields: Map<String, Any>? = null
)

@Serdeable
data class ClassificationResponse(
    val classification: Classification,
    val classificationHash: String,
    val confidenceScore: Double,
    val appliedRuleName: String? = null,
    val evaluationLog: List<String>,
    val timestamp: LocalDateTime
)