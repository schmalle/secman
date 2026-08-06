package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RequirementService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for retrieving security requirements with filtering and pagination.
 *
 * Filters by usecase, norm, and chapter work uniformly: they match against both
 * the structured ManyToMany entity names AND the free-text fields on the requirement.
 * This ensures "SAAS" matches whether it's a UseCase entity or text in the usecase field.
 *
 * Returns a compact summary by default to avoid token limits. Use detailed=true for full content.
 */
@Singleton
class GetRequirementsTool(
    @Inject private val requirementService: RequirementService
) : McpTool {

    override val name = "get_requirements"
    override val description = "Retrieve security requirements with optional filtering by usecase, norm, chapter, or full-text search. " +
        "Filters match against both structured entity relationships and free-text fields uniformly. " +
        "Returns compact summaries by default; use detailed=true for full content."
    override val operation = McpOperation.READ

    companion object {
        const val DEFAULT_LIMIT = 50
    }

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "search" to mapOf(
                "type" to "string",
                "description" to "Full-text search across title, description, usecase, example, chapter, and norm fields (case-insensitive)"
            ),
            "usecase" to mapOf(
                "type" to "string",
                "description" to "Filter by use case — matches UseCase entity names AND free-text usecase field (case-insensitive), e.g. 'SaaS', 'IoT', 'Network'"
            ),
            "norm" to mapOf(
                "type" to "string",
                "description" to "Filter by norm — matches Norm entity names AND free-text norm field (case-insensitive), e.g. 'ISO 27001', 'NIST'"
            ),
            "chapter" to mapOf(
                "type" to "string",
                "description" to "Filter by chapter name (case-insensitive contains match)"
            ),
            "detailed" to mapOf(
                "type" to "boolean",
                "description" to "If true, also include legacy free-text usecase/norm fields and timestamps. Default: false",
                "default" to false
            ),
            "limit" to mapOf(
                "type" to "number",
                "description" to "Maximum number of requirements to return (default: $DEFAULT_LIMIT)",
                "minimum" to 1,
                "default" to DEFAULT_LIMIT
            ),
            "offset" to mapOf(
                "type" to "number",
                "description" to "Number of requirements to skip",
                "default" to 0,
                "minimum" to 0
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        val search = arguments["search"] as? String
        val usecase = arguments["usecase"] as? String
        val norm = arguments["norm"] as? String
        val chapter = arguments["chapter"] as? String
        val detailed = arguments["detailed"] as? Boolean ?: false
        val limit = (arguments["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT
        val offset = (arguments["offset"] as? Number)?.toInt() ?: 0

        try {
            val (requirements, total) = requirementService.filterRequirements(
                search = search,
                usecase = usecase,
                norm = norm,
                chapter = chapter,
                limit = limit,
                offset = offset
            )

            val result = mapOf(
                "requirements" to requirements.map { requirement ->
                    buildRequirementMap(requirement, detailed)
                },
                "total" to total,
                "returned" to requirements.size,
                "limit" to limit,
                "offset" to offset,
                "hasMore" to (offset + requirements.size < total)
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve requirements: ${e.message}")
        }
    }

    private fun buildRequirementMap(requirement: com.secman.domain.Requirement, detailed: Boolean): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "id" to requirement.id,
            "internalId" to requirement.internalId,
            "shortreq" to requirement.shortreq,
            "description" to requirement.details,
            "example" to requirement.example,
            "motivation" to requirement.motivation,
            "chapter" to requirement.chapter,
            "usecases" to requirement.usecases.map { it.name },
            "norms" to requirement.norms.map { it.name },
            "language" to requirement.language
        )

        if (detailed) {
            map["usecase"] = requirement.usecase
            map["norm"] = requirement.norm
            map["createdAt"] = requirement.createdAt?.toString()
            map["updatedAt"] = requirement.updatedAt?.toString()
        }

        return map
    }
}
