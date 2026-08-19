package com.secman.dto

import com.secman.domain.ProductClass
import com.secman.domain.ProductClassificationRule
import com.secman.domain.RuleMatchField
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Serdeable
data class ProductClassificationRuleResponse(
    val id: Long,
    val matchField: RuleMatchField,
    val pattern: String,
    val classification: ProductClass,
    val priority: Int,
    val enabled: Boolean,
    val description: String?,
    val createdBy: String?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
) {
    companion object {
        fun from(rule: ProductClassificationRule) = ProductClassificationRuleResponse(
            id = rule.id!!,
            matchField = rule.matchField,
            pattern = rule.pattern,
            classification = rule.classification,
            priority = rule.priority,
            enabled = rule.enabled,
            description = rule.description,
            createdBy = rule.createdBy,
            createdAt = rule.createdAt,
            updatedAt = rule.updatedAt
        )
    }
}

@Serdeable
data class ProductClassificationRuleRequest(
    val matchField: RuleMatchField = RuleMatchField.PRODUCT_NAME,

    @field:NotBlank
    @field:Size(max = ProductClassificationRule.MAX_PATTERN_LENGTH)
    val pattern: String,

    val classification: ProductClass = ProductClass.INSTALLER_ARTIFACT,
    val priority: Int = 100,
    val enabled: Boolean = true,

    @field:Nullable
    @field:Size(max = 512)
    val description: String? = null
)

/** Result of the admin "what would this value classify as?" box. */
@Serdeable
data class ProductClassificationTestRequest(
    @field:NotBlank
    @field:Size(max = 1024)
    val value: String,

    /** Which kind of value [value] is. Defaults to a product name. */
    val matchField: RuleMatchField = RuleMatchField.PRODUCT_NAME
)

@Serdeable
data class ProductClassificationTestResponse(
    val value: String,
    val classification: ProductClass,
    val matchedRuleId: Long?,
    val matchedPattern: String?
)

/** Current row counts per class, so an admin can see a rule change's blast radius. */
@Serdeable
data class ProductClassificationStatsResponse(
    val installedProductArtifacts: Long,
    val eolFindingArtifacts: Long,
    val vulnerabilityArtifacts: Long,
    val enabledRules: Long
)
