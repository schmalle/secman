package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.Requirement
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RequirementService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for creating a new security requirement.
 * Feature: 057-cli-mcp-requirements
 *
 * Returns the created requirement ID and confirmation.
 */
@Singleton
class AddRequirementTool(
    @Inject private val requirementService: RequirementService
) : McpTool {

    override val name = "add_requirement"
    override val description = "Create a new security requirement"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "shortreq" to mapOf(
                "type" to "string",
                "description" to "Short requirement text (required)"
            ),
            "details" to mapOf(
                "type" to "string",
                "description" to "Detailed description"
            ),
            "motivation" to mapOf(
                "type" to "string",
                "description" to "Why this requirement exists"
            ),
            "example" to mapOf(
                "type" to "string",
                "description" to "Implementation example"
            ),
            "norm" to mapOf(
                "type" to "string",
                "description" to "Regulatory norm reference"
            ),
            "usecase" to mapOf(
                "type" to "string",
                "description" to "Use case description"
            ),
            "chapter" to mapOf(
                "type" to "string",
                "description" to "Chapter/category for grouping"
            )
        ),
        "required" to listOf("shortreq")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        val shortreq = arguments["shortreq"] as? String
            ?: return McpToolResult.error("VALIDATION_ERROR", "Short requirement text is required")

        if (shortreq.isBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Short requirement text cannot be empty")
        }

        val details = arguments["details"] as? String
        val motivation = arguments["motivation"] as? String
        val example = arguments["example"] as? String
        val norm = arguments["norm"] as? String
        val usecase = arguments["usecase"] as? String
        val chapter = arguments["chapter"] as? String

        try {
            val requirement = Requirement(
                shortreq = shortreq,
                details = details,
                motivation = motivation,
                example = example,
                norm = norm,
                usecase = usecase,
                chapter = chapter
            )

            val created = requirementService.createRequirement(requirement)

            val result = mapOf(
                "success" to true,
                "id" to created.id,
                "message" to "Requirement created successfully",
                "operation" to "CREATED",
                "shortreq" to created.shortreq,
                "chapter" to (created.chapter ?: "Uncategorized")
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to create requirement: ${e.message}")
        }
    }
}
