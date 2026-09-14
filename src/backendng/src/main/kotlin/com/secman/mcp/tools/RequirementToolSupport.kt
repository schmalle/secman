package com.secman.mcp.tools

import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.service.InputValidationService
import com.secman.service.RequirementManagementConflict
import com.secman.service.RequirementManagementNotFound

internal const val MAX_REQUIREMENT_RELATIONSHIPS = 50
internal const val MAX_USE_CASE_NAME_LENGTH = 255

internal fun parsePositiveId(arguments: Map<String, Any>, key: String): Long? =
    (arguments[key] as? Number)?.toLong()?.takeIf { it > 0 }

internal fun parseIdList(
    arguments: Map<String, Any>,
    key: String,
    required: Boolean = false
): Pair<List<Long>?, McpToolResult.Error?> {
    if (key !in arguments) {
        return if (required) {
            null to McpToolResult.error("VALIDATION_ERROR", "$key is required")
        } else {
            null to null
        }
    }
    val values = arguments[key] as? List<*>
        ?: return null to McpToolResult.error("VALIDATION_ERROR", "$key must be an array")
    if (values.size > MAX_REQUIREMENT_RELATIONSHIPS) {
        return null to McpToolResult.error(
            "VALIDATION_ERROR",
            "$key must contain at most $MAX_REQUIREMENT_RELATIONSHIPS entries"
        )
    }
    val ids = values.mapIndexed { index, value ->
        (value as? Number)?.toLong()?.takeIf { it > 0 }
            ?: return null to McpToolResult.error("VALIDATION_ERROR", "$key[$index] must be a positive number")
    }
    if (ids.distinct().size != ids.size) {
        return null to McpToolResult.error("VALIDATION_ERROR", "$key must not contain duplicates")
    }
    return ids to null
}

internal fun validateRequirementText(
    validationService: InputValidationService,
    value: String?,
    fieldName: String,
    required: Boolean = false,
    maxLength: Int = InputValidationService.MAX_DESCRIPTION_LENGTH
): McpToolResult.Error? {
    if (required && value.isNullOrBlank()) {
        return McpToolResult.error("VALIDATION_ERROR", "$fieldName is required")
    }
    if (value == null) return null
    if (value.length > maxLength) {
        return McpToolResult.error("VALIDATION_ERROR", "$fieldName must not exceed $maxLength characters")
    }
    val result = validationService.validateDescription(value, fieldName)
    return if (result.isValid) null else McpToolResult.error(
        "VALIDATION_ERROR",
        result.errorMessage ?: "$fieldName is invalid"
    )
}

internal fun requirementResult(requirement: Requirement): Map<String, Any?> = mapOf(
    "id" to requirement.id,
    "internalId" to requirement.internalId,
    "revision" to requirement.versionNumber,
    "idRevision" to requirement.idRevision,
    "shortreq" to requirement.shortreq,
    "details" to requirement.details,
    "language" to requirement.language,
    "example" to requirement.example,
    "motivation" to requirement.motivation,
    "usecase" to requirement.usecase,
    "norm" to requirement.norm,
    "chapter" to requirement.chapter,
    "useCases" to requirement.usecases.sortedBy { it.name }.map(::useCaseResult),
    "norms" to requirement.norms.sortedBy { it.name }.map { mapOf("id" to it.id, "name" to it.name, "version" to it.version) },
    "createdAt" to requirement.createdAt?.toString(),
    "updatedAt" to requirement.updatedAt?.toString()
)

internal fun useCaseResult(useCase: UseCase): Map<String, Any?> = mapOf(
    "id" to useCase.id,
    "name" to useCase.name,
    "systemProtected" to useCase.systemProtected,
    "createdAt" to useCase.createdAt?.toString(),
    "updatedAt" to useCase.updatedAt?.toString()
)

internal inline fun runRequirementMutation(block: () -> Map<String, Any?>): McpToolResult = try {
    McpToolResult.success(block())
} catch (e: RequirementManagementNotFound) {
    McpToolResult.error("NOT_FOUND", e.message ?: "Requested record was not found")
} catch (e: RequirementManagementConflict) {
    McpToolResult.error("CONFLICT", e.message ?: "The operation conflicts with existing data")
}
