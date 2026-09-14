package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.InputValidationService
import com.secman.service.McpRequirementManagementService
import com.secman.service.RequirementChanges
import jakarta.inject.Singleton

/** Partially updates requirement content and relationships; clearFields explicitly removes optional values. */
@Singleton
class UpdateRequirementTool(
    private val managementService: McpRequirementManagementService,
    private val validationService: InputValidationService
) : McpTool {
    override val name = "update_requirement"
    override val description =
        "Update any requirement field or relationship; use clearFields to remove optional text values"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "requirementId" to mapOf("type" to "number", "minimum" to 1),
            "shortreq" to mapOf("type" to "string", "maxLength" to 255),
            "details" to mapOf("type" to "string"),
            "language" to mapOf("type" to "string", "maxLength" to 255),
            "example" to mapOf("type" to "string"),
            "motivation" to mapOf("type" to "string"),
            "usecase" to mapOf("type" to "string", "description" to "Legacy free-text use-case description"),
            "norm" to mapOf("type" to "string", "description" to "Legacy free-text norm reference"),
            "chapter" to mapOf("type" to "string"),
            "clearFields" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "maxItems" to CLEARABLE_FIELDS.size,
                "description" to "Optional fields to set to null: ${CLEARABLE_FIELDS.sorted().joinToString(", ")}"
            ),
            "useCaseIds" to relationshipSchema("Complete replacement set of assigned use-case IDs"),
            "normIds" to relationshipSchema("Complete replacement set of assigned norm IDs")
        ),
        "required" to listOf("requirementId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context,
            "ADMIN",
            "REQ",
            "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to update requirements"
        )?.let { return it }
        val id = parsePositiveId(arguments, "requirementId")
            ?: return McpToolResult.error("VALIDATION_ERROR", "requirementId must be a positive number")
        val shortreq = (arguments["shortreq"] as? String)?.trim()
        if ("shortreq" in arguments) {
            validateRequirementText(validationService, shortreq, "shortreq", required = true, maxLength = 255)?.let { return it }
        }
        TEXT_FIELDS.forEach { field ->
            val maxLength = if (field == "language") 255 else InputValidationService.MAX_DESCRIPTION_LENGTH
            validateRequirementText(validationService, arguments[field] as? String, field, maxLength = maxLength)?.let { return it }
        }

        val clearFieldValues = arguments["clearFields"] as? List<*> ?: emptyList<Any>()
        if (clearFieldValues.size > CLEARABLE_FIELDS.size) {
            return McpToolResult.error("VALIDATION_ERROR", "clearFields contains too many entries")
        }
        val clearFields = clearFieldValues.mapIndexed { index, value ->
            value as? String
                ?: return McpToolResult.error("VALIDATION_ERROR", "clearFields[$index] must be a string")
        }.toSet()
        if (clearFields.size != clearFieldValues.size) {
            return McpToolResult.error("VALIDATION_ERROR", "clearFields must not contain duplicates")
        }
        val invalidClearFields = clearFields - CLEARABLE_FIELDS
        if (invalidClearFields.isNotEmpty()) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "Unsupported clearFields: ${invalidClearFields.sorted().joinToString(", ")}"
            )
        }
        val conflictingFields = clearFields.intersect(arguments.keys)
        if (conflictingFields.isNotEmpty()) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "Fields cannot be supplied and cleared together: ${conflictingFields.sorted().joinToString(", ")}"
            )
        }
        val (useCaseIds, useCaseError) = parseIdList(arguments, "useCaseIds")
        useCaseError?.let { return it }
        val (normIds, normError) = parseIdList(arguments, "normIds")
        normError?.let { return it }
        val changedKeys = arguments.keys - "requirementId"
        if (changedKeys.isEmpty()) {
            return McpToolResult.error("VALIDATION_ERROR", "At least one field or relationship must be updated")
        }

        return runRequirementMutation {
            val saved = managementService.updateRequirement(
                id,
                RequirementChanges(
                    shortreq = shortreq,
                    details = arguments["details"] as? String,
                    language = arguments["language"] as? String,
                    example = arguments["example"] as? String,
                    motivation = arguments["motivation"] as? String,
                    usecase = arguments["usecase"] as? String,
                    norm = arguments["norm"] as? String,
                    chapter = arguments["chapter"] as? String,
                    clearFields = clearFields,
                    useCaseIds = useCaseIds,
                    normIds = normIds
                ),
                context.delegatedUserId
            )
            requirementResult(saved) + ("operation" to "UPDATED")
        }
    }

    private fun relationshipSchema(description: String) = mapOf(
        "type" to "array",
        "items" to mapOf("type" to "number", "minimum" to 1),
        "maxItems" to MAX_REQUIREMENT_RELATIONSHIPS,
        "description" to description
    )

    companion object {
        private val TEXT_FIELDS = setOf("details", "language", "example", "motivation", "usecase", "norm", "chapter")
        private val CLEARABLE_FIELDS = TEXT_FIELDS
    }
}
