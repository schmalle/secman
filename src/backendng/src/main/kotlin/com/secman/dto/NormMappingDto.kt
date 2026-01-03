package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTOs for AI-powered norm mapping feature.
 * Used to suggest and apply ISO 27001 and IEC 62443 mappings to requirements.
 */

@Serdeable
data class NormMappingSuggestionRequest(
    val requirementIds: List<Long>? = null  // Optional filter, null = all unmapped
)

@Serdeable
data class NormMappingSuggestionResponse(
    val suggestions: List<RequirementSuggestions>,
    val totalRequirementsAnalyzed: Int,
    val totalSuggestionsGenerated: Int
)

@Serdeable
data class RequirementSuggestions(
    val requirementId: Long,
    val requirementTitle: String,
    val suggestions: List<NormSuggestion>
)

@Serdeable
data class NormSuggestion(
    val standard: String,           // e.g., "ISO 27001:2022"
    val control: String,            // e.g., "A.8.1.1"
    val controlName: String,        // e.g., "Inventory of assets"
    val confidence: Int,            // 1-5
    val reasoning: String,          // Brief explanation
    val normId: Long? = null        // ID if norm exists in DB, null if will be created
)

@Serdeable
data class ApplyMappingsRequest(
    val mappings: Map<Long, List<NormToApply>>  // requirementId -> norms to add
)

@Serdeable
data class NormToApply(
    val normId: Long? = null,       // Existing norm ID
    val standard: String? = null,   // For new norm creation
    val control: String? = null,    // For new norm creation
    val version: String? = null     // For new norm creation
)

@Serdeable
data class ApplyMappingsResponse(
    val updatedRequirements: Int,
    val newNormsCreated: Int,
    val existingNormsLinked: Int
)

@Serdeable
data class UnmappedCountResponse(
    val count: Int
)

@Serdeable
data class NormMappingErrorResponse(
    val error: String,
    val details: String? = null
)
