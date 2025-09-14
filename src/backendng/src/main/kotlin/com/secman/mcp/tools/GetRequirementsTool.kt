package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.service.RequirementService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for retrieving security requirements with filtering and pagination.
 */
@Singleton
class GetRequirementsTool(
    @Inject private val requirementService: RequirementService
) : McpTool {

    override val name = "get_requirements"
    override val description = "Retrieve security requirements with optional filtering and pagination"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "limit" to mapOf(
                "type" to "number",
                "description" to "Maximum number of requirements to return (max 100)",
                "default" to 20,
                "maximum" to 100
            ),
            "offset" to mapOf(
                "type" to "number",
                "description" to "Number of requirements to skip",
                "default" to 0,
                "minimum" to 0
            ),
            "tags" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Filter by tags"
            ),
            "status" to mapOf(
                "type" to "string",
                "enum" to listOf("DRAFT", "ACTIVE", "DEPRECATED", "ARCHIVED"),
                "description" to "Filter by requirement status"
            ),
            "priority" to mapOf(
                "type" to "string",
                "enum" to listOf("LOW", "MEDIUM", "HIGH", "CRITICAL"),
                "description" to "Filter by priority level"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 20
        val offset = (arguments["offset"] as? Number)?.toInt() ?: 0
        val tags = (arguments["tags"] as? List<*>)?.filterIsInstance<String>()
        val status = arguments["status"] as? String
        val priority = arguments["priority"] as? String

        // Validate limit
        if (limit > 100) {
            return McpToolResult.error("INVALID_PARAMETER", "Limit cannot exceed 100")
        }

        try {
            val requirements = requirementService.getAllRequirements(limit)

            val result = mapOf(
                "requirements" to requirements.map { requirement ->
                    mapOf(
                        "id" to requirement.id,
                        "title" to requirement.shortreq,
                        "description" to requirement.details,
                        "language" to requirement.language,
                        "example" to requirement.example,
                        "motivation" to requirement.motivation,
                        "usecase" to requirement.usecase,
                        "norm" to requirement.norm,
                        "chapter" to requirement.chapter,
                        "norms" to requirement.norms.map { it.name ?: "" },
                        "usecases" to requirement.usecases.map { it.name ?: "" },
                        "createdAt" to requirement.createdAt?.toString(),
                        "updatedAt" to requirement.updatedAt?.toString()
                    )
                },
                "total" to requirements.size,
                "limit" to limit,
                "offset" to offset
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve requirements: ${e.message}")
        }
    }
}