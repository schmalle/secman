package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.Requirement
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.InputValidationService
import com.secman.service.McpRequirementManagementService
import jakarta.inject.Singleton

/** Creates a complete security requirement, optionally with structured relationships. */
@Singleton
class AddRequirementTool(
    private val managementService: McpRequirementManagementService,
    private val validationService: InputValidationService
) : McpTool {
    override val name = "add_requirement"
    override val description = "Create a security requirement, optionally assigning use cases and norms"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "shortreq" to mapOf("type" to "string", "maxLength" to 255, "description" to "Short requirement text"),
            "details" to mapOf("type" to "string", "description" to "Detailed requirement description"),
            "language" to mapOf("type" to "string", "maxLength" to 255, "description" to "Requirement language code or name"),
            "example" to mapOf("type" to "string", "description" to "Implementation example"),
            "motivation" to mapOf("type" to "string", "description" to "Why the requirement exists"),
            "usecase" to mapOf("type" to "string", "description" to "Legacy free-text use-case description"),
            "norm" to mapOf("type" to "string", "description" to "Legacy free-text norm reference"),
            "chapter" to mapOf("type" to "string", "description" to "Chapter or category"),
            "useCaseIds" to relationshipSchema("Use-case IDs to assign immediately"),
            "normIds" to relationshipSchema("Norm IDs to assign immediately")
        ),
        "required" to listOf("shortreq")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context,
            "ADMIN",
            "REQ",
            "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to add requirements"
        )?.let { return it }

        val shortreq = (arguments["shortreq"] as? String)?.trim()
        validateRequirementText(validationService, shortreq, "shortreq", required = true, maxLength = 255)?.let { return it }
        val textFields = listOf("details", "language", "example", "motivation", "usecase", "norm", "chapter")
        textFields.forEach { field ->
            val maxLength = if (field == "language") 255 else InputValidationService.MAX_DESCRIPTION_LENGTH
            validateRequirementText(validationService, arguments[field] as? String, field, maxLength = maxLength)?.let { return it }
        }
        val (useCaseIds, useCaseError) = parseIdList(arguments, "useCaseIds")
        useCaseError?.let { return it }
        val (normIds, normError) = parseIdList(arguments, "normIds")
        normError?.let { return it }

        return runRequirementMutation {
            val saved = managementService.createRequirement(
                requirement = Requirement(
                    shortreq = shortreq!!,
                    details = arguments["details"] as? String,
                    language = arguments["language"] as? String,
                    example = arguments["example"] as? String,
                    motivation = arguments["motivation"] as? String,
                    usecase = arguments["usecase"] as? String,
                    norm = arguments["norm"] as? String,
                    chapter = arguments["chapter"] as? String
                ),
                useCaseIds = useCaseIds.orEmpty(),
                normIds = normIds.orEmpty(),
                actorUserId = context.delegatedUserId
            )
            requirementResult(saved) + ("operation" to "CREATED")
        }
    }

    private fun relationshipSchema(description: String) = mapOf(
        "type" to "array",
        "items" to mapOf("type" to "number", "minimum" to 1),
        "maxItems" to MAX_REQUIREMENT_RELATIONSHIPS,
        "description" to description
    )
}
